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

            /**
             * A start attempt failed outright, so protection was never obtained.
             *
             * Covers boot restore, a backend switch from Settings, and the toggle itself.
             */
            START_FAILED,
        }
    }

    /**
     * Rules are still being enforced, but through VPN after the preferred backend failed.
     *
     * Protection is intact, so this is informational.
     *
     * It persists until the firewall is stopped or restarted. The only path that sets it,
     * FirewallManager.startVpnFallback, stops health monitoring and never restarts it, so there is
     * no "next successful check" to clear it.
     */
    data class SwitchedToVpn(
        val failedBackend: FirewallBackendType,
        /** True when the user had picked that backend by hand, so the mode also fell back to AUTO. */
        val fromManualMode: Boolean
    ) : FirewallHealth

    /**
     * The user asked to stop the firewall and the running backend refused to tear down.
     *
     * The mirror image of [Down]: there, the user wants blocking and is getting none; here, the user
     * wants none and may still be getting some. Apps can be offline with every control showing OFF,
     * which is why this needs to be said out loud rather than logged.
     *
     * Not critical - nothing is unprotected - so it is styled as a warning, not as FIREWALL DOWN.
     */
    data class StopFailed(
        /** The backend that would not stop, when one is known. */
        val backend: FirewallBackendType?
    ) : FirewallHealth
}
