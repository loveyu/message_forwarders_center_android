package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.config.VpnAccessControlMode
import info.loveyu.mfca.config.VpnInputConfig
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.ServerSocket
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
    @Volatile private var configDownloadProxy: String? = null

    private fun effectiveDownloadProxy(): String? {
        val override = store?.getDownloadProxy()?.trim()?.takeIf { it.isNotBlank() }
        return override ?: configDownloadProxy
    }

    fun initialize(context: Context, vpnConfigs: List<VpnInputConfig>, pluginUrl: String? = null, downloadProxy: String? = null) {
        appContext = context.applicationContext
        store = VpnStateStore(context.applicationContext)
        configs = vpnConfigs
        m2mCoreUrl = pluginUrl
        configDownloadProxy = downloadProxy
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
        MihomoCoreManager.downloadCore(context, url, effectiveDownloadProxy()).getOrThrow()
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

        // Step 1: Ensure config is cached (auto-download if needed)
        val cachedSource =
            VpnConfigCacheManager.getCachedSourceFile(context, selected.config.name)
                ?: run {
                    updateRuntimeStatus(VpnRuntimeStatus.preparing, "Downloading config for ${selected.config.name}")
                    LogManager.logInfo("VPN", "Config not cached, auto-downloading for ${selected.config.name}")
                    VpnConfigCacheManager.downloadConfig(context, selected.config)
                        .getOrNull()
                        ?.let { VpnConfigCacheManager.getCachedSourceFile(context, selected.config.name) }
                }
        if (cachedSource == null) {
            LogManager.logError("VPN", "Failed to download config for ${selected.config.name}")
            updateRuntimeStatus(VpnRuntimeStatus.error, "Failed to download config for ${selected.config.name}")
            return Result.failure(IllegalStateException("Failed to download config for ${selected.config.name}"))
        }

        // Step 2: Ensure core is available (auto-download if needed)
        return MihomoCoreManager.ensureCore(context, m2mCoreUrl, effectiveDownloadProxy()).map { coreFile ->
            LogManager.logInfo("VPN", "Prepared mihomo core for ${selected.config.name}: ${coreFile.absolutePath}")
            val effectivePort = store?.getLocalPort(selected.config.name) ?: LOCAL_PROXY_PORT
            val effectiveRuleMode = store?.getRuleMode(selected.config.name)
            val effectiveLogLevel = store?.getLogLevel(selected.config.name)
            val effectiveUdpRelay = store?.getUdpRelay(selected.config.name) ?: false
            val effectiveIpv6 = store?.getIpv6(selected.config.name) ?: false
            val effectiveDnsHijack = store?.getDnsHijack(selected.config.name) ?: true
            val apiPort = ServerSocket(0).use { it.localPort }
            val apiSecret = store?.getOrCreateApiSecret() ?: ""
            LogManager.logDebug("VPN", "Effective settings for ${selected.config.name}: port=$effectivePort, apiPort=$apiPort, ruleMode=$effectiveRuleMode, logLevel=$effectiveLogLevel, udpRelay=$effectiveUdpRelay, dnsHijack=$effectiveDnsHijack")
            val profileFile = VpnProfileManager.buildRuntimeProfile(
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
            PreparedVpnArtifacts(
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
            updateRuntimeStatus(VpnRuntimeStatus.prepared, "Prepared ${it.candidate.name}")
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
                downloadProxy = effectiveDownloadProxy() ?: "",
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
