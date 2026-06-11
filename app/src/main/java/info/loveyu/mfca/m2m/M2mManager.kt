package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.config.M2mAccessControlMode
import info.loveyu.mfca.config.M2mInputConfig
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.ServerSocket
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object M2mManager {
    private const val LOCAL_PROXY_PORT = 17890

    private val stateFlow = MutableStateFlow(M2mUiState())
    val state: StateFlow<M2mUiState> = stateFlow.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: M2mStateStore? = null
    @Volatile private var configs: List<M2mInputConfig> = emptyList()
    @Volatile private var m2mCoreUrl: String? = null
    @Volatile private var configDownloadProxy: String? = null

    private fun effectiveDownloadProxy(): String? {
        val override = store?.getDownloadProxy()?.trim()?.takeIf { it.isNotBlank() }
        return override ?: configDownloadProxy
    }

    fun initialize(context: Context, vpnConfigs: List<M2mInputConfig>, pluginUrl: String? = null, downloadProxy: String? = null) {
        appContext = context.applicationContext
        store = M2mStateStore(context.applicationContext)
        configs = vpnConfigs
        m2mCoreUrl = pluginUrl
        configDownloadProxy = downloadProxy
        rebuildState()
    }

    fun clear() {
        configs = emptyList()
        m2mCoreUrl = null
        updateState(
            M2mUiState(
                hasVpnConfig = false,
                isEnabled = false,
                runtimeStatus = M2mRuntimeStatus.disabled,
                statusMessage = "",
                activeCandidateName = null,
                candidates = emptyList(),
            )
        )
    }

    fun refresh() {
        rebuildState()
    }

    fun setEnabled(enabled: Boolean) {
        store?.setGlobalEnabled(enabled)
        rebuildState(
            runtimeStatus = if (enabled) M2mRuntimeStatus.idle else M2mRuntimeStatus.disabled,
            statusMessage = if (enabled) "m2m 已启用，等待启动核心" else "m2m is disabled",
            runningCandidateName = if (enabled) stateFlow.value.runningCandidateName else null,
        )
    }

    fun selectCandidate(name: String) {
        store?.pushSelection(name)
        rebuildState()
    }

    fun updateAccessControl(name: String, mode: M2mAccessControlMode, packages: List<String>) {
        store?.setAccessControl(name, mode, packages)
        rebuildState()
    }

    fun getOverridePort(candidateName: String): Int? = store?.getLocalPort(candidateName)

    fun setOverridePort(candidateName: String, port: Int?) {
        store?.setLocalPort(candidateName, port)
    }

    fun getOverrideRuleMode(candidateName: String): M2mRuleMode? = store?.getRuleMode(candidateName)

    fun setOverrideRuleMode(candidateName: String, mode: M2mRuleMode?) {
        store?.setRuleMode(candidateName, mode)
    }

    fun getOverrideLogLevel(candidateName: String): M2mLogLevel? = store?.getLogLevel(candidateName)

    fun setOverrideLogLevel(candidateName: String, level: M2mLogLevel?) {
        store?.setLogLevel(candidateName, level)
    }

    fun getUdpRelay(candidateName: String): Boolean = store?.getUdpRelay(candidateName) ?: false

    fun setUdpRelay(candidateName: String, enabled: Boolean?) {
        store?.setUdpRelay(candidateName, enabled)
    }

    fun getIpv6(candidateName: String): Boolean = store?.getIpv6(candidateName) ?: false

    fun setIpv6(candidateName: String, enabled: Boolean?) {
        store?.setIpv6(candidateName, enabled)
    }

    fun getDnsHijack(candidateName: String): Boolean = store?.getDnsHijack(candidateName) ?: true

    fun setDnsHijack(candidateName: String, enabled: Boolean?) {
        store?.setDnsHijack(candidateName, enabled)
    }

    fun getDownloadProxyOverride(): String? = store?.getDownloadProxy()

    fun setDownloadProxyOverride(proxy: String?) {
        store?.setDownloadProxy(proxy)
        rebuildState()
    }

    fun getSelectedCandidate(): M2mCandidateState? {
        return stateFlow.value.candidates.firstOrNull { it.isSelected && it.isAvailable }
    }

    fun downloadConfig(context: Context, candidateName: String): Result<M2mConfigCacheState> {
        val config =
            configs.firstOrNull { it.name == candidateName }
                ?: return Result.failure(IllegalArgumentException("Unknown m2m candidate: $candidateName"))
        LogManager.logInfo("VPN", "Downloading config for $candidateName: ${config.configUrl}")
        return M2mConfigCacheManager.downloadConfig(context, config).map {
            rebuildState()
            M2mConfigCacheManager.inspect(context, config)
        }
    }

    fun deleteCorePlugin(context: Context): Result<Unit> = runCatching {
        M2mCoreManager.deleteCore(context)
        rebuildState()
    }

    fun downloadCorePlugin(context: Context): Result<Unit> = runCatching {
        val url = m2mCoreUrl ?: throw IllegalStateException("未配置 plugin.m2mCore 下载地址")
        M2mCoreManager.downloadCore(context, url, effectiveDownloadProxy()).getOrThrow()
        rebuildState()
    }

    fun deleteConfigCache(context: Context, candidateName: String): Result<M2mConfigCacheState> {
        val config =
            configs.firstOrNull { it.name == candidateName }
                ?: return Result.failure(IllegalArgumentException("Unknown m2m candidate: $candidateName"))
        return M2mConfigCacheManager.deleteCache(context, candidateName).map {
            rebuildState()
            M2mConfigCacheManager.inspect(context, config)
        }
    }

    /**
     * Called on every tick. Checks each candidate's auto-refresh schedule and downloads
     * if due. Returns true if a running candidate's config changed (caller should restart VPN).
     */
    fun onTick(context: Context): Boolean {
        val now = System.currentTimeMillis()
        var runningConfigChanged = false
        configs.forEach { config ->
            if (config.refreshIntervalMs <= 0) return@forEach
            val cacheState = M2mConfigCacheManager.inspect(context, config)
            val nextRefreshMs = cacheState.nextRefreshMs ?: return@forEach
            if (now < nextRefreshMs) return@forEach
            LogManager.logInfo("VPN", "Auto-refreshing config for ${config.name}")
            M2mConfigCacheManager.downloadConfig(context, config)
                .onSuccess { changed ->
                    rebuildState()
                    if (changed && stateFlow.value.runningCandidateName == config.name) {
                        LogManager.logInfo(
                            "VPN",
                            "Config changed for running candidate ${config.name}, signalling restart",
                        )
                        runningConfigChanged = true
                    }
                }
                .onFailure { error ->
                    LogManager.logWarn(
                        "VPN",
                        "Auto-refresh failed for ${config.name}: ${error.message}",
                    )
                }
        }
        return runningConfigChanged
    }

    fun prepareSelectedCandidate(context: Context): Result<PreparedM2mArtifacts> {
        val selected = getSelectedCandidate()
            ?: return Result.failure(IllegalStateException("No available m2m candidate selected"))
        LogManager.logInfo("VPN", "Preparing VPN candidate ${selected.config.name}")
        updateRuntimeStatus(M2mRuntimeStatus.preparing, "Preparing ${selected.config.name}")

        // Step 1: Ensure config is cached (auto-download if needed)
        val cachedSource =
            M2mConfigCacheManager.getCachedSourceFile(context, selected.config.name)
                ?: run {
                    updateRuntimeStatus(M2mRuntimeStatus.preparing, "Downloading config for ${selected.config.name}")
                    LogManager.logInfo("VPN", "Config not cached, auto-downloading for ${selected.config.name}")
                    M2mConfigCacheManager.downloadConfig(context, selected.config)
                        .getOrNull()
                        ?.let { M2mConfigCacheManager.getCachedSourceFile(context, selected.config.name) }
                }
        if (cachedSource == null) {
            LogManager.logError("VPN", "Failed to download config for ${selected.config.name}")
            updateRuntimeStatus(M2mRuntimeStatus.error, "Failed to download config for ${selected.config.name}")
            return Result.failure(IllegalStateException("Failed to download config for ${selected.config.name}"))
        }

        // Step 2: Ensure core is available (auto-download if needed)
        return M2mCoreManager.ensureCore(context, m2mCoreUrl, effectiveDownloadProxy()).map { coreFile ->
            LogManager.logInfo("VPN", "Prepared m2m core for ${selected.config.name}: ${coreFile.absolutePath}")
            val effectivePort = store?.getLocalPort(selected.config.name) ?: LOCAL_PROXY_PORT
            val effectiveRuleMode = store?.getRuleMode(selected.config.name)
            val effectiveLogLevel = store?.getLogLevel(selected.config.name)
            val effectiveUdpRelay = store?.getUdpRelay(selected.config.name) ?: false
            val effectiveIpv6 = store?.getIpv6(selected.config.name) ?: false
            val effectiveDnsHijack = store?.getDnsHijack(selected.config.name) ?: true
            val apiPort = ServerSocket(0).use { it.localPort }
            val apiSecret = store?.getOrCreateApiSecret() ?: ""
            LogManager.logDebug("VPN", "Effective settings for ${selected.config.name}: port=$effectivePort, apiPort=$apiPort, ruleMode=$effectiveRuleMode, logLevel=$effectiveLogLevel, udpRelay=$effectiveUdpRelay, dnsHijack=$effectiveDnsHijack")
            val profileFile = M2mProfileManager.buildRuntimeProfile(
                context,
                selected.config.name,
                cachedSource.readText(),
                effectivePort,
                apiPort,
                apiSecret,
                effectiveRuleMode,
                effectiveLogLevel,
            ).getOrThrow()
            LogManager.logInfo("VPN", "Prepared VPN profile for ${selected.config.name}: ${profileFile.absolutePath}")
            PreparedM2mArtifacts(
                candidate = selected.config,
                coreFilePath = coreFile.absolutePath,
                profileFilePath = profileFile.absolutePath,
                localProxyPort = effectivePort,
                apiPort = apiPort,
                apiSecret = apiSecret,
                udpRelay = effectiveUdpRelay,
                dnsHijack = effectiveDnsHijack,
                logLevel = effectiveLogLevel,
                ipv6 = effectiveIpv6,
            )
        }.onSuccess {
            updateRuntimeStatus(M2mRuntimeStatus.prepared, "Prepared ${it.candidate.name}")
        }.onFailure { error ->
            LogManager.logError("VPN", "Failed to prepare VPN artifacts: ${error.message}")
            updateRuntimeStatus(M2mRuntimeStatus.error, error.message ?: "Failed to prepare m2m")
        }
    }

    fun markRunning(candidateName: String, message: String) {
        store?.pushSelection(candidateName)
        rebuildState(
            runtimeStatus = M2mRuntimeStatus.running,
            statusMessage = message,
            runningCandidateName = candidateName,
        )
    }

    fun clearRunningCandidate(status: M2mRuntimeStatus, message: String) {
        rebuildState(
            runtimeStatus = status,
            statusMessage = message,
            runningCandidateName = null,
        )
    }

    fun updateRuntimeStatus(status: M2mRuntimeStatus, message: String) {
        rebuildState(
            runtimeStatus = status,
            statusMessage = message,
            runningCandidateName = stateFlow.value.runningCandidateName,
        )
    }

    private fun rebuildState(
        runtimeStatus: M2mRuntimeStatus = stateFlow.value.runtimeStatus,
        statusMessage: String = stateFlow.value.statusMessage,
        runningCandidateName: String? = stateFlow.value.runningCandidateName,
    ) {
        val context = appContext ?: return
        val stateStore = store ?: return
        val selectionHistory = stateStore.getSelectionHistory()
        val enabled = stateStore.isGlobalEnabled(defaultValue = false)
        val candidates = configs.map { config ->
            val effectiveMode = stateStore.getAccessControlMode(config.name, config.accessControlMode)
            val effectivePackages = stateStore.getPackages(config.name, config.packages)
            val networkAvailability = if (!config.enabled) {
                NetworkChecker.EnableResult(enabled = false, reason = "Disabled in config")
            } else {
                NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
            }
            val coreState = M2mCoreManager.inspectCore(context)
            val configCacheState = M2mConfigCacheManager.inspect(context, config)
            M2mCandidateState(
                config = config,
                effectiveAccessControlMode = effectiveMode,
                effectivePackages = effectivePackages,
                coreState = coreState,
                configCacheState = configCacheState,
                isAvailable = networkAvailability.enabled && configCacheState.isCached,
                availabilityReason = when {
                    !networkAvailability.enabled -> networkAvailability.reason
                    !configCacheState.isCached -> "配置未缓存，请先下载配置"
                    else -> null
                },
            )
        }
        val activeName = resolveActiveCandidateName(candidates, selectionHistory)
        val globalCoreState = M2mCoreManager.inspectCore(context)
        val normalizedRuntimeStatus = when {
            !enabled -> M2mRuntimeStatus.disabled
            runtimeStatus == M2mRuntimeStatus.disabled -> M2mRuntimeStatus.idle
            else -> runtimeStatus
        }
        val normalizedMessage = when {
            !enabled -> "m2m is disabled"
            statusMessage.isBlank() && normalizedRuntimeStatus == M2mRuntimeStatus.idle -> "m2m 已启用，等待启动核心"
            else -> statusMessage
        }
        updateState(
            M2mUiState(
                hasVpnConfig = configs.isNotEmpty(),
                isEnabled = enabled,
                runtimeStatus = normalizedRuntimeStatus,
                statusMessage = normalizedMessage,
                activeCandidateName = activeName,
                runningCandidateName = runningCandidateName,
                isRuntimeOutOfSync = enabled &&
                    runningCandidateName != null &&
                    runningCandidateName != activeName,
                coreState = globalCoreState,
                m2mCoreUrl = m2mCoreUrl ?: "",
                downloadProxy = effectiveDownloadProxy() ?: "",
                candidates = candidates.map { it.copy(isSelected = it.config.name == activeName) },
            ),
        )
    }

    private fun updateState(next: M2mUiState) {
        stateFlow.value = next
    }

    private fun resolveActiveCandidateName(
        candidates: List<M2mCandidateState>,
        selectionHistory: List<String>,
    ): String? {
        val availableNames = candidates.filter { it.isAvailable }.map { it.config.name }.toSet()
        selectionHistory.firstOrNull { it in availableNames }?.let { return it }
        return candidates.firstOrNull { it.isAvailable }?.config?.name
    }
}
