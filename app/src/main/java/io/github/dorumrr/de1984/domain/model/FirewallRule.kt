package io.github.dorumrr.de1984.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model for firewall rules.
 *
 * @property packageName The package name of the app
 * @property userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 * @property uid Absolute UID: userId * 100000 + appId
 */
@Serializable
data class FirewallRule(
    val packageName: String,
    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,
    /** Absolute UID: userId * 100000 + appId */
    val uid: Int,
    val appName: String,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val blockWhenBackground: Boolean = false,
    val blockWhenRoaming: Boolean = false,
    val lanBlocked: Boolean = false,
    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
    val hasInternetPermission: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isFullyBlocked(): Boolean = wifiBlocked && mobileBlocked
    
    /** Nothing blocked at all - LAN included. See NetworkPackage.isFullyAllowed. */
    fun isFullyAllowed(): Boolean = !wifiBlocked && !mobileBlocked && !blockWhenRoaming && !lanBlocked
    
    fun isPartiallyBlocked(): Boolean = (wifiBlocked || mobileBlocked) && !isFullyBlocked()
    
    /**
     * Whether this rule blocks the app on [networkType].
     *
     * [NetworkType.NONE] keeps the block in force rather than lifting it. Nothing can connect with
     * no network, so holding the block costs nothing, and lifting it opened a gap: when a network
     * appeared, every app was unblocked until the next rule pass landed - about a second on a light
     * rule set. The VPN backend already worked around this locally; this is the same rule, in one
     * place, for every backend.
     */
    fun isBlockedOn(networkType: NetworkType): Boolean {
        if (!enabled) return false
        
        return when (networkType) {
            NetworkType.WIFI -> wifiBlocked
            NetworkType.MOBILE -> mobileBlocked
            NetworkType.ROAMING -> blockWhenRoaming || mobileBlocked
            NetworkType.NONE -> wifiBlocked || mobileBlocked || blockWhenRoaming
        }
    }
    
    fun getBlockingStatus(): String {
        return when {
            !enabled -> "Disabled"
            isFullyBlocked() -> "Fully Blocked"
            isFullyAllowed() -> "Allowed"
            wifiBlocked && !mobileBlocked -> "WiFi Blocked"
            !wifiBlocked && mobileBlocked -> "Mobile Blocked"
            else -> "Partially Blocked"
        }
    }
    
    /**
     * "All" is WiFi + Mobile + Roaming + LAN.
     *
     * [blockWhenBackground] is deliberately NOT part of it: it is a condition, not a network, and an
     * app blocked on every network is already blocked while the screen is off. Every other path that
     * claims to block or allow "all" must cover exactly these four.
     */
    fun blockAll(): FirewallRule = copy(
        wifiBlocked = true,
        mobileBlocked = true,
        blockWhenRoaming = true,
        lanBlocked = true,
        updatedAt = System.currentTimeMillis()
    )
    
    fun allowAll(): FirewallRule = copy(
        wifiBlocked = false,
        mobileBlocked = false,
        blockWhenRoaming = false,
        lanBlocked = false,
        updatedAt = System.currentTimeMillis()
    )
}

