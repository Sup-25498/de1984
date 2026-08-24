package io.github.dorumrr.de1984.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.dorumrr.de1984.data.database.entity.FirewallRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallRuleDao {


    @Query("SELECT * FROM firewall_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM firewall_rules ORDER BY appName ASC")
    suspend fun getAllRulesSync(): List<FirewallRuleEntity>

    @Query("SELECT * FROM firewall_rules WHERE (wifiBlocked = 1 OR mobileBlocked = 1) AND enabled = 1 ORDER BY appName ASC")
    fun getBlockedRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM firewall_rules WHERE wifiBlocked = 0 AND mobileBlocked = 0 AND enabled = 1 ORDER BY appName ASC")
    fun getAllowedRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM firewall_rules WHERE isSystemApp = 0 ORDER BY appName ASC")
    fun getUserAppRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM firewall_rules WHERE isSystemApp = 1 ORDER BY appName ASC")
    fun getSystemAppRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT COUNT(*) FROM firewall_rules WHERE (wifiBlocked = 1 OR mobileBlocked = 1) AND enabled = 1")
    fun getBlockedCount(): Flow<Int>


    @Query("SELECT * FROM firewall_rules WHERE userId = :userId ORDER BY appName ASC")
    fun getRulesByUserId(userId: Int): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM firewall_rules WHERE userId = :userId ORDER BY appName ASC")
    suspend fun getRulesByUserIdSync(userId: Int): List<FirewallRuleEntity>


    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName AND userId = :userId")
    fun getRuleByPackage(packageName: String, userId: Int): Flow<FirewallRuleEntity?>

    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName AND userId = :userId")
    suspend fun getRuleByPackageSync(packageName: String, userId: Int): FirewallRuleEntity?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FirewallRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<FirewallRuleEntity>)

    @Update
    suspend fun updateRule(rule: FirewallRuleEntity)

    @Query("DELETE FROM firewall_rules WHERE packageName = :packageName AND userId = :userId")
    suspend fun deleteRule(packageName: String, userId: Int)

    @Query("DELETE FROM firewall_rules WHERE userId = :userId")
    suspend fun deleteRulesByUserId(userId: Int)

    @Query("DELETE FROM firewall_rules")
    suspend fun deleteAllRules()


    // "All" means WiFi + Mobile + Roaming + LAN. Screen-off (blockWhenBackground) is deliberately
    // excluded: it is a condition, not a network, and an app blocked on every network is already
    // blocked while the screen is off. Leaving roaming out here left apps blocked while roaming with
    // the roaming toggle reading ON, and leaving LAN out disagreed with the default-policy path.
    @Query("UPDATE firewall_rules SET wifiBlocked = 1, mobileBlocked = 1, blockWhenRoaming = 1, lanBlocked = 1, updatedAt = :timestamp WHERE enabled = 1 AND packageName NOT IN (:excludedPackages)")
    suspend fun blockAllApps(excludedPackages: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET wifiBlocked = 0, mobileBlocked = 0, blockWhenRoaming = 0, lanBlocked = 0, updatedAt = :timestamp WHERE enabled = 1 AND packageName NOT IN (:excludedPackages)")
    suspend fun allowAllApps(excludedPackages: List<String>, timestamp: Long = System.currentTimeMillis())


    @Query("UPDATE firewall_rules SET wifiBlocked = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateWifiBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET mobileBlocked = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateMobileBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET blockWhenRoaming = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateRoamingBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET blockWhenBackground = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE firewall_rules SET lanBlocked = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateLanBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    // Atomic batch update for the three internet transports - prevents race conditions when
    // toggling them at once. Deliberately NOT LAN: this backs the "Internet Access" toggle, which
    // is labelled "WiFi, Mobile, Roaming" and sits beside a separate LAN control. "Block All" in the
    // policy sense is wider and goes through blockAllApps / FirewallRule.blockAll() instead.
    @Query("UPDATE firewall_rules SET wifiBlocked = :blocked, mobileBlocked = :blocked, blockWhenRoaming = :blocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean, timestamp: Long = System.currentTimeMillis())

    // Atomic batch update for mobile+roaming dependency - prevents race conditions when enforcing business rule
    @Query("UPDATE firewall_rules SET mobileBlocked = :mobileBlocked, blockWhenRoaming = :roamingBlocked, updatedAt = :timestamp WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean, timestamp: Long = System.currentTimeMillis())
}

