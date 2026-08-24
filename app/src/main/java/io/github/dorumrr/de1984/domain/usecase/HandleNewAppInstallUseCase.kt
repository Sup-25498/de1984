package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.first

class HandleNewAppInstallUseCase constructor(
    private val context: Context,
    private val firewallRepository: FirewallRepository,
    private val errorHandler: ErrorHandler
) {
    
    companion object {
        private const val TAG = "HandleNewAppInstallUseCase"
    }
    
    /**
     * Handle a new app installation by creating a default firewall rule.
     *
     * @param packageName The package name of the newly installed app
     * @param uid Optional UID of the app. If provided, userId will be derived from it.
     *            If not provided, the app's UID will be looked up from PackageManager.
     */
    suspend fun execute(packageName: String, uid: Int? = null): Result<Unit> {
        return try {
            // Derive userId from UID first: userId = uid / 100000
            val userId = uid?.let { it / 100000 } ?: 0

            val packageInfo = validatePackage(packageName, userId)
                ?: return Result.failure(Exception("Package not found or invalid: $packageName"))

            // Check if app was installed before De1984 to prevent notification spam
            // after clearing app data. Apps that existed before De1984 should not
            // trigger "new app" notifications.
            val de1984InstallTime = try {
                context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
            } catch (e: Exception) {
                0L  // Fallback: treat as new app if we can't get our install time
            }
            
            val isPreExistingApp = packageInfo.firstInstallTime < de1984InstallTime
            
            if (isPreExistingApp) {
                AppLogger.d(TAG, "Pre-existing app (installed before De1984): $packageName")
                // Still create rule for pre-existing apps, but return failure to skip notification
                if (!hasNetworkPermissions(packageName, userId)) {
                    return Result.failure(Exception("Pre-existing app without network permissions"))
                }
                
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()
                if (existingRule == null) {
                    val defaultRule = createDefaultFirewallRule(packageName, packageInfo, userId)
                    if (defaultRule != null) {
                        firewallRepository.insertRule(defaultRule)
                        AppLogger.d(TAG, "Created rule for pre-existing app: $packageName")
                    }
                } else {
                    refreshRuleIdentity(existingRule, packageInfo)
                }
                return Result.failure(Exception("Pre-existing app - notification skipped"))
            }

            if (!hasNetworkPermissions(packageName, userId)) {
                return Result.success(Unit)
            }

            val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()
            if (existingRule != null) {
                // The old rule is kept on purpose - the user configured it - but its UID must be
                // re-read. Android hands a reinstalled app a NEW uid, and the privileged backends
                // block by uid: a stale one matches nothing, so the app showed "Blocked" in the UI
                // while its traffic flowed. The label is refreshed for the same reason.
                refreshRuleIdentity(existingRule, packageInfo)
                return Result.success(Unit)
            }

            val defaultRule = createDefaultFirewallRule(packageName, packageInfo, userId)
                ?: return Result.failure(Exception("Failed to create firewall rule: applicationInfo is null"))
            firewallRepository.insertRule(defaultRule)

            Result.success(Unit)

        } catch (e: Exception) {
            val error = errorHandler.handleError(e, "new app install handling")
            Result.failure(error)
        }
    }

    private fun validatePackage(packageName: String, userId: Int = 0): android.content.pm.PackageInfo? {
        return try {
            if (packageName.isBlank() || !packageName.contains(".")) {
                return null
            }

            if (Constants.App.isOwnApp(packageName)) {
                return null
            }

            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context, packageName, PackageManager.GET_PERMISSIONS, userId
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun hasNetworkPermissions(packageName: String, userId: Int = 0): Boolean {
        return try {
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context, packageName, PackageManager.GET_PERMISSIONS, userId
            ) ?: return false
            val permissions = packageInfo.requestedPermissions ?: return false

            val networkPermissions = setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE"
            )

            permissions.any { it in networkPermissions }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Re-point an existing rule at the app as it exists now.
     *
     * A reinstall keeps the package name and changes the uid. Rules are keyed on
     * (packageName, userId), so the row survives - carrying a uid that no longer belongs to anyone.
     * Writes only when something actually changed, to avoid waking every rule observer on boot.
     */
    private suspend fun refreshRuleIdentity(
        existingRule: FirewallRule,
        packageInfo: android.content.pm.PackageInfo
    ) {
        val appInfo = packageInfo.applicationInfo ?: return
        val currentUid = appInfo.uid
        val currentName = try {
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            existingRule.appName
        }

        if (existingRule.uid == currentUid && existingRule.appName == currentName) {
            return
        }

        AppLogger.d(
            TAG,
            "Refreshing rule identity for ${existingRule.packageName}: " +
                "uid ${existingRule.uid} -> $currentUid, name '${existingRule.appName}' -> '$currentName'"
        )
        firewallRepository.updateRule(
            existingRule.copy(
                uid = currentUid,
                appName = currentName,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun createDefaultFirewallRule(packageName: String, packageInfo: android.content.pm.PackageInfo, userId: Int): FirewallRule? {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val defaultPolicy = prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        )
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        val appInfo = packageInfo.applicationInfo ?: return null
        val appName = try {
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        val uid = appInfo.uid
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

        // Check if this is a critical package (SYSTEM_WHITELIST or VPN app)
        val isSystemCritical = Constants.Firewall.isSystemCritical(packageName)
        val isVpnApp = hasVpnService(packageName, uid / 100000)
        val isCriticalPackage = isSystemCritical || isVpnApp

        // System-recommended apps are ALWAYS allowed, regardless of default policy
        val isRecommendedAllow = Constants.Firewall.isSystemRecommendedAllow(packageName)

        return when {
            // Critical packages (SYSTEM_WHITELIST + VPN apps) - handle based on allowCritical setting
            isCriticalPackage -> {
                if (!allowCritical) {
                    // Setting OFF: Create 'allow all' rule to protect critical package
                    AppLogger.d(TAG, "Creating 'allow all' rule for critical package (protection ON): $packageName (userId=$userId)")
                    FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = uid,
                        appName = appName,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        blockWhenRoaming = false,
                        enabled = true,
                        isSystemApp = isSystemApp
                    )
                } else {
                    // Setting ON: Don't create a rule - critical package will default to ALLOW
                    // User can manually change it if they want (critical packages are immune to bulk operations)
                    AppLogger.d(TAG, "Skipping rule creation for critical package (protection OFF, user can manually configure): $packageName (userId=$userId)")
                    null
                }
            }
            // System-recommended apps always get "allow all" rules
            isRecommendedAllow -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = false,
                    mobileBlocked = false,
                    blockWhenRoaming = false,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            // Block All policy - block everything except VPN apps and system-recommended
            defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = true,
                    mobileBlocked = true,
                    blockWhenRoaming = true,
                    lanBlocked = true,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            // Allow All policy or default - allow everything
            else -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = false,
                    mobileBlocked = false,
                    blockWhenRoaming = false,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
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
