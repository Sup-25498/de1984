package io.github.dorumrr.de1984.domain.firewall

/**
 * Whether the firewall is actually enforcing rules, in a form the UI can render.
 *
 * This is deliberately typed rather than a message string. The app ships seven locales, so the
 * data layer must not decide the wording - it reports what happened and the UI translates it.
 *
 * Distinct from `FirewallManager.FirewallState`, which tracks the backend lifecycle
 * (Stopped/Starting/Running/Error). Two states can be Error for very different reasons; this type
 * carries the reason, and therefore what the user can do about it.
 */
sealed interface FirewallHealth {

    /** Rules are being enforced, or the firewall is intentionally off. Nothing to warn about. */
    data object Healthy : FirewallHealth

    /**
     * The firewall is not enforcing anything. Every app has full network access right now.
     *
     * The user always wanted it on in this state - a deliberate stop reports [Healthy] instead.
     */
    data class Down(
        val reason: Reason,
        /** The backend that was lost, when one is known. */
        val backend: FirewallBackendType?
    ) : FirewallHealth {

        enum class Reason {
            /** A manually chosen backend failed. No automatic fallback is attempted by design. */
            MANUAL_BACKEND_FAILED,

            /** No usable backend could be planned on this device. */
            NO_FALLBACK_PLAN,

            /** A fallback backend was chosen but refused to start. */
            FALLBACK_FAILED,

            /** Another VPN app holds the VPN slot, and no privileged backend can take over. */
            VPN_CONFLICT,

            /** VPN fallback is possible but the user has not granted VPN permission. */
            VPN_PERMISSION_REQUIRED,
        }
    }

    /**
     * Rules are still being enforced, but through VPN after the preferred backend failed.
     *
     * Protection is intact, so this is informational. The health monitor clears it back to
     * [Healthy] on the next successful check.
     */
    data class SwitchedToVpn(
        val failedBackend: FirewallBackendType,
        /** True when the user had picked that backend by hand, so the mode also fell back to AUTO. */
        val fromManualMode: Boolean
    ) : FirewallHealth
}
