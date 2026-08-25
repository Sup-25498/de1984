package io.github.dorumrr.de1984.data.multiuser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.utils.Constants
import android.os.Build
import android.os.UserHandle
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.utils.AppLogger
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Helper class for accessing hidden Android APIs to support multi-user/work profile functionality.
 * 
 * Uses LSPosed HiddenApiBypass library to access:
 * - UserManager.getUsers() - enumerate all user profiles
 * - PackageManager.getInstalledApplicationsAsUser() - get apps per user
 * 
 * Gracefully falls back to standard APIs if hidden APIs are unavailable.
 */
object HiddenApiHelper {
    private const val TAG = "HiddenApiHelper"

    private var initialized = false
    private var hiddenApiAvailable = false

    @Volatile
    private var shizukuManager: ShizukuManager? = null

    /**
     * Set the ShizukuManager reference for Shizuku shell fallback.
     * Must be called after dependencies are initialized in De1984Application.
     */
    fun setShizukuManager(manager: ShizukuManager) {
        shizukuManager = manager
        AppLogger.d(TAG, "ShizukuManager reference set for work profile shell fallback")
    }

    @Volatile
    private var cachedUsers: List<UserProfile>? = null
    @Volatile
    private var usersCacheTime: Long = 0
    private const val USERS_CACHE_TTL = 30_000L

    @Volatile
    private var installedAppsCache: MutableMap<Int, List<ApplicationInfo>> = mutableMapOf()
    @Volatile
    private var installedAppsCacheTime: Long = 0
    // Deliberately short, and NOT raised despite the cost of what it guards.
    //
    // The receivers below it invalidate on package add, change and removal, but only for the user
    // De1984 is installed in. Verified on hardware: installing a package into the work profile
    // (user 10) while De1984 is installed only in user 0 fires no receiver at all, so a work-profile
    // install is caught by this TTL and nothing else. Raising it to 60 s made that blind spot twelve
    // times longer - a newly installed work-profile app would go unblocked for a minute.
    //
    // It buys nothing anyway: AndroidPackageDataSource.loadPackagesInternal clears these caches on
    // every package-list load, and a rule toggle triggers one, so the cache is wiped microseconds
    // before the firewall needs it regardless of the TTL. Fixing that properly needs work-profile
    // aware invalidation, not a longer window.
    private const val INSTALLED_APPS_CACHE_TTL = 5_000L

    // Which packages request a network permission. Shares the installed-apps TTL and invalidation,
    // because the answer changes only when a package is installed or removed.
    @Volatile
    private var networkPackagesCache: List<ApplicationInfo>? = null
    @Volatile
    private var networkPackagesCacheTime: Long = 0
    private val networkPackagesLock = Any()
    
    data class UserProfile(
        val userId: Int,
        val name: String?,
        val isWorkProfile: Boolean,
        val isCloneProfile: Boolean
    ) {
        val displayName: String
            get() = when {
                userId == 0 -> "Personal"
                isWorkProfile -> "Work"
                isCloneProfile -> "Clone"
                else -> name ?: "User $userId"
            }
    }
    
