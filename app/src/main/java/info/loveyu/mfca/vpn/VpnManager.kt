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

    fun initialize(context: Context, vpnConfigs: List<VpnInputConfig>) {
        appContext = context.applicationContext
        store = VpnStateStore(context.applicationContext)
        configs = vpnConfigs
        rebuildState()
    }

    fun clear() {
        configs = emptyList()
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

    fun getSelectedCandidate(): VpnCandidateState? {
        return stateFlow.value.candidates.firstOrNull { it.isSelected && it.isAvailable }
    }

    fun prepareSelectedCandidate(context: Context): Result<PreparedVpnArtifacts> {
        val selected = getSelectedCandidate()
            ?: return Result.failure(IllegalStateException("No available VPN candidate selected"))
        updateRuntimeStatus(VpnRuntimeStatus.preparing, "Preparing ${selected.config.name}")
        return MihomoCoreManager.ensureCore(context, selected.config.coreUrl).fold(
            onSuccess = { coreFile ->
                VpnProfileManager.ensureProfile(context, selected.config, LOCAL_PROXY_PORT).map { profileFile ->
                    PreparedVpnArtifacts(
                        candidate = selected.config,
                        coreFilePath = coreFile.absolutePath,
                        profileFilePath = profileFile.absolutePath,
                        localProxyPort = LOCAL_PROXY_PORT,
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
            val availability = if (!config.enabled) {
                NetworkChecker.EnableResult(enabled = false, reason = "Disabled in config")
            } else {
                NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
            }
            VpnCandidateState(
                config = config,
                effectiveAccessControlMode = effectiveMode,
                effectivePackages = effectivePackages,
                isAvailable = availability.enabled,
                availabilityReason = availability.reason,
            )
        }
        val activeName = resolveActiveCandidateName(candidates, selectionHistory)
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
