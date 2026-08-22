package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages boot protection by creating/deleting Magisk boot scripts.
 * Boot protection blocks all network traffic during device startup until De1984 firewall activates.
 */
class BootProtectionManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val TAG = "BootProtectionManager"
    }

    /**
     * Check if boot script support is available by verifying the post-fs-data.d directory exists.
     * This directory is supported by Magisk, KernelSU, and APatch.
     */
    suspend fun isBootScriptSupportAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Checking if boot script support is available...")

            val command = "test -d ${Constants.BootProtection.MAGISK_POST_FS_DIR} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)

            val available = result.first == 0 && result.second.trim() == "exists"
            AppLogger.d(TAG, "Boot script support available: $available (exitCode=${result.first}, output='${result.second.trim()}')")

            available
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot script support availability", e)
            false
        }
    }

    /**
     * Check if boot protection is currently enabled by verifying the script file exists.
     */
    suspend fun isBootProtectionEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = "test -f ${Constants.BootProtection.BOOT_SCRIPT_PATH} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)
            
            val enabled = result.first == 0 && result.second.trim() == "exists"
            AppLogger.d(TAG, "Boot protection enabled: $enabled")
            
            enabled
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot protection status", e)
            false
        }
    }

    /**
     * Enable or disable boot protection.
     * 
     * @param enabled true to enable boot protection, false to disable
     * @return Result with Unit on success, or error message on failure
     */
    suspend fun setBootProtection(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "${if (enabled) "ENABLING" else "DISABLING"} BOOT PROTECTION")

            if (enabled) {
                createBootScript()
            } else {
                deleteBootScript()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to ${if (enabled) "enable" else "disable"} boot protection", e)
            Result.failure(e)
        }
    }

    /**
     * Create the boot protection script in Magisk's post-fs-data.d directory.
     */
    private suspend fun createBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Creating boot protection script...")

        // Script content that blocks all network traffic during boot
        // We use a custom chain to isolate boot protection rules from system rules
        // This allows clean removal when De1984 starts
        val scriptContent = """#!/system/bin/sh
# De1984 Boot Protection
# Blocks all network traffic until De1984 starts
# Author: Doru Moraru

remove_boot_chain() {
    while iptables -D OUTPUT -j de1984_boot 2>/dev/null; do :; done
    while ip6tables -D OUTPUT -j de1984_boot 2>/dev/null; do :; done
    iptables -F de1984_boot 2>/dev/null
    iptables -X de1984_boot 2>/dev/null
    ip6tables -F de1984_boot 2>/dev/null
    ip6tables -X de1984_boot 2>/dev/null
}

# If De1984 has been uninstalled this script is orphaned: no app will ever lift the
# block. Delete ourselves and leave the device alone. These paths are device-encrypted
# so the check works before the user unlocks.
if [ ! -e ${Constants.BootProtection.DE_DATA_DIR_RELEASE} ] && [ ! -e ${Constants.BootProtection.DE_DATA_DIR_DEBUG} ]; then
    rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}
    exit 0
fi

# Create custom chain for boot protection
iptables -N de1984_boot 2>/dev/null || iptables -F de1984_boot
ip6tables -N de1984_boot 2>/dev/null || ip6tables -F de1984_boot

# Allow loopback traffic (required for system services)
iptables -A de1984_boot -o lo -j ACCEPT
ip6tables -A de1984_boot -o lo -j ACCEPT

# Allow critical system UIDs needed for network connectivity
# UID 0 (root) - netd and other critical network daemons
iptables -A de1984_boot -m owner --uid-owner 0 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 0 -j ACCEPT

# UID 1000 (system) - system_server and Android framework
iptables -A de1984_boot -m owner --uid-owner 1000 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1000 -j ACCEPT

# UID 1010 (wifi) - WiFi services (wpa_supplicant, wificond)
iptables -A de1984_boot -m owner --uid-owner 1010 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1010 -j ACCEPT

# UID 1016 (media) - May be needed for captive portal detection
iptables -A de1984_boot -m owner --uid-owner 1016 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1016 -j ACCEPT

# UID 1051 (gps) - GPS/location services
iptables -A de1984_boot -m owner --uid-owner 1051 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1051 -j ACCEPT

# Block everything else (user apps)
iptables -A de1984_boot -j DROP
ip6tables -A de1984_boot -j DROP

# Insert boot protection chain at the beginning of OUTPUT.
# Guarded so a re-run cannot stack a second jump that a single -D would miss.
iptables -C OUTPUT -j de1984_boot 2>/dev/null || iptables -I OUTPUT -j de1984_boot
ip6tables -C OUTPUT -j de1984_boot 2>/dev/null || ip6tables -I OUTPUT -j de1984_boot

# Safety net. The block is only meant to cover the gap before De1984 takes over.
# If that never happens - firewall left off, start failed, screen still locked, app
# data cleared - nothing else would ever lift it and the device would have no network
# on every boot, forever. This makes the block expire on its own.
# De1984 normally removes the chain within seconds, long before this fires.
(
    sleep ${Constants.BootProtection.SELF_HEAL_TIMEOUT_SECONDS}
    remove_boot_chain
) &
"""

        // Create the script file
        val createCommand = "echo '${scriptContent.replace("'", "'\\''")}' > ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val createResult = executeCommand(createCommand)

        if (createResult.first != 0) {
            val error = "Failed to create boot script (exit code: ${createResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Boot script created successfully")

        // Read the script back and verify it landed intact. A partial write would install the
        // catch-all DROP without the ACCEPT rules above it, which blocks the device at every boot.
        // The caller reboots on success, so we must never report success on an unverified write.
        val verifyResult = executeCommand("cat ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
        if (verifyResult.first != 0 || verifyResult.second.trimEnd() != scriptContent.trimEnd()) {
            AppLogger.e(TAG, "Boot script readback did not match what was written - removing it")
            executeCommand("rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
            return Result.failure(Exception("Boot script was written incorrectly and has been removed"))
        }

        AppLogger.d(TAG, "✅ Boot script content verified")

        // Set executable permissions (755)
        val chmodCommand = "chmod ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS} ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val chmodResult = executeCommand(chmodCommand)

        if (chmodResult.first != 0) {
            val error = "Failed to set script permissions (exit code: ${chmodResult.first})"
            AppLogger.e(TAG, error)
            // Do not leave an orphan script behind: the preference stays off, so nothing would
            // ever remove it and the user would have no way to see or clear it.
            executeCommand("rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}")
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Script permissions set to ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS}")
        AppLogger.d(TAG, "✅ Boot protection enabled successfully")

        return Result.success(Unit)
    }

    /**
     * Reboot the device.
     *
     * Called immediately after boot protection is successfully enabled or disabled, so that the
     * on-disk script and the live iptables state can never disagree. Only ever called after a
     * verified successful change - never after a failed one.
     */
    suspend fun rebootDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            AppLogger.d(TAG, "Rebooting device to apply boot protection change")
            val result = executeCommand("svc power reboot")

            if (result.first != 0) {
                val error = "Failed to reboot device (exit code: ${result.first})"
                AppLogger.e(TAG, error)
                Result.failure(Exception(error))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to reboot device", e)
            Result.failure(e)
        }
    }

    /**
     * Delete the boot protection script.
     */
    private suspend fun deleteBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Deleting boot protection script...")

        val deleteCommand = "rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val deleteResult = executeCommand(deleteCommand)

        if (deleteResult.first != 0) {
            val error = "Failed to delete boot script (exit code: ${deleteResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        // Confirm the file is actually gone. The caller reboots on success and would otherwise
        // reboot straight back into a device that is still blocked at every boot.
        if (isBootProtectionEnabled()) {
            val error = "Boot script still present after deletion"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }

        AppLogger.d(TAG, "✅ Boot script deleted successfully")

        // Remove the live chain too. The caller reboots immediately, which would also clear it,
        // but if that reboot never happens the device must still recover on its own.
        resetIptablesPolicies()

        AppLogger.d(TAG, "✅ Boot protection disabled successfully")

        return Result.success(Unit)
    }

    /**
     * Lift the boot-protection block if the script is installed on disk.
     *
     * Called early in both boot paths, BEFORE any decision about the firewall. This used to happen
     * only inside startFirewall().onSuccess, which meant a firewall the user had switched off - or one
     * that failed to start - left the device blocked on every boot with no in-app way out.
     *
     * Deliberately keyed on the script actually being present on disk, not on the boot_protection
     * preference, because clearing app data resets that preference to false while leaving the script
     * in place.
     */
    suspend fun clearBootBlockIfInstalled(): Result<Unit> {
        // At boot the app has not yet asked Magisk for root, so hasRootPermission is still false and
        // every command here would silently no-op - isBootProtectionEnabled() would report "false"
        // for a script that is plainly on disk. Wake the privilege first.
        if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
            AppLogger.d(TAG, "No privilege yet - requesting root before checking boot protection")
            rootManager.forceRecheckRootStatus()
        }

        if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
            // Cannot check and cannot act. Say so rather than reporting "not enabled": the boot
            // script may well be installed and still blocking. The script's own expiry timer is
            // the remaining safety net.
            AppLogger.w(TAG, "No privileged access - cannot check or lift a boot protection block")
            return Result.failure(Exception("No root or Shizuku access"))
        }

        if (!isBootProtectionEnabled()) {
            AppLogger.d(TAG, "No boot protection script installed - nothing to lift")
            return Result.success(Unit)
        }

        AppLogger.d(TAG, "Boot protection script is installed - lifting its block")
        return resetIptablesPolicies()
    }

    /**
     * Remove boot protection iptables rules after firewall starts.
     * This is called after boot when boot protection was enabled.
     *
     * We remove the custom boot protection chain that was created by the boot script.
     * This allows De1984's firewall to take over network control cleanly.
     */
    suspend fun resetIptablesPolicies(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                AppLogger.d(TAG, "Removing boot protection iptables rules...")

                // IPv4: Remove boot protection chain
                // 1. Unlink the chain from OUTPUT
                var result = executeCommand("iptables -D OUTPUT -j de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Unlinked de1984_boot chain (exit code: ${result.first})")

                // 2. Flush the chain
                result = executeCommand("iptables -F de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Flushed de1984_boot chain (exit code: ${result.first})")

                // 3. Delete the chain
                result = executeCommand("iptables -X de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Deleted de1984_boot chain (exit code: ${result.first})")

                // IPv6: Remove boot protection chain
                // 1. Unlink the chain from OUTPUT
                result = executeCommand("ip6tables -D OUTPUT -j de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Unlinked de1984_boot chain (exit code: ${result.first})")

                // 2. Flush the chain
                result = executeCommand("ip6tables -F de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Flushed de1984_boot chain (exit code: ${result.first})")

                // 3. Delete the chain
                result = executeCommand("ip6tables -X de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Deleted de1984_boot chain (exit code: ${result.first})")

                AppLogger.d(TAG, "✅ Boot protection iptables rules removed successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to remove boot protection iptables rules", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Execute command using root or Shizuku.
     */
    private suspend fun executeCommand(command: String): Pair<Int, String> {
        return if (rootManager.hasRootPermission) {
            rootManager.executeRootCommand(command)
        } else if (shizukuManager.hasShizukuPermission) {
            shizukuManager.executeShellCommand(command)
        } else {
            Pair(-1, "No root or Shizuku access")
        }
    }
}