    fun initialize() {
        if (initialized) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("L")
                hiddenApiAvailable = true
                AppLogger.i(TAG, "✅ HiddenApiBypass initialized successfully")
            } else {
                // Hidden API restrictions don't exist before Android P
                hiddenApiAvailable = true
                AppLogger.i(TAG, "✅ Pre-Android P, no hidden API bypass needed")
            }
        } catch (e: Exception) {
            hiddenApiAvailable = false
            AppLogger.e(TAG, "❌ Failed to initialize HiddenApiBypass: ${e.message}")
        }
        
        initialized = true
    }
    
    fun getUsers(context: Context): List<UserProfile> {
        if (!initialized) initialize()

        cachedUsers?.let { cached ->
            if (System.currentTimeMillis() - usersCacheTime < USERS_CACHE_TTL) {
                AppLogger.d(TAG, "🔍 MULTI-USER: Returning cached ${cached.size} user profiles")
                return cached
            }
        }

        AppLogger.i(TAG, "🔍 MULTI-USER: Starting user profile detection...")

        try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            val profiles = userManager.userProfiles
            AppLogger.d(TAG, "🔍 MULTI-USER: UserManager.getUserProfiles() returned ${profiles.size} handles")

            if (profiles.isNotEmpty()) {
                val userProfiles = profiles.mapNotNull { userHandle ->
                    try {
                        val getIdentifierMethod = userHandle.javaClass.getMethod("getIdentifier")
                        val userId = getIdentifierMethod.invoke(userHandle) as Int

                        val isWorkProfile = userId > 0 && userManager.isManagedProfile(userId)

                        // Clone profiles only exist on Android 12+ (API 31)
                        // IMPORTANT: getUserProfiles() doesn't provide flags, so we can't 
                        // reliably detect clone profiles here. Strategy 2 (getUsers with flags)
                        // should be used for accurate clone detection. We conservatively
                        // set this to false to avoid misclassifying secondary users or
                        // Shelter/Island profiles as clones.
                        val isCloneProfile = false

                        val name = when {
                            userId == 0 -> "Personal"
                            isWorkProfile -> "Work"
                            else -> "User $userId"
                        }

                        val profile = UserProfile(userId, name, isWorkProfile, isCloneProfile)
                        AppLogger.d(TAG, "🔍 MULTI-USER: Detected profile: userId=$userId, name=$name, isWork=$isWorkProfile, isClone=$isCloneProfile")
                        profile
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to parse UserHandle: ${e.message}")
                        null
                    }
                }

                if (userProfiles.isNotEmpty()) {
                    AppLogger.i(TAG, "✅ MULTI-USER: Found ${userProfiles.size} user profiles via getUserProfiles(): ${userProfiles.map { "${it.userId}:${it.displayName}(work=${it.isWorkProfile},clone=${it.isCloneProfile})" }}")
                    return cacheAndReturn(userProfiles)
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "getUserProfiles() failed: ${e.message}")
        }

        if (hiddenApiAvailable) {
            try {
                val userManager = context.getSystemService(Context.USER_SERVICE)
                val getUsersMethod = userManager!!.javaClass.getMethod("getUsers")

                @Suppress("UNCHECKED_CAST")
                val userInfoList = getUsersMethod.invoke(userManager) as? List<*>

                if (!userInfoList.isNullOrEmpty()) {
                    val profiles = userInfoList.mapNotNull { userInfo ->
                        try {
                            val idField = userInfo!!.javaClass.getField("id")
                            val nameField = userInfo.javaClass.getField("name")
                            val flagsField = userInfo.javaClass.getField("flags")

                            val id = idField.getInt(userInfo)
                            val name = nameField.get(userInfo) as? String
                            val flags = flagsField.getInt(userInfo)

                            // FLAG_MANAGED_PROFILE = 0x20 (work profile, available since Android 5.0)
                            // FLAG_CLONE_PROFILE = 0x40000000 (clone profile, Android 12+ / API 31+)
                            // Note: Before Android 12, clone profiles don't exist, so the flag will never be set
                            val isWorkProfile = (flags and 0x20) != 0
                            val isCloneProfile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                                 (flags and 0x40000000) != 0

                            UserProfile(id, name, isWorkProfile, isCloneProfile)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to parse UserInfo: ${e.message}")
                            null
                        }
                    }

                    if (profiles.isNotEmpty()) {
                        AppLogger.i(TAG, "✅ Found ${profiles.size} user profiles via getUsers(): ${profiles.map { "${it.userId}:${it.displayName}" }}")
                        return cacheAndReturn(profiles)
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getUsers() failed: ${e.message}")
            }
        }

        AppLogger.d(TAG, "All user enumeration methods failed, returning only user 0")
        return cacheAndReturn(listOf(UserProfile(0, "Personal", isWorkProfile = false, isCloneProfile = false)))
    }

    private fun cacheAndReturn(users: List<UserProfile>): List<UserProfile> {
        cachedUsers = users
        usersCacheTime = System.currentTimeMillis()
        return users
    }

    fun clearUserCache() {
        cachedUsers = null
        usersCacheTime = 0
        AppLogger.d(TAG, "User profile cache cleared")
    }

    private fun android.os.UserManager.isManagedProfile(userId: Int): Boolean {
        return try {
            val method = this.javaClass.getMethod("isManagedProfile", Int::class.javaPrimitiveType)
            method.invoke(this, userId) as? Boolean ?: false
        } catch (e: Exception) {
            try {
                val myUserHandle = android.os.Process.myUserHandle()
                val getIdentifierMethod = myUserHandle.javaClass.getMethod("getIdentifier")
                val myUserId = getIdentifierMethod.invoke(myUserHandle) as Int
                
                if (myUserId == userId) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        this.isManagedProfile
                    } else {
                        // Before API 30, we can't reliably determine this
                        // Return false to avoid false positives
                        false
                    }
                } else {
                    // For other users, we can't determine without hidden API access
                    // Return false to avoid misclassifying users as work profiles
                    false
                }
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    fun getInstalledApplicationsAsUser(
        context: Context,
        flags: Int,
        userId: Int
    ): List<ApplicationInfo> {
        if (!initialized) initialize()

        if (userId == 0) {
            return context.packageManager.getInstalledApplications(flags)
        }

        val now = System.currentTimeMillis()
        if (now - installedAppsCacheTime < INSTALLED_APPS_CACHE_TTL) {
            installedAppsCache[userId]?.let { cached ->
                AppLogger.d(TAG, "📦 Returning cached ${cached.size} apps for user $userId")
                return cached
            }
        }

        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getInstalledApplicationsAsUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                @Suppress("UNCHECKED_CAST")
                val apps = method.invoke(pm, flags, userId) as? List<ApplicationInfo>
                if (!apps.isNullOrEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via hidden API")
                    cacheInstalledApps(userId, apps)
                    return apps
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Hidden API getInstalledApplicationsAsUser failed for user $userId: ${e.message}")
            }
        }

        // Strategy 2: Use root shell to get package list
        // Create synthetic ApplicationInfo objects based on personal profile info
        // This is MUCH faster than calling pm dump for each package
        try {
            val packageNames = getPackageListViaShell(userId)
            if (packageNames.isNotEmpty()) {
                val apps = packageNames.mapNotNull { packageName ->
                    createSyntheticApplicationInfo(context, packageName, userId)
                }
                if (apps.isNotEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via root shell (synthetic)")
                    cacheInstalledApps(userId, apps)
                    return apps
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Root shell method failed for user $userId: ${e.message}")
        }

        // Strategy 3: Use Shizuku shell if available (Issue #68 - work apps not showing with Shizuku)
        // This fallback enables work profile support when user has Shizuku but not root
        try {
            val packageNames = getPackageListViaShizuku(userId)
            if (packageNames.isNotEmpty()) {
                val apps = packageNames.mapNotNull { packageName ->
                    createSyntheticApplicationInfo(context, packageName, userId)
                }
                if (apps.isNotEmpty()) {
                    AppLogger.d(TAG, "✅ Found ${apps.size} apps for user $userId via Shizuku shell (synthetic)")
                    cacheInstalledApps(userId, apps)
                    return apps
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku shell method failed for user $userId: ${e.message}")
        }

        AppLogger.w(TAG, "⚠️ Could not get apps for user $userId - all methods failed")
        return emptyList()
    }

    private fun cacheInstalledApps(userId: Int, apps: List<ApplicationInfo>) {
        installedAppsCache[userId] = apps
        installedAppsCacheTime = System.currentTimeMillis()
    }

    fun clearInstalledAppsCache() {
        synchronized(this) { packageUidsCache.clear() }
        installedAppsCache.clear()
        installedAppsCacheTime = 0
        networkPackagesCache = null
        networkPackagesCacheTime = 0
        AppLogger.d(TAG, "Cleared installed apps cache")
    }

    /**
     * Every installed app, across every user profile, that requests a network permission.
     *
     * All four firewall backends ran this identical filter inline, and it is the dominant cost of
     * applying rules: one `getPackageInfoAsUser` binder call per package, with no caching. Measured
     * on a two-profile device with 466 packages at roughly **8 seconds per rule application** - far
     * more than the policy writes it feeds.
     *
     * The result changes only when a package is installed or removed, so it shares
     * [INSTALLED_APPS_CACHE_TTL] and is dropped by [clearInstalledAppsCache].
     */
    fun getPackagesWithNetworkPermissions(context: Context): List<ApplicationInfo> {
        val entryTime = System.currentTimeMillis()
        networkPackagesCache?.let { cached ->
            if (entryTime - networkPackagesCacheTime < INSTALLED_APPS_CACHE_TTL) {
                AppLogger.d(TAG, "📦 Returning cached ${cached.size} packages with network permissions")
                return cached
            }
        }

        // One computation at a time. This takes seconds - measured at 9,499 ms for 466 packages
        // across two profiles - and the firewall runs two backend instances that both ask for it at
        // startup. Without this they both paid the full cost. A caller that waited here takes
        // whatever the winner produced, regardless of the TTL: a result computed *after* we started
        // waiting is by definition fresher than we are.
        synchronized(networkPackagesLock) {
            networkPackagesCache?.let { cached ->
                if (networkPackagesCacheTime >= entryTime) {
                    AppLogger.d(TAG, "📦 Reusing ${cached.size} packages computed while waiting")
                    return cached
                }
            }

        val startTime = System.currentTimeMillis()
        val packages = getUsers(context).flatMap { profile ->
            getInstalledApplicationsAsUser(context, PackageManager.GET_META_DATA, profile.userId)
                .map { appInfo -> appInfo to profile.userId }
        }.filter { (appInfo, userId) ->
            try {
                val packageInfo = getPackageInfoAsUser(
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

            networkPackagesCache = packages
            networkPackagesCacheTime = System.currentTimeMillis()
            AppLogger.d(TAG, "📦 Found ${packages.size} packages with network permissions " +
                    "in ${System.currentTimeMillis() - startTime}ms")
            return packages
        }
    }

    private fun createSyntheticApplicationInfo(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            // Query the enabled state for this specific user (not from personal profile!)
            val isEnabled = isPackageEnabledForUser(packageName, userId)

            val personalInfo = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            if (personalInfo != null) {
                ApplicationInfo(personalInfo).apply {
                    val appId = personalInfo.uid % 100000
                    this.uid = userId * 100000 + appId
                    this.enabled = isEnabled
                }
            } else {
                // App exists only in this profile, so there is no personal-profile uid to derive
                // from. Ask the shell for the real one; it answers for the whole user in a single
                // call and the result is cached.
                //
                // The fallback is deliberately NOT a plausible app uid. This used to be
                // `userId * 100000 + 10000 + packageName.hashCode().and(0xFFFF)`, which looks like a
                // real uid, lands anywhere in 10000..75535, and was handed to the firewall backends
                // as if it were one - so a rule could be written against a uid belonging to some
                // other app, or to nothing at all. UID_UNKNOWN cannot be mistaken for an app: it
                // fails Constants.Firewall.isFirewallableAppUid, so no privileged backend acts on
                // it, and iptables rejects it rather than matching something real.
                val realUid = getPackageUidsForUser(context, userId)[packageName]
                if (realUid == null) {
                    AppLogger.w(TAG, "No uid available for $packageName in user $userId - " +
                            "marking it UID_UNKNOWN; the firewall will not act on it")
                }
                ApplicationInfo().apply {
                    this.packageName = packageName
                    this.uid = realUid ?: UID_UNKNOWN
                    this.flags = 0
                    this.enabled = isEnabled
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed to create synthetic ApplicationInfo for $packageName: ${e.message}")
            null
        }
    }

    /**
     * A uid that is not, and cannot be mistaken for, an installed app.
     *
     * Used when the real uid cannot be determined. It fails
     * [io.github.dorumrr.de1984.utils.Constants.Firewall.isFirewallableAppUid], so the privileged
     * backends skip it instead of writing a rule against a number that belongs to someone else.
     */
    const val UID_UNKNOWN = -1

    private val packageUidsCache = mutableMapOf<Int, Map<String, Int>>()

    /**
     * packageName -> uid for one user, from `pm list packages -U`. One shell call per user, cached
     * until [clearInstalledAppsCache]. Empty when no privileged shell is available.
     */
    @Synchronized
    private fun getPackageUidsForUser(context: Context, userId: Int): Map<String, Int> {
        packageUidsCache[userId]?.let { return it }

        val lines = getPackageUidLinesViaShell(userId)
        val map = lines.mapNotNull { line ->
            val body = line.removePrefix("package:").trim()
            val uid = body.substringAfterLast("uid:", "").trim().toIntOrNull() ?: return@mapNotNull null
            val name = body.substringBefore(" uid:").trim()
            if (name.isEmpty()) null else name to uid
        }.toMap()

        if (map.isNotEmpty()) {
            packageUidsCache[userId] = map
        }
        return map
    }

    private fun getPackageUidLinesViaShell(userId: Int): List<String> {
        val command = "pm list packages -U --user $userId"

        try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell != null && cachedShell.isRoot) {
                val out = mutableListOf<String>()
                val result = cachedShell.newJob().add(command).to(out).exec()
                if (result.isSuccess) return out.filter { it.startsWith("package:") }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Root shell uid query failed for user $userId: ${e.message}")
        }

        // Same runBlocking shape as getPackageListViaShizuku above, which already reaches this file's
        // callers, so this adds no new class of risk - and the result is cached per user, so it runs
        // once. Without it a Shizuku-only user gets UID_UNKNOWN for every work-only app and the
        // firewall leaves them alone entirely.
        val manager = shizukuManager
        if (manager != null && manager.hasShizukuPermission) {
            return try {
                val (exitCode, output) = runBlocking { manager.executeShellCommand(command) }
                if (exitCode == 0) output.lines().filter { it.startsWith("package:") } else emptyList()
            } catch (e: Exception) {
                AppLogger.d(TAG, "Shizuku uid query failed for user $userId: ${e.message}")
                emptyList()
            }
        }

        return emptyList()
    }

    private fun getPackageListViaShell(userId: Int): List<String> {
        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                AppLogger.d(TAG, "No cached root shell available for user $userId")
                return emptyList()
            }

            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add("pm list packages --user $userId")
                .to(outputList)
                .exec()

            if (!result.isSuccess) {
                AppLogger.d(TAG, "Root shell pm list packages failed for user $userId: exit code ${result.code}")
                return emptyList()
            }

            outputList
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Root shell pm list packages failed: ${e.message}")
            emptyList()
        }
    }

    private fun getPackageListViaShizuku(userId: Int): List<String> {
        val manager = shizukuManager
        if (manager == null) {
            AppLogger.d(TAG, "ShizukuManager not set - cannot use Shizuku shell for user $userId")
            return emptyList()
        }

        if (!manager.hasShizukuPermission) {
            AppLogger.d(TAG, "No Shizuku permission - cannot use Shizuku shell for user $userId")
            return emptyList()
        }

        return try {
            // Use runBlocking since HiddenApiHelper methods are synchronous
            // and Shizuku executeShellCommand is suspend
            val (exitCode, output) = runBlocking {
                manager.executeShellCommand("pm list packages --user $userId")
            }

            if (exitCode != 0) {
                AppLogger.d(TAG, "Shizuku shell pm list packages failed for user $userId: exit code $exitCode")
                return emptyList()
            }

            output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku shell pm list packages failed: ${e.message}")
            emptyList()
        }
    }

    private val disabledPackagesCache = mutableMapOf<Int, Set<String>>()

    private fun getDisabledPackagesForUser(userId: Int): Set<String> {
        disabledPackagesCache[userId]?.let { return it }

        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                // Fall back to Shizuku instead of giving up. Root was the ONLY route here, so on a
                // Shizuku-only device this returned an empty set - indistinguishable from "nothing is
                // disabled". Every work-profile app then showed as Enabled, and the detail sheet
                // offered "Disable" for apps that were already disabled.
                //
                // The same shell already lists packages for other profiles a few lines above, so the
                // capability was there and simply unused for this one query.
                AppLogger.d(TAG, "No cached root shell for disabled packages (user $userId) - trying Shizuku")
                val viaShizuku = getDisabledPackagesViaShizuku(userId)
                if (viaShizuku != null) {
                    disabledPackagesCache[userId] = viaShizuku
                    AppLogger.d(TAG, "Found ${viaShizuku.size} disabled packages for user $userId via Shizuku")
                    return viaShizuku
                }
                // CACHED, even though it is a failure. This function is called once per PACKAGE
                // (createSyntheticApplicationInfo, ~466 of them here), and without caching the
                // negative every one of them would retry the Shizuku shell - each with its own 5s
                // timeout. The old code returned early here with no work at all, so leaving this
                // uncached turned a free path into a very expensive one.
                AppLogger.d(TAG, "Could not determine disabled packages for user $userId - assuming none")
                disabledPackagesCache[userId] = emptySet()
                return emptySet()
            }

            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add("pm list packages -d --user $userId")
                .to(outputList)
                .exec()

            if (!result.isSuccess) {
                // Cached for the same reason as above - one shell call per enumeration, not one per
                // package. clearDisabledPackagesCache() is the way back when state changes.
                AppLogger.d(TAG, "Shell pm list packages -d failed for user $userId: exit code ${result.code}")
                disabledPackagesCache[userId] = emptySet()
                return emptySet()
            }

            val disabledSet = outputList
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            disabledPackagesCache[userId] = disabledSet
            AppLogger.d(TAG, "Found ${disabledSet.size} disabled packages for user $userId")
            disabledSet
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm list packages -d failed: ${e.message}")
            disabledPackagesCache[userId] = emptySet()
            emptySet()
        }
    }

    private fun getDisabledPackagesViaShizuku(userId: Int): Set<String>? {
        val manager = shizukuManager ?: return null
        if (!manager.hasShizukuPermission) return null

        return try {
            val (exitCode, output) = runBlocking {
                manager.executeShellCommand("pm list packages -d --user $userId")
            }
            if (exitCode != 0) {
                AppLogger.d(TAG, "Shizuku pm list packages -d failed for user $userId: exit $exitCode")
                return null
            }
            output.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shizuku pm list packages -d threw for user $userId: ${e.message}")
            null
        }
    }

    private fun isPackageEnabledForUser(packageName: String, userId: Int): Boolean {
        val disabledPackages = getDisabledPackagesForUser(userId)
        return !disabledPackages.contains(packageName)
    }

    fun clearDisabledPackagesCache() {
        disabledPackagesCache.clear()
        AppLogger.d(TAG, "Cleared disabled packages cache")
    }

    fun getApplicationInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): ApplicationInfo? {
        if (!initialized) initialize()

        if (userId == 0) {
            return try {
                context.packageManager.getApplicationInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getApplicationInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                val result = method.invoke(pm, packageName, flags, userId) as? ApplicationInfo
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
            }
        }

        return getApplicationInfoViaShell(context, packageName, userId)
    }

    private fun getApplicationInfoViaShell(
        context: Context,
        packageName: String,
        userId: Int
    ): ApplicationInfo? {
        return try {
            val cachedShell = Shell.getCachedShell()
            if (cachedShell == null || !cachedShell.isRoot) {
                return null
            }

            val outputList = mutableListOf<String>()
            val result = cachedShell.newJob()
                .add("pm dump $packageName --user $userId")
                .to(outputList)
                .exec()

            val output = outputList.joinToString("\n")

            if (!result.isSuccess || output.contains("Unable to find package") || output.isBlank()) {
                return null
            }

            val uidMatch = Regex("""userId=(\d+)""").find(output)
            val codePath = Regex("""codePath=([^\s]+)""").find(output)?.groupValues?.get(1)
            val flagsMatch = Regex("""pkgFlags=\[\s*([^\]]*)\s*\]""").find(output)

            val appId = uidMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val absoluteUid = userId * 100000 + appId

            val isSystem = flagsMatch?.groupValues?.get(1)?.contains("SYSTEM") == true

            val isEnabled = isPackageEnabledForUser(packageName, userId)

            ApplicationInfo().apply {
                this.packageName = packageName
                this.uid = absoluteUid
                this.sourceDir = codePath ?: "/data/app/$packageName"
                this.flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
                this.enabled = isEnabled

                try {
                    val personalInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    this.labelRes = personalInfo.labelRes
                    this.nonLocalizedLabel = personalInfo.nonLocalizedLabel
                    this.icon = personalInfo.icon
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Shell pm dump failed for $packageName user $userId: ${e.message}")
            null
        }
    }

    fun getPackageInfoAsUser(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        if (!initialized) initialize()

        if (userId == 0) {
            return try {
                context.packageManager.getPackageInfo(packageName, flags)
            } catch (e: PackageManager.NameNotFoundException) {
                AppLogger.d(TAG, "Package $packageName not found for user 0")
                null
            }
        }

        if (hiddenApiAvailable) {
            try {
                val pm = context.packageManager
                val method = pm.javaClass.getMethod(
                    "getPackageInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

                val result = method.invoke(pm, packageName, flags, userId) as? PackageInfo
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
            }
        }

        // Strategy 2: Create synthetic PackageInfo based on personal profile data
        // Work profile apps are typically clones of personal profile apps with same permissions
        return createSyntheticPackageInfo(context, packageName, flags, userId)
    }

    private fun createSyntheticPackageInfo(
        context: Context,
        packageName: String,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        return try {
            val personalInfo = context.packageManager.getPackageInfo(packageName, flags)

            PackageInfo().apply {
                this.packageName = personalInfo.packageName
                this.versionName = personalInfo.versionName
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    this.longVersionCode = personalInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    this.versionCode = personalInfo.versionCode
                }
                this.firstInstallTime = personalInfo.firstInstallTime
                this.lastUpdateTime = personalInfo.lastUpdateTime
                this.requestedPermissions = personalInfo.requestedPermissions
                this.requestedPermissionsFlags = personalInfo.requestedPermissionsFlags
                this.services = personalInfo.services
                this.activities = personalInfo.activities
                this.receivers = personalInfo.receivers
                this.providers = personalInfo.providers
                this.permissions = personalInfo.permissions

                this.applicationInfo = personalInfo.applicationInfo?.let { appInfo ->
                    ApplicationInfo(appInfo).apply {
                        val appId = appInfo.uid % 100000
                        this.uid = userId * 100000 + appId
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}

