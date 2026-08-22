package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.dorumrr.de1984.domain.model.CaptivePortalMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalPreset
import io.github.dorumrr.de1984.domain.model.CaptivePortalSettings
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Android captive portal detection settings.
 * 
 * Provides read/write access to system captive portal configuration,
 * with support for capturing and restoring original device settings.
 * 
 * Read operations work without privileges (settings get global).
 * Write operations require root or Shizuku (settings put global).
 */
class CaptivePortalManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val TAG = "CaptivePortalManager"

        /** Characters with meaning to a POSIX shell. A URL never legitimately contains these. */
        private val SHELL_METACHARACTERS = charArrayOf(
            '$', '`', '\\', '"', '\'', ';', '&', '|', '<', '>', '(', ')', '{', '}', '[', ']', '\n', '\r'
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Constants.CaptivePortal.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check if we have privileges to modify captive portal settings.
     */
    fun hasPrivileges(): Boolean {
        return rootManager.hasRootPermission || shizukuManager.hasShizukuPermission
    }

    /**
     * Get current captive portal settings from the system.
     * This works WITHOUT root/Shizuku (read-only).
     */
    suspend fun getCurrentSettings(): Result<CaptivePortalSettings> = withContext(Dispatchers.IO) {
        return@withContext try {
            val mode = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_MODE)?.toIntOrNull()
                ?: Constants.CaptivePortal.DEFAULT_MODE
            val httpUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL)
            val httpsUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL)
            val fallbackUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_FALLBACK_URL)
            val otherFallbackUrls = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_OTHER_FALLBACK_URLS)
            val useHttps = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_USE_HTTPS)?.toIntOrNull() == 1

            val settings = CaptivePortalSettings(
                mode = CaptivePortalMode.fromValue(mode),
                httpUrl = httpUrl,
                httpsUrl = httpsUrl,
                fallbackUrl = fallbackUrl,
                otherFallbackUrls = otherFallbackUrls,
                useHttps = useHttps
            )

            AppLogger.d(TAG, "Current settings: mode=$mode, httpUrl=$httpUrl, httpsUrl=$httpsUrl")
            Result.success(settings)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get current settings", e)
            Result.failure(Exception("Failed to read captive portal settings: ${e.message}"))
        }
    }

    /**
     * Check if original settings have been captured.
     */
    fun hasOriginalSettings(): Boolean {
        return prefs.getBoolean(Constants.CaptivePortal.KEY_ORIGINAL_CAPTURED, false)
    }

    /**
     * Capture current system settings as "original" for later restoration.
     * This should be called the first time the user opens the Captive Portal settings.
     */
    suspend fun captureOriginalSettings(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (hasOriginalSettings()) {
                AppLogger.d(TAG, "Original settings already captured, skipping")
                return@withContext Result.success(Unit)
            }

            // Read the raw system values, not getCurrentSettings(). That helper substitutes a
            // default for an unset key, and storing the substitute as the "original" meant restore
            // would later write a setting the device never had. Verified on a device where
            // captive_portal_mode was genuinely unset and the app had recorded it as 1.
            val rawMode = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_MODE)
            val rawHttpUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL)
            val rawHttpsUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL)
            val rawFallbackUrl = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_FALLBACK_URL)
            val rawOtherFallbackUrls = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_OTHER_FALLBACK_URLS)
            val rawUseHttps = getSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_USE_HTTPS)

            AppLogger.d(TAG, "Capturing originals: mode=$rawMode, httpUrl=$rawHttpUrl, " +
                    "httpsUrl=$rawHttpsUrl, fallbackUrl=$rawFallbackUrl, useHttps=$rawUseHttps " +
                    "(null means the key is unset)")

            prefs.edit()
                .putBoolean(Constants.CaptivePortal.KEY_ORIGINAL_CAPTURED, true)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_MODE_RAW, rawMode)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_HTTP_URL, rawHttpUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_HTTPS_URL, rawHttpsUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_FALLBACK_URL, rawFallbackUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_OTHER_FALLBACK_URLS, rawOtherFallbackUrls)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS_RAW, rawUseHttps)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_DEVICE_MODEL, Build.MODEL)
                .putInt(Constants.CaptivePortal.KEY_ORIGINAL_SDK_INT, Build.VERSION.SDK_INT)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_ROM_NAME, Build.DISPLAY)
                .apply()

            AppLogger.d(TAG, "Original settings captured successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to capture original settings", e)
            Result.failure(Exception("Failed to capture original settings: ${e.message}"))
        }
    }

    /**
     * Apply a server preset (Google, GrapheneOS, Kuketz, Cloudflare).
     * Requires root or Shizuku.
     */
    suspend fun applyPreset(preset: CaptivePortalPreset): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!hasPrivileges()) {
                return@withContext Result.failure(Exception("Root or Shizuku access required"))
            }

            if (preset == CaptivePortalPreset.CUSTOM) {
                return@withContext Result.failure(Exception("Cannot apply CUSTOM preset directly. Use setCustomUrls() instead."))
            }

            AppLogger.d(TAG, "Applying preset: ${preset.name}")

            // Set HTTP URL
            val httpResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL, preset.httpUrl)
            if (httpResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTP URL: ${httpResult.second}"))
            }

            // Set HTTPS URL
            val httpsResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL, preset.httpsUrl)
            if (httpsResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTPS URL: ${httpsResult.second}"))
            }

            AppLogger.d(TAG, "Preset applied successfully: ${preset.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply preset", e)
            Result.failure(Exception("Failed to apply preset: ${e.message}"))
        }
    }

    /**
     * Set captive portal detection mode.
     * Requires root or Shizuku.
     */
    suspend fun setDetectionMode(mode: CaptivePortalMode): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!hasPrivileges()) {
                return@withContext Result.failure(Exception("Root or Shizuku access required"))
            }

            AppLogger.d(TAG, "Setting detection mode: ${mode.name} (${mode.value})")

            val result = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_MODE, mode.value.toString())
            if (result.first != 0) {
                return@withContext Result.failure(Exception("Failed to set detection mode: ${result.second}"))
            }

            AppLogger.d(TAG, "Detection mode set successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set detection mode", e)
            Result.failure(Exception("Failed to set detection mode: ${e.message}"))
        }
    }

    /**
     * Set custom captive portal URLs.
     * Requires root or Shizuku.
     */
    suspend fun setCustomUrls(httpUrl: String, httpsUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!hasPrivileges()) {
                return@withContext Result.failure(Exception("Root or Shizuku access required"))
            }

            // Validate URLs
            if (!isValidUrl(httpUrl)) {
                return@withContext Result.failure(Exception("Invalid HTTP URL: must start with http://"))
            }
            if (!isValidUrl(httpsUrl)) {
                return@withContext Result.failure(Exception("Invalid HTTPS URL: must start with https://"))
            }

            AppLogger.d(TAG, "Setting custom URLs: http=$httpUrl, https=$httpsUrl")

            // Set HTTP URL
            val httpResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL, httpUrl)
            if (httpResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTP URL: ${httpResult.second}"))
            }

            // Set HTTPS URL
            val httpsResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL, httpsUrl)
            if (httpsResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTPS URL: ${httpsResult.second}"))
            }

            AppLogger.d(TAG, "Custom URLs set successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set custom URLs", e)
            Result.failure(Exception("Failed to set custom URLs: ${e.message}"))
        }
    }

    /**
     * Restore original captive portal settings.
     * Requires root or Shizuku.
     */
    suspend fun restoreOriginalSettings(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!hasPrivileges()) {
                return@withContext Result.failure(Exception("Root or Shizuku access required"))
            }

            if (!hasOriginalSettings()) {
                return@withContext Result.failure(Exception("No original settings found. Cannot restore."))
            }

            AppLogger.d(TAG, "Restoring original settings")

            // All six keys, not three. The other three were captured into the backup but no code
            // path ever wrote them, which made the backup look more complete than it was.
            //
            // A null recorded value means the key was unset when we captured it, so the key is
            // deleted rather than written. Restore used to skip nulls entirely and always write the
            // mode, so it left De1984's own URLs in place while reporting success, and created a
            // captive_portal_mode on devices that never had one.
            val toRestore = listOf(
                Constants.CaptivePortal.SYSTEM_KEY_MODE to originalRawMode(),
                Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL to
                        prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_HTTP_URL, null),
                Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL to
                        prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_HTTPS_URL, null),
                Constants.CaptivePortal.SYSTEM_KEY_FALLBACK_URL to
                        prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_FALLBACK_URL, null),
                Constants.CaptivePortal.SYSTEM_KEY_OTHER_FALLBACK_URLS to
                        prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_OTHER_FALLBACK_URLS, null),
                Constants.CaptivePortal.SYSTEM_KEY_USE_HTTPS to originalRawUseHttps()
            )

            toRestore.forEach { (key, value) ->
                val result = if (value == null) {
                    AppLogger.d(TAG, "Restoring $key: was unset, deleting")
                    deleteSystemSetting(key)
                } else {
                    AppLogger.d(TAG, "Restoring $key: $value")
                    setSystemSetting(key, value)
                }

                if (result.first != 0) {
                    return@withContext Result.failure(
                        Exception("Failed to restore $key: ${result.second}")
                    )
                }
            }

            AppLogger.d(TAG, "Original settings restored successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restore original settings", e)
            Result.failure(Exception("Failed to restore original settings: ${e.message}"))
        }
    }

    /**
     * The captured `captive_portal_mode`, or null if the key was unset when captured.
     *
     * Backups written before the raw keys existed stored an Int, which could not express "unset".
     * Those are read verbatim, which is exactly what the old restore would have written, so nothing
     * gets worse for a backup that already exists.
     */
    private fun originalRawMode(): String? {
        if (prefs.contains(Constants.CaptivePortal.KEY_ORIGINAL_MODE_RAW)) {
            return prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_MODE_RAW, null)
        }
        if (!prefs.contains(Constants.CaptivePortal.KEY_ORIGINAL_MODE)) return null
        return prefs.getInt(
            Constants.CaptivePortal.KEY_ORIGINAL_MODE,
            Constants.CaptivePortal.DEFAULT_MODE
        ).toString()
    }

    /** As [originalRawMode], for `captive_portal_use_https`. */
    private fun originalRawUseHttps(): String? {
        if (prefs.contains(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS_RAW)) {
            return prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS_RAW, null)
        }
        if (!prefs.contains(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS)) return null
        return if (prefs.getBoolean(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS, true)) "1" else "0"
    }

    /**
     * Reset to Google's default captive portal settings.
     * Requires root or Shizuku.
     */
    suspend fun resetToGoogleDefaults(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!hasPrivileges()) {
                return@withContext Result.failure(Exception("Root or Shizuku access required"))
            }

            AppLogger.d(TAG, "Resetting to Google defaults")

            // Set mode to ENABLED (1)
            val modeResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_MODE, Constants.CaptivePortal.DEFAULT_MODE.toString())
            if (modeResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set mode: ${modeResult.second}"))
            }

            // Set HTTP URL
            val httpResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL, Constants.CaptivePortal.DEFAULT_HTTP_URL)
            if (httpResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTP URL: ${httpResult.second}"))
            }

            // Set HTTPS URL
            val httpsResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL, Constants.CaptivePortal.DEFAULT_HTTPS_URL)
            if (httpsResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to set HTTPS URL: ${httpsResult.second}"))
            }

            AppLogger.d(TAG, "Reset to Google defaults successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to reset to Google defaults", e)
            Result.failure(Exception("Failed to reset to Google defaults: ${e.message}"))
        }
    }

    /**
     * Read a system setting value.
     * Works WITHOUT root/Shizuku (read-only operation).
     */
    private suspend fun getSystemSetting(key: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val command = "settings get global $key"

            // Try with Shizuku first (if available), then root, then regular shell
            val result = when {
                shizukuManager.hasShizukuPermission -> shizukuManager.executeShellCommand(command)
                rootManager.hasRootPermission -> rootManager.executeRootCommand(command)
                else -> {
                    // Try regular shell (works for read operations)
                    val process = Runtime.getRuntime().exec(command)
                    // Read both streams to prevent blocking
                    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                    process.errorStream.bufferedReader().use { it.readText() } // Drain error stream
                    val exitCode = process.waitFor()
                    process.destroy()
                    Pair(exitCode, output)
                }
            }

            if (result.first == 0 && result.second.isNotBlank() && result.second != "null") {
                result.second.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get system setting: $key", e)
            null
        }
    }

    /**
     * Write a system setting value.
     * Requires root or Shizuku.
     */
    private suspend fun setSystemSetting(key: String, value: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Single-quote the value and escape any embedded single quote. Inside single quotes the
            // shell expands nothing, so $(...) or backticks in a user-supplied URL cannot execute.
            //
            // This runs as root. With the previous double-quoted form, a URL of
            // http://example.com$(touch /data/local/tmp/INJECTED) created a root-owned file while the
            // stored setting still read back as a plain URL, leaving no trace in the UI. Verified.
            val quotedValue = "'" + value.replace("'", "'\\''") + "'"
            val command = "settings put global $key $quotedValue"

            when {
                rootManager.hasRootPermission -> rootManager.executeRootCommand(command)
                shizukuManager.hasShizukuPermission -> shizukuManager.executeShellCommand(command)
                else -> Pair(-1, "No root or Shizuku access")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set system setting: $key", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Remove a system setting, so the framework falls back to its own built-in value.
     *
     * Restore needs this: a key that was unset when captured must be put back to unset, not written
     * with a substitute. Requires root or Shizuku.
     */
    private suspend fun deleteSystemSetting(key: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val command = "settings delete global $key"

            when {
                rootManager.hasRootPermission -> rootManager.executeRootCommand(command)
                shizukuManager.hasShizukuPermission -> shizukuManager.executeShellCommand(command)
                else -> Pair(-1, "No root or Shizuku access")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete system setting: $key", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Validate a URL for captive portal use.
     */
    private fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false

        // Must start with http:// or https://
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false
        }

        // Basic validation: must have a hostname after protocol
        val withoutProtocol = url.substringAfter("://")
        if (withoutProtocol.isBlank() || withoutProtocol.startsWith("/")) {
            return false
        }

        // Reject anything that could be meaningful to a shell. setSystemSetting already single-quotes
        // the value, so this is a second line of defence rather than the only one - but a URL has no
        // legitimate reason to contain these, and this value is passed to a command running as root.
        if (url.any { it in SHELL_METACHARACTERS } || url.any { it.isWhitespace() }) {
            AppLogger.w(TAG, "Rejected URL containing shell metacharacters or whitespace")
            return false
        }

        return true
    }
}
