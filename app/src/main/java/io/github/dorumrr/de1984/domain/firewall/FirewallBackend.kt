package io.github.dorumrr.de1984.domain.firewall

import android.content.Context
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType

interface FirewallBackend {

    suspend fun start(): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit>

    fun isActive(): Boolean

    fun getType(): FirewallBackendType

    suspend fun checkAvailability(): Result<Unit>

    fun supportsGranularControl(): Boolean
}

enum class FirewallBackendType {
    VPN,

    IPTABLES,

    CONNECTIVITY_MANAGER,

    NETWORK_POLICY_MANAGER;

    fun displayName(context: Context): String = context.getString(
        when (this) {
            VPN -> R.string.backend_vpn_name
            IPTABLES -> R.string.backend_iptables_name
            CONNECTIVITY_MANAGER -> R.string.backend_connectivity_manager_name
            NETWORK_POLICY_MANAGER -> R.string.backend_network_policy_manager_name
        }
    )
}

enum class FirewallMode {
    AUTO,

    VPN,

    IPTABLES,

    CONNECTIVITY_MANAGER,

    NETWORK_POLICY_MANAGER;

    companion object {
        fun fromString(value: String?): FirewallMode? {
            return when (value?.lowercase()) {
                "auto" -> AUTO
                "vpn" -> VPN
                "iptables" -> IPTABLES
                "connectivity_manager" -> CONNECTIVITY_MANAGER
                "network_policy_manager" -> NETWORK_POLICY_MANAGER
                else -> null
            }
        }

        fun FirewallMode.toStorageString(): String {
            return when (this) {
                AUTO -> "auto"
                VPN -> "vpn"
                IPTABLES -> "iptables"
                CONNECTIVITY_MANAGER -> "connectivity_manager"
                NETWORK_POLICY_MANAGER -> "network_policy_manager"
            }
        }
    }
}

