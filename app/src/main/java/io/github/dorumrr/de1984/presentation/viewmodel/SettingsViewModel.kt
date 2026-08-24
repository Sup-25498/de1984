package io.github.dorumrr.de1984.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.data.common.BootProtectionManager
import io.github.dorumrr.de1984.data.common.CaptivePortalManager
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalPreset
import io.github.dorumrr.de1984.domain.model.CaptivePortalSettings
import io.github.dorumrr.de1984.domain.model.FirewallRulesBackup
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.domain.usecase.SmartPolicySwitchUseCase
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val firewallManager: FirewallManager,
    private val firewallRepository: FirewallRepository,
    private val captivePortalManager: CaptivePortalManager,
    private val bootProtectionManager: BootProtectionManager,
    private val smartPolicySwitchUseCase: SmartPolicySwitchUseCase,
    private val packageRepository: PackageRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(loadInitialSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitialSettings(): SettingsUiState {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        val firewallModeString = prefs.getString(
            Constants.Settings.KEY_FIREWALL_MODE,
            Constants.Settings.DEFAULT_FIREWALL_MODE
        ) ?: Constants.Settings.DEFAULT_FIREWALL_MODE

        return SettingsUiState(
            autoRefresh = prefs.getBoolean("auto_refresh", true),
            showSystemApps = prefs.getBoolean("show_system_apps", false),
            darkTheme = prefs.getBoolean("dark_theme", false),
            refreshInterval = prefs.getInt("refresh_interval", 30),
            showAppIcons = prefs.getBoolean(Constants.Settings.KEY_SHOW_APP_ICONS, Constants.Settings.DEFAULT_SHOW_APP_ICONS),
            defaultFirewallPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY,
            newAppNotifications = prefs.getBoolean(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS),
            bootProtection = prefs.getBoolean(Constants.Settings.KEY_BOOT_PROTECTION, Constants.Settings.DEFAULT_BOOT_PROTECTION),
            appLanguage = prefs.getString(Constants.Settings.KEY_APP_LANGUAGE, Constants.Settings.DEFAULT_APP_LANGUAGE) ?: Constants.Settings.DEFAULT_APP_LANGUAGE,
            firewallMode = FirewallMode.fromString(firewallModeString) ?: FirewallMode.AUTO,
            allowCriticalPackageUninstall = prefs.getBoolean(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL),
            allowCriticalPackageFirewall = prefs.getBoolean(Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL, Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL),
            showFirewallStartPrompt = prefs.getBoolean(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT),
            confirmRuleChanges = prefs.getBoolean(Constants.Settings.KEY_CONFIRM_RULE_CHANGES, Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES),
            useDynamicColors = prefs.getBoolean(Constants.Settings.KEY_USE_DYNAMIC_COLORS, Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS)
        )
    }

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.shizukuStatus
    val activeBackendType: StateFlow<FirewallBackendType?> = firewallManager.activeBackendType



    init {
        // Note: Settings are loaded synchronously in loadInitialSettings() to avoid
        // emitting default values first, which would cause observers to see a "change"
        loadSystemInfo()
        cleanupOrphanedPreferences()
        requestRootPermission()
        requestShizukuPermission()

        viewModelScope.launch {
            rootStatus.collect {
                AppLogger.d(TAG, "Root status changed: $it, re-checking boot protection availability")
                checkBootProtectionAvailability()
                updateCaptivePortalPrivileges()
            }
        }

        viewModelScope.launch {
            shizukuStatus.collect {
                AppLogger.d(TAG, "Shizuku status changed: $it, re-checking boot protection availability")
                checkBootProtectionAvailability()
                updateCaptivePortalPrivileges()
            }
        }

        viewModelScope.launch {
            firewallManager.currentMode.collect { mode ->
                AppLogger.d(TAG, "🔄 Firewall mode changed externally: $mode")
                if (_uiState.value.firewallMode != mode) {
                    AppLogger.d(TAG, "🔄 Updating UI mode from ${_uiState.value.firewallMode} to $mode")
                    _uiState.value = _uiState.value.copy(firewallMode = mode)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AppLogger.d(TAG, "SettingsViewModel cleared - all coroutines will be cancelled")
    }

    private fun updateCaptivePortalPrivileges() {
        _uiState.value = _uiState.value.copy(
            captivePortalHasPrivileges = captivePortalManager.hasPrivileges()
        )
    }


    fun requestRootPermission() {
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
    }

    fun hasRequestedRootPermission(): Boolean {
        return rootManager.hasRequestedRootPermission()
    }

    fun markRootPermissionRequested() {
        rootManager.markRootPermissionRequested()
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            shizukuManager.checkShizukuStatus()

            val status = shizukuManager.shizukuStatus.value
            // Skip if user already denied to prevent prompt spam (Issue #68)
            // This is called from init{}, so only auto-request if user hasn't denied
            if (status == ShizukuStatus.RUNNING_NO_PERMISSION && !shizukuManager.hasUserDeniedPermission) {
                shizukuManager.requestShizukuPermission()
            }
        }
    }

    fun grantShizukuPermission() {
        shizukuManager.resetPermissionDenial()
        shizukuManager.requestShizukuPermission()
    }

    private fun loadSystemInfo() {
        viewModelScope.launch {
            val systemCapabilities = permissionManager.getSystemCapabilities()

            val systemInfo = SystemInfo(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                androidROM = getAndroidROMInfo(),
                hasRoot = false,
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
            )

            _uiState.value = _uiState.value.copy(
                systemInfo = systemInfo,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                hasBasicPermissions = systemCapabilities.hasBasicPermissions,
                hasEnhancedPermissions = true,
                hasAdvancedPermissions = false
            )
        }
    }

    private fun getAndroidROMInfo(): String {
        return try {
            val displayInfo = Build.DISPLAY
            when {
                displayInfo.contains("lineage", ignoreCase = true) -> displayInfo
                displayInfo.contains("pixel", ignoreCase = true) -> "Pixel Experience"
                displayInfo.isNotBlank() -> displayInfo
                else -> "Stock Android"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun saveSetting(key: String, value: Any, durable: Boolean = false) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }

        if (durable) {
            // Synchronous on purpose: setBootProtection reboots the device a few lines after calling
            // this, and an async write could simply not land. Callers are responsible for being off
            // the main thread - see the withContext wrappers at the boot-protection call sites.
            @Suppress("ApplySharedPref")
            editor.commit()
        } else {
            editor.apply()
        }
    }
    
    fun setAutoRefresh(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoRefresh = enabled)
        saveSetting("auto_refresh", enabled)
    }
    
    fun setShowSystemApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
        saveSetting("show_system_apps", show)
    }
    
    fun setDarkTheme(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkTheme = enabled)
        saveSetting("dark_theme", enabled)
    }

    fun setShowAppIcons(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAppIcons = show)
        saveSetting(Constants.Settings.KEY_SHOW_APP_ICONS, show)
    }

    fun setAppLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(appLanguage = languageCode)
        saveSetting(Constants.Settings.KEY_APP_LANGUAGE, languageCode)
    }

    /**
     * Change the default firewall policy with smart handling of system-critical packages.
     *
     * When allowCriticalPackageFirewall is ON:
     * - Preserves existing user preferences for critical packages
     * - Defaults critical packages to ALLOW (if no user preference exists) to ensure system stability
     * - Applies normal policy to non-critical packages
     *
     * When allowCriticalPackageFirewall is OFF:
     * - Uses standard policy switching (critical packages are protected by backend logic anyway)
     */
    fun setDefaultFirewallPolicy(newPolicy: String) {
        val oldPolicy = _uiState.value.defaultFirewallPolicy
        AppLogger.d(TAG, "setDefaultFirewallPolicy: oldPolicy=$oldPolicy, newPolicy=$newPolicy")

        if (oldPolicy == newPolicy) {
            AppLogger.d(TAG, "setDefaultFirewallPolicy: Policy unchanged, skipping")
            return
        }

        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "setDefaultFirewallPolicy: Updating uiState and saving to SharedPreferences")
                _uiState.value = _uiState.value.copy(
                    defaultFirewallPolicy = newPolicy
                )
                saveSetting(Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY, newPolicy)
                AppLogger.d(TAG, "setDefaultFirewallPolicy: uiState updated to: ${_uiState.value.defaultFirewallPolicy}")

                AppLogger.d(TAG, "setDefaultFirewallPolicy: Applying smart policy switch")
                when (newPolicy) {
                    Constants.Settings.POLICY_BLOCK_ALL -> {
                        smartPolicySwitchUseCase.switchToBlockAll()
                    }
                    Constants.Settings.POLICY_ALLOW_ALL -> {
                        smartPolicySwitchUseCase.switchToAllowAll()
                    }
                }

                if (firewallManager.isActive()) {
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Firewall active, triggering rule reapplication")
                    firewallManager.triggerRuleReapplication()

                    val intent = Intent("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Broadcast sent")
                } else {
                    AppLogger.d(TAG, "setDefaultFirewallPolicy: Firewall not active, skipping rule reapplication")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to change policy", e)
            }
        }
    }

    fun setNewAppNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(newAppNotifications = enabled)
        saveSetting(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, enabled)
    }

    fun setBootProtection(enabled: Boolean) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "setBootProtection: enabled=$enabled")

                val result = bootProtectionLock.withLock { bootProtectionManager.setBootProtection(enabled) }

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(bootProtection = enabled)
                    // Durable, not apply(): the device is rebooted a few lines below. Losing this
                    // write would leave the preference disagreeing with the boot script actually on
                    // disk - the exact mismatch this feature exists to prevent.
                    withContext(Dispatchers.IO) { saveSetting(Constants.Settings.KEY_BOOT_PROTECTION, enabled, durable = true) }

                    AppLogger.d(TAG, "✅ Boot protection ${if (enabled) "enabled" else "disabled"} successfully")

                    // Reboot immediately. The user already confirmed in the warning dialog, which is
                    // the only gate: no second prompt, no delay, no message they would never see.
                    // This guarantees the on-disk script and the live iptables state always agree.
                    // Only reached after a verified successful change - never after a failure.
                    val rebootResult = bootProtectionManager.rebootDevice()
                    if (rebootResult.isFailure) {
                        AppLogger.e(TAG, "❌ Reboot failed after boot protection change", rebootResult.exceptionOrNull())
                        _uiState.value = _uiState.value.copy(
                            error = context.getString(io.github.dorumrr.de1984.R.string.boot_protection_reboot_failed)
                        )
                    }
                } else {
                    val errorMessage = if (enabled) {
                        context.getString(io.github.dorumrr.de1984.R.string.boot_protection_enable_failed, result.exceptionOrNull()?.message ?: "Unknown error")
                    } else {
                        context.getString(io.github.dorumrr.de1984.R.string.boot_protection_disable_failed, result.exceptionOrNull()?.message ?: "Unknown error")
                    }
                    _uiState.value = _uiState.value.copy(error = errorMessage)

                    AppLogger.e(TAG, "❌ Failed to ${if (enabled) "enable" else "disable"} boot protection", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception in setBootProtection", e)
                _uiState.value = _uiState.value.copy(error = e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Serialises boot-protection reads and writes.
     *
     * Both the toggle and the disk reconciliation suspend on IO, so without this they interleave: the
     * reconciliation reads the disk, the user confirms the toggle, the toggle writes script and
     * preference, then the reconciliation resumes with its stale reading and commits the opposite -
     * leaving a script installed with the preference saying it is not.
     */
    private val bootProtectionLock = kotlinx.coroutines.sync.Mutex()

    fun checkBootProtectionAvailability() {
        AppLogger.d(TAG, "checkBootProtectionAvailability() called")
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "Checking boot protection availability...")

                // Check if root/Shizuku is available
                // Must match BootProtectionManager.hasBootProtectionPrivilege(). ADB-mode Shizuku runs
                // as uid 2000 and can touch neither iptables nor /data/adb, so counting it here made
                // the reconciliation below run with a check that always fails - writing "not
                // installed" over a device that is blocked at every boot.
                val hasPrivileges = rootManager.hasRootPermission ||
                    (shizukuManager.hasShizukuPermission && shizukuManager.isShizukuRootMode())
                AppLogger.d(TAG, "hasPrivileges: $hasPrivileges (root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission})")

                val hasBootScriptSupport = if (hasPrivileges) {
                    AppLogger.d(TAG, "Checking boot script support availability...")
                    bootProtectionManager.isBootScriptSupportAvailable()
                } else {
                    AppLogger.d(TAG, "No privileges, skipping boot script support check")
                    false
                }

                val available = hasPrivileges && hasBootScriptSupport

                AppLogger.d(TAG, "Boot protection availability: hasPrivileges=$hasPrivileges, hasBootScriptSupport=$hasBootScriptSupport, available=$available")

                _uiState.value = _uiState.value.copy(bootProtectionAvailable = available)
                AppLogger.d(TAG, "Updated UI state with bootProtectionAvailable=$available")

                // The switch used to read only the preference. Clearing app data resets that
                // preference to false while leaving the script on disk, so the switch said OFF for a
                // device that is still blocked at every boot - and the user had no way to see it.
                // Disk is the truth: if a script is installed, boot protection IS on.
                if (available) bootProtectionLock.withLock {
                    // null = could not determine. Never write that over the preference: a dropped
                    // Shizuku call or an expired su grant would otherwise erase the only record that
                    // boot protection is on.
                    val installedOnDisk = bootProtectionManager.isBootProtectionInstalled()
                    if (installedOnDisk != null && installedOnDisk != _uiState.value.bootProtection) {
                        AppLogger.w(
                            TAG,
                            "Boot protection preference (${_uiState.value.bootProtection}) disagreed with " +
                                "the script on disk ($installedOnDisk) - trusting disk"
                        )
                        _uiState.value = _uiState.value.copy(bootProtection = installedOnDisk)
                        withContext(Dispatchers.IO) { saveSetting(Constants.Settings.KEY_BOOT_PROTECTION, installedOnDisk, durable = true) }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check boot protection availability", e)
                _uiState.value = _uiState.value.copy(bootProtectionAvailable = false)
            }
        }
    }

    fun setAllowCriticalPackageUninstall(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowCriticalPackageUninstall = allow)
        saveSetting(Constants.Settings.KEY_ALLOW_CRITICAL_UNINSTALL, allow)
    }

    fun setAllowCriticalPackageFirewall(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowCriticalPackageFirewall = allow)
        saveSetting(Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL, allow)
    }

    fun setShowFirewallStartPrompt(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFirewallStartPrompt = show)
        saveSetting(Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT, show)
    }

    fun setConfirmRuleChanges(confirm: Boolean) {
        _uiState.value = _uiState.value.copy(confirmRuleChanges = confirm)
        saveSetting(Constants.Settings.KEY_CONFIRM_RULE_CHANGES, confirm)
    }

    fun setUseDynamicColors(enabled: Boolean, showRestartDialog: Boolean = false) {
        _uiState.value = _uiState.value.copy(useDynamicColors = enabled, requiresRestart = showRestartDialog)
        saveSetting(Constants.Settings.KEY_USE_DYNAMIC_COLORS, enabled)
    }

    fun clearRestartPrompt() {
        _uiState.value = _uiState.value.copy(requiresRestart = false)
    }

    fun wouldDisconnectOtherVpn(mode: FirewallMode): Boolean {
        if (mode != FirewallMode.VPN) return false
        
        return firewallManager.isAnotherVpnActive()
    }

    fun setFirewallMode(mode: FirewallMode, forceEvenIfOtherVpnActive: Boolean = false) {
        AppLogger.i(TAG, "👆 USER ACTION: setFirewallMode($mode, forceEvenIfOtherVpnActive=$forceEvenIfOtherVpnActive)")
        AppLogger.d(TAG, "   Current UI state: mode=${_uiState.value.firewallMode}, activeBackend=${firewallManager.activeBackendType.value}")
        
        firewallManager.dismissVpnConflictSwitchNotification()
        
        val wouldDisconnectVpn = wouldDisconnectOtherVpn(mode)
        if (wouldDisconnectVpn && !forceEvenIfOtherVpnActive) {
            AppLogger.d(TAG, "   Another VPN is active and user hasn't confirmed - showing warning")
            _uiState.value = _uiState.value.copy(
                pendingModeChange = mode,
                showVpnConflictWarning = true
            )
            return
        }
        
        AppLogger.i(TAG, "   Updating mode to $mode and restarting firewall")
        
        _uiState.value = _uiState.value.copy(
            firewallMode = mode,
            pendingModeChange = null,
            showVpnConflictWarning = false
        )
        firewallManager.setMode(mode)

        // Restart firewall if user has it enabled (regardless of current active state)
        // This handles the case where a previous backend switch failed and firewall is down
        viewModelScope.launch {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
            AppLogger.d(TAG, "   Firewall enabled: $isFirewallEnabled")
            if (isFirewallEnabled) {
                AppLogger.d(TAG, "   Calling restartFirewallIfRunning() with mode=$mode")
                restartFirewallIfRunning()
            }
        }
    }

    fun cancelPendingModeChange() {
        _uiState.value = _uiState.value.copy(
            pendingModeChange = null,
            showVpnConflictWarning = false
        )
    }

    fun confirmPendingModeChange() {
        val pendingMode = _uiState.value.pendingModeChange ?: return
        setFirewallMode(pendingMode, forceEvenIfOtherVpnActive = true)
    }

    fun checkIptablesAvailability(callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val available = firewallManager.isIptablesAvailable()
            callback(available)
        }
    }

    fun isShizukuRootMode(): Boolean {
        return shizukuManager.isShizukuRootMode()
    }

    fun checkVpnPermissionNeeded(): android.content.Intent? {
        val mode = _uiState.value.firewallMode
        if (mode != FirewallMode.VPN) return null
        
        return android.net.VpnService.prepare(context)
    }

    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            restartFirewallIfRunning()
        }
    }

    private suspend fun restartFirewallIfRunning() {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

            if (!isFirewallEnabled) {
                return
            }

            val newMode = _uiState.value.firewallMode
            if (newMode == FirewallMode.VPN) {
                val prepareIntent = android.net.VpnService.prepare(context)
                if (prepareIntent != null) {
                    _uiState.value = _uiState.value.copy(
                        vpnPermissionRequired = true
                    )
                    return
                }
            }

            // A failed stop must ABORT the switch. This used to discard the Result and start the
            // new backend anyway, which was wrong twice over: the old backend is still enforcing,
            // so two sets of rules end up live with only one of them visible or undoable - and the
            // successful start then calls reportFirewallHealthy(), erasing the very warning that
            // said the first backend is stuck. The user is left with a firewall they cannot turn
            // off and nothing on screen saying so.
            //
            // Aborting keeps exactly one backend running and leaves the StopFailed banner up, whose
            // "Stop again" button is the way out. The mode preference has already been written by
            // the caller, so a later successful stop lets the user retry the switch.
            firewallManager.stopFirewall().onFailure { error ->
                AppLogger.e(TAG, "Backend switch aborted - the old backend would not stop: ${error.message}", error)
                _uiState.value = _uiState.value.copy(
                    error = context.getString(
                        io.github.dorumrr.de1984.R.string.error_firewall_restart_failed,
                        error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                    )
                )
                return
            }
            delay(500)

            val result = firewallManager.startFirewall(newMode)

            result.onFailure { error ->
                // IMPORTANT: Do NOT clear KEY_FIREWALL_ENABLED here!
                // We want to preserve user intent so handlePrivilegeChange() can attempt recovery.
                // FirewallManager will set isFirewallDown=true to track the error state.

                _uiState.value = _uiState.value.copy(
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_firewall_restart_failed, error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restart firewall", e)
        }
    }

    fun clearVpnPermissionRequired() {
        _uiState.value = _uiState.value.copy(vpnPermissionRequired = false)
    }

    fun setRefreshInterval(interval: Int) {
        _uiState.value = _uiState.value.copy(refreshInterval = interval)
        saveSetting("refresh_interval", interval)
    }
    

    
    fun showLicenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                message = context.getString(io.github.dorumrr.de1984.R.string.licenses_coming_soon)
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun backupRules(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                val rules = firewallRepository.getAllRules().first()

                if (rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_no_rules_to_backup)
                    )
                    return@launch
                }

                val backup = FirewallRulesBackup(
                    version = 1,
                    exportDate = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    rulesCount = rules.size,
                    rules = rules
                )

                val json = Json.encodeToString(FirewallRulesBackup.serializer(), backup)

                writeToUri(uri, json)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Backup successful: ${rules.size} rules saved"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Backup failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    fun restoreRules(uri: Uri, replaceExisting: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                val json = readFromUri(uri)

                val backup = Json.decodeFromString<FirewallRulesBackup>(json)

                if (backup.version > 1) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_unsupported_backup_version, backup.version)
                    )
                    return@launch
                }

                if (backup.rules.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_backup_no_rules)
                    )
                    return@launch
                }

                if (replaceExisting) {
                    firewallRepository.deleteAllRules()
                }

                firewallRepository.insertRules(backup.rules)

                val action = if (replaceExisting) "restored" else "merged"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "✅ Rules $action successfully: ${backup.rules.size} rules"
                )
            } catch (e: SerializationException) {
                AppLogger.e(TAG, "Invalid backup file format", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_invalid_backup_format)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_restore_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    suspend fun parseBackupFile(uri: Uri): Result<FirewallRulesBackup> {
        return withContext(Dispatchers.IO) {
            try {
                val json = readFromUri(uri)
                val backup = Json.decodeFromString<FirewallRulesBackup>(json)
                Result.success(backup)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse backup file", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun writeToUri(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        } ?: throw IOException("Failed to open output stream")
    }

    private suspend fun readFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().readText()
        } ?: throw IOException("Failed to open input stream")
    }

    fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    private fun cleanupOrphanedPreferences() {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("auto_check_updates")
            .remove("last_update_check")
            .remove("last_update_check_result")
            .remove("last_update_version")
            .remove("last_update_url")
            .remove("last_update_notes")
            .remove("last_update_error")
            .apply()
    }


    fun exportUninstalledApps(uri: Uri) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "📤 EXPORT: Starting export of uninstalled apps")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                AppLogger.d(TAG, "📤 EXPORT: Privilege check - root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission}")
                if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_export_requires_privileges)
                    )
                    return@launch
                }

                val result = packageRepository.getUninstalledSystemPackages()
                val packages = result.getOrNull()

                if (packages.isNullOrEmpty()) {
                    AppLogger.d(TAG, "📤 EXPORT: No uninstalled system packages found")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_export_no_uninstalled_apps)
                    )
                    return@launch
                }

                AppLogger.d(TAG, "📤 EXPORT: Found ${packages.size} uninstalled system packages")

                val content = createExportContent(packages)

                AppLogger.d(TAG, "📤 EXPORT: Writing to file: $uri")
                writeToUri(uri, content)

                AppLogger.d(TAG, "📤 EXPORT: Success - exported ${packages.size} packages")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = context.getString(io.github.dorumrr.de1984.R.string.success_export_uninstalled, packages.size)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "📤 EXPORT: Failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_export_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    private fun createExportContent(packages: List<Package>): String {
        return buildString {
            appendLine("# De1984 Uninstalled Apps Export")
            appendLine("# Date: ${getCurrentDate()}")
            appendLine("# App Version: ${BuildConfig.VERSION_NAME}")
            appendLine("# Count: ${packages.size}")
            appendLine()
            packages.forEach { pkg ->
                appendLine(pkg.packageName)
            }
        }
    }

    fun importUninstalledApps(uri: Uri) {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "📥 IMPORT: Starting import from file: $uri")
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, message = null)

                AppLogger.d(TAG, "📥 IMPORT: Privilege check - root=${rootManager.hasRootPermission}, shizuku=${shizukuManager.hasShizukuPermission}")
                if (!rootManager.hasRootPermission && !shizukuManager.hasShizukuPermission) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.error_import_requires_privileges)
                    )
                    return@launch
                }

                val content = readFromUri(uri)
                val packageNames = parseUninstalledAppsFile(content)

                AppLogger.d(TAG, "📥 IMPORT: Parsed ${packageNames.size} package names from file")

                if (packageNames.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(io.github.dorumrr.de1984.R.string.dialog_import_empty_file)
                    )
                    return@launch
                }

                val installedPackages = packageRepository.getPackages().first()
                val installedPackageNames = installedPackages.map { it.packageName }.toSet()

                val packagesToUninstallNames = packageNames.filter { it in installedPackageNames }
                val packagesNotFound = packageNames.filter { it !in installedPackageNames }

                val packagesToUninstall = packagesToUninstallNames.map { it to 0 }

                AppLogger.d(TAG, "📥 IMPORT: Validation - ${packagesToUninstall.size} found, ${packagesNotFound.size} not found")

                when {
                    packagesToUninstall.isEmpty() -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = context.getString(io.github.dorumrr.de1984.R.string.dialog_import_all_not_found, packagesNotFound.size)
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            importUninstalledPreview = ImportUninstalledPreview(
                                totalPackages = packageNames.size,
                                packagesToUninstall = packagesToUninstall,
                                packagesNotFound = packagesNotFound
                            )
                        )
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "📥 IMPORT: File read failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_import_file_read_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "📥 IMPORT: Failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(io.github.dorumrr.de1984.R.string.error_import_failed, e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown))
                )
            }
        }
    }

    private fun parseUninstalledAppsFile(content: String): List<String> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("#") }
            .filter { it.contains(".") }
            .distinct()
    }

    fun confirmImportUninstall() {
        viewModelScope.launch {
            val preview = _uiState.value.importUninstalledPreview ?: return@launch

            AppLogger.d(TAG, "📥 IMPORT: User confirmed - starting batch uninstall of ${preview.packagesToUninstall.size} packages")
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                importUninstalledPreview = null
            )

            val result = packageRepository.uninstallMultiplePackages(preview.packagesToUninstall)

            result.fold(
                onSuccess = { batchResult ->
                    AppLogger.d(TAG, "📥 IMPORT: Batch uninstall complete - ${batchResult.succeeded.size} succeeded, ${batchResult.failed.size} failed")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        batchUninstallResult = batchResult
                    )
                },
                onFailure = { error ->
                    AppLogger.e(TAG, "📥 IMPORT: Batch uninstall failed", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                    )
                }
            )
        }
    }

    fun clearImportPreview() {
        _uiState.value = _uiState.value.copy(importUninstalledPreview = null)
    }

    fun clearBatchUninstallResult() {
        _uiState.value = _uiState.value.copy(batchUninstallResult = null)
    }


    fun loadCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                if (!captivePortalManager.hasOriginalSettings()) {
                    captivePortalManager.captureOriginalSettings()
                }

                val result = captivePortalManager.getCurrentSettings()
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        captivePortalSettings = result.getOrNull(),
                        captivePortalOriginalCaptured = captivePortalManager.hasOriginalSettings(),
                        captivePortalHasPrivileges = captivePortalManager.hasPrivileges(),
                        captivePortalLoading = false,
                        captivePortalError = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_load_settings)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load captive portal settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun applyCaptivePortalPreset(preset: CaptivePortalPreset) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.applyPreset(preset)
                if (result.isSuccess) {
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.captive_portal_preset_applied, preset.getDisplayName(context))
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_apply_preset)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply preset", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun setCaptivePortalDetectionMode(mode: CaptivePortalMode) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setDetectionMode(mode)
                if (result.isSuccess) {
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.captive_portal_mode_set, mode.getDisplayName(context))
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_set_detection_mode)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to set detection mode", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun setCustomCaptivePortalUrls(httpUrl: String, httpsUrl: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.setCustomUrls(httpUrl, httpsUrl)
                if (result.isSuccess) {
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_custom_urls_applied)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_set_custom_urls)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to set custom URLs", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun restoreOriginalCaptivePortalSettings() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.restoreOriginalSettings()
                if (result.isSuccess) {
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_original_settings_restored)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_restore_original_settings)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to restore original settings", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    fun resetCaptivePortalToGoogleDefaults() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = true,
                    captivePortalError = null
                )

                val result = captivePortalManager.resetToGoogleDefaults()
                if (result.isSuccess) {
                    loadCaptivePortalSettings()
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(io.github.dorumrr.de1984.R.string.success_reset_to_google_defaults)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        captivePortalLoading = false,
                        captivePortalError = result.exceptionOrNull()?.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_failed_to_reset_to_google_defaults)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to reset to Google defaults", e)
                _uiState.value = _uiState.value.copy(
                    captivePortalLoading = false,
                    captivePortalError = e.message ?: context.getString(io.github.dorumrr.de1984.R.string.error_unknown)
                )
            }
        }
    }

    class Factory(
        private val context: Context,
        private val permissionManager: PermissionManager,
        private val rootManager: RootManager,
        private val shizukuManager: ShizukuManager,
        private val firewallManager: FirewallManager,
        private val firewallRepository: FirewallRepository,
        private val captivePortalManager: CaptivePortalManager,
        private val bootProtectionManager: BootProtectionManager,
        private val smartPolicySwitchUseCase: SmartPolicySwitchUseCase,
        private val packageRepository: PackageRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    context,
                    permissionManager,
                    rootManager,
                    shizukuManager,
                    firewallManager,
                    firewallRepository,
                    captivePortalManager,
                    bootProtectionManager,
                    smartPolicySwitchUseCase,
                    packageRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class SettingsUiState(
    val autoRefresh: Boolean = true,
    val showSystemApps: Boolean = false,
    val darkTheme: Boolean = false,
    val showAppIcons: Boolean = true,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY,
    val newAppNotifications: Boolean = Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS,
    val bootProtection: Boolean = Constants.Settings.DEFAULT_BOOT_PROTECTION,
    val bootProtectionAvailable: Boolean = false,
    val firewallMode: FirewallMode = FirewallMode.AUTO,
    val allowCriticalPackageUninstall: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_UNINSTALL,
    val allowCriticalPackageFirewall: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL,
    val showFirewallStartPrompt: Boolean = Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT,
    val confirmRuleChanges: Boolean = Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES,
    val useDynamicColors: Boolean = Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS,
    val appLanguage: String = Constants.Settings.DEFAULT_APP_LANGUAGE,

    val refreshInterval: Int = 30,

    val requiresRestart: Boolean = false,

    val systemInfo: SystemInfo = SystemInfo(
        deviceModel = "Unknown",
        androidVersion = "Unknown",
        androidROM = "Unknown",
        hasRoot = false,
        architecture = "Unknown"
    ),

    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildNumber: String = BuildConfig.VERSION_CODE.toString(),

    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,

    val hasBasicPermissions: Boolean = true,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false,

    val captivePortalSettings: CaptivePortalSettings? = null,
    val captivePortalOriginalCaptured: Boolean = false,
    val captivePortalHasPrivileges: Boolean = false,
    val captivePortalLoading: Boolean = false,
    val captivePortalError: String? = null,

    val importUninstalledPreview: ImportUninstalledPreview? = null,
    val batchUninstallResult: UninstallBatchResult? = null,

    val pendingModeChange: FirewallMode? = null,
    val showVpnConflictWarning: Boolean = false,
    val vpnPermissionRequired: Boolean = false
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)

data class ImportUninstalledPreview(
    val totalPackages: Int,
    val packagesToUninstall: List<Pair<String, Int>>,
    val packagesNotFound: List<String>
)


