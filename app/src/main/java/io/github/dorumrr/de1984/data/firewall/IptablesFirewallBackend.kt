package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptablesFirewallBackend(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {
    
    companion object {
        private const val TAG = "IptablesFirewall"

        // Custom chain name to avoid conflicts with Android netd
        // Note: We only use OUTPUT chain because the owner module only works for OUTPUT
        // (locally generated packets). INPUT chain cannot match by UID.
        private const val CHAIN_OUTPUT = "de1984_output"

        private const val IPTABLES = "iptables"
        private const val IP6TABLES = "ip6tables"

        private const val PROBE_PRESENT = "DE1984_CHAIN_PRESENT"
        private const val PROBE_ABSENT = "DE1984_CHAIN_ABSENT"
        private const val PROBE_NOPRIV = "DE1984_CHAIN_NOPRIV"
    }
    
    private val mutex = Mutex()

    private val blockedUids = mutableSetOf<Int>()

    private val blockedLanUids = mutableSetOf<Int>()

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== IptablesFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with iptables backend")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "IPTABLES")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ iptables firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start iptables firewall", e)
            val error = errorHandler.handleError(e, "start iptables firewall")
            Result.failure(error)
        }
    }

    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Creating iptables chains")

            checkAvailability().getOrElse { error ->
                return Result.failure(error)
            }

            createCustomChains().getOrElse { error ->
                return Result.failure(error)
            }

            // iptables rules live in the kernel, not in this process, so they outlive a crash, a
            // force-stop and the app itself. This flag is the only thing that tells a later, fresh
            // instance that there is something out there to undo - and therefore whether an
            // unverifiable teardown is "nothing to do" or "we just failed to remove live rules".
            // commit(), not apply(): the chains exist now, so the record must exist now too.
            setChainsInstalled(true)

            AppLogger.d(TAG, "✅ iptables chains created")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create iptables chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping iptables firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
                // Name the backend. The service holds ONE currentBackend, so an unqualified stop
                // tears down whatever it happens to be running - which during a switch is the
                // backend that was just STARTED, not this one.
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "IPTABLES")
            }
            context.startService(intent)

            AppLogger.d(TAG, "iptables firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop iptables firewall", e)
            val error = errorHandler.handleError(e, "stop iptables firewall")
            Result.failure(error)
        }
    }

    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Deleting iptables chains")

            val chainsWereInstalled = wereChainsInstalled()

            clearAllRules().getOrElse { error ->
                AppLogger.w(TAG, "Failed to clear rules during stop: ${error.message}")
            }

            deleteCustomChains().getOrElse { error ->
                AppLogger.w(TAG, "Failed to delete chains during stop: ${error.message}")
            }

            blockedUids.clear()
            blockedLanUids.clear()

            // Ask the kernel instead of trusting the commands we just ran. Every teardown command
            // ends in "|| true" and none of them has its exit code inspected, which is deliberate -
            // deleting a chain that is already gone is not an error. The cost of that is that the
            // commands can ALL fail (revoked root, xtables lock, permission denied) and still look
            // fine, so this method used to return success over chains that were still dropping
            // traffic. FirewallManager turns that success into "firewall stopped", clears the
            // warning banner and shows OFF. The only honest way to end a teardown is to look.
            when (probeChains()) {
                TeardownProof.CLEAN -> {
                    setChainsInstalled(false)
                    AppLogger.d(TAG, "iptables chains deleted and verified gone")
                    Result.success(Unit)
                }
                TeardownProof.RESIDUE -> {
                    AppLogger.e(TAG, "iptables teardown FAILED - chain $CHAIN_OUTPUT is still installed")
                    Result.failure(
                        errorHandler.handleError(
                            IllegalStateException("iptables chain $CHAIN_OUTPUT is still installed"),
                            "delete iptables chains"
                        )
                    )
                }
                TeardownProof.UNVERIFIABLE -> {
                    // We could not run the probe at all - no root, no Shizuku. That is only a
                    // failure if there was something to remove. A device that never created the
                    // chains has nothing to lose, and reporting a failed stop there would fire the
                    // warning on every stop of every other backend, because the sweep runs this
                    // cleanup unconditionally.
                    if (chainsWereInstalled) {
                        AppLogger.e(TAG, "iptables teardown UNVERIFIED and chains were installed - assuming rules are still live")
                        Result.failure(
                            errorHandler.handleError(
                                IllegalStateException("cannot verify iptables teardown - no root or Shizuku access"),
                                "delete iptables chains"
                            )
                        )
                    } else {
                        AppLogger.d(TAG, "iptables teardown unverified, but no chains were ever installed - nothing to undo")
                        Result.success(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete iptables chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }

    private enum class TeardownProof {
        CLEAN,

        RESIDUE,

        /** The probe itself could not run, so nothing is proven either way. */
        UNVERIFIABLE
    }

    private suspend fun probeChains(): TeardownProof {
        val v4 = probeChain(IPTABLES)
        val v6 = probeChain(IP6TABLES)

        // A family that will not answer while the OTHER family answered is not a privilege problem.
        // Privilege belongs to the shell, not to an address family: the same root or Shizuku-root
        // shell runs both binaries. So if iptables answered and ip6tables did not, ip6tables is
        // unusable on this device - no ip6_tables module, no binary, no IPv6 filter table - and an
        // unusable family cannot be holding our chains either. That is CLEAN for it, not "could not
        // look".
        //
        // Reading it as "could not look" was a real defect: checkAvailability only ever tests
        // "iptables --version" and createCustomChains inspects no exit codes, so a v4-only device
        // starts fine and sets the chains-installed flag. Every stop then collapsed a proven-clean
        // v4 into UNVERIFIABLE, which with the flag set is a hard failure - and the flag is cleared
        // only on the CLEAN path, so the STUCK badge, banner and notification latched forever on a
        // firewall that was genuinely off, with no way out inside the app.
        val v4Answered = v4 != TeardownProof.UNVERIFIABLE
        val v6Answered = v6 != TeardownProof.UNVERIFIABLE

        return when {
            v4 == TeardownProof.RESIDUE || v6 == TeardownProof.RESIDUE -> TeardownProof.RESIDUE

            // Neither family answered: the shell itself could not look. Genuinely unverifiable.
            !v4Answered && !v6Answered -> TeardownProof.UNVERIFIABLE

            // At least one answered and nothing was found. Any silent family is unusable, so it
            // holds nothing.
            else -> {
                if (!v4Answered || !v6Answered) {
                    val silent = if (v4Answered) IP6TABLES else IPTABLES
                    AppLogger.d(TAG, "$silent is unusable on this device - treating it as holding no chains")
                }
                TeardownProof.CLEAN
            }
        }
    }

    /**
     * Probe one address family.
     *
     * The probe echoes its own token rather than letting the caller read an exit code or an error
     * message, because neither survives the trip reliably. RootManager returns libsu's result.out,
     * which is stdout only - the shell is built without FLAG_REDIRECT_STDERR - so iptables' "No
     * chain/target/match by that name" never arrives on the root path, while ShizukuManager does
     * return stderr. Matching on that text would have read every clean teardown on a rooted device
     * as unverifiable, and with chains installed that is a "firewall would not stop" warning on
     * every single successful stop. Tokens are the same on both paths and in every locale.
     *
     * The outer "-S" with no chain name is the privilege test: listing the whole filter table needs
     * exactly the access that listing one chain needs, so a failure there means we could not look,
     * not that the chain is gone. Without it, a permission-denied probe is indistinguishable from a
     * clean one - which is the false "all clear" this whole method exists to prevent.
     */
    private suspend fun probeChain(binary: String): TeardownProof {
        val probe = "if $binary -S >/dev/null 2>&1; then " +
            "if $binary -S $CHAIN_OUTPUT >/dev/null 2>&1; then echo $PROBE_PRESENT; else echo $PROBE_ABSENT; fi; " +
            "else echo $PROBE_NOPRIV; fi"

        val (exitCode, output) = executeCommand(probe)
        return when {
            output.contains(PROBE_PRESENT) -> TeardownProof.RESIDUE
            output.contains(PROBE_ABSENT) -> TeardownProof.CLEAN
            else -> {
                // PROBE_NOPRIV, or no token at all: no root and no Shizuku, so executeCommand
                // returned Pair(-1, ...) without running anything.
                AppLogger.w(TAG, "Chain probe could not answer for $binary (exit=$exitCode): $output")
                TeardownProof.UNVERIFIABLE
            }
        }
    }

    private fun wereChainsInstalled(): Boolean =
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Constants.Settings.KEY_IPTABLES_CHAINS_INSTALLED, false)

    private fun setChainsInstalled(installed: Boolean) {
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.Settings.KEY_IPTABLES_CHAINS_INSTALLED, installed)
            .commit()
    }
    
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        val startTime = System.currentTimeMillis()
        return try {
            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules START: ${rules.size} rules, network=$networkType, screenOn=$screenOn")


            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            val uidsToBlock = mutableSetOf<Int>()

            // Group rules by UID to handle shared UIDs correctly
            // Multiple apps can share the same UID (sharedUserId in manifest)
            // For security, we use the most restrictive rule (block if ANY app with that UID should be blocked)
            val rulesByUid = rules.filter { it.enabled }.groupBy { it.uid }

            if (isBlockAllDefault) {
                val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
                val allPackages = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
                    .getPackagesWithNetworkPermissions(context)

                AppLogger.d(TAG, "Block All mode: found ${allPackages.size} packages with network permissions across ${userProfiles.size} profiles")

                val allowCritical = prefs.getBoolean(
                    Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                    Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
                )

                // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
                // This is needed because we block by UID, not by package - so if ANY package
                // in a UID is critical with no rule, the entire UID should be allowed
                val uidsWithCritical = if (allowCritical) {
                    allPackages
                        .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName, it.uid / 100000) }
                        .map { it.uid }
                        .toSet()
                } else {
                    emptySet()
                }

                for (appInfo in allPackages) {
                    val uid = appInfo.uid
                    val packageName = appInfo.packageName

                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, allPackages)) {
                        continue
                    }

                    val rulesForUid = rulesByUid[uid]

                    val shouldBlock = if (rulesForUid != null && rulesForUid.isNotEmpty()) {
                        val blockDecision = rulesForUid.any { rule ->
                            when {
                                !screenOn && rule.blockWhenBackground -> true
                                rule.isBlockedOn(networkType) -> true
                                else -> false
                            }
                        }
                        AppLogger.d(TAG, "  $packageName (UID $uid): has rule, shouldBlock=$blockDecision")
                        blockDecision
                    } else {
                        // No rule - check if this UID contains ANY critical package with allowCritical enabled
                        // When allowCritical is ON and no explicit rule exists, default to ALLOW for system stability
                        // IMPORTANT: Check at UID level because we block by UID, not by package
                        if (allowCritical && uidsWithCritical.contains(uid)) {
                            val isSelfCritical = Constants.Firewall.isSystemCritical(packageName) || hasVpnService(packageName, uid / 100000)
                            if (isSelfCritical) {
                                AppLogger.d(TAG, "  $packageName (UID $uid): no rule, critical package → allowing")
                            } else {
                                AppLogger.d(TAG, "  $packageName (UID $uid): no rule, shares UID with critical package → allowing")
                            }
                            false
                        } else {
                            AppLogger.d(TAG, "  $packageName (UID $uid): no rule, blocking by default")
                            true
                        }
                    }

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            } else {

                val userProfilesForAllowAll = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
                val allPackages = userProfilesForAllowAll.flatMap { profile ->
                    io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                        context, PackageManager.GET_META_DATA, profile.userId
                    )
                }

                for ((uid, rulesForUid) in rulesByUid) {
                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, allPackages)) {
                        continue
                    }

                    val shouldBlock = rulesForUid.any { rule ->
                        when {
                            !screenOn && rule.blockWhenBackground -> true
                            rule.isBlockedOn(networkType) -> true
                            else -> false
                        }
                    }

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            }

            val uidsToAdd = uidsToBlock - blockedUids
            val uidsToRemove = blockedUids - uidsToBlock

            AppLogger.d(TAG, "🔥 [TIMING] Rule diff calculated: +${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Rule diff: add=${uidsToAdd.size} UIDs, remove=${uidsToRemove.size} UIDs, keep=${blockedUids.intersect(uidsToBlock).size} UIDs")

            if (uidsToAdd.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to ADD (block): $uidsToAdd")
            }
            if (uidsToRemove.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to REMOVE (unblock): $uidsToRemove")
            }

            val ruleStartTime = System.currentTimeMillis()
            if (uidsToRemove.isNotEmpty() || uidsToAdd.isNotEmpty()) {
                applyRulesBatch(uidsToAdd, uidsToRemove).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to apply batched rules: ${error.message}")
                }
                AppLogger.d(TAG, "🔥 [TIMING] Batched rules (unblock=${uidsToRemove.size}, block=${uidsToAdd.size}) took ${System.currentTimeMillis() - ruleStartTime}ms")
            }


            val userProfilesForLan = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val allPackagesForLan = userProfilesForLan.flatMap { profile ->
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                    context, PackageManager.GET_META_DATA, profile.userId
                )
            }

            val uidsToBlockLan = mutableSetOf<Int>()

            for ((uid, rulesForUid) in rulesByUid) {
                if (isUidExempted(uid, allPackagesForLan)) {
                    continue
                }

                val shouldBlockLan = rulesForUid.any { rule -> rule.lanBlocked }
                if (shouldBlockLan) {
                    uidsToBlockLan.add(uid)
                }
            }

            val uidsToAddLan = uidsToBlockLan - blockedLanUids
            val uidsToRemoveLan = blockedLanUids - uidsToBlockLan

            AppLogger.d(TAG, "LAN blocking diff: add=${uidsToAddLan.size}, remove=${uidsToRemoveLan.size}, keep=${blockedLanUids.intersect(uidsToBlockLan).size}")

            val lanStartTime = System.currentTimeMillis()
            if (uidsToRemoveLan.isNotEmpty() || uidsToAddLan.isNotEmpty()) {
                applyLanRulesBatch(uidsToAddLan, uidsToRemoveLan).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to apply batched LAN rules: ${error.message}")
                }
                AppLogger.d(TAG, "🔥 [TIMING] Batched LAN rules (unblock=${uidsToRemoveLan.size}, block=${uidsToAddLan.size}) took ${System.currentTimeMillis() - lanStartTime}ms")
            }

            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules COMPLETE: total=${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Final state: ${blockedUids.size} apps blocked (Internet), ${blockedLanUids.size} apps blocked (LAN)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply rules", e)
            val error = errorHandler.handleError(e, "apply iptables rules")
            Result.failure(error)
        }
    }
    
    override fun isActive(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            if (!isServiceRunning || backendType != "IPTABLES") {
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

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check if iptables firewall is active", e)
            false
        }
    }
    
    override fun getType(): FirewallBackendType = FirewallBackendType.IPTABLES
    
    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            val hasRoot = rootManager.hasRootPermission
            val hasShizuku = shizukuManager.hasShizukuPermission
            val hasAccess = hasRoot || hasShizuku

            if (!hasAccess) {
                val error = errorHandler.createRootRequiredError("iptables firewall")
                return Result.failure(error)
            }

            if (hasShizuku && !hasRoot) {
                val isRootMode = shizukuManager.isShizukuRootMode()

                if (!isRootMode) {
                    val error = errorHandler.createUnsupportedDeviceError(
                        operation = "iptables firewall",
                        reason = "Shizuku must be started with ROOT privileges (not ADB) to use iptables firewall"
                    )
                    return Result.failure(error)
                }
            }

            val (exitCode, _) = executeCommand("$IPTABLES --version")

            if (exitCode != 0) {
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "iptables firewall",
                    reason = "iptables not available on this device"
                )
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: java.util.concurrent.CancellationException) {
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check iptables availability", e)
            val error = errorHandler.handleError(e, "check iptables availability")
            Result.failure(error)
        }
    }
    
    /**
     * Create custom chains for rule isolation.
     * Only creates OUTPUT chain since owner module only works for OUTPUT.
     */
    private suspend fun createCustomChains(): Result<Unit> {
        return try {
            executeCommand("$IPTABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IPTABLES -I OUTPUT -j $CHAIN_OUTPUT")

            executeCommand("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IP6TABLES -I OUTPUT -j $CHAIN_OUTPUT")

            AppLogger.d(TAG, "Custom chains created successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create custom chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }
    
    private suspend fun deleteCustomChains(): Result<Unit> {
        return try {
            executeCommand("$IPTABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            executeCommand("$IP6TABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            AppLogger.d(TAG, "Custom chains deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete custom chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }
    
    /**
     * Block an app by UID.
     * Only blocks OUTPUT since owner module only works for locally generated packets.
     *
     * CRITICAL: This runs in NonCancellable context to prevent iptables commands from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * An interrupted iptables command could leave the firewall in an inconsistent state.
     */
    private suspend fun blockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking UID $uid ===")

            val ipv4Command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv4 command: $ipv4Command")
            val (ipv4ExitCode, ipv4Output) = executeCommand(ipv4Command)
            AppLogger.d(TAG, "IPv4 result: exitCode=$ipv4ExitCode, output='$ipv4Output'")

            val ipv6Command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv6 command: $ipv6Command")
            val (ipv6ExitCode, ipv6Output) = executeCommand(ipv6Command)
            AppLogger.d(TAG, "IPv6 result: exitCode=$ipv6ExitCode, output='$ipv6Output'")

            if (ipv4ExitCode == 0 && ipv6ExitCode == 0) {
                blockedUids.add(uid)
                AppLogger.d(TAG, "✅ Successfully blocked UID $uid (IPv4 and IPv6)")
            } else {
                AppLogger.e(TAG, "❌ Failed to block UID $uid - IPv4 exitCode=$ipv4ExitCode, IPv6 exitCode=$ipv6ExitCode")
                return@withContext Result.failure(Exception("Failed to block UID $uid"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block UID $uid", e)
            val error = errorHandler.handleError(e, "block app UID $uid")
            Result.failure(error)
        }
    }
    
    private suspend fun blockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking LAN for UID $uid ===")

            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                val command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv4 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv4 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv4 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv4 for UID $uid"))
                }
            }

            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                val command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv6 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv6 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv6 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv6 for UID $uid"))
                }
            }

            blockedLanUids.add(uid)
            AppLogger.d(TAG, "✅ Successfully blocked LAN for UID $uid (IPv4 and IPv6)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "block LAN for app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun unblockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            blockedUids.remove(uid)
            AppLogger.d(TAG, "Unblocked UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock UID $uid", e)
            val error = errorHandler.handleError(e, "unblock app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun unblockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            blockedLanUids.remove(uid)
            AppLogger.d(TAG, "Unblocked LAN for UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "unblock LAN for app UID $uid")
            Result.failure(error)
        }
    }

    private suspend fun clearAllRules(): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")

            blockedUids.clear()
            blockedLanUids.clear()
            AppLogger.d(TAG, "All rules cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear rules", e)
            val error = errorHandler.handleError(e, "clear iptables rules")
            Result.failure(error)
        }
    }
    
    private suspend fun applyRulesBatch(
        uidsToBlock: Set<Int>,
        uidsToUnblock: Set<Int>
    ): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val script = StringBuilder()

            for (uid in uidsToUnblock) {
                script.appendLine("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")
                script.appendLine("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")
            }

            for (uid in uidsToBlock) {
                script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP")
                script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP")
            }

            if (script.isNotEmpty()) {
                val (exitCode, output) = executeCommand(script.toString())
                if (exitCode != 0) {
                    AppLogger.w(TAG, "Batch rule script returned non-zero: exitCode=$exitCode, output=$output")
                    // Don't fail - some delete commands may fail if rule doesn't exist, that's OK
                }
            }

            blockedUids.removeAll(uidsToUnblock)
            blockedUids.addAll(uidsToBlock)

            AppLogger.d(TAG, "✅ Batched rules applied: blocked=${uidsToBlock.size}, unblocked=${uidsToUnblock.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply batched rules", e)
            val error = errorHandler.handleError(e, "apply batched iptables rules")
            Result.failure(error)
        }
    }

    private suspend fun applyLanRulesBatch(
        uidsToBlock: Set<Int>,
        uidsToUnblock: Set<Int>
    ): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            val script = StringBuilder()

            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")

            for (uid in uidsToUnblock) {
                for (range in ipv4Ranges) {
                    script.appendLine("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
                }
                for (range in ipv6Ranges) {
                    script.appendLine("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
                }
            }

            for (uid in uidsToBlock) {
                for (range in ipv4Ranges) {
                    script.appendLine("$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP")
                }
                for (range in ipv6Ranges) {
                    script.appendLine("$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP")
                }
            }

            if (script.isNotEmpty()) {
                val (exitCode, output) = executeCommand(script.toString())
                if (exitCode != 0) {
                    AppLogger.w(TAG, "Batch LAN rule script returned non-zero: exitCode=$exitCode, output=$output")
                }
            }

            blockedLanUids.removeAll(uidsToUnblock)
            blockedLanUids.addAll(uidsToBlock)

            AppLogger.d(TAG, "✅ Batched LAN rules applied: blocked=${uidsToBlock.size}, unblocked=${uidsToUnblock.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply batched LAN rules", e)
            val error = errorHandler.handleError(e, "apply batched LAN iptables rules")
            Result.failure(error)
        }
    }

    private suspend fun executeCommand(command: String): Pair<Int, String> {
        return if (rootManager.hasRootPermission) {
            rootManager.executeRootCommand(command)
        } else if (shizukuManager.hasShizukuPermission) {
            shizukuManager.executeShellCommand(command)
        } else {
            Pair(-1, "No root or Shizuku access")
        }
    }

    override fun supportsGranularControl(): Boolean = true

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isUidExempted(uid: Int, allPackages: List<android.content.pm.ApplicationInfo>): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        val packagesWithUid = allPackages.filter { it.uid == uid }

        return packagesWithUid.any { appInfo ->
            (!allowCritical && Constants.Firewall.isSystemCritical(appInfo.packageName)) ||
            (!allowCritical && hasVpnService(appInfo.packageName, appInfo.uid / 100000))
        }
    }
}

