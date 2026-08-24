package io.github.dorumrr.de1984.data.datasource

import io.github.dorumrr.de1984.data.model.PackageEntity
import kotlinx.coroutines.flow.Flow

interface PackageDataSource {

    fun getPackages(): Flow<List<PackageEntity>>
    suspend fun getPackage(packageName: String, userId: Int): PackageEntity?
    suspend fun getUninstalledSystemPackages(): List<PackageEntity>


    suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Boolean
    suspend fun uninstallPackage(packageName: String, userId: Int): Boolean
    suspend fun reinstallPackage(packageName: String, userId: Int): Boolean
    suspend fun forceStopPackage(packageName: String, userId: Int): Boolean


    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Boolean
    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Boolean
}

