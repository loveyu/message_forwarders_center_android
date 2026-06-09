package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.config.VpnAccessControlMode
import info.loveyu.mfca.config.VpnInputConfig
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnManager {
    private const val LOCAL_PROXY_PORT = 17890

    private val stateFlow = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = stateFlow.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: VpnStateStore? = null
    @Volatile private var configs: List<VpnInputConfig> = emptyList()
    @Volatile private var m2mCoreUrl: String? = null

    fun initialize(context: Context, vpnConfigs: List<VpnInputConfig>, pluginUrl: String? = null) {
        appContext = context.applicationContext
        store = VpnStateStore(context.applicationContext)
        configs = vpnConfigs
        m2mCoreUrl = pluginUrl
        rebuildState()
    }

    fun clear() {
        configs = emptyList()
        m2mCoreUrl = null
        updateState(
            VpnUiState(
                hasVpnConfig = false,
                isEnabled = false,
                runtimeStatus = VpnRuntimeStatus.disabled,
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
            runtimeStatus = if (enabled) VpnRuntimeStatus.idle else VpnRuntimeStatus.disabled,
            statusMessage = if (enabled) "VPN 已启用，等待启动核心" else "VPN is disabled",
            runningCandidateName = if (enabled) stateFlow.value.runningCandidateName else null,
        )
    }

    fun selectCandidate(name: String) {
        store?.pushSelection(name)
        rebuildState()
    }

    fun updateAccessControl(name: String, mode: VpnAccessControlMode, packages: List<String>) {
        store?.setAccessControl(name, mode, packages)
        rebuildState()
    }

    fun getOverridePort(candidateName: String): Int? = store?.getLocalPort(candidateName)

    fun setOverridePort(candidateName: String, port: Int?) {
        store?.setLocalPort(candidateName, port)
    }

    fun getOverrideRuleMode(candidateName: String): VpnRuleMode? = store?.getRuleMode(candidateName)

    fun setOverrideRuleMode(candidateName: String, mode: VpnRuleMode?) {
        store?.setRuleMode(candidateName, mode)
    }

    fun getOverrideLogLevel(candidateName: String): VpnLogLevel? = store?.getLogLevel(candidateName)

    fun setOverrideLogLevel(candidateName: String, level: VpnLogLevel?) {
        store?.setLogLevel(candidateName, level)
    }

    fun getSelectedCandidate(): VpnCandidateState? {
        return stateFlow.value.candidates.firstOrNull { it.isSelected && it.isAvailable }
    }

    fun downloadConfig(context: Context, candidateName: String): Result<VpnConfigCacheState> {
        val config =
            configs.firstOrNull { it.name == candidateName }
                ?: return Result.failure(IllegalArgumentException("Unknown VPN candidate: $candidateName"))
        LogManager.logInfo("VPN", "Downloading config for $candidateName: ${config.configUrl}")
        return VpnConfigCacheManager.downloadConfig(context, config).map {
            rebuildState()
            VpnConfigCacheManager.inspect(context, config)
        }
    }

    fun deleteCorePlugin(context: Context): Result<Unit> = runCatching {
        MihomoCoreManager.deleteCore(context)
        rebuildState()
    }

    fun downloadCorePlugin(context: Context): Result<Unit> = runCatching {
        val url = m2mCoreUrl ?: throw IllegalStateException("未配置 plugin.m2mCore 下载地址")
        MihomoCoreManager.downloadCore(context, url).getOrThrow()
        rebuildState()
    }

    fun deleteConfigCache(context: Context, candidateName: String): Result<VpnConfigCacheState> {
        val config =
            configs.firstOrNull { it.name == candidateName }
                ?: return Result.failure(IllegalArgumentException("Unknown VPN candidate: $candidateName"))
        return VpnConfigCacheManager.deleteCache(context, candidateName).map {
            rebuildState()
            VpnConfigCacheManager.inspect(context, config)
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
            val cacheState = VpnConfigCacheManager.inspect(context, config)
            val nextRefreshMs = cacheState.nextRefreshMs ?: return@forEach
            if (now < nextRefreshMs) return@forEach
            LogManager.logInfo("VPN", "Auto-refreshing config for ${config.name}")
            VpnConfigCacheManager.downloadConfig(context, config)
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

    fun prepareSelectedCandidate(context: Context): Result<PreparedVpnArtifacts> {
        val selected = getSelectedCandidate()
            ?: return Result.failure(IllegalStateException("No available VPN candidate selected"))
        LogManager.logInfo("VPN", "Preparing VPN candidate ${selected.config.name}")
        updateRuntimeStatus(VpnRuntimeStatus.preparing, "Preparing ${selected.config.name}")
        return MihomoCoreManager.ensureCore(context, m2mCoreUrl).fold(
            onSuccess = { coreFile ->
                LogManager.logInfo("VPN", "Prepared mihomo core for ${selected.config.name}: ${coreFile.absolutePath}")
                val cachedSource =
                    VpnConfigCacheManager.getCachedSourceFile(context, selected.config.name)
                        ?: return@fold Result.failure(
                            IllegalStateException("配置未缓存，请先下载 ${selected.config.name} 的配置"),
                        )
                val effectivePort = store?.getLocalPort(selected.config.name) ?: LOCAL_PROXY_PORT
                val effectiveRuleMode = store?.getRuleMode(selected.config.name)
                val effectiveLogLevel = store?.getLogLevel(selected.config.name)
                VpnProfileManager.buildRuntimeProfile(
                    context,
                    selected.config.name,
                    cachedSource.readText(),
                    effectivePort,
                    effectiveRuleMode,
                    effectiveLogLevel,
                ).map { profileFile ->
                    LogManager.logInfo("VPN", "Prepared VPN profile for ${selected.config.name}: ${profileFile.absolutePath}")
                    PreparedVpnArtifacts(
                        candidate = selected.config,
                        coreFilePath = coreFile.absolutePath,
                        profileFilePath = profileFile.absolutePath,
                        localProxyPort = effectivePort,
                    )
                }
            },
            onFailure = { error -> Result.failure(error) },
        ).onSuccess {
            updateRuntimeStatus(
                VpnRuntimeStatus.prepared,
                "Prepared ${it.candidate.name}",
            )
        }.onFailure { error ->
            LogManager.logError("VPN", "Failed to prepare VPN artifacts: ${error.message}")
            updateRuntimeStatus(VpnRuntimeStatus.error, error.message ?: "Failed to prepare VPN")
        }
    }

    fun markRunning(candidateName: String, message: String) {
        store?.pushSelection(candidateName)
        rebuildState(
            runtimeStatus = VpnRuntimeStatus.running,
            statusMessage = message,
            runningCandidateName = candidateName,
        )
    }

    fun clearRunningCandidate(status: VpnRuntimeStatus, message: String) {
        rebuildState(
            runtimeStatus = status,
            statusMessage = message,
            runningCandidateName = null,
        )
    }

    fun updateRuntimeStatus(status: VpnRuntimeStatus, message: String) {
        rebuildState(
            runtimeStatus = status,
            statusMessage = message,
            runningCandidateName = stateFlow.value.runningCandidateName,
        )
    }

    private fun rebuildState(
        runtimeStatus: VpnRuntimeStatus = stateFlow.value.runtimeStatus,
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
            val coreState = MihomoCoreManager.inspectCore(context)
            val configCacheState = VpnConfigCacheManager.inspect(context, config)
            VpnCandidateState(
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
        val globalCoreState = MihomoCoreManager.inspectCore(context)
        val normalizedRuntimeStatus = when {
            !enabled -> VpnRuntimeStatus.disabled
            runtimeStatus == VpnRuntimeStatus.disabled -> VpnRuntimeStatus.idle
            else -> runtimeStatus
        }
        val normalizedMessage = when {
            !enabled -> "VPN is disabled"
            statusMessage.isBlank() && normalizedRuntimeStatus == VpnRuntimeStatus.idle -> "VPN 已启用，等待启动核心"
            else -> statusMessage
        }
        updateState(
            VpnUiState(
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
                candidates = candidates.map { it.copy(isSelected = it.config.name == activeName) },
            ),
        )
    }

    private fun updateState(next: VpnUiState) {
        stateFlow.value = next
    }

    private fun resolveActiveCandidateName(
        candidates: List<VpnCandidateState>,
        selectionHistory: List<String>,
    ): String? {
        val availableNames = candidates.filter { it.isAvailable }.map { it.config.name }.toSet()
        selectionHistory.firstOrNull { it in availableNames }?.let { return it }
        return candidates.firstOrNull { it.isAvailable }?.config?.name
    }
}
