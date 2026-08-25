package io.github.dorumrr.de1984.data.receiver

import io.github.dorumrr.de1984.utils.AppLogger
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.service.BackendMonitoringService
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.data.worker.BootWorker
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "De1984.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        AppLogger.d(TAG, "🔄 BOOT RECEIVER TRIGGERED | Action: $action | Android Version: ${Build.VERSION.SDK_INT} (API ${Build.VERSION.SDK_INT})")

        when (action) {
            // LOCKED_BOOT_COMPLETED is deliberately not handled - see the receiver's manifest
            // entry. Everything below reads credential-encrypted storage, which is unreadable
            // before the user unlocks, and on a device with no lock screen it made the whole
            // restore run twice.
            Intent.ACTION_BOOT_COMPLETED -> {
                val bootType = "BOOT_COMPLETED (after user unlock)"
                AppLogger.d(TAG, "📱 Device boot completed - $bootType")

                // iptables rules live in the kernel, so a reboot wipes them - but the
                // "chains are installed" record is on disk and survives. Left stale, it makes the
                // first stop after a boot report "the firewall would not stop" on a device with no
                // chains at all, whenever the probe cannot run yet (Shizuku takes a while to
                // connect after boot). Clearing it here is safe: boot protection uses its own
                // de1984_boot chain, never de1984_output, so nothing can have recreated it yet.
                clearIptablesChainRecord(context)

                // Android 12+ (API 31+): Use WorkManager to avoid foreground service restrictions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AppLogger.d(TAG, "Android 12+ detected - scheduling WorkManager job for firewall restoration")
                    scheduleBootWorker(context)
                } else {
                    AppLogger.d(TAG, "Android 11 or below - directly restoring firewall state")
                    restoreFirewallState(context, bootType)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLogger.d(TAG, "📦 App package replaced - checking if firewall should be restored")

                restoreFirewallState(context, "PACKAGE_REPLACED")
            }
            else -> {
                AppLogger.w(TAG, "⚠️ Unknown action received: $action")
            }
        }

    }

    private fun scheduleBootWorker(context: Context) {
        try {
            AppLogger.d(TAG, "Scheduling BootWorker...")

            val workRequest = OneTimeWorkRequestBuilder<BootWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                BootWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            AppLogger.d(TAG, "✅ BootWorker scheduled successfully")

        } catch (e: IllegalStateException) {
            // WorkManager not initialized yet (can happen at boot time)
            // Fall back to direct restoration
            AppLogger.e(TAG, "❌ Failed to schedule BootWorker: WorkManager not initialized", e)
            AppLogger.d(TAG, "⚠️ Falling back to direct firewall restoration")
            restoreFirewallState(context, "BOOT_COMPLETED (WorkManager fallback)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to schedule BootWorker", e)
        }
    }

    private fun restoreFirewallState(context: Context, trigger: String) {
        try {
            AppLogger.d(TAG, "restoreFirewallState: trigger=$trigger")

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            AppLogger.d(TAG, "Firewall was enabled before $trigger: $wasEnabled")

            if (wasEnabled) {
                AppLogger.d(TAG, "✅ Firewall was enabled - proceeding with restoration")

                val app = context.applicationContext as? De1984Application
                if (app != null) {
                    val firewallManager = app.dependencies.firewallManager
                    val shizukuManager = app.dependencies.shizukuManager
                    val rootManager = app.dependencies.rootManager

                    val pendingResult = goAsync()

                    app.dependencies.applicationScope.launch(Dispatchers.IO) {
                        try {
                            // CRITICAL: Request root permission FIRST to wake up Magisk
                            // Magisk doesn't grant root permission until the app requests it after boot/update
                            // Without this, FirewallManager.selectBackend() will think root is not available
                            // and fall back to VPN backend, which kills user's third-party VPN (like Proton VPN)
                            AppLogger.d(TAG, "Requesting root permission to wake up Magisk...")
                            rootManager.forceRecheckRootStatus()

                            kotlinx.coroutines.delay(500)

                            // Wait for Shizuku to be initialized before starting firewall
                            // This is important for ACTION_MY_PACKAGE_REPLACED (app update)
                            // where Shizuku may not be fully initialized yet
                            AppLogger.d(TAG, "Checking Shizuku status before starting firewall...")
                            shizukuManager.checkShizukuStatus()

                            kotlinx.coroutines.delay(500)

                            AppLogger.d(TAG, "🚀 Starting firewall after $trigger...")
                            val result = firewallManager.startFirewall()
                            result.onSuccess { backendType ->
                                AppLogger.d(TAG, "✅ FIREWALL RESTORED SUCCESSFULLY | Trigger: $trigger | Backend: $backendType")

                                // Lift the boot-protection block. Keyed on the script actually
                                // being on disk, not on the preference: clearing app data resets
                                // the preference to false and leaves the script in place, which is
                                // the case clearBootBlockIfInstalled exists for. The other two
                                // paths already use it; this one used to read the preference and
                                // would leave the de1984_boot DROP chain up for its full timeout.
                                AppLogger.d(TAG, "Lifting any boot protection block after successful start")
                                try {
                                    val bootProtectionManager = app.dependencies.bootProtectionManager
                                    val resetResult = bootProtectionManager.clearBootBlockIfInstalled()
                                    if (resetResult.isSuccess) {
                                        AppLogger.d(TAG, "✅ Boot protection block lifted (or none present)")
                                    } else {
                                        AppLogger.e(TAG, "❌ Failed to lift boot protection block: ${resetResult.exceptionOrNull()?.message}")
                                    }
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "❌ Exception while lifting boot protection block", e)
                                }

                                if (backendType == FirewallBackendType.VPN) {
                                    val currentMode = firewallManager.getCurrentMode()
                                    val shizukuStatus = shizukuManager.shizukuStatus.value

                                    val shouldMonitor = currentMode == FirewallMode.AUTO &&
                                        (shizukuStatus == ShizukuStatus.INSTALLED_NOT_RUNNING ||
                                         shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION)

                                    if (shouldMonitor) {
                                        AppLogger.d(TAG, "Started with VPN fallback (Shizuku status: $shizukuStatus). Starting backend monitoring service...")
                                        val monitorIntent = Intent(context, BackendMonitoringService::class.java).apply {
                                            action = Constants.BackendMonitoring.ACTION_START
                                            putExtra(Constants.BackendMonitoring.EXTRA_SHIZUKU_STATUS, shizukuStatus.name)
                                        }

                                        try {
                                            context.startForegroundService(monitorIntent)
                                            AppLogger.d(TAG, "Backend monitoring service started successfully")
                                        } catch (e: Exception) {
                                            AppLogger.e(TAG, "Failed to start backend monitoring service", e)
                                        }
                                    } else {
                                        AppLogger.d(TAG, "Backend monitoring not needed. Mode: $currentMode, Shizuku: $shizukuStatus")
                                    }
                                }
                            }.onFailure { error ->
                                AppLogger.e(TAG, "❌ FAILED TO RESTORE FIREWALL | Trigger: $trigger | Error: ${error.message}")

                                // A failed start must not leave the boot-protection block in place.
                                // Protection is already gone at this point; keeping the block only
                                // takes the device offline with no in-app way to recover.
                                try {
                                    app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "Failed to lift boot protection block", e)
                                }

                                showBootFailureNotification(context)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Not a failure. Swallowing it here would log a boot-restore error and
                            // post a failure notification to the user for an ordinary scope shutdown,
                            // and would break structured concurrency - the same reason ErrorHandler
                            // re-throws it before anything else.
                            throw e
                        } catch (e: Exception) {
                            // Without this the block below never ran. try/finally alone lets a throw
                            // from anywhere above - a wedged root probe, a backend blowing up - skip
                            // every exit that lifts the boot-protection chain, so the device came up
                            // with no network for any app and nothing in the process left to undo it.
                            // Only the script's own 120-second timer saved the user.
                            AppLogger.e(TAG, "❌ BOOT RESTORE THREW | Lifting the boot protection block so the device is not left offline", e)
                            try {
                                app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                            } catch (lift: Exception) {
                                AppLogger.e(TAG, "Failed to lift boot protection block after a throw", lift)
                            }
                            showBootFailureNotification(context)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    AppLogger.e(TAG, "❌ FAILED TO GET APPLICATION INSTANCE | Cannot restore firewall - application context not available")

                    AppLogger.d(TAG, "Attempting fallback to VPN service...")
                    val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
                        action = FirewallVpnService.ACTION_START
                    }

                    try {
                        context.startService(serviceIntent)
                        AppLogger.d(TAG, "✅ VPN service started successfully (fallback)")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "❌ Failed to start VPN service (fallback)", e)
                    }
                }
            } else {
                AppLogger.d(TAG, "ℹ️  FIREWALL WAS NOT ENABLED | Skipping firewall restoration after $trigger")

                // The firewall being off must NOT leave a boot-protection block in place. The boot
                // script runs regardless of this preference, so without this the device stays blocked
                // on every boot with no in-app way out.
                val app = context.applicationContext as? De1984Application
                if (app != null) {
                    val pendingResult = goAsync()
                    app.dependencies.applicationScope.launch(Dispatchers.IO) {
                        try {
                            app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed to lift boot protection block", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ ERROR IN BOOT RECEIVER | Trigger: $trigger | Error: ${e.message}")
            AppLogger.e(TAG, "Stack trace:", e)
        }
    }

    private fun showBootFailureNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    Constants.BootFailure.CHANNEL_ID,
                    Constants.BootFailure.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications when firewall fails to start at boot"
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                action = Constants.Notifications.ACTION_BOOT_FAILURE_RECOVERY
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, Constants.BootFailure.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Firewall failed to start")
                .setContentText("Tap to open De1984 and grant VPN permission")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("The firewall could not start after boot. This usually happens when VPN permission needs to be re-granted. Tap to open De1984 and enable the firewall."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(Constants.BootFailure.NOTIFICATION_ID, notification)
            AppLogger.d(TAG, "Boot failure notification shown")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show boot failure notification", e)
        }
    }

    private fun clearIptablesChainRecord(context: Context) {
        try {
            context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.Settings.KEY_IPTABLES_CHAINS_INSTALLED, false)
                .commit()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not clear the iptables chain record on boot: ${e.message}")
        }
    }

}

