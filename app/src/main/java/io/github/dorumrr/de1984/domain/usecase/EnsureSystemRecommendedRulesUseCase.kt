package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants

class EnsureSystemRecommendedRulesUseCase(
    private val context: Context,
    private val firewallRepository: FirewallRepository
) {
    companion object {
        private const val TAG = "EnsureSystemRecommendedRulesUseCase"
    }

    suspend fun invoke() {
        try {
            AppLogger.d(TAG, "Starting system-recommended apps rule sync")

            val userProfiles = HiddenApiHelper.getUsers(context)
            AppLogger.d(TAG, "Found ${userProfiles.size} user profiles")

            val allRules = firewallRepository.getAllRulesSync()
            val existingRuleKeys = allRules.map { rule -> "${rule.packageName}:${rule.userId}" }.toSet()

            var createdCount = 0
            var skippedCount = 0

            for (profile in userProfiles) {
                val userId = profile.userId

                Constants.Firewall.SYSTEM_RECOMMENDED_ALLOW.forEach { packageName ->
                    val ruleKey = "$packageName:$userId"

                    if (existingRuleKeys.contains(ruleKey)) {
                        skippedCount++
                        AppLogger.d(TAG, "Rule already exists for $packageName (userId=$userId) - skipping")
                        return@forEach
                    }

                    val appInfo = try {
                        HiddenApiHelper.getApplicationInfoAsUser(context, packageName, 0, userId)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to get app info for $packageName (userId=$userId): ${e.message}")
                        skippedCount++
                        return@forEach
                    }

                    // If null, package not installed for this user - skip silently
                    if (appInfo == null) {
                        skippedCount++
                        return@forEach
                    }

                    val uid = appInfo.uid
                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val appName = try {
                        context.packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName
                    }

                    val rule = FirewallRule(
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

                    firewallRepository.insertRule(rule)
                    createdCount++
                    AppLogger.d(TAG, "Created 'allow all' rule for system-recommended app: $packageName (userId=$userId, uid=$uid)")
                }
            }

            AppLogger.i(TAG, "System-recommended apps sync complete: created $createdCount rules, skipped $skippedCount across ${userProfiles.size} profiles")
        } catch (e: Exception) {
            // Don't crash the app if sync fails - this is best-effort
            AppLogger.w(TAG, "Failed to sync system-recommended apps: ${e.message}", e)
        }
    }
}
