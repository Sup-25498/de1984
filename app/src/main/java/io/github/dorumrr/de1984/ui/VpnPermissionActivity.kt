package io.github.dorumrr.de1984.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transparent activity that handles VPN permission requests from widget/tile.
 * 
 * This activity has no UI - it only shows the system VPN permission dialog
 * and then starts the firewall and finishes immediately.
 */
class VpnPermissionActivity : Activity() {

    companion object {
        private const val TAG = "VpnPermissionActivity"
        private const val REQUEST_VPN_PERMISSION = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d(TAG, "VpnPermissionActivity created")
        
        // Make absolutely sure we're invisible
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Check if VPN permission is needed
        val prepareIntent = VpnService.prepare(this)
        AppLogger.d(TAG, "VpnService.prepare() returned: ${if (prepareIntent == null) "null (permission granted)" else "Intent (need permission)"}")
        
        if (prepareIntent != null) {
            // Need to request VPN permission - show system dialog
            AppLogger.d(TAG, "🔐 Requesting VPN permission via system dialog...")
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else {
            // Already have permission, start firewall directly - NO UI at all
            AppLogger.d(TAG, "✅ VPN permission already granted, starting firewall silently...")
            startFirewallAndFinish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                AppLogger.d(TAG, "VPN permission granted")
                startFirewallAndFinish()
            } else {
                AppLogger.d(TAG, "VPN permission denied")
                finish()
            }
        }
    }

    private fun startFirewallAndFinish() {
        val app = application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        
        scope.launch(Dispatchers.IO) {
            // Every widget and tile start on a device without root lands here, so this is the path
            // that matters most. It used to hard-code AUTO, throwing away the mode the user picked,
            // and to record "firewall enabled" whether or not the start worked.
            val mode = firewallManager.getCurrentMode()
            AppLogger.d(TAG, "Starting firewall in persisted mode: $mode")
            val result = firewallManager.startFirewall(mode)
            AppLogger.d(TAG, "startFirewall result: $result")

            result
                .onSuccess {
                    val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()
                }
                .onFailure { error ->
                    // Left untouched on purpose. The preference records what the user wants, and a
                    // start that never happened is not evidence they want it on - writing true here
                    // told boot restore to bring back a firewall that was never up.
                    AppLogger.e(TAG, "Start after VPN permission failed, KEY_FIREWALL_ENABLED untouched", error)
                }

            // Finish the activity
            runOnUiThread {
                finish()
            }
        }
    }
}
