package info.loveyu.mfca.output

/**
 * Marker interface for outputs that fan-out to multiple sub-targets.
 * When RuleEngine encounters a FanOut output with sub-targets that have their own
 * queueRef, it enqueues one record per sub-target to allow independent retry per upstream.
 */
interface FanOut {
    fun subTargets(): List<Output>
}
