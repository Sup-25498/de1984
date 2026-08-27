package io.github.dorumrr.de1984.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.*


class PackageMonitoringService : Service() {
    
    
    lateinit var handleNewAppInstallUseCase: HandleNewAppInstallUseCase
    
    
    lateinit var newAppNotificationManager: NewAppNotificationManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var lastKnownPackages: Set<Pair<String, Int>> = emptySet()

    /**
     * False until an enumeration has actually succeeded once.
     *
     * Without it, an empty [lastKnownPackages] is ambiguous: it means either "this device has no
     * user apps" or "we have never managed to look". Treating the second as the first makes every
     * installed app look newly installed on the first successful pass.
     */
    private var hasBaseline = false

    /**
     * userId -> the packages disabled in that profile, as of the last successful read.
     *
     * Separate from [lastKnownPackages] because the two answer different questions. That set is
     * (packageName, userId) pairs, and disabling an app does not remove it from the device - so
     * membership never changes and an enable or disable is completely invisible to it. That is the
     * whole of issue #61a: ACTION_PACKAGE_CHANGED reaches only user 0, so a work-profile app turned
     * off by another app stayed "Enabled" in De1984 until something unrelated forced a refresh.
     *
     * A profile is absent from this map until it has been read successfully once, so a first read
     * never counts as a change.
     */
    private var lastKnownDisabled: MutableMap<Int, Set<String>> = mutableMapOf()
    
    companion object {
        private const val TAG = "PackageMonitoringService"
        const val ACTION_START_MONITORING = "io.github.dorumrr.de1984.action.START_PACKAGE_MONITORING"
        const val ACTION_STOP_MONITORING = "io.github.dorumrr.de1984.action.STOP_PACKAGE_MONITORING"
        
        fun startMonitoring(context: Context) {
            val intent = Intent(context, PackageMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            context.startService(intent)
        }
        
        fun stopMonitoring(context: Context) {
            val intent = Intent(context, PackageMonitoringService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()

        val app = application as De1984Application
        val deps = app.dependencies
        handleNewAppInstallUseCase = deps.provideHandleNewAppInstallUseCase()
        newAppNotificationManager = deps.newAppNotificationManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun startMonitoring() {
        if (monitoringJob?.isActive == true) {
            return
        }
        
        getCurrentInstalledPackages()?.let {
            lastKnownPackages = it
            hasBaseline = true
        }
        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(15_000)
                    checkForNewPackages()
                } catch (e: Exception) {
                    delay(60_000)
                }
            }
        }
    }
    
    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }
    
    private suspend fun checkForNewPackages() {
        // The notification preference used to return here. This service is the ONLY code that sees
        // installs in other user profiles - a manifest PACKAGE_ADDED receiver in user 0 never does -
        // so with notifications off a work-profile app got no rule at all, and a reinstalled one
        // kept a uid that matches nothing. Only the notification is optional; the rule is not.
        // null means the enumeration itself failed, which is NOT the same as "no packages". It used
        // to return an empty set on any exception, and the baseline was then overwritten with it -
        // so one Shizuku or binder hiccup made every installed app look new on the very next tick,
        // firing a rule write and a "new app" notification for each of them. Keep the old baseline
        // and try again in 15 seconds.
        val currentPackages = getCurrentInstalledPackages()
        if (currentPackages == null) {
            AppLogger.w(TAG, "Package enumeration failed - keeping the previous baseline")
            return
        }

        if (!hasBaseline) {
            // First enumeration that worked. Everything on the device right now is the starting
            // point, not a burst of installs - the startup enumeration must have failed.
            AppLogger.d(TAG, "Baseline established from the first successful enumeration")
            lastKnownPackages = currentPackages
            hasBaseline = true
            return
        }

        val newPackages = currentPackages - lastKnownPackages

        if (newPackages.isNotEmpty()) {
            AppLogger.d(TAG, "📦 Detected ${newPackages.size} new packages")
            newPackages.forEach { (packageName, userId) ->
                processNewPackage(packageName, userId)
            }
        }

        checkForEnabledStateChanges()

        // Updated unconditionally. Inside the branch above, an uninstall left the package in the
        // baseline, so it was never "new" again and a reinstall was never processed - the exact
        // case the stale-uid refresh exists for.
        lastKnownPackages = currentPackages
    }

