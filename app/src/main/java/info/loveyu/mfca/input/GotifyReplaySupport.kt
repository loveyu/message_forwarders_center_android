package info.loveyu.mfca.input

import android.content.Context
import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.config.ReplayConfig
import info.loveyu.mfca.config.ReplayProvider
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.GotifyApiConfig
import info.loveyu.mfca.util.GotifyApiSupport
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

internal class GotifyReplaySupport(
    context: Context,
    private val config: LinkInputConfig,
    private val dispatchMessage: (InputMessage) -> Unit
) {
    private data class PendingInput(
        val data: ByteArray,
        val headers: Map<String, String>
    )

    private data class ReplayPayload(
        val id: Long,
        val json: String
    )

    private data class MessagePage(
        val messages: List<ReplayPayload>,
        val nextSince: Long?
    )

    private val replayConfig: ReplayConfig = config.replay!!
    private val prefs = context.getSharedPreferences("mfca_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private val pendingInputs = ArrayDeque<PendingInput>()
    private val linkStateListener: (String, Boolean) -> Unit = { linkId, connected ->
        if (connected && linkId == config.linkId) {
            scheduleSync("connected")
        }
    }

    @Volatile
    private var running = false
    private var stateLoaded = false
    private var syncInProgress = false
    private var lastSeenId = 0L

    private val stateKey = "gotify_replay_last_seen:${config.name}:${config.linkId}"

    fun start() {
        if (!isEnabled(config)) return
        synchronized(lock) {
            if (running) return
            running = true
        }
        LinkManager.addLinkStateListener(linkStateListener)
        if (LinkManager.getLink(config.linkId)?.isConnected() == true) {
            scheduleSync("startup")
        }
    }

    fun stop() {
        if (!isEnabled(config)) return
        synchronized(lock) {
            running = false
            pendingInputs.clear()
        }
        LinkManager.removeLinkStateListener(linkStateListener)
    }

    fun handleRealtimeMessage(data: ByteArray, headers: Map<String, String>) {
        val messageId = extractMessageId(data)
        if (messageId == null) {
            dispatchMessage(InputMessage(source = config.name, data = data.copyOf(), headers = headers))
            return
        }

        synchronized(lock) {
            if (!running) return
            if (syncInProgress) {
                pendingInputs.addLast(PendingInput(data.copyOf(), headers))
                return
            }
        }

        deliverRealtimeMessage(data.copyOf(), headers, messageId)
    }

    private fun scheduleSync(reason: String) {
        synchronized(lock) {
            if (!running || syncInProgress) return
            syncInProgress = true
        }

        scope.launch {
            try {
                val apiConfig = GotifyApiSupport.resolveApiConfig(config.linkId, replayConfig)
                if (apiConfig == null) {
                    LogManager.logWarn("REPLAY", "No Gotify API config for ${config.name} (${config.linkId}), skipping replay")
                    return@launch
                }

                val shouldCatchUp = ensureStateLoaded(apiConfig)
                if (shouldCatchUp) {
                    val sinceId = synchronized(lock) { lastSeenId }
                    val missingMessages = fetchMissingMessages(apiConfig, sinceId)
                    if (missingMessages.isNotEmpty()) {
                        LogManager.logInfo("REPLAY", "Replaying ${missingMessages.size} Gotify messages for ${config.name} after $reason")
                    }
                    missingMessages.forEach { payload ->
                        if (!running) return@forEach
                        deliverReplayPayload(payload)
                    }
                }
            } catch (e: Exception) {
                LogManager.logWarn("REPLAY", "Replay sync failed for ${config.name}: ${e.message}")
            } finally {
                val pending = synchronized(lock) {
                    syncInProgress = false
                    if (!running) {
                        pendingInputs.clear()
                        emptyList()
                    } else {
                        val queued = pendingInputs.toList()
                        pendingInputs.clear()
                        queued
                    }
                }
                pending.forEach { pendingInput ->
                    deliverRealtimeMessage(
                        data = pendingInput.data,
                        headers = pendingInput.headers,
                        messageId = extractMessageId(pendingInput.data)
                    )
                }
            }
        }
    }

    private fun ensureStateLoaded(apiConfig: GotifyApiConfig): Boolean {
        synchronized(lock) {
            if (stateLoaded) return true
        }

        if (replayConfig.persistState && prefs.contains(stateKey)) {
            val persistedId = prefs.getLong(stateKey, 0L)
            synchronized(lock) {
                lastSeenId = persistedId
                stateLoaded = true
            }
            LogManager.logDebug("REPLAY", "Loaded persisted Gotify replay state for ${config.name}: id=$persistedId")
            return true
        }

        val latestId = fetchLatestMessageId(apiConfig) ?: 0L
        synchronized(lock) {
            lastSeenId = latestId
            stateLoaded = true
        }
        persistLastSeenId(latestId)
        LogManager.logInfo("REPLAY", "Initialized Gotify replay baseline for ${config.name}: id=$latestId")
        return false
    }

    private fun fetchLatestMessageId(apiConfig: GotifyApiConfig): Long? {
        return fetchPage(apiConfig, limit = 1, since = null)?.messages?.firstOrNull()?.id
    }

    private fun fetchMissingMessages(apiConfig: GotifyApiConfig, lastProcessedId: Long): List<ReplayPayload> {
        val result = mutableListOf<ReplayPayload>()
        var since: Long? = null
        var pageCount = 0

        while (pageCount < replayConfig.maxPages && result.size < replayConfig.maxMessages) {
            val page = fetchPage(apiConfig, replayConfig.pageSize, since) ?: break
            if (page.messages.isEmpty()) break

            val filtered = page.messages.filter { it.id > lastProcessedId }
            if (filtered.isEmpty()) break

            val remaining = replayConfig.maxMessages - result.size
            result.addAll(filtered.take(remaining))
            pageCount++

            if (filtered.size != page.messages.size || page.nextSince == null) {
                break
            }
            since = page.nextSince
        }

        return result.asReversed()
    }

    private fun fetchPage(apiConfig: GotifyApiConfig, limit: Int, since: Long?): MessagePage? {
        val url = buildMessagesUrl(apiConfig, limit, since) ?: return null
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Gotify-Key", apiConfig.token)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogManager.logWarn("REPLAY", "Gotify API returned ${response.code} for ${url.encodedPath}")
                    return null
                }
                val body = response.body?.string() ?: return null
                parsePage(body)
            }
        } catch (e: Exception) {
            LogManager.logWarn("REPLAY", "Failed to call Gotify API for ${config.name}: ${e.message}")
            null
        }
    }

    private fun buildMessagesUrl(apiConfig: GotifyApiConfig, limit: Int, since: Long?) =
        apiConfig.baseUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            apiConfig.applicationId?.let {
                addPathSegment("application")
                addPathSegment(it.toString())
            }
            addPathSegment("message")
            addQueryParameter("limit", limit.toString())
            if (since != null && since > 0L) {
                addQueryParameter("since", since.toString())
            }
        }?.build()

    private fun parsePage(body: String): MessagePage {
        val root = JSONObject(body)
        val messagesJson = root.optJSONArray("messages")
        val messages = mutableListOf<ReplayPayload>()
        if (messagesJson != null) {
            for (index in 0 until messagesJson.length()) {
                val message = messagesJson.optJSONObject(index) ?: continue
                val id = extractMessageId(message) ?: continue
                messages.add(ReplayPayload(id = id, json = message.toString()))
            }
        }

        val paging = root.optJSONObject("paging")
        val nextSince = if (paging != null && paging.optString("next").isNotBlank()) {
            paging.optLong("since").takeIf { it > 0L }
        } else {
            null
        }
        return MessagePage(messages = messages, nextSince = nextSince)
    }

    private fun deliverReplayPayload(payload: ReplayPayload) {
        if (!markSeenIfNew(payload.id)) return
        val headers = mutableMapOf(
            "gotify_replay" to "true",
            "gotify_replay_source" to "gotifyApi",
            "gotify_message_id" to payload.id.toString()
        )
        dispatchMessage(
            InputMessage(
                source = config.name,
                data = payload.json.toByteArray(),
                headers = headers
            )
        )
    }

    private fun deliverRealtimeMessage(data: ByteArray, headers: Map<String, String>, messageId: Long?) {
        if (messageId != null && !markSeenIfNew(messageId)) {
            LogManager.logDebug("REPLAY", "Skipped duplicate Gotify message for ${config.name}: id=$messageId")
            return
        }

        val mergedHeaders = headers.toMutableMap()
        if (messageId != null && !mergedHeaders.containsKey("gotify_message_id")) {
            mergedHeaders["gotify_message_id"] = messageId.toString()
        }
        dispatchMessage(InputMessage(source = config.name, data = data, headers = mergedHeaders))
    }

    private fun markSeenIfNew(messageId: Long): Boolean {
        synchronized(lock) {
            if (stateLoaded && messageId <= lastSeenId) {
                return false
            }
            if (messageId > lastSeenId) {
                lastSeenId = messageId
                stateLoaded = true
                persistLastSeenId(messageId)
            }
            return true
        }
    }

    private fun persistLastSeenId(messageId: Long) {
        if (!replayConfig.persistState) return
        prefs.edit().putLong(stateKey, messageId).apply()
    }

    private fun extractMessageId(data: ByteArray): Long? {
        return try {
            extractMessageId(JSONObject(String(data)))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMessageId(json: JSONObject): Long? {
        val path = replayConfig.messageIdPath.split('.').filter { it.isNotBlank() }
        if (path.isEmpty()) return null

        var current: Any = json
        for (segment in path) {
            current = when (current) {
                is JSONObject -> if (current.has(segment)) current.get(segment) else return null
                else -> return null
            }
        }

        return when (current) {
            is Number -> current.toLong()
            is String -> current.toLongOrNull()
            else -> null
        }
    }

    companion object {
        fun isEnabled(config: LinkInputConfig): Boolean {
            val replay = config.replay ?: return false
            return replay.enabled && replay.provider == ReplayProvider.gotifyApi
        }
    }
}
