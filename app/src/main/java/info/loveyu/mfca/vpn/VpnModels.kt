package info.loveyu.mfca.vpn

import info.loveyu.mfca.config.VpnAccessControlMode
import info.loveyu.mfca.config.VpnInputConfig

enum class VpnRuntimeStatus {
    disabled,
    idle,
    preparing,
    prepared,
    starting,
    running,
    stopping,
    error,
}

enum class VpnCoreSourceType {
    remote,
    local,
}

data class VpnCoreState(
    val source: String,
    val sourceType: VpnCoreSourceType,
    val resolvedSourcePath: String? = null,
    val cachePath: String? = null,
    val archivePath: String? = null,
    val sourceExists: Boolean = true,
    val isReady: Boolean = false,
    val errorMessage: String? = null,
)

data class VpnCandidateState(
    val config: VpnInputConfig,
    val effectiveAccessControlMode: VpnAccessControlMode,
    val effectivePackages: List<String>,
    val coreState: VpnCoreState,
    val isAvailable: Boolean,
    val availabilityReason: String? = null,
    val isSelected: Boolean = false,
)

data class PreparedVpnArtifacts(
    val candidate: VpnInputConfig,
    val coreFilePath: String,
    val profileFilePath: String,
    val localProxyPort: Int,
)

data class VpnUiState(
    val hasVpnConfig: Boolean = false,
    val isEnabled: Boolean = false,
    val runtimeStatus: VpnRuntimeStatus = VpnRuntimeStatus.disabled,
    val statusMessage: String = "",
    val activeCandidateName: String? = null,
    val runningCandidateName: String? = null,
    val isRuntimeOutOfSync: Boolean = false,
    val candidates: List<VpnCandidateState> = emptyList(),
)
