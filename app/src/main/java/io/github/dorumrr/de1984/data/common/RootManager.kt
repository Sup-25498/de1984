package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class RootStatus {
    NOT_ROOTED,

    ROOTED_NO_PERMISSION,

    ROOTED_WITH_PERMISSION,

    CHECKING
}

class RootManager(private val context: Context) {

    companion object {
        private const val TAG = "RootManager"
        private const val PREFS_NAME = "de1984_root"
        private const val KEY_ROOT_PERMISSION_REQUESTED = "root_permission_requested"

        /** Attempts before a missing root shell is recorded as NOT_ROOTED. See checkRootStatusInternal. */
        private const val MAX_ROOT_ATTEMPTS = 3

        /** Breathing room for a root manager that is still starting - KernelSU LKM, a slow grant dialog. */
        private const val ROOT_RETRY_DELAY_MS = 800L
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _rootStatus = MutableStateFlow<RootStatus>(RootStatus.CHECKING)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private var hasCheckedOnce = false

    val hasRootPermission: Boolean
        get() = _rootStatus.value == RootStatus.ROOTED_WITH_PERMISSION

    fun hasRequestedRootPermission(): Boolean {
        return prefs.getBoolean(KEY_ROOT_PERMISSION_REQUESTED, false)
    }

    fun markRootPermissionRequested() {
        prefs.edit().putBoolean(KEY_ROOT_PERMISSION_REQUESTED, true).apply()
    }

    suspend fun checkRootStatus() {
        checkRootStatusInternalWithCaching(forceRecheck = false)
    }

    /**
     * Internal helper that allows callers (like backend health monitoring)
     * to force a re-check even if we previously had ROOTED_WITH_PERMISSION.
     */
    suspend fun forceRecheckRootStatus() {
        checkRootStatusInternalWithCaching(forceRecheck = true)
    }

    private suspend fun checkRootStatusInternalWithCaching(forceRecheck: Boolean) {
        val currentStatus = _rootStatus.value

        AppLogger.d(TAG, "=== checkRootStatusInternalWithCaching() called ===")
        AppLogger.d(TAG, "Current status: $currentStatus, hasCheckedOnce: $hasCheckedOnce, forceRecheck: $forceRecheck")

        // Only skip check if we have definitive permission AND caller did not
        // explicitly request a re-check (e.g., health monitoring after root
        // revocation from Magisk).
        if (!forceRecheck && hasCheckedOnce && currentStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            AppLogger.d(TAG, "Skipping check - already have permission and no forceRecheck")
            return
        }

        if (!hasCheckedOnce) {
            _rootStatus.value = RootStatus.CHECKING
            AppLogger.d(TAG, "First check - setting status to CHECKING")
        }

        val newStatus = checkRootStatusInternal()
        _rootStatus.value = newStatus
        hasCheckedOnce = true
        AppLogger.d(TAG, "Root status check complete: $newStatus")
    }

    private fun verifyRootWithCachedShell(): Boolean {
        val cachedShell = Shell.getCachedShell()
        if (cachedShell == null) {
            AppLogger.d(TAG, "No cached shell available")
            return false
        }
        if (!cachedShell.isAlive) {
            AppLogger.d(TAG, "Cached shell is no longer alive")
            return false
        }
        if (!cachedShell.isRoot) {
            AppLogger.d(TAG, "Cached shell is not a root shell (isRoot=false)")
            return false
        }

        // Verify root is still valid by running a command on the existing shell
        // This does NOT spawn a new su process = NO TOAST
        return try {
            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add(Constants.RootAccess.ROOT_VERIFICATION_COMMAND)
                .to(outputList)
                .exec()
            
            val isValid = result.isSuccess && 
                outputList.any { it.contains(Constants.RootAccess.ROOT_VERIFICATION_SUCCESS_MARKER) }
            
            if (isValid) {
                AppLogger.d(TAG, "✅ Cached shell verified - root still valid (no toast triggered)")
            } else {
                AppLogger.w(TAG, "⚠️ Cached shell verification failed - root likely revoked")
            }
            isValid
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception verifying cached shell: ${e.message}", e)
            false
        }
    }

    private suspend fun checkRootStatusInternal(): RootStatus = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "🔍 CHECKING ROOT STATUS (using libsu)")

            if (verifyRootWithCachedShell()) {
                AppLogger.d(TAG, "✅ Root verified via cached shell (no toast triggered)")
                return@withContext RootStatus.ROOTED_WITH_PERMISSION
            }

            // STEP 2: Check if we have a cached NON-root shell
            // This can happen if initial shell creation timed out (e.g., Magisk grant dialog)
            // In this case, we need to invalidate the cache and try fresh
            val cachedShell = Shell.getCachedShell()
            if (cachedShell != null && cachedShell.isAlive && !cachedShell.isRoot) {
                AppLogger.w(TAG, "⚠️ Found cached NON-root shell - this may be from a previous timeout")
                AppLogger.w(TAG, "   Closing cached shell and retrying fresh...")
                try {
                    cachedShell.close()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "   Exception closing cached shell: ${e.message}")
                }
            }

            // STEP 3: Get/create a shell
            // Shell.getShell() will:
            // - Return existing cached shell if alive (no toast)
            // - Create new shell if none exists (shows toast ONCE on first grant)
            // - Show permission dialog if never granted
            AppLogger.d(TAG, "Getting main shell (may show toast on first creation)...")

            // Retry before concluding the device has no root.
            //
            // A single failed attempt does NOT mean NOT_ROOTED. It also covers "the root manager was
            // not ready yet", and that is a real case rather than a theoretical one: KernelSU in LKM
            // mode loads its module during boot, so an app that asks early gets a non-root shell from
            // a device that is perfectly rooted. libsu then CACHES that shell, and every later check
            // reads the cached answer - which is how a user ends up with "none of the root features
            // are available" for the whole session. Reported in issue #79 on KernelSU v1.1.1, LKM
            // mode, stock kernel.
            //
            // Each attempt closes the cached non-root shell first, so the retry genuinely re-asks
            // instead of re-reading the same stale answer.
            //
            // Same shape as PackageSafetyLoader's MAX_LOAD_ATTEMPTS: try a bounded number of times,
            // and only then record the negative. A user who has actually denied root pays a short
            // delay once per check, which is the cheaper mistake of the two.
            var shell = Shell.getShell()
            var attempt = 1

            while (!shell.isRoot && attempt < MAX_ROOT_ATTEMPTS) {
                AppLogger.d(TAG, "No root on attempt $attempt of $MAX_ROOT_ATTEMPTS - the manager may not be ready, retrying")
                delay(ROOT_RETRY_DELAY_MS)

                Shell.getCachedShell()?.let { stale ->
                    if (!stale.isRoot) {
                        try {
                            stale.close()
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Could not close the cached non-root shell: ${e.message}")
                        }
                    }
                }

                attempt++
                shell = Shell.getShell()
            }

            return@withContext if (shell.isRoot) {
                AppLogger.d(TAG, "✅ Root access GRANTED - ROOTED_WITH_PERMISSION (attempt $attempt)")
                RootStatus.ROOTED_WITH_PERMISSION
            } else {
                AppLogger.d(TAG, "❌ No root after $attempt attempts - NOT_ROOTED")
                RootStatus.NOT_ROOTED
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Exception during root check: ${e.message}", e)
            return@withContext RootStatus.NOT_ROOTED
        }
    }

    suspend fun executeRootCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!hasRootPermission) {
            return@withContext Pair(-1, "No root permission")
        }

        try {
            val result = Shell.cmd(command).exec()

            val output = result.out.joinToString("\n")

            Pair(result.code, output)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
}

