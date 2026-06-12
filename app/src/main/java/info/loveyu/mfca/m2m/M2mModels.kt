package info.loveyu.mfca.m2m

import info.loveyu.mfca.config.models.M2mAccessControlMode
import info.loveyu.mfca.config.models.M2mInputConfig

enum class M2mRuleMode {
    rule,
    global,
    direct,
}

enum class M2mLogLevel {
    debug,
    info,
    warning,
    error,
    silent,
}

enum class M2mRuntimeStatus {
    disabled,
    idle,
    preparing,
    prepared,
    starting,
    running,
    stopping,
    error,
}

data class M2mCoreState(
    val isReady: Boolean = false,
    val path: String? = null,
    val pluginVersion: String? = null,
)

data class M2mConfigCacheState(
    val isCached: Boolean = false,
    val lastUpdatedMs: Long? = null,
    val nextRefreshMs: Long? = null,
    val filePath: String? = null,
)

data class M2mCandidateState(
    val config: M2mInputConfig,
    val effectiveAccessControlMode: M2mAccessControlMode,
    val effectivePackages: List<String>,
    val coreState: M2mCoreState,
    val configCacheState: M2mConfigCacheState,
    val isAvailable: Boolean,
    val availabilityReason: String? = null,
    val isSelected: Boolean = false,
)

data class PreparedM2mArtifacts(
    val candidate: M2mInputConfig,
    val coreFilePath: String,
    val profileFilePath: String,
    val localProxyPort: Int,
    val apiPort: Int,
    val apiSecret: String,
    val udpRelay: Boolean = false,
    val dnsHijack: Boolean = true,
    val logLevel: M2mLogLevel? = null,
    val ipv6: Boolean = false,
)

data class M2mTrafficStats(
    val totalRxBytes: Long = 0,
    val totalTxBytes: Long = 0,
    val rxSpeed: Long = 0,
    val txSpeed: Long = 0,
)

enum class M2mProviderType { Proxy, Rule }

enum class M2mVehicleType { HTTP, File, Compatible }

data class M2mProviderInfo(
    val name: String,
    val type: M2mProviderType,
    val vehicleType: M2mVehicleType,
    val updatedAt: String = "",
    val proxyCount: Int = 0,
    val ruleCount: Int = 0,
    val subscriptionUrl: String = "",
) {
    fun hasValidUpdatedAt(): Boolean {
        if (updatedAt.isBlank() || updatedAt == "0") return false
        if (updatedAt.startsWith("0001")) return false
        return true
    }
}

data class M2mUiState(
    val hasVpnConfig: Boolean = false,
    val isEnabled: Boolean = false,
    val runtimeStatus: M2mRuntimeStatus = M2mRuntimeStatus.disabled,
    val statusMessage: String = "",
    val activeCandidateName: String? = null,
    val runningCandidateName: String? = null,
    val isRuntimeOutOfSync: Boolean = false,
    val coreState: M2mCoreState = M2mCoreState(),
    val m2mCoreUrl: String = "",
    val downloadProxy: String = "",
    val candidates: List<M2mCandidateState> = emptyList(),
) {
    val isBusy: Boolean
        get() = runtimeStatus in setOf(M2mRuntimeStatus.preparing, M2mRuntimeStatus.starting, M2mRuntimeStatus.stopping)
}
