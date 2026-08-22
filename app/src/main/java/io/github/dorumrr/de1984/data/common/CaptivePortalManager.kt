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
     * Get the original settings that were captured.
     */
    fun getOriginalSettings(): CaptivePortalSettings? {
        if (!hasOriginalSettings()) return null

        return try {
            val mode = prefs.getInt(Constants.CaptivePortal.KEY_ORIGINAL_MODE, Constants.CaptivePortal.DEFAULT_MODE)
            val httpUrl = prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_HTTP_URL, null)
            val httpsUrl = prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_HTTPS_URL, null)
            val fallbackUrl = prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_FALLBACK_URL, null)
            val otherFallbackUrls = prefs.getString(Constants.CaptivePortal.KEY_ORIGINAL_OTHER_FALLBACK_URLS, null)
            val useHttps = prefs.getBoolean(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS, true)

            CaptivePortalSettings(
                mode = CaptivePortalMode.fromValue(mode),
                httpUrl = httpUrl,
                httpsUrl = httpsUrl,
                fallbackUrl = fallbackUrl,
                otherFallbackUrls = otherFallbackUrls,
                useHttps = useHttps
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load original settings", e)
            null
        }
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

            val currentSettings = getCurrentSettings().getOrThrow()

            prefs.edit()
                .putBoolean(Constants.CaptivePortal.KEY_ORIGINAL_CAPTURED, true)
                .putInt(Constants.CaptivePortal.KEY_ORIGINAL_MODE, currentSettings.mode.value)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_HTTP_URL, currentSettings.httpUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_HTTPS_URL, currentSettings.httpsUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_FALLBACK_URL, currentSettings.fallbackUrl)
                .putString(Constants.CaptivePortal.KEY_ORIGINAL_OTHER_FALLBACK_URLS, currentSettings.otherFallbackUrls)
                .putBoolean(Constants.CaptivePortal.KEY_ORIGINAL_USE_HTTPS, currentSettings.useHttps)
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

            val originalSettings = getOriginalSettings()
                ?: return@withContext Result.failure(Exception("No original settings found. Cannot restore."))

            AppLogger.d(TAG, "Restoring original settings")

            // Restore mode
            val modeResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_MODE, originalSettings.mode.value.toString())
            if (modeResult.first != 0) {
                return@withContext Result.failure(Exception("Failed to restore mode: ${modeResult.second}"))
            }

            // Restore HTTP URL
            originalSettings.httpUrl?.let { url ->
                val httpResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTP_URL, url)
                if (httpResult.first != 0) {
                    return@withContext Result.failure(Exception("Failed to restore HTTP URL: ${httpResult.second}"))
                }
            }

            // Restore HTTPS URL
            originalSettings.httpsUrl?.let { url ->
                val httpsResult = setSystemSetting(Constants.CaptivePortal.SYSTEM_KEY_HTTPS_URL, url)
                if (httpsResult.first != 0) {
                    return@withContext Result.failure(Exception("Failed to restore HTTPS URL: ${httpsResult.second}"))
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
