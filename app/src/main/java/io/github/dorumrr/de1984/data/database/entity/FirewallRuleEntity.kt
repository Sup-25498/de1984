package io.github.dorumrr.de1984.data.database.entity

import androidx.room.Entity

@Entity(
    tableName = "firewall_rules",
    primaryKeys = ["packageName", "userId"]
)
data class FirewallRuleEntity(
    val packageName: String,

    val userId: Int = 0,

    val uid: Int,
    val appName: String,

    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,

    val blockWhenBackground: Boolean = false,
    val blockWhenRoaming: Boolean = false,
    val lanBlocked: Boolean = false,

    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
    val hasInternetPermission: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

