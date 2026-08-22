package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
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
import java.lang.reflect.Method

/**
 * Firewall backend using Android's NetworkPolicyManager API via Shizuku.
 *
 * This is a LEGACY backend for Android 12 and below. On Android 13+, use
 * ConnectivityManager backend instead as it blocks all networks reliably.
 *
 * This backend:
 * - Works with Shizuku in ADB mode (UID 2000) - no root required
 * - Does NOT show VPN icon
 * - Does NOT occupy VPN slot
 * - Uses system-level network policies
 *
 * Limitations:
 * - May not block WiFi on some devices (only Mobile/Roaming)
 * - Less granular than iptables (per-app only, not per-network-type)
 * - Uses hidden Android API (may break in future Android versions)
 * - Requires Shizuku to be running
 *
 * Recommendation: Use ConnectivityManager on Android 13+ for reliable blocking.
 */
class NetworkPolicyManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "NetworkPolicyManagerFirewall"
        
        // NetworkPolicyManager constants
        private const val POLICY_NONE = 0x0
        private const val POLICY_REJECT_METERED_BACKGROUND = 0x1
        private const val POLICY_REJECT_ALL = 0x4  // Android 11+ / Custom ROMs

        // System service name
        private const val SERVICE_NAME = "netpolicy"

        /**
         * Guards the record of "what each UID looked like before this backend touched it".
         *
         * FirewallManager, PrivilegedFirewallService and cleanupAllBackends each hold their own
         * instance of this backend, so the per-instance [mutex] does not make them exclusive. Two
         * instances applying at the same time would both read the same UID, each see the other's
         * write as the original value, and destroy the real one.
         *
         * Observed on hardware: uid 10212 held POLICY_REJECT_ALL (262144). Instance A read it
         * correctly and wrote the blocking policy at 22:04:31.917; instance B read the same uid
         * 3 ms later, got A's value, and saved 1 as the "original". The real value was lost.
         *
         * Lock order is always instance [mutex] first, then this. Never the other way round.
         */
        private val originalPolicyLock = Mutex()
    }

    private val mutex = Mutex()

    // Track applied policies to avoid redundant reflection calls (memory leak fix)
    // Maps UID -> isBlocked (boolean, not policy constant to avoid mismatch issues)
    private val appliedPolicies = mutableMapOf<Int, Boolean>()

    // Track which policy constant works on this device
    private var blockingPolicy: Int = POLICY_REJECT_ALL  // Try POLICY_REJECT_ALL first
    private var policyTested: Boolean = false

    // Cached reflection objects
    private var networkPolicyManagerClass: Class<*>? = null
    private var stubClass: Class<*>? = null
    private var asInterfaceMethod: Method? = null
    private var setUidPolicyMethod: Method? = null
    private var getUidPolicyMethod: Method? = null

    /**
     * Start the firewall by starting the PrivilegedFirewallService.
     * The service will call startInternal() to initialize reflection.
     */
    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with NetworkPolicyManager backend")

            // Start the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "NETWORK_POLICY_MANAGER")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ NetworkPolicyManager firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start NetworkPolicyManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to initialize reflection.
     */
    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Initializing reflection")

            // Initialize reflection if needed
            if (!initializeReflection()) {
                val error = errorHandler.handleError(
                    Exception("Failed to initialize reflection for NetworkPolicyManager"),
                    "start NetworkPolicyManager firewall"
                )
                return Result.failure(error)
            }

            AppLogger.d(TAG, "✅ Reflection initialized")
            AppLogger.d(TAG, "ℹ️  Policy support will be tested on first rule application")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize reflection", e)
            Result.failure(errorHandler.handleError(e, "initialize NetworkPolicyManager reflection"))
        }
    }

    /**
     * Stop the firewall by stopping the PrivilegedFirewallService.
     */
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping NetworkPolicyManager firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            // Stop the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
            }
            context.startService(intent)

            AppLogger.d(TAG, "NetworkPolicyManager firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop NetworkPolicyManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to cleanup.
     */
    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Cleaning up")

            val result = clearBlockedUidPoliciesInternal()

            AppLogger.d(TAG, "Cleanup complete")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop NetworkPolicyManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop NetworkPolicyManager firewall"))
        }
    }

    /**
     * Set every UID this backend blocked back to POLICY_NONE.
     *
     * Android persists UID policies in `/data/system/netpolicy.xml`, so a policy written here
     * outlives the firewall, this process, a reboot and even an uninstall - and no Android screen
     * exposes it. Stopping used to clear only the in-memory cache, which left the affected apps
     * blocked with no way back.
     *
     * Use this from outside the backend; [stopInternal] already holds the mutex and calls the
     * internal form directly.
     */
    suspend fun clearOrphanedPolicies(): Result<Unit> = mutex.withLock {
        return clearBlockedUidPoliciesInternal()
    }

    /**
     * Body of [clearOrphanedPolicies]. The caller MUST already hold [mutex].
     *
     * Reverts the union of the in-memory cache and the persisted UID list. The persisted list is
     * what makes cleanup possible from a fresh process - after a crash or a backend switch,
     * [appliedPolicies] is empty but the system policies are still in place.
     */
    private suspend fun clearBlockedUidPoliciesInternal(): Result<Unit> = withContext(Dispatchers.IO) {
        // Off the caller's thread on purpose. stopFirewall() reaches here from viewModelScope, which
        // is Dispatchers.Main.immediate, and this loop makes one blocking binder call per UID -
        // hundreds of them for a block-all-by-default user. On the main thread that is an ANR.
        originalPolicyLock.withLock {
            val originals = loadOriginalPolicies()
            if (originals.isEmpty()) {
                AppLogger.d(TAG, "No UID policies of ours to restore")
                appliedPolicies.clear()
                    return@withContext Result.success(Unit)
            }

            if (!initializeReflection()) {
                AppLogger.e(TAG, "Reflection unavailable - ${originals.size} UID policies left in place")
                    return@withContext Result.failure(
                    errorHandler.handleError(
                        Exception("Failed to initialize reflection for NetworkPolicyManager"),
                        "restore network policies"
                    )
                )
            }

            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "Cannot reach NetworkPolicyManager - ${originals.size} UID policies left in place")
                    return@withContext Result.failure(
                    errorHandler.handleError(
                        Exception("Failed to get NetworkPolicyManager instance"),
                        "restore network policies"
                    )
                )
            }

            // Anything still in here after the loop failed to restore, so it stays on disk for the next
            // attempt rather than being silently forgotten.
            val remaining = originals.toMutableMap()
            originals.forEach { (uid, original) ->
                try {
                    setUidPolicyMethod?.invoke(networkPolicyManager, uid, original)
                    remaining.remove(uid)
                    appliedPolicies.remove(uid)
                    AppLogger.d(TAG, "Restored UID $uid to policy $original")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to restore policy for UID $uid", e)
                }
            }

            saveOriginalPolicies(remaining)

            return@withContext if (remaining.isEmpty()) {
                AppLogger.d(TAG, "✅ Restored ${originals.size} UID policies")
                appliedPolicies.clear()
                Result.success(Unit)
            } else {
                AppLogger.e(TAG, "❌ ${remaining.size} of ${originals.size} UID policies could not be restored")
                Result.failure(
                    errorHandler.handleError(
                        Exception("${remaining.size} UID policies could not be restored"),
                        "restore network policies"
                    )
                )
            }
        }
    }

    /**
     * Read a UID's current policy. Returns null if it cannot be read, in which case the caller must
     * not write to that UID - overwriting a policy we cannot record would destroy it.
     */
    private fun readUidPolicy(networkPolicyManager: Any, uid: Int): Int? {
        return try {
            getUidPolicyMethod?.invoke(networkPolicyManager, uid) as? Int
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read current policy for UID $uid", e)
            null
        }
    }

    /**
     * UIDs this backend has written to, mapped to the policy that was in place before the first
     * write. Mirrored on disk as "uid:policy" strings so a fresh process can still put them back -
     * after a crash or a backend switch the in-memory state is gone but the system policies remain.
     */
    private fun loadOriginalPolicies(): Map<Int, Int> {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(Constants.Settings.KEY_NPM_ORIGINAL_POLICIES, emptySet())
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                val uid = parts.getOrNull(0)?.toIntOrNull()
                val policy = parts.getOrNull(1)?.toIntOrNull()
                if (uid != null && policy != null) uid to policy else null
            }
            ?.toMap()
            ?: emptyMap()
    }

    private fun saveOriginalPolicies(originals: Map<Int, Int>) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(
                Constants.Settings.KEY_NPM_ORIGINAL_POLICIES,
                originals.map { (uid, policy) -> "$uid:$policy" }.toSet()
            )
            .apply()
    }

    /**
     * Apply firewall rules using NetworkPolicyManager reflection.
     *
     * CRITICAL: This runs in NonCancellable context to prevent reflection calls from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * Interrupted operations could leave the firewall in an inconsistent state where some
     * apps are blocked and others aren't.
     */
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = withContext(NonCancellable) {
        mutex.withLock {
            return@withContext try {
                AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.applyRules() ===")
            AppLogger.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")

            // Note: No need to check isActive() here - service will only call this when active

                // Get NetworkPolicyManager instance
                val networkPolicyManager = getNetworkPolicyManager()
                if (networkPolicyManager == null) {
                    val error = errorHandler.handleError(
                        Exception("Failed to get NetworkPolicyManager instance"),
                        "apply network policies"
                    )
                    return@withContext Result.failure(error)
                }

                // Test which policy works on first run
                if (!policyTested) {
                    testPolicySupport(networkPolicyManager)
                }

            // Get default policy from SharedPreferences
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            // Get critical package protection setting once (outside the loop)
            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )

            AppLogger.d(TAG, "Default policy: $defaultPolicy (isBlockAllDefault=$isBlockAllDefault, allowCritical=$allowCritical)")

            var appliedCount = 0
            var errorCount = 0

            // Create a map of rules by UID for quick lookup
            val rulesByUid = rules.filter { it.enabled }.groupBy { it.uid }

            // Get all installed packages with network permissions from ALL user profiles
            val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val allPackages = userProfiles.flatMap { profile ->
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                    context, PackageManager.GET_META_DATA, profile.userId
                ).map { appInfo -> appInfo to profile.userId }
            }.filter { (appInfo, userId) ->
                try {
                    val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                        context,
                        appInfo.packageName,
                        PackageManager.GET_PERMISSIONS,
                        userId
                    )
                    packageInfo?.requestedPermissions?.any { permission ->
                        Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }.map { (appInfo, _) -> appInfo }

            AppLogger.d(TAG, "Found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

            // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
            // This is needed because we block by UID, not by package - so if ANY package
            // in a UID is critical with no rule, the entire UID should be allowed
            val uidsWithCritical = if (allowCritical) {
                allPackages
                    .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName) }
                    .map { it.uid }
                    .toSet()
            } else {
                emptySet()
            }

            // First pass: Calculate desired policies for all UIDs
            // This is done separately to enable differential application (memory leak fix)
            val desiredPolicies = mutableMapOf<Int, Boolean>()  // uid -> shouldBlock

            allPackages.forEach { appInfo ->
                val uid = appInfo.uid

                // Never block UIDs that contain system-critical packages or VPN apps
                // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                if (isUidExempted(uid, allPackages)) {
                    return@forEach
                }

                val rulesForUid = rulesByUid[uid]

                val shouldBlock = if (rulesForUid != null && rulesForUid.isNotEmpty()) {
                    // Has explicit rules - use them
                    // For shared UIDs, block if ANY rule says to block (most restrictive)
                    rulesForUid.any { rule ->
                        when {
                            !screenOn && rule.blockWhenBackground -> true
                            rule.isBlockedOn(networkType) -> true
                            else -> false
                        }
                    }
                } else {
                    // No rule - apply default policy
                    // Per FIREWALL.md lines 220-230:
                    // - Block All mode: Apps without rules are blocked on all networks
                    // - Allow All mode: Apps without rules are allowed on all networks
                    // EXCEPT: When allowCritical is ON and UID contains critical package, default to ALLOW for stability
                    // IMPORTANT: Check at UID level because we block by UID, not by package
                    if (isBlockAllDefault && allowCritical && uidsWithCritical.contains(uid)) {
                        // Log shared UID scenario for debugging
                        val packagesInUid = allPackages.filter { it.uid == uid }.map { it.packageName }
                        val criticalInUid = packagesInUid.filter { Constants.Firewall.isSystemCritical(it) || hasVpnService(it) }
                        if (criticalInUid.isNotEmpty() && packagesInUid.size > 1) {
                            AppLogger.d(TAG, "  UID $uid: allowing (shares UID with critical: ${criticalInUid.joinToString()})")
                        }
                        false  // Allow UIDs with critical packages without rules for system stability
                    } else {
                        isBlockAllDefault
                    }
                }

                desiredPolicies[uid] = shouldBlock
            }

            // Second pass: Only apply changes for UIDs whose policy changed
            // This drastically reduces reflection calls (memory leak fix)
            var skippedCount = 0
            var untouchedCount = 0

            // UIDs this backend has written to, with the policy that was in place beforehand.
            // A UID in this map is "ours"; anything else belongs to the user or the ROM.
            // One lock for every instance: reading a UID's current policy and writing over it must
            // be atomic across the whole process, or a concurrent instance's write gets recorded as
            // the original. See originalPolicyLock.
            originalPolicyLock.withLock {
                val originalPolicies = loadOriginalPolicies().toMutableMap()
                val originalPoliciesBefore = originalPolicies.toMap()

                desiredPolicies.forEach { (uid, shouldBlock) ->
                    val currentPolicy = appliedPolicies[uid]

                    // Skip if policy hasn't changed
                    if (currentPolicy == shouldBlock) {
                        skippedCount++
                        return@forEach
                    }

                    val isOurs = originalPolicies.containsKey(uid)

                    // Leave every UID we never blocked exactly as it is.
                    //
                    // POLICY_NONE does not mean "no opinion" - it erases whatever policy is set,
                    // including one the user chose. Android's own "Restrict background data" switch
                    // writes POLICY_REJECT_METERED_BACKGROUND, the same value used to block here, and
                    // custom ROMs write POLICY_REJECT_ALL for their per-app restrictions. Writing
                    // POLICY_NONE to every unblocked app silently destroyed all of it.
                    if (!shouldBlock && !isOurs) {
                        untouchedCount++
                        appliedPolicies[uid] = false
                        return@forEach
                    }

                    try {
                        if (shouldBlock) {
                            // Record what was there before the first write, so it can be given back.
                            if (!isOurs) {
                                val existing = readUidPolicy(networkPolicyManager, uid)
                                if (existing == null) {
                                    errorCount++
                                    val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"
                                    AppLogger.e(TAG, "Cannot read current policy for $packageName (UID $uid) - " +
                                            "refusing to overwrite it")
                                    return@forEach
                                }
                                originalPolicies[uid] = existing
                            }
                            setUidPolicyMethod?.invoke(networkPolicyManager, uid, blockingPolicy)
                        } else {
                            // Ours, and no longer blocked: restore exactly what was there before.
                            val original = originalPolicies.remove(uid) ?: POLICY_NONE
                            setUidPolicyMethod?.invoke(networkPolicyManager, uid, original)
                        }

                        appliedPolicies[uid] = shouldBlock  // Track applied policy
                        appliedCount++

                        val policyName = when (blockingPolicy) {
                            POLICY_REJECT_ALL -> "REJECT_ALL (WiFi+Mobile)"
                            POLICY_REJECT_METERED_BACKGROUND -> "REJECT_METERED (Mobile only)"
                            else -> "UNKNOWN"
                        }

                        val rulesForUid = rulesByUid[uid]
                        val ruleStatus = if (rulesForUid != null) "has rule" else "no rule (default policy)"

                        // Find package name for logging (may be multiple packages with same UID)
                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"

                        AppLogger.d(TAG, "Applied policy for $packageName (UID $uid, $ruleStatus): " +
                                "policy=${if (shouldBlock) "BLOCK ($policyName)" else "RESTORED"}")
                    } catch (e: Exception) {
                        errorCount++
                        val packageName = allPackages.find { it.uid == uid }?.packageName ?: "UID $uid"
                        AppLogger.e(TAG, "Failed to apply policy for $packageName (UID $uid)", e)
                    }
                }

                if (originalPolicies != originalPoliciesBefore) {
                    saveOriginalPolicies(originalPolicies)
                }
            }

                AppLogger.d(TAG, "✅ Applied $appliedCount policies, skipped $skippedCount unchanged, " +
                        "left $untouchedCount foreign policies alone, $errorCount errors")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply rules", e)
                Result.failure(errorHandler.handleError(e, "apply network policies"))
            }
        }
    }

    override fun isActive(): Boolean {
        // Check if PrivilegedFirewallService is running with NetworkPolicyManager backend
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            // If SharedPreferences says service is not running, it's definitely not active
            if (!isServiceRunning || backendType != "NETWORK_POLICY_MANAGER") {
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
            AppLogger.e(TAG, "Failed to check if NetworkPolicyManager firewall is active", e)
            false
        }
    }

    override fun getType(): FirewallBackendType = FirewallBackendType.NETWORK_POLICY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            AppLogger.d(TAG, "=== NetworkPolicyManagerFirewallBackend.checkAvailability() ===")
            
            // Check if Shizuku is available
            if (!shizukuManager.hasShizukuPermission) {
                AppLogger.d(TAG, "❌ NetworkPolicyManager not available: No Shizuku permission")
                val error = errorHandler.createRootRequiredError("NetworkPolicyManager firewall")
                return Result.failure(error)
            }
            
            // Initialize reflection
            if (!initializeReflection()) {
                AppLogger.e(TAG, "❌ NetworkPolicyManager not available: Failed to initialize reflection")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager API (reflection failed)"
                )
                return Result.failure(error)
            }
            
            // Try to get NetworkPolicyManager instance
            val networkPolicyManager = getNetworkPolicyManager()
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "❌ NetworkPolicyManager not available: Failed to get service instance")
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "NetworkPolicyManager firewall",
                    reason = "Failed to access NetworkPolicyManager service"
                )
                return Result.failure(error)
            }
            
            AppLogger.d(TAG, "✅ NetworkPolicyManager is available")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "NetworkPolicyManager availability check failed", e)
            Result.failure(errorHandler.handleError(e, "check NetworkPolicyManager availability"))
        }
    }

    /**
     * Initialize reflection objects for NetworkPolicyManager API.
     * This is done once and cached for performance.
     */
    private fun initializeReflection(): Boolean {
        // Return true if already initialized
        if (networkPolicyManagerClass != null && 
            stubClass != null && 
            asInterfaceMethod != null && 
            setUidPolicyMethod != null &&
            getUidPolicyMethod != null) {
            return true
        }

        return try {
            AppLogger.d(TAG, "Initializing reflection for NetworkPolicyManager...")
            
            // Get INetworkPolicyManager class
            networkPolicyManagerClass = Class.forName("android.net.INetworkPolicyManager")
            AppLogger.d(TAG, "✅ Found INetworkPolicyManager class")
            
            // Get INetworkPolicyManager.Stub class
            stubClass = Class.forName("android.net.INetworkPolicyManager\$Stub")
            AppLogger.d(TAG, "✅ Found INetworkPolicyManager.Stub class")
            
            // Get asInterface method
            asInterfaceMethod = stubClass?.getMethod("asInterface", IBinder::class.java)
            AppLogger.d(TAG, "✅ Found asInterface method")
            
            // Get setUidPolicy method
            setUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "setUidPolicy",
                Int::class.javaPrimitiveType,  // uid
                Int::class.javaPrimitiveType   // policy
            )
            AppLogger.d(TAG, "✅ Found setUidPolicy method")
            
            // Get getUidPolicy method (for debugging)
            getUidPolicyMethod = networkPolicyManagerClass?.getMethod(
                "getUidPolicy",
                Int::class.javaPrimitiveType   // uid
            )
            AppLogger.d(TAG, "✅ Found getUidPolicy method")
            
            AppLogger.d(TAG, "✅ Reflection initialization complete")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize reflection", e)
            networkPolicyManagerClass = null
            stubClass = null
            asInterfaceMethod = null
            setUidPolicyMethod = null
            getUidPolicyMethod = null
            false
        }
    }

    /**
     * Get NetworkPolicyManager instance via Shizuku.
     */
    private suspend fun getNetworkPolicyManager(): Any? = withContext(Dispatchers.IO) {
        try {
            // Get system service binder via Shizuku
            val serviceBinder = shizukuManager.getSystemServiceBinder(SERVICE_NAME)
            if (serviceBinder == null) {
                AppLogger.e(TAG, "Failed to get system service binder for: $SERVICE_NAME")
                return@withContext null
            }
            
            // Convert binder to INetworkPolicyManager interface
            val networkPolicyManager = asInterfaceMethod?.invoke(null, serviceBinder)
            if (networkPolicyManager == null) {
                AppLogger.e(TAG, "Failed to convert binder to INetworkPolicyManager")
                return@withContext null
            }
            
            AppLogger.d(TAG, "✅ Got NetworkPolicyManager instance")
            return@withContext networkPolicyManager
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get NetworkPolicyManager instance", e)
            return@withContext null
        }
    }

    /**
     * Test which policy constant is supported on this device.
     * Try POLICY_REJECT_ALL first (blocks WiFi + Mobile), fall back to POLICY_REJECT_METERED_BACKGROUND.
     */
    private fun testPolicySupport(networkPolicyManager: Any) {
        try {
            AppLogger.d(TAG, "Testing policy support on this device...")

            // Try POLICY_REJECT_ALL first (Android 11+ / Custom ROMs)
            try {
                // Use a dummy UID that won't affect anything (UID 0 is root, always allowed)
                setUidPolicyMethod?.invoke(networkPolicyManager, 0, POLICY_REJECT_ALL)
                setUidPolicyMethod?.invoke(networkPolicyManager, 0, POLICY_NONE)  // Reset

                blockingPolicy = POLICY_REJECT_ALL
                AppLogger.d(TAG, "✅ POLICY_REJECT_ALL is supported! Will block WiFi + Mobile networks")
            } catch (e: Exception) {
                // POLICY_REJECT_ALL not supported, fall back to POLICY_REJECT_METERED_BACKGROUND
                AppLogger.w(TAG, "⚠️  POLICY_REJECT_ALL not supported on this device")
                AppLogger.w(TAG, "⚠️  Falling back to POLICY_REJECT_METERED_BACKGROUND")
                AppLogger.w(TAG, "⚠️  ⚠️  ⚠️  LIMITATION: WiFi networks will NOT be blocked! ⚠️  ⚠️  ⚠️")
                AppLogger.w(TAG, "⚠️  Only Mobile/Roaming networks will be blocked")
                AppLogger.w(TAG, "⚠️  For WiFi blocking, use iptables backend (requires root)")
                blockingPolicy = POLICY_REJECT_METERED_BACKGROUND
            }

            policyTested = true

            val policyName = when (blockingPolicy) {
                POLICY_REJECT_ALL -> "POLICY_REJECT_ALL (blocks WiFi + Mobile)"
                POLICY_REJECT_METERED_BACKGROUND -> "POLICY_REJECT_METERED_BACKGROUND (blocks Mobile only, WiFi NOT blocked)"
                else -> "UNKNOWN"
            }
            AppLogger.d(TAG, "✅ Using policy: $policyName")

            if (blockingPolicy == POLICY_REJECT_METERED_BACKGROUND) {
                AppLogger.w(TAG, "⚠️  IMPORTANT: WiFi networks will NOT be blocked! | Only Mobile/Roaming data will be blocked. | For full WiFi blocking, root your device and use iptables.")
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to test policy support", e)
            blockingPolicy = POLICY_REJECT_METERED_BACKGROUND  // Safe fallback
            policyTested = true
        }
    }

    override fun supportsGranularControl(): Boolean = false  // Only Mobile/Roaming (WiFi doesn't work on stock Android)

    /**
     * Clear the applied policies cache.
     * This should be called when the default policy changes to force re-evaluation of all packages.
     */
    fun clearAppliedPoliciesCache() {
        appliedPolicies.clear()
        AppLogger.d(TAG, "Cleared applied policies cache (forced)")
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     */
    private fun hasVpnService(packageName: String, userId: Int = 0): Boolean {
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

    /**
     * Check if a UID should be exempted from blocking.
     * A UID is exempted if ANY package with that UID is:
     * - System-critical (in SYSTEM_WHITELIST)
     * - A VPN app (has BIND_VPN_SERVICE permission)
     *
     * This prevents shared UID bypass vulnerability where a non-critical app
     * (e.g., Gboard) shares a UID with a system-critical package.
     *
     * @param uid The UID to check
     * @param allPackages List of all installed applications
     * @return true if the UID should be exempted from blocking
     */
    private fun isUidExempted(uid: Int, allPackages: List<android.content.pm.ApplicationInfo>): Boolean {
        // Check if critical package protection is disabled
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        // Get all packages with this UID
        val packagesWithUid = allPackages.filter { it.uid == uid }

        // Check if ANY package with this UID is system-critical or a VPN app (unless setting is enabled)
        return packagesWithUid.any { appInfo ->
            (!allowCritical && Constants.Firewall.isSystemCritical(appInfo.packageName)) ||
            (!allowCritical && hasVpnService(appInfo.packageName))
        }
    }
}

