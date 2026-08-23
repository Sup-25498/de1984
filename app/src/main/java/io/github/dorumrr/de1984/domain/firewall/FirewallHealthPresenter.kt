package io.github.dorumrr.de1984.domain.firewall

import android.content.Context
import androidx.annotation.StringRes
import io.github.dorumrr.de1984.R

/**
 * What the user can do about a firewall problem, and the label of the button that does it.
 *
 * The activity maps each of these onto an existing recovery path, so the banner button and the
 * notification it mirrors always lead to the same place.
 */
enum class FirewallHealthAction(@StringRes val label: Int) {
    /** Open Settings, where the backend can be changed. */
    CHOOSE_BACKEND(R.string.firewall_down_action_choose_backend),

    /** Start the firewall again from scratch. */
    RETRY(R.string.firewall_down_action_retry),

    /** Ask for VPN permission so the VPN fallback can run. */
    ENABLE_VPN(R.string.firewall_down_action_enable_vpn),

    /** Take the VPN slot from whichever app currently holds it. */
    REPLACE_VPN(R.string.firewall_down_action_replace_vpn),
}

/**
 * Turns a [FirewallHealth] into the words a user reads.
 *
 * Single source for this wording. The in-app banner and the notification both come through here, so
 * they can never drift apart, and every sentence stays translatable - the reason the data layer
 * stopped composing English strings of its own.
 *
 * Lives in domain, not ui, because FirewallManager needs it for the notification text. Putting it in
 * ui would make the data layer depend on the UI layer. Holding a Context to resolve strings matches
 * what the other domain models here already do (see CaptivePortalMode.getDisplayName).
 */
object FirewallHealthPresenter {

    /** Headline, or null when there is nothing to warn about. */
    fun title(context: Context, health: FirewallHealth): String? = when (health) {
        is FirewallHealth.Healthy -> null
        is FirewallHealth.Down -> context.getString(R.string.firewall_down_title)
        is FirewallHealth.SwitchedToVpn -> context.getString(R.string.firewall_switched_title)
    }

    /** What went wrong and what it means, or null when there is nothing to warn about. */
    fun message(context: Context, health: FirewallHealth): String? = when (health) {
        is FirewallHealth.Healthy -> null

        is FirewallHealth.Down -> when (health.reason) {
            FirewallHealth.Down.Reason.MANUAL_BACKEND_FAILED -> {
                val backendName = health.backend?.displayName(context)
                if (backendName != null) {
                    context.getString(R.string.firewall_down_reason_manual_backend, backendName)
                } else {
                    context.getString(R.string.firewall_down_reason_manual_backend_unknown)
                }
            }
            FirewallHealth.Down.Reason.NO_FALLBACK_PLAN ->
                context.getString(R.string.firewall_down_reason_no_plan)
            FirewallHealth.Down.Reason.FALLBACK_FAILED ->
                context.getString(R.string.firewall_down_reason_fallback_failed)
            FirewallHealth.Down.Reason.VPN_CONFLICT ->
                context.getString(R.string.firewall_down_reason_vpn_conflict)
            FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED ->
                context.getString(R.string.firewall_down_reason_vpn_permission)
            FirewallHealth.Down.Reason.START_FAILED ->
                context.getString(R.string.firewall_down_reason_start_failed)
        }

        is FirewallHealth.SwitchedToVpn -> {
            val backendName = health.failedBackend.displayName(context)
            if (health.fromManualMode) {
                context.getString(R.string.firewall_switched_message_manual, backendName)
            } else {
                context.getString(R.string.firewall_switched_message_auto, backendName)
            }
        }
    }

    /** The one useful thing to offer, or null when there is nothing the user can do from here. */
    fun action(health: FirewallHealth): FirewallHealthAction? = when (health) {
        is FirewallHealth.Healthy -> null

        // Protection is intact, so there is nothing to fix
        is FirewallHealth.SwitchedToVpn -> null

        is FirewallHealth.Down -> when (health.reason) {
            FirewallHealth.Down.Reason.MANUAL_BACKEND_FAILED -> FirewallHealthAction.CHOOSE_BACKEND
            FirewallHealth.Down.Reason.NO_FALLBACK_PLAN -> FirewallHealthAction.CHOOSE_BACKEND
            FirewallHealth.Down.Reason.FALLBACK_FAILED -> FirewallHealthAction.RETRY
            FirewallHealth.Down.Reason.VPN_CONFLICT -> FirewallHealthAction.REPLACE_VPN
            FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED -> FirewallHealthAction.ENABLE_VPN
            FirewallHealth.Down.Reason.START_FAILED -> FirewallHealthAction.RETRY
        }
    }

    /**
     * True when nothing is being blocked right now.
     *
     * Drives the red styling and the DOWN badge. [FirewallHealth.SwitchedToVpn] is deliberately not
     * critical: protection is still on, just not on the backend the user had.
     */
    fun isCritical(health: FirewallHealth): Boolean = health is FirewallHealth.Down
}
