package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Firewall backend using Android's ConnectivityManager firewall chain API.
 *
 * This backend uses shell commands to access the ConnectivityManager firewall chain
 * to block network access for specific apps using the FIREWALL_CHAIN_OEM_DENY_3 chain.
 *
 * Requirements:
 * - Android 13+ (API 33+)
 * - Shizuku in ADB mode (UID 2000) or root mode (UID 0)
 *
 * Advantages:
 * - Blocks ALL network types (WiFi, Mobile, Roaming, VPN, etc.)
 * - No VPN icon in status bar
 * - Kernel-level blocking (packets are dropped)
 *
 * Limitations:
 * - All-or-nothing blocking (cannot block only WiFi or only Mobile)
 * - Settings lost on reboot (must restore on boot)
 *
 * Shell commands used:
 * - cmd connectivity set-chain3-enabled true/false
 * - cmd connectivity set-package-networking-enabled true/false <package>
 * - cmd connectivity get-package-networking-enabled <package>
 */
class ConnectivityManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "ConnectivityManagerFirewall"
        private const val SERVICE_NAME = "connectivity"
        private const val MIN_API_LEVEL = Build.VERSION_CODES.TIRAMISU // Android 13
        private const val FIREWALL_CHAIN_OEM_DENY_3 = 3 // OEM-specific deny chain

        /**
         * Process-wide, NOT per-instance.
         *
         * Everything this backend guards - the OEM_DENY_3 chain, the system's per-package denials,
         * the on-disk record - is shared by every instance. FirewallManager.cleanupAllBackends()
         * builds a second instance while the privileged service may still be inside applyRules, and
         * a per-instance lock let the sweep restore every package and write an empty record while
         * that apply re-denied them, leaving apps offline with the firewall off.
         */
        private val mutex = Mutex()
    }

    // Track applied policies to avoid redundant shell commands (memory leak fix)
    // Maps packageName -> isBlocked
    private val appliedPolicies = mutableMapOf<String, Boolean>()

    /**
     * Start the firewall by starting the PrivilegedFirewallService.
     * The service will call startInternal() to actually enable the firewall chain.
     */
    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== ConnectivityManagerFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with ConnectivityManager backend")

            // Start the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "CONNECTIVITY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ ConnectivityManager firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start ConnectivityManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually enable the firewall chain.
     */
    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Enabling firewall chain")

            // Enable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled true")
            if (exitCode != 0) {
                val error = "Failed to enable firewall chain: $output"
                AppLogger.e(TAG, error)
                return Result.failure(Exception(error))
            }

            // Recorded durably so a fresh process after a crash knows this backend ran and has
            // something to undo. It records "De1984 asked for this chain", NOT "De1984 was first":
            // set-chain3-enabled reports success whether or not the chain was already on, and the
            // connectivity shell offers no getter, so a chain an OEM had already enabled cannot be
            // told apart from one we enabled. Undoing it on stop is what the app has always done.
            setChain3EnabledByUs(true)

            AppLogger.d(TAG, "✅ Firewall chain enabled (FIREWALL_CHAIN_OEM_DENY_3)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "enable ConnectivityManager firewall chain"))
        }
    }

    /**
     * Stop the firewall by stopping the PrivilegedFirewallService.
     * The service will call stopInternal() to actually disable the firewall chain.
     */
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping ConnectivityManager firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            // Stop the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
                // Name the backend. The service holds ONE currentBackend, so an unqualified stop
                // tears down whatever it happens to be running - which during a switch is the
                // backend that was just STARTED, not this one.
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "CONNECTIVITY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "ConnectivityManager firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop ConnectivityManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually disable the firewall chain.
     */
    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Disabling firewall chain")

            // Re-enable networking for every package we denied, BEFORE the chain goes down.
            // Disabling the chain alone only masks those denials; they stay recorded in the system
            // and come back the moment anything turns the chain on again.
            val notRestored = restoreBlockedPackages()

            // Disable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            var chainStillEnabled = false
            if (exitCode != 0) {
                AppLogger.e(TAG, "Failed to disable firewall chain: $output")
                // This used to say "Don't fail on stop - just log the warning" and swallow it. That
                // is the same hole the iptables backend had: the command that switches enforcement
                // off can fail and the teardown still reports success. OEM_DENY_3 stays enabled
                // system-wide, ready to re-arm any denial still recorded in the system, while the
                // app shows the firewall as off.
                chainStillEnabled = chain3EnabledByUs()
            } else {
                setChain3EnabledByUs(false)
            }

            // Clear applied policies cache when stopping firewall
            appliedPolicies.clear()
            AppLogger.d(TAG, "Cleared applied policies cache")

            if (notRestored.isNotEmpty()) {
                AppLogger.e(TAG, "❌ ${notRestored.size} packages still denied networking after stop")
                return Result.failure(
                    errorHandler.handleError(
                        Exception("${notRestored.size} packages could not have networking restored"),
                        "restore ConnectivityManager package networking"
                    )
                )
            }

            // Only OUR chain counts. If we never turned OEM_DENY_3 on, a failed disable leaves it
            // exactly as we found it and is not ours to report.
            if (chainStillEnabled) {
                return Result.failure(
                    errorHandler.handleError(
                        Exception("the OEM_DENY_3 firewall chain we enabled could not be disabled"),
                        "disable ConnectivityManager firewall chain"
                    )
                )
            }

            AppLogger.d(TAG, "Firewall chain disabled")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to disable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "disable ConnectivityManager firewall chain"))
        }
    }

    /**
     * Apply firewall rules using ConnectivityManager shell commands.
     *
     * CRITICAL: This runs in NonCancellable context to prevent shell commands from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * Interrupted commands could leave the firewall in an inconsistent state where some
     * apps are blocked and others aren't.
     */
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = withContext(NonCancellable) {
        mutex.withLock {
            return@withContext try {
                AppLogger.d(TAG, "=== ConnectivityManagerFirewallBackend.applyRules() ===")
            AppLogger.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")

            // Get default policy from SharedPreferences
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            AppLogger.d(TAG, "Default policy: $defaultPolicy (isBlockAllDefault=$isBlockAllDefault)")

            var appliedCount = 0
            var errorCount = 0
            var skippedCount = 0

            // Create a map of rules by (packageName, userId) for quick lookup
            val rulesByPackageAndUser = rules.filter { it.enabled }.associateBy { "${it.packageName}:${it.userId}" }

            // Get all installed packages with network permissions from ALL user profiles
            val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val allPackages = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
                .getPackagesWithNetworkPermissions(context)

            AppLogger.d(TAG, "Found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

            // Calculate desired policies for all packages
            val desiredPolicies = mutableMapOf<String, Boolean>()  // packageName -> shouldBlock

            // Get critical package protection setting once (outside the loop)
            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )

            // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
            // Even though this backend operates per-package, Android's network permissions are UID-based
            // So if ANY package in a UID is critical with no rule, all packages in that UID should be allowed
            val uidsWithCritical = if (allowCritical) {
                allPackages
                    .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName, it.uid / 100000) }
                    .map { it.uid }
                    .toSet()
            } else {
                emptySet()
            }

            // First pass: Calculate what the policy should be for each package
            // NOTE: ConnectivityManager backend has limited multi-user support because
            // "cmd connectivity set-package-networking-enabled" operates on package names
            // in the current user context. Work profile apps may not be blocked correctly.
            // For full multi-user support, use iptables backend (root) or NetworkPolicyManager.
            allPackages.forEach { appInfo ->
                val packageName = appInfo.packageName
                val uid = appInfo.uid
                // Derive userId from UID for rule lookup
                val userId = uid / 100000

                // Never block system-critical packages - always allow (unless setting is enabled)
                if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
                    desiredPolicies[packageName] = false  // false = allow
                    return@forEach
                }

                // Never block VPN apps to prevent VPN reconnection issues (unless setting is enabled)
                if (hasVpnService(packageName, userId) && !allowCritical) {
                    desiredPolicies[packageName] = false  // false = allow
                    return@forEach
                }

                // Look up rule by (packageName, userId) composite key
                val rule = rulesByPackageAndUser["$packageName:$userId"]

                val shouldBlock = if (rule != null) {
                    // Has explicit rule - use it.
                    //
                    // isBlockedOnAnyNetwork(), NOT isBlockedOn(networkType). This backend reports
                    // supportsGranularControl() == false and has one switch per app, so asking about
                    // the CURRENT network made it silently granular: a rule left behind by iptables
                    // or VPN with only Mobile blocked left the app blocked on mobile and wide open on
                    // WiFi, while the single "Internet Access" toggle this backend shows said blocked
                    // either way. The code and its own comment disagreed.
                    val result = when {
                        !screenOn && rule.blockWhenBackground -> true
                        rule.isBlockedOnAnyNetwork() -> true
                        else -> false
                    }
                    // Debug log for packages with rules
                    AppLogger.d(TAG, "🔍 [RULE DEBUG] $packageName: found rule wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, " +
                            "roaming=${rule.blockWhenRoaming}, anyNetwork=${rule.isBlockedOnAnyNetwork()} → shouldBlock=$result")
                    result
                } else {
                    // No rule - apply default policy
                    // Per FIREWALL.md lines 220-230:
                    // - Block All mode: Apps without rules are blocked on all networks
                    // - Allow All mode: Apps without rules are allowed on all networks
                    // EXCEPT: When allowCritical is ON and UID contains critical package, default to ALLOW for stability
                    // IMPORTANT: Check at UID level because Android's network permissions are UID-based
                    if (isBlockAllDefault && allowCritical && uidsWithCritical.contains(uid)) {
                        val isSelfCritical = Constants.Firewall.isSystemCritical(packageName) || hasVpnService(packageName, userId)
                        if (!isSelfCritical) {
                            AppLogger.d(TAG, "  $packageName (UID $uid): no rule, shares UID with critical package → allowing")
                        }
                        false  // Allow UIDs with critical packages without rules for system stability
                    } else {
                        isBlockAllDefault
                    }
                }

                desiredPolicies[packageName] = shouldBlock
            }

            // Second pass: Only apply changes for packages whose policy changed
            // This drastically reduces shell command execution (memory leak fix)
            // NOTE: This only affects user 0 packages due to ConnectivityManager limitations
            AppLogger.d(TAG, "🔍 [CACHE DEBUG] appliedPolicies cache size: ${appliedPolicies.size}, desiredPolicies size: ${desiredPolicies.size}")

            // Record what we are ABOUT to deny, durably, before the first command runs. If the
            // process dies mid-loop the record still covers everything that was denied. It
            // deliberately over-records: a package whose command later failed is listed anyway, and
            // re-enabling an already-enabled package is a harmless no-op. The other direction -
            // denying a package we never recorded - strands it with no network and nothing to
            // point at. One synchronous write per apply, not one per package.
            // Union, not overwrite. After a process restart appliedPolicies is empty, so this loop
            // also re-enables packages that were denied by the previous process. Overwriting the
            // record first would drop them from it, and a death before the loop reached them would
            // strand them with no network and no record to undo it.
            val intendedBlocked = desiredPolicies.filterValues { it }.keys.toSet()
            val existingRecord = loadBlockedPackages()
            // Only pay for the synchronous commit when the record would actually change. Under the
            // Block All default intendedBlocked is close to every network-capable package, and this
            // runs on every network change, screen toggle and rule edit.
            if (!existingRecord.containsAll(intendedBlocked)) {
                saveBlockedPackages(existingRecord + intendedBlocked, durable = true)
            }

            desiredPolicies.forEach { (packageName, shouldBlock) ->
                val currentPolicy = appliedPolicies[packageName]

                // Skip if policy hasn't changed
                if (currentPolicy == shouldBlock) {
                    skippedCount++
                    // Log skipped packages that have explicit rules (these are the ones we care about)
                    if (rulesByPackageAndUser.keys.any { it.startsWith("$packageName:") }) {
                        AppLogger.d(TAG, "🔍 [CACHE DEBUG] SKIPPED $packageName: currentPolicy=$currentPolicy, shouldBlock=$shouldBlock (has rule)")
                    }
                    return@forEach
                }
                
                // Log when we're about to apply a change
                AppLogger.d(TAG, "🔍 [CACHE DEBUG] APPLYING $packageName: currentPolicy=$currentPolicy → shouldBlock=$shouldBlock")

                try {
                    // Set package networking enabled/disabled using shell command
                    val enabled = !shouldBlock  // true = allow, false = block
                    val (exitCode, output) = shizukuManager.executeShellCommand(
                        "cmd connectivity set-package-networking-enabled $enabled $packageName"
                    )

                    if (exitCode == 0) {
                        appliedCount++
                        appliedPolicies[packageName] = shouldBlock  // Track applied policy
                        val ruleStatus = if (rulesByPackageAndUser.keys.any { it.startsWith("$packageName:") }) "has rule" else "no rule (default policy)"
                        AppLogger.d(TAG, "Applied policy for $packageName ($ruleStatus): " +
                                "policy=${if (shouldBlock) "BLOCK (all networks)" else "ALLOW"}")
                    } else {
                        errorCount++
                        AppLogger.e(TAG, "Failed to apply policy for $packageName: $output")
                    }
                } catch (e: Exception) {
                    errorCount++
                    AppLogger.e(TAG, "Failed to apply policy for $packageName", e)
                }
            }

            // Update the record from what this pass actually did, rather than overwriting it with
            // appliedPolicies. A package can be denied and absent from desiredPolicies - it was
            // uninstalled, disabled, or moved out of the enumeration - and after a cache clear it is
            // in neither map. Overwriting dropped it from the record while the system denial stood,
            // leaving it offline with nothing left to undo it. Only an explicit re-enable removes a
            // package from the record.
            val record = loadBlockedPackages().toMutableSet()
            appliedPolicies.forEach { (pkg, isBlocked) ->
                if (isBlocked) record.add(pkg) else record.remove(pkg)
            }
            saveBlockedPackages(record)

                AppLogger.d(TAG, "✅ Applied $appliedCount policies, skipped $skippedCount unchanged, $errorCount errors")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply rules", e)
                Result.failure(errorHandler.handleError(e, "apply connectivity manager rules"))
            }
        }
    }

    override fun isActive(): Boolean {
        // Check if PrivilegedFirewallService is running with ConnectivityManager backend
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            // If SharedPreferences says service is not running, it's definitely not active
            if (!isServiceRunning || backendType != "CONNECTIVITY_MANAGER") {
                return false
            }

            // SharedPreferences says service is running, but verify the service is actually alive
            // This is important after app reinstall (e.g., dev.sh update) where SharedPreferences
            // persist but the service process is killed
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                @Suppress("DEPRECATION")
                val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
                val serviceClassName = "io.github.dorumrr.de1984.data.service.PrivilegedFirewallService"
                val isServiceActuallyRunning = runningServices.any { service ->
                    service.service.className == serviceClassName
                }

                // If service is not actually running, clear the SharedPreferences flags
                if (!isServiceActuallyRunning) {
                    AppLogger.w(TAG, "SharedPreferences says privileged service is running, but service is not actually running. Clearing flags.")
                    prefs.edit()
                        .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
                        .remove(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE)
                        .apply()
                    return false
                }

                return true
            }

            // Fallback: if we can't check running services, trust SharedPreferences
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check if ConnectivityManager firewall is active", e)
            false
        }
    }

    override fun getType(): FirewallBackendType = FirewallBackendType.CONNECTIVITY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            // Check Android version
            if (Build.VERSION.SDK_INT < MIN_API_LEVEL) {
                val error = "ConnectivityManager firewall requires Android 13+"
                return Result.failure(Exception(error))
            }

            // Check Shizuku permission
            if (!shizukuManager.hasShizukuPermission) {
                val error = "Shizuku permission required"
                return Result.failure(Exception(error))
            }

            // Test if the connectivity command is available
            val (_, output) = shizukuManager.executeShellCommand("cmd connectivity help")

            // Check if output contains expected help text
            if (!output.contains("set-chain3-enabled")) {
                val error = "ConnectivityManager firewall chain API not available"
                return Result.failure(Exception(error))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "ConnectivityManager firewall not available", e)
            Result.failure(errorHandler.handleError(e, "check ConnectivityManager availability"))
        }
    }

    override fun supportsGranularControl(): Boolean = false  // All-or-nothing blocking

    /**
     * Clear the applied policies cache.
     * This should be called when the default policy changes to force re-evaluation of all packages.
     */
    fun clearAppliedPoliciesCache() {
        appliedPolicies.clear()
        AppLogger.d(TAG, "Cleared applied policies cache (forced)")
    }

    /**
     * Undo everything this backend did to the system, from any process.
     *
     * Exists because none of it lives in this app. `set-package-networking-enabled false` and
     * `set-chain3-enabled true` are system state: if De1984 is force-stopped, crashes, or is
     * uninstalled while this backend is running, nothing calls [stopInternal] and the denied apps
     * stay offline with no De1984 on screen to explain it. FirewallManager.cleanupAllBackends()
     * calls this on every stop, so a fresh process cleans up after a dead one.
     *
     * Returns success without touching anything when there is no record of ours - the normal case
     * for the many users who never run this backend. That matters: blindly disabling chain3 could
     * clobber an OEM that uses it for its own purposes.
     */
    suspend fun clearOrphanedPolicies(): Result<Unit> = mutex.withLock {
        val blocked = loadBlockedPackages()
        val weEnabledChain3 = chain3EnabledByUs()

        if (blocked.isEmpty() && !weEnabledChain3) {
            AppLogger.d(TAG, "No ConnectivityManager state of ours to undo")
            appliedPolicies.clear()
            return Result.success(Unit)
        }

        AppLogger.d(TAG, "Undoing ConnectivityManager state: ${blocked.size} denied packages, chain3ByUs=$weEnabledChain3")

        val notRestored = restoreBlockedPackages()

        // A non-empty package record is equally good evidence that this backend ran, and covers an
        // upgrade from a build that never wrote the flag.
        var chainStillEnabled = false
        if (weEnabledChain3 || blocked.isNotEmpty()) {
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            if (exitCode == 0) {
                setChain3EnabledByUs(false)
            } else {
                AppLogger.e(TAG, "Failed to disable firewall chain during cleanup: $output")
                // Reported, not just logged - same reasoning as stopInternal. Read the flag back
                // rather than reusing weEnabledChain3: it stays true on a failed disable, and that
                // is exactly the state that means the chain is still ours and still on.
                chainStillEnabled = chain3EnabledByUs()
            }
        }

        appliedPolicies.clear()

        if (notRestored.isEmpty() && chainStillEnabled) {
            return Result.failure(
                errorHandler.handleError(
                    Exception("the OEM_DENY_3 firewall chain we enabled could not be disabled"),
                    "disable ConnectivityManager firewall chain"
                )
            )
        }

        return if (notRestored.isEmpty()) {
            AppLogger.d(TAG, "✅ Restored networking for ${blocked.size} packages")
            Result.success(Unit)
        } else {
            AppLogger.e(TAG, "❌ ${notRestored.size} of ${blocked.size} packages could not have networking restored")
            Result.failure(
                errorHandler.handleError(
                    Exception("${notRestored.size} packages could not have networking restored"),
                    "restore ConnectivityManager package networking"
                )
            )
        }
    }

    /**
     * Re-enable networking for every package in the persisted record.
     *
     * @return the packages that could not be restored. They stay on disk for the next attempt
     * rather than being silently forgotten.
     */
    private suspend fun restoreBlockedPackages(): Set<String> {
        val blocked = loadBlockedPackages()
        if (blocked.isEmpty()) return emptySet()

        val restored = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        blocked.forEach { packageName ->
            try {
                val (exitCode, output) = shizukuManager.executeShellCommand(
                    "cmd connectivity set-package-networking-enabled true $packageName"
                )
                if (exitCode == 0) {
                    restored.add(packageName)
                    appliedPolicies.remove(packageName)
                    AppLogger.d(TAG, "Restored networking for $packageName")
                } else if (!isInstalled(packageName)) {
                    // Proof that there is nothing left to undo: the package is gone, so the system
                    // has no denial to hold against it. Without this a package uninstalled while
                    // denied stayed in the record forever, and every stop from then on reported a
                    // teardown failure that could never be cleared.
                    restored.add(packageName)
                    appliedPolicies.remove(packageName)
                    AppLogger.w(TAG, "$packageName is no longer installed - dropping it from the record")
                } else {
                    failed.add(packageName)
                    AppLogger.e(TAG, "Failed to restore networking for $packageName: $output")
                }
            } catch (e: Exception) {
                failed.add(packageName)
                AppLogger.e(TAG, "Failed to restore networking for $packageName", e)
            }
        }

        // Re-read the record instead of writing the snapshot taken before the loop. This function
        // can run on a second backend instance - cleanupAllBackends() builds a fresh one - while the
        // service instance is still inside applyRules and denying new packages. Writing the stale
        // snapshot would erase whatever it recorded in the meantime.
        saveBlockedPackages(loadBlockedPackages() - restored, durable = true)
        return failed
    }

    /**
     * Is this package still installed for any user?
     *
     * Deliberately conservative: any failure to answer returns true, so an unreadable package is
     * kept in the record rather than dropped. Dropping is only ever allowed on a definite "gone".
     */
    private fun isInstalled(packageName: String): Boolean {
        return try {
            // MATCH_UNINSTALLED_PACKAGES covers "pm uninstall -k" - app removed, data kept - and
            // MATCH_DISABLED_COMPONENTS covers an app the user or a device admin disabled. Plain
            // getApplicationInfo(name, 0) throws NameNotFound for both, so a package that is only
            // temporarily out of sight was counted as gone and erased from the record. Re-enable or
            // reinstall it and it comes back with our denial still applied and nothing left able to
            // undo it. Neither is "gone"; only a package with no trace at all is.
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS
            context.packageManager.getApplicationInfo(packageName, flags)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    /** Packages this backend has denied networking, as last written to disk. */
    private fun loadBlockedPackages(): Set<String> {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(Constants.Settings.KEY_CM_BLOCKED_PACKAGES, emptySet())?.toSet()
            ?: emptySet()
    }

    /**
     * @param durable flush synchronously. Use it before denying anything and after restoring, so
     * the record cannot be lost by a process death that lands between the write and the command.
     */
    private suspend fun saveBlockedPackages(packages: Set<String>, durable: Boolean = false) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putStringSet(Constants.Settings.KEY_CM_BLOCKED_PACKAGES, packages)
            if (durable) {
                @Suppress("ApplySharedPref")
                editor.commit()
            } else {
                editor.apply()
            }
        }
    }

    private fun chain3EnabledByUs(): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Constants.Settings.KEY_CM_CHAIN3_ENABLED_BY_US, false)
    }

    private suspend fun setChain3EnabledByUs(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            @Suppress("ApplySharedPref")
            prefs.edit().putBoolean(Constants.Settings.KEY_CM_CHAIN3_ENABLED_BY_US, enabled).commit()
        }
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     */
        /**
     * Does this package host a VPN service, for THIS user profile?
     *
     * userId has no default on purpose. It used to default to 0, and every enforcement call
     * site omitted it - so a VPN app installed only in the work profile was looked up in the
     * personal profile, not found, and treated as an ordinary app. Block All then cut the work
     * profile's VPN. Making it required means the compiler catches the next such caller.
     */
        private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            // Check if any service has BIND_VPN_SERVICE permission
            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

