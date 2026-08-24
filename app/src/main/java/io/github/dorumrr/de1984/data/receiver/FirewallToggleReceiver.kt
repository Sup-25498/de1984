package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.ui.widget.FirewallWidget
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FirewallToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FirewallToggleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.d(TAG, "━━━━━ onReceive() called ━━━━━")
        AppLogger.d(TAG, "Received intent action: ${intent?.action}")
        AppLogger.d(TAG, "Intent extras: ${intent?.extras}")
        
        if (intent?.action != Constants.Firewall.ACTION_TOGGLE_FIREWALL) {
            AppLogger.d(TAG, "⚠️ Action mismatch, ignoring. Expected: ${Constants.Firewall.ACTION_TOGGLE_FIREWALL}")
            return
        }

        AppLogger.d(TAG, "✅ ACTION_TOGGLE_FIREWALL received!")

        val app = context.applicationContext as De1984Application
        val firewallManager = app.dependencies.firewallManager
        
        AppLogger.d(TAG, "FirewallManager obtained, isActive=${firewallManager.isActive()}")

        val pendingResult = goAsync()
        AppLogger.d(TAG, "goAsync() called, starting coroutine...")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val isCurrentlyActive = firewallManager.isActive()
                AppLogger.d(TAG, "Current firewall state: isActive=$isCurrentlyActive")
                
                if (isCurrentlyActive) {
                    AppLogger.d(TAG, "🔴 Firewall is active, opening app for stop confirmation...")
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        action = Constants.Firewall.ACTION_TOGGLE_FIREWALL
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(activityIntent)
                    AppLogger.d(TAG, "MainActivity launched for stop confirmation")
                } else {
                    AppLogger.d(TAG, "🟢 Firewall is stopped, starting directly...")

                    FirewallWidget.setLoadingState(context)

                    // Honour the mode the user picked in Settings. Hard-coding AUTO here started a
                    // different backend than the one they chose, and a manual VPN choice was ignored
                    // every time the firewall was started from the widget or the tile.
                    val persistedMode = firewallManager.getCurrentMode()
                    AppLogger.d(TAG, "Using persisted firewall mode: $persistedMode")

                    var mode = persistedMode
                    var planResult = firewallManager.computeStartPlan(mode)

                    // A manual mode whose backend is no longer available - root lost, Shizuku gone -
                    // makes computeStartPlan fail outright. Hard-coded AUTO used to reach VPN here,
                    // so honouring the mode without this would cost the user the ability to start
                    // the firewall from the widget at all. Honour the choice, then fall back.
                    if (planResult.isFailure && mode != FirewallMode.AUTO) {
                        AppLogger.w(TAG, "Persisted mode $mode is unavailable (${planResult.exceptionOrNull()?.message}); falling back to AUTO")
                        mode = FirewallMode.AUTO
                        planResult = firewallManager.computeStartPlan(mode)
                    }

                    val plan = planResult.getOrNull()
                    AppLogger.d(TAG, "computeStartPlan result: $plan")
                    AppLogger.d(TAG, "requiresVpnPermission: ${plan?.requiresVpnPermission}")

                    if (plan?.requiresVpnPermission == true) {
                        AppLogger.d(TAG, "🔐 VPN permission required, launching transparent VpnPermissionActivity...")
                        // Launch transparent activity in its own task to handle VPN permission dialog only
                        // Using NEW_TASK + MULTIPLE_TASK + NO_ANIMATION to avoid bringing main app to focus
                        val activityIntent = Intent(context, io.github.dorumrr.de1984.ui.VpnPermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            // Hand over the mode already resolved above. Without this the activity
                            // re-read the preference and started the unavailable backend again,
                            // undoing the AUTO fallback for the case it was written for.
                            putExtra(
                                io.github.dorumrr.de1984.ui.VpnPermissionActivity.EXTRA_RESOLVED_MODE,
                                mode.name
                            )
                        }
                        context.startActivity(activityIntent)
                        AppLogger.d(TAG, "VpnPermissionActivity launched")
                    } else {
                        AppLogger.d(TAG, "🚀 No VPN permission needed, starting firewall directly...")
                        val startResult = firewallManager.startFirewall(mode)
                        AppLogger.d(TAG, "startFirewall() result: $startResult")

                        // Only record "enabled" when the start actually succeeded. Writing it
                        // unconditionally told boot restore the firewall had been running when it
                        // never started, so the next reboot tried to restore a firewall that was
                        // never up - and the widget showed ON over an unprotected device.
                        startResult
                            .onSuccess {
                                val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                                AppLogger.d(TAG, "SharedPreferences updated: KEY_FIREWALL_ENABLED=true")
                            }
                            .onFailure { error ->
                                // The widget clears its own loading state: a failed start reports
                                // down through FirewallManager, which broadcasts the new state and
                                // FirewallWidget.onReceive redraws from it.
                                AppLogger.e(TAG, "❌ Start from widget/tile failed, leaving KEY_FIREWALL_ENABLED untouched", error)
                            }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Error toggling firewall", e)
            } finally {
                AppLogger.d(TAG, "Coroutine complete, calling pendingResult.finish()")
                pendingResult.finish()
            }
        }
    }
}
