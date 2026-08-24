package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.FirewallRule
import kotlinx.coroutines.flow.Flow

interface FirewallRepository {


    fun getAllRules(): Flow<List<FirewallRule>>

    suspend fun getAllRulesSync(): List<FirewallRule>

    fun getBlockedRules(): Flow<List<FirewallRule>>

    fun getAllowedRules(): Flow<List<FirewallRule>>

    fun getUserAppRules(): Flow<List<FirewallRule>>

    fun getSystemAppRules(): Flow<List<FirewallRule>>

    fun getBlockedCount(): Flow<Int>


    fun getRulesByUserId(userId: Int): Flow<List<FirewallRule>>

    suspend fun getRulesByUserIdSync(userId: Int): List<FirewallRule>


    fun getRuleByPackage(packageName: String, userId: Int): Flow<FirewallRule?>

    suspend fun getRuleByPackageSync(packageName: String, userId: Int): FirewallRule?


    suspend fun insertRule(rule: FirewallRule)

    suspend fun insertRules(rules: List<FirewallRule>)

    suspend fun updateRule(rule: FirewallRule)

    suspend fun deleteRule(packageName: String, userId: Int)

    suspend fun deleteRulesByUserId(userId: Int)

    suspend fun deleteAllRules()


    suspend fun blockAllApps()

    suspend fun allowAllApps()


    suspend fun updateWifiBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateMobileBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateRoamingBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateLanBlocking(packageName: String, userId: Int, blocked: Boolean)

    // Atomic batch update for all network types - prevents race conditions when toggling all networks at once
    suspend fun updateAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean)

    // Atomic batch update for mobile+roaming dependency - prevents race conditions when enforcing business rule
    suspend fun updateMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean)
}

