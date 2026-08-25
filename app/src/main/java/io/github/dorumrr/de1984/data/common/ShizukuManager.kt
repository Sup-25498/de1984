package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper
import rikka.sui.Sui

class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE_PERMISSION = 1001
    }

    private val _shizukuStatus = MutableStateFlow(ShizukuStatus.CHECKING)
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    private var hasCheckedOnce = false
    private var listenersRegistered = false

    // Track if SUI (Magisk-based Shizuku) is available
    // SUI doesn't install a separate package - it provides Shizuku API through Magisk
    private var isSuiAvailable = false

    // Cache the reflection method to avoid repeated lookups
    // This is accessed via Shizuku.newProcess() which is private, so we use reflection
    private val newProcessMethod by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache Shizuku.newProcess() method: ${e.message}")
            null
        }
    }

    val hasShizukuPermission: Boolean
        get() = _shizukuStatus.value == ShizukuStatus.RUNNING_WITH_PERMISSION

    // Track if user explicitly denied permission to avoid prompt spam (Issue #68)
    // This is reset when Shizuku restarts (binder received) to allow retry
    @Volatile
    private var userExplicitlyDeniedPermission = false

    val hasUserDeniedPermission: Boolean
        get() = userExplicitlyDeniedPermission

    fun resetPermissionDenial() {
        AppLogger.d(TAG, "🔄 Resetting permission denial flag - user can be prompted again")
        userExplicitlyDeniedPermission = false
    }

    private val binderDeathRecipient = IBinder.DeathRecipient {
        _shizukuStatus.value = ShizukuStatus.INSTALLED_NOT_RUNNING
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        AppLogger.d(TAG, "🔧 SYSTEM EVENT: Shizuku permission result received | requestCode: $requestCode, grantResult: $grantResult")
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                AppLogger.d(TAG, "✅ Shizuku permission GRANTED - updating status to RUNNING_WITH_PERMISSION")
                AppLogger.d(TAG, "✅ hasShizukuPermission will now return TRUE")
                _shizukuStatus.value = ShizukuStatus.RUNNING_WITH_PERMISSION
                userExplicitlyDeniedPermission = false
            } else {
                AppLogger.d(TAG, "❌ Shizuku permission DENIED - updating status to RUNNING_NO_PERMISSION")
                AppLogger.d(TAG, "❌ hasShizukuPermission will now return FALSE")
                AppLogger.d(TAG, "❌ Setting userExplicitlyDeniedPermission=true to prevent prompt spam")
                _shizukuStatus.value = ShizukuStatus.RUNNING_NO_PERMISSION
                userExplicitlyDeniedPermission = true
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AppLogger.d(TAG, "🔧 SYSTEM EVENT: Shizuku binder received (Shizuku started)")

        if (userExplicitlyDeniedPermission) {
            AppLogger.d(TAG, "🔄 Shizuku restarted - resetting permission denial flag to allow new prompt")
            userExplicitlyDeniedPermission = false
        }

        // Per @embeddedtofu suggestion, re-check SUI availability on reconnection
        // This handles the case where SUI module was enabled/disabled since app start
        // Pattern: Sui.init() initializes, Sui.isSui() confirms it's actually SUI.
        try {
            val suiInitResult = Sui.init(context.packageName)
            val previousSuiState = isSuiAvailable
            isSuiAvailable = suiInitResult && Sui.isSui()
            if (isSuiAvailable != previousSuiState) {
                AppLogger.d(TAG, "🔧 SUI availability changed: $previousSuiState → $isSuiAvailable")
            }
            AppLogger.d(TAG, "🔧 SUI check on reconnect: init=$suiInitResult, isSui=${if (suiInitResult) Sui.isSui() else "N/A"}, isSuiAvailable=$isSuiAvailable")
        } catch (e: Exception) {
            AppLogger.d(TAG, "🔧 SUI check failed on reconnect (expected if not installed): ${e.message}")
            isSuiAvailable = false
        }

        checkShizukuStatusSync()

        // IMPORTANT: The status update above will trigger FirewallManager's privilege monitoring
        // via the combine() flow in startPrivilegeMonitoring(). This will automatically:
        // 1. Detect that Shizuku is now available
        // 2. Check if firewall should switch backends (VPN → ConnectivityManager)
        // 3. Start firewall if it was down waiting for privileges
        //
        // No additional action needed here - the reactive flow handles everything!
        AppLogger.d(TAG, "Shizuku status updated - FirewallManager will handle any needed backend switch")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AppLogger.d(TAG, "🔧 SYSTEM EVENT: Shizuku binder died (Shizuku stopped)")
        _shizukuStatus.value = ShizukuStatus.INSTALLED_NOT_RUNNING
    }

    suspend fun checkShizukuStatus() {
        val currentStatus = _shizukuStatus.value

        AppLogger.d(TAG, "=== checkShizukuStatus() called ===")
        AppLogger.d(TAG, "Current status: $currentStatus, hasCheckedOnce: $hasCheckedOnce")

        // No early return on a cached grant. Revocation that does NOT kill the binder - the user
        // switching De1984 off inside Shizuku while Shizuku keeps running - fires neither listener:
        // binderDeadListener only sees the service die, and permissionResultListener only sees the
        // result of a request WE made. So a cached RUNNING_WITH_PERMISSION never expired. Settings
        // kept showing "Granted" and every privileged action failed, until the app was restarted.
        //
        // Nothing is saved by skipping it: the check is a package lookup, a binder ping and a
        // permission read, and the eight callers are all lifecycle events - app start, activity
        // resume, boot, the 15s health job - never a tight loop.

        if (!hasCheckedOnce) {
            _shizukuStatus.value = ShizukuStatus.CHECKING
            AppLogger.d(TAG, "First check - setting status to CHECKING")
        }

        val newStatus = checkShizukuStatusInternal()
        _shizukuStatus.value = newStatus
        hasCheckedOnce = true
        AppLogger.d(TAG, "Shizuku status check complete: $newStatus")
    }

    private fun checkShizukuStatusSync() {
        val newStatus = when {
            !isShizukuInstalled() -> ShizukuStatus.NOT_INSTALLED
            !isShizukuRunning() -> ShizukuStatus.INSTALLED_NOT_RUNNING
            !checkShizukuPermissionSync() -> ShizukuStatus.RUNNING_NO_PERMISSION
            else -> ShizukuStatus.RUNNING_WITH_PERMISSION
        }
        _shizukuStatus.value = newStatus
    }

    private suspend fun checkShizukuStatusInternal(): ShizukuStatus = withContext(Dispatchers.IO) {
        try {
            val source = if (isSuiAvailable) "SUI (Magisk)" else "Shizuku"
            AppLogger.d(TAG, "Checking if $source is installed... (isSuiAvailable=$isSuiAvailable)")

            val installed = isShizukuInstalled()
            if (!installed) {
                AppLogger.d(TAG, "$source is NOT_INSTALLED")
                return@withContext ShizukuStatus.NOT_INSTALLED
            }

            AppLogger.d(TAG, "$source is installed, checking if service is running...")
            val running = isShizukuRunning()
            if (!running) {
                AppLogger.d(TAG, "$source is INSTALLED_NOT_RUNNING (binder not responding)")
                return@withContext ShizukuStatus.INSTALLED_NOT_RUNNING
            }

            AppLogger.d(TAG, "$source service is running, checking permission...")
            val hasPermission = checkShizukuPermissionSync()
            if (!hasPermission) {
                AppLogger.d(TAG, "$source is RUNNING_NO_PERMISSION")
                return@withContext ShizukuStatus.RUNNING_NO_PERMISSION
            }

            AppLogger.d(TAG, "$source is RUNNING_WITH_PERMISSION ✅")
            ShizukuStatus.RUNNING_WITH_PERMISSION
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during Shizuku/SUI check: ${e.message}", e)
            ShizukuStatus.NOT_INSTALLED
        }
    }

    fun isShizukuInstalled(): Boolean {
        if (isSuiAvailable) {
            AppLogger.d(TAG, "isShizukuInstalled: SUI is available (no package needed)")
            return true
        }

        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            AppLogger.d(TAG, "isShizukuInstalled: Standalone Shizuku package found")
            true
        } catch (e: Exception) {
            // A "hide Shizuku from other apps" build renames the package, so the fixed id above
            // finds nothing while Shizuku is running fine. The permission it declares keeps its
            // name, and its owner is the manager. See issue #92.
            val owner = shizukuPackageFromPermission()
            if (owner != null) {
                AppLogger.d(TAG, "isShizukuInstalled: Shizuku found via permission owner: $owner")
                true
            } else {
                AppLogger.d(TAG, "isShizukuInstalled: No Shizuku package and no SUI")
                false
            }
        }
    }

    private fun shizukuPackageFromPermission(): String? {
        return try {
            context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName
        } catch (e: Exception) {
            null
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuAvailable(): Boolean {
        return isShizukuInstalled() && isShizukuRunning()
    }

    private fun checkShizukuPermissionSync(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestShizukuPermission() {
        try {
            AppLogger.d(TAG, "requestShizukuPermission() called")
            if (isShizukuRunning()) {
                AppLogger.d(TAG, "Shizuku is running - requesting permission via Shizuku.requestPermission()")
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            } else {
                AppLogger.d(TAG, "Shizuku is not running - cannot request permission")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to request Shizuku permission: ${e.message}", e)
        }
    }

    fun getShizukuVersion(): Int {
        return try {
            if (isShizukuRunning()) {
                Shizuku.getVersion()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    fun getShizukuUid(): Int {
        return try {
            if (isShizukuRunning()) {
                Shizuku.getUid()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    fun isShizukuRootMode(): Boolean {
        return getShizukuUid() == 0
    }

    suspend fun executeShellCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission) {
            return@withContext Pair(-1, "No Shizuku permission")
        }

        try {
            val method = newProcessMethod
                ?: return@withContext Pair(-1, "Shizuku.newProcess() method not available")

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            // Destroy on every path, not just on timeout: destroy() is the only thing here that
            // releases the process and its pipes on the Shizuku side. ShizukuRemoteProcess also
            // holds itself in a static CACHE until its binderDied fires. See issue #93.
            try {
                val output = StringBuilder()
                val error = StringBuilder()

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        output.append(line).append("\n")
                    }
                }

                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        error.append(line).append("\n")
                    }
                }

                val exitCode = kotlinx.coroutines.withTimeoutOrNull(5000) {
                    process.waitFor()
                } ?: -1

                val outputStr = output.toString().trim()
                val errorStr = error.toString().trim()

                return@withContext Pair(exitCode, if (outputStr.isNotEmpty()) outputStr else errorStr)
            } finally {
                // Do not close outputStream here: getOutputStream() is lazy, so asking for it would
                // open an fd over binder that no caller ever wanted. Nothing writes stdin.
                runCatching { process.destroy() }
            }
        } catch (e: Exception) {
            return@withContext Pair(-1, "Shizuku command execution failed: ${e.message}")
        }
    }

    fun registerListeners() {
        if (listenersRegistered) {
            AppLogger.d(TAG, "Shizuku listeners already registered, skipping")
            return
        }

        try {
            // Initialize Sui if available (required for SUI support)
            // This must be called before any Shizuku API usage when SUI is installed
            // Returns true if SUI is available, false otherwise (no exception thrown)
            //
            // IMPORTANT: SUI (Magisk-based Shizuku) doesn't install a separate package.
            // When Sui.init() returns true, we must track this to bypass package installation checks.
            try {
                isSuiAvailable = Sui.init(context.packageName)
                if (isSuiAvailable) {
                    AppLogger.d(TAG, "✅ Sui initialized successfully - SUI is available (Magisk module)")
                    AppLogger.d(TAG, "   SUI provides Shizuku API without separate package installation")
                } else {
                    AppLogger.d(TAG, "ℹ️ Sui not available - will check for standalone Shizuku or root")
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "ℹ️ Sui initialization failed (expected if SUI not installed): ${e.message}")
                isSuiAvailable = false
            }

            AppLogger.d(TAG, "🔧 Registering Shizuku listeners")
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
            AppLogger.d(TAG, "✅ Shizuku listeners registered successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to register Shizuku listeners: ${e.message}", e)
        }
    }

    fun unregisterListeners() {
        if (!listenersRegistered) {
            return
        }

        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            listenersRegistered = false
        } catch (e: Exception) {
        }
    }

    suspend fun getSystemServiceBinder(serviceName: String): IBinder? = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission) {
            AppLogger.e(TAG, "Cannot get system service binder: No Shizuku permission")
            return@withContext null
        }

        try {
            AppLogger.d(TAG, "Getting system service binder for: $serviceName")
            val serviceBinder = SystemServiceHelper.getSystemService(serviceName)
            if (serviceBinder == null) {
                AppLogger.e(TAG, "Failed to get system service: $serviceName (service returned null)")
                return@withContext null
            }

            val wrappedBinder = ShizukuBinderWrapper(serviceBinder)
            AppLogger.d(TAG, "✅ Successfully got system service binder for: $serviceName")
            return@withContext wrappedBinder
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get system service binder: $serviceName", e)
            return@withContext null
        }
    }
}

enum class ShizukuStatus {
    CHECKING,
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    RUNNING_WITH_PERMISSION
}