    /**
     * Get all installed packages across all user profiles.
     *
     * @return the (packageName, userId) pairs, or null when the enumeration failed. Null and empty
     * mean different things to the caller, so they must not be collapsed into one value.
     */
    private fun getCurrentInstalledPackages(): Set<Pair<String, Int>>? {
        return try {
            val result = mutableSetOf<Pair<String, Int>>()
            val userProfiles = HiddenApiHelper.getUsers(this)

            for (profile in userProfiles) {
                val packages = HiddenApiHelper.getInstalledApplicationsAsUser(
                    this,
                    PackageManager.GET_META_DATA,
                    profile.userId
                )

                packages
                    .filter { appInfo ->
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        hasInternetPermission(appInfo.packageName, profile.userId)
                    }
                    .forEach { appInfo ->
                        result.add(appInfo.packageName to profile.userId)
                    }
            }

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get installed packages: ${e.message}", e)
            null
        }
    }

    /**
     * Notices an app being enabled or disabled in ANY profile, which nothing else here can see.
     *
     * One `pm list packages -d --user N` per profile, not one call per package - that is what makes
     * this cheap enough to sit in a 15-second poll. No notification is raised: this is not a new
     * app, it is the same app in a different state, so the only job is to tell the UI to re-read.
     *
     * A profile whose read fails is skipped rather than recorded, so a temporary shell failure does
     * not first look like "everything got enabled" and then like "everything got disabled again".
     */
    private fun checkForEnabledStateChanges() {
        val profiles = try {
            HiddenApiHelper.getUsers(this)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not list profiles for the enabled-state check: ${e.message}")
            return
        }

        var changed = false

        for (profile in profiles) {
            val fresh = HiddenApiHelper.readDisabledPackagesFresh(profile.userId)
            if (fresh == null) {
                AppLogger.d(TAG, "Disabled-state read failed for user ${profile.userId} - leaving the previous snapshot alone")
                continue
            }

            val previous = lastKnownDisabled[profile.userId]
            lastKnownDisabled[profile.userId] = fresh

            if (previous == null) {
                // First successful read for this profile. It is the starting point, not a change.
                continue
            }

            if (previous != fresh) {
                val nowDisabled = fresh - previous
                val nowEnabled = previous - fresh
                AppLogger.d(
                    TAG,
                    "📦 Enabled state changed in user ${profile.userId}: " +
                        "${nowDisabled.size} newly disabled, ${nowEnabled.size} newly enabled"
                )
                changed = true
            }
        }

        // Forget profiles that no longer exist. Without this, a work profile removed and later
        // re-created reusing the same userId is compared against the DELETED profile's snapshot, so
        // its very first read reports a change that never happened.
        val liveIds = profiles.map { it.userId }.toSet()
        lastKnownDisabled.keys.retainAll(liveIds)

        if (changed) {
            // The disabled sets are already refreshed by the read above, but the built
            // ApplicationInfo objects are cached separately for a few seconds with the OLD enabled
            // flag baked in - so without this the screen can redraw showing exactly what we just
            // detected had changed. Only on a real change, which is rare, so the cost is not paid
            // on ordinary polls.
            HiddenApiHelper.clearInstalledAppsCache()
            (application as De1984Application).dependencies.notifyPackageDataChanged()
        }
    }

    private fun hasInternetPermission(packageName: String, userId: Int = 0): Boolean {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                this, packageName, PackageManager.GET_PERMISSIONS, userId
            ) ?: return false
            packageInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processNewPackage(packageName: String, userId: Int) {
        try {
            val appInfo = try {
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                    this, packageName, 0, userId
                )
            } catch (e: Exception) {
                null
            }
            // appId 0 is a placeholder, not a real appId - but the userId half of this value IS
            // real, and HandleNewAppInstallUseCase derives the profile from it (`uid / 100000`).
            // A sentinel here was tried and reverted: it made that division yield profile 0, so a
            // work-profile app whose ApplicationInfo could not be read had its rule created in the
            // personal profile instead. The placeholder is harmless because the use case re-reads
            // the real uid itself before writing anything.
            val appId = appInfo?.uid?.rem(100000) ?: 0
            val uid = userId * 100000 + appId

            AppLogger.d(TAG, "📦 Processing new package: $packageName (userId=$userId, uid=$uid)")

            handleNewAppInstallUseCase.execute(packageName, uid)
                .onSuccess {
                    newAppNotificationManager.showNewAppNotification(packageName)
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error processing new package $packageName: ${e.message}", e)
        }
    }
}
