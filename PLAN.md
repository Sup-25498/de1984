# De1984 — Open Findings

What is **still wrong or still undecided**. Closed findings are not here.

**Trust order: current code and tests first, this file second.** Every claim below was true when
written. Verify against code before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NEEDS-RUNTIME` = needs a real device.
`INFERRED` = strongly implied by code, not directly observed.

Last distilled: 2026-08-25 · against v2.6.5 (versionCode 36) + 10 unreleased commits

Nothing is lost. The full 3,527-line audit log and every closed finding live in git:

```
git show f8f45d2:PLAN.md      # the original log
git log --follow -p PLAN.md   # what was removed, and when
```

---

# Open — 1

Everything else was closed on 2026-08-25 (see **Closed** at the end). This one was kept because it is
a live wrong answer from a function the firewall trusts.

## 1. `getInstalledApplicationsAsUser` is flaky for the work profile

Observed twice on 2026-08-25: `Profile 10 (Work): ... returned 0 packages` from the hidden API,
before a Shizuku fallback found 216.

Nothing is known to break today, but it is a **live wrong answer from a function the firewall
trusts**, and it is the root cause under two other entries:

- it is why `AndroidPackageDataSource.hasNetworkPermissions` must stay a per-package call and cannot
  read a whole-profile scan (see the comment there);
- it caps the `getPackageInfoAsUser` cache at a ~49% hit rate, because the UI sweep never caches
  work-profile packages it was told do not exist.

Work-profile package events also reach neither receiver, which is the same subsystem and is why
`clearInstalledAppsCache()` on the UI path cannot simply be dropped.

---

# Verified on hardware 2026-08-25 — scenario 5

Both halves of the boot-protection recovery have now executed on a real device.

**Failure path.** Staged by revoking De1984's Shizuku authorisation — root was already denied for the
debug build, so Shizuku running as root was the actual privilege source, and `pm revoke` does not
reach it because Shizuku holds its own list. With `bootProtectionAvailable=false` and the preference
true: the stuck row rendered with text that no longer says to run `su`; the "Try to remove" button
appeared and only there; it reused the disable warning, which already says the device will restart;
on Continue, `BootProtectionManager.kt:305` threw `NoPrivilegeException` and the user was told
*"Could not remove it. De1984 still has no root access… Restore root and try again."* **No `rm` ran
and no reboot happened.**

**Success path.** With the button already rendered, De1984's Magisk policy was flipped to allow while
the app was running — exactly the real case, where privilege was gone when the screen drew and back
by the time the button was pressed. On Continue:

```
Boot protection preference (true) disagreed with the script on disk (false) - trusting disk
✅ Boot protection removed on retry - restarting
BootProtectionManager: Rebooting device to apply boot protection change
```

The device rebooted (38 s uptime afterwards confirmed it), and prefs and rules were **identical to
the pre-test baseline**. `forceRecheckRootStatus()` recovering privilege at tap time is proven, and
so is the disk reconciliation trusting the disk over a stale preference.

**One caveat, honestly.** The "Restarting…" screen was **not** visually confirmed. Eight consecutive
screen dumps immediately after Continue all returned "device gone" — the reboot lands in well under a
second. The dialog is created and shown before `rebootDevice()` is called, but whether a user
actually sees it is unproven, and it may be too brief to serve its purpose.

---

# Known and accepted — not actionable

Kept because the knowledge is load-bearing, not because there is work to do.

- **NetworkPolicyManager leaves apps blocked permanently after an uninstall.** Confirmed on hardware:
  the policy lives in `/data/system/netpolicy.xml`, survives reboot, and Android never tells a package
  it is being removed. **Decided 2026-08-24: document, do not build the `service.d` cleaner.** The
  warning and the manual `cmd netpolicy` recovery are in FIREWALL.md, "What survives uninstalling".
- **Captive portal: reinstall or Clear Data destroys the true original.** `KEY_ORIGINAL_CAPTURED` lives
  in the app's own prefs with `allowBackup=false`. There is no reliable in-app fix — nothing the app
  owns survives uninstall, and a marker in `Settings.Global` would add the very device-wide state the
  finding is about. The preset-matching mitigation was tested and does not work: the test device's
  genuine ROM default *is* a De1984 preset.
- **`PackageAddedReceiver` never receives `PACKAGE_ADDED` on this ROM.** Reproduced twice; the system
  did broadcast it. Manifest, `QUERY_ALL_PACKAGES` and process liveness all ruled out. **Root cause
  not established.** This matters: the uid re-point that issue #81 depends on works *because*
  `PackageChangedReceiver` covers for it.
- **The ConnectivityManager backend cannot be exercised on the test device at all** —
  `cmd connectivity set-chain3-enabled` does not exist on that ROM. Everything about it is
  code-review only, including the issue #93 fix.
- **The widget/tile VPN permission path is only partly testable here.** The device has root, so the
  plan never selects VPN, and the receiver is `exported="false"` so adb cannot drive it. What WAS
  verified end to end on 2026-08-25: `VpnPermissionActivity` launches, reads `EXTRA_RESOLVED_MODE`,
  raises the system dialog, and handles a denial cleanly — logged "VPN permission denied", finished,
  firewall state untouched, 0 crashes. Unverified: the receiver → notification → tap hop, and the
  permission-granted branch.
- **M108 StopFailed** cannot be forced here: revoking the Shizuku permission force-stops the app.
- **The `StopFailed` over `Down` precedence cannot be forced here either** — it needs the privilege
  provider killed mid-run. No spurious suppression was logged across a full stop/start cycle.
- **Ethernet mapping in `NetworkStateMonitor.networkTypeOf`** stays reasoned, not proven — a
  consequence of the "no tests for now" decision.
- **The 7 locales are machine-translated and need a native review.** Not something to fix in code.
- **Clear-app-data with no root** shows "unavailable" rather than "stuck" — `/data/adb` is unreadable
  without root.
- **Two expiry timers stacking** is unproven: Magisk runs `post-fs-data.d` once and no double run has
  been observed. Left alone — that script can take a device off the network, so no speculative edits.

## Rules written in more than one place

Traps, not tasks. Nothing here is scheduled, but anything touching these areas should know the same
rule lives elsewhere too. One of them (the network-permission list) was found the hard way on
2026-08-25 and is now settled decision 20.

1. `userId = 0` is a **Kotlin default parameter** repeated across ~15 signatures. There is no
   multi-profile policy; every new call site silently targets the personal profile.
2. "Is this a VPN app" is re-implemented as a private `hasVpnService` in five files.
3. The screen-off rule is written out six times across four backends.
4. Roaming is derived on read but the flags are written independently by SQL — storage can hold a
   state the read model calls invalid.
5. Backend monitoring is wired three different ways for the same rules.
6. The two protection tiers are membership tests on two hardcoded sets, and consumers pick different
   sets for the same intent.
7. `KEY_FIREWALL_ENABLED` has four writers.
8. **CLOSED 2026-08-25.** "Has a network permission" was asked two ways: `AndroidPackageDataSource`
   checked three permissions inline while `Constants.Firewall.NETWORK_PERMISSIONS`, used by every
   firewall backend, holds five. An app requesting only `CHANGE_WIFI_STATE` or
   `CHANGE_NETWORK_STATE` was shown as having no network permission while the firewall applied a
   policy to it. Now reads the shared constant. Measured on hardware: **0 apps change** — nothing
   requests those two without one of the original three — so this prevents future divergence rather
   than fixing a visible bug.

---

# Reference — settled product decisions

1. **Boot protection stays**, with a self-healing script plus a forced reboot on both toggles.
   (2026-08-22)
2. **Work/clone profile is best-effort**, not a guaranteed dimension. (2026-08-23)
3. **"Block All" means WiFi + Mobile + Roaming + LAN.** Screen-off (`blockWhenBackground`) is
   deliberately excluded: it is a condition, not a network. (2026-08-23)
4. **Controls the active backend cannot enforce are HIDDEN**, not shown with an explanation.
   (2026-08-23)
5. **Rules are portable across devices, best-effort.** The app must re-resolve uid from packageName at
   restore time rather than trust the stored uid. (2026-08-23 — implemented 2026-08-25, issue #81)
6. **The captive-portal controller stays.** (2026-08-23)
7. **No tests or CI for now.** (2026-08-24)
8. **The degraded-blocking gap stays log-only.** (2026-08-25) On a ROM without `POLICY_REJECT_ALL`
   the backend falls back to metered-background-only and the UI still reads Blocked. The fallback is
   logged as a warning and nothing surfaces it. Accepted knowingly: the alternative is a warning on
   every ROM that cannot be detected up front, or refusing to run a backend that still blocks
   something. Documented in FIREWALL.md section 4 under "Known gap".
9. **`LOCKED_BOOT_COMPLETED` is not handled, deliberately.** (2026-08-25) Removed from the manifest
   and from `BootReceiver`, along with `directBootAware`. It could never do the job: the rules live
   in a Room database on credential-encrypted storage, so before unlock there is nothing to restore
   from, and the preferences and WorkManager's own database sit on the same storage. It was not
   merely inert — on a device with no lock screen credential, credential storage IS readable at that
   point, so both actions ran and the whole boot restore executed twice. The window before unlock
   stays covered by the boot script's own 120-second self-heal timer. Closing it properly means
   moving the rules database and the firewall preferences to device-protected storage.
10. **`StopFailed` outranks `Down` while the user's intent is OFF.** (2026-08-25) Killing Shizuku
    raises both and the last writer used to win, so the same failure showed a different banner from
    run to run. The tiebreak is `KEY_FIREWALL_ENABLED`, which is what separates the two states in the
    first place. With intent OFF, StopFailed is the truthful one — on iptables, ConnectivityManager
    and NetworkPolicyManager the rules outlive the backend that wrote them, so "apps are unblocked"
    is false there. Intent ON is deliberately not suppressed: that is the orphan-after-switch case,
    where losing the firewall IS the news. Implemented in `FirewallManager.reportFirewallDown`.

    Refined the same day after audit: a **start attempt** is not suppressed either
    (`afterStartAttempt = true` from `reportStartFailure`). That path only reaches the report after
    proving `currentBackend.isActive()` is false and nulling the refs, so the app has already decided
    nothing is enforcing; keeping "some apps may still be blocked" over that would contradict it.
    Without this, tapping ON after a failed stop and having the start fail left a "stuck" banner over
    a device where nothing was enforcing.

    Known residual, not worth a redesign: `handleVpnConflict` and `handleVpnConflictFallbackFailed`
    write `_firewallHealth` outside `startStopMutex`, so the read-then-write in the precedence check
    is not atomic. Pre-existing pattern — `clearHealthWarningIfEnforcing` has always done the same —
    and the window is microseconds against what used to be a permanent coin flip.
11. **All three privileged backends share a process-wide lock.** (2026-08-25) `iptables` and
    `NetworkPolicyManager` had per-instance mutexes, so `cleanupAllBackends()` could sweep while the
    privileged service was still inside `applyRules`. ConnectivityManager was moved to a companion
    object mutex when that was found; the other two were simply older than the fix and now match it.
    Verified on hardware: a stop with both new locks settled in 495 ms, and two concurrent applies
    serialised cleanly with 0 errors.
12. **Boot protection has an in-app recovery when root is lost.** (2026-08-25) Scenario 5's greyed-out
    row carries a "Try to remove" button. `BootProtectionManager.retryRemoveBootProtection()` re-asks
    Magisk for root and, if it comes back, removes the script through the existing `deleteBootScript()`
    — keeping that function's read-back check and live-chain teardown rather than being a second
    removal path. A dedicated `NoPrivilegeException` separates "root is really gone" from "the
    deletion went wrong". The help text no longer tells the user to run `su`, which is precisely what
    they lost. A successful removal restarts the device behind a non-cancellable "Restarting…" screen;
    the confirmation reuses the existing disable warning rather than adding a near-identical second
    dialog. Deleting the file does not clear the chain live in the current boot, and calling
    `resetIptablesPolicies()` separately would be a second way to undo one thing.
13. **The widget and tile reach the VPN dialog through a notification.** (2026-08-25) A
    `BroadcastReceiver` cannot open it: with targetSdk 34, Android 14 refuses the launch with
    `BAL_BLOCK`, so the tap did nothing at all. `FirewallToggleReceiver` now reports
    `Down(VPN_PERMISSION_REQUIRED)`, which raises the existing VPN fallback notification; tapping a
    notification is a gesture Android accepts. It runs on every Android version, not only 14+ —
    keeping the direct launch below 14 would be a second way to do one thing. Cost: one extra tap on
    older devices. Rejected: disabling the widget when VPN permission is missing, because the receiver
    only learns that after `computeStartPlan`, so the widget cannot know at draw time.

    Completed the same day: the notification for this case targets **`VpnPermissionActivity`**, not
    `MainActivity`, and carries the mode the receiver resolved. Routing it to MainActivity landed in
    `startVpnFallbackManually()` — the "a backend failed, fall back to VPN" flow — which publishes
    `SwitchedToVpn(failedBackend = VPN, fromManualMode = false)`, both hardcoded. That told the user
    "VPN failed, so we switched to VPN" when nothing had failed, called a manual VPN choice
    automatic, and discarded the receiver's AUTO fallback. The wording had the same fault: the shared
    text said "Privileged backend failed" to a user who never had one, so this case now has its own
    `vpn_permission_notification_*` strings in all 7 locales. `showVpnFallbackNotification` branches
    on the mode being non-null; the in-app fallback path is unchanged.
14. **Restore-from-search gets a way out, not a fix.** (2026-08-25) `SettingsViewModel.readFromUri`
    remaps a failure to OPEN a picked file into `error_backup_file_unreadable`, which tells the user
    to open the folder and choose the file there. The underlying failure is not ours: the picker
    contract is correct and the denial is inside the provider chain the picker chose, and no
    permission the app could hold changes it — a `.json` backup is not covered by `READ_MEDIA_*` on
    API 33+. Only the open is remapped; a failure part-way through reading keeps its own message.
15. **The four `dev.sh` traps are closed.** (2026-08-25) `install` no longer uninstalls the
    **production** package — it never needed to, the two application IDs coexist, and on a
    daily-driver phone it wiped the user's real firewall rules with no backup. `-wipe-data` is now
    opt-in via `./dev.sh emulator wipe`; `check_device` auto-starts an emulator whenever nothing is
    attached, so unconditional wiping meant a bare `./dev.sh install` could factory-reset it
    silently. The keystore password goes to `keytool` over stdin instead of argv, where `ps` could
    read it. `APP_VERSION` is validated and the script exits loudly if it cannot be parsed, rather
    than building paths from a wrong string.

    Fixed on audit the same day: the opt-in wipe used `"${wipe_args[@]}"` on an array that is EMPTY
    by default. macOS ships bash 3.2, where expanding an empty array under `set -u` aborts with
    "unbound variable" — so `./dev.sh emulator` would have failed outright on the common path. Now
    `${wipe_args[@]+"${wipe_args[@]}"}`, proven under 3.2.57 in both the empty and non-empty case.
19. **`getPackageInfoAsUser` is cached.** (2026-08-25) It was the most expensive call in the app and
    had no cache at all. Two independent sweeps made it for every installed package moments apart:
    `AndroidPackageDataSource.getPackageMetadataBatch` on a list load, and
    `getPackagesWithNetworkPermissions` when rules are applied.

    Cached in `HiddenApiHelper` by `"userId:flags:packageName"`, and the firewall's sweep now asks
    for the same flags as the UI's so they share entries. Caching the binder call rather than sharing
    a package list between the callers is deliberate: the two lists are **not the same set** and must
    not become one — the UI filters out De1984's own package via `Constants.App.isOwnApp` and the
    firewall does not. Every caller keeps its own filtering; only the round trip is shared.

    It has its own `PACKAGE_INFO_CACHE_TTL = 30s` rather than the 5s installed-apps window. Measured:
    at a cold start the two sweeps are **6.58 s** apart, so 5 s expired before the second could reuse
    anything; during a rule change they are 0.4 s apart. One constant cannot serve both. Longer is
    safe here because the cache is keyed by package name: a newly installed package is a new key,
    always a miss, always fetched fresh. Only an in-place permission change can go stale, and that
    means an app update, which fires `PackageChangedReceiver` -> `clearInstalledAppsCache()`.

    Hit/miss counters are reported once per sweep — a cache whose hit rate is invisible is one nobody
    can tell is broken.

    **Measured on hardware, cold start, three runs:**

    | | Before | After |
    |---|---|---|
    | network-permission sweep | 1,062 ms | **526 / 516 / 163 ms** |
    | `getPackageInfo` cache | 0 hit / 731 miss | **466 hit / 481 miss** |

    Roughly half the sweep, gone. The remaining misses are work-profile packages: when
    `getInstalledApplicationsAsUser` returns 0 for user 10 (see the known flakiness above) the UI
    sweep never caches them and the firewall pays full price.

18. **One rule-apply pass per start, not two.** (2026-08-25) `FirewallManager` applied on its own
    backend instance during the start, and `PrivilegedFirewallService` applied again on its own
    instance moments later - measured on hardware at ~1.0s and ~235-466ms writing identical
    policies. The service's pass came from `startMonitoring()` collecting the rules Flow and the
    state monitors: their FIRST emission is the current value, not a change, and FirewallManager had
    already written exactly those rules.

    So the service now records that first emission and does not act on it. The state fields are
    still written from it - skipping them would leave `currentNetworkType` at `NetworkType.NONE`,
    and `isBlockedOn(NONE)` blocks, which is the trap the removed "initial" schedule fell into.

    FirewallManager's pass was kept rather than the service's because it is the only one that can
    fail the start: `startFirewallInternal` returns `START_FAILED` if it throws, while the service's
    is fire-and-forget and never reaches the start result.

    Two things had to be fixed for this to be safe:

    - The **same-backend restart** branch of `startFirewallInternal` did not apply rules at all; it
      leaned on the service's startup pass. It now applies like every other start path, so the
      invariant the service depends on - FirewallManager has always applied by the time the service
      finishes starting - is true everywhere.
    - The rules-Flow collector was launched into `serviceScope` **untracked**, so `stopFirewall()`
      never cancelled it and every backend switch left another one alive. They collapsed onto one
      debounced apply so nothing visibly broke, but each leaked collector woke on every rule change
      for the life of the service. It now has its own Job, is cancelled with the others, and
      `startMonitoring()` cancels its own previous collectors so it cannot double up.

    Fixed on audit the same day: the new same-backend apply did not stop the backend when it
    failed, unlike the switch path above it. With the service no longer applying at startup, that
    left a live backend enforcing nothing for the session, and nothing would have written the rules
    until an unrelated change came along. It now calls `oldBackend.stop()` first, matching the
    established pattern - there is no old backend to fall back to on that path, old and new are the
    same one.

    Verified on hardware: a stop+start produced **1** completed pass, down from 2, with both
    collectors logging the skip; a real rule change (Aurora Store allow, then block) still applied
    exactly once and took effect - `policy=RESTORED` then `policy=BLOCK (REJECT_ALL)`. 0 crashes.
    Only the NetworkPolicyManager backend was exercised; the change itself lives in the shared
    service, not in any backend.

17. **The dead-code sweep is done.** (2026-08-25) Removed: 4 use cases wired in DI with zero
    callers, `wouldBackendChange`, `dismissVpnConflictNotification`, `showLicenses()`, the four
    write-only settings (auto-refresh, show system apps, dark theme, refresh interval — setters,
    state fields and preference reads, none of which any UI touched), the entire unused second
    navigation stack, and 143 unused resources: 106 strings across all 7 locales, 17 colours,
    4 colour state lists, 14 drawables and 2 layouts.

    Every entry was verified independently of lint, not just taken from its report. That caught two
    things in both directions. **Kept on purpose:** the 6 `dimen`s lint calls unused are OUR
    overrides of Material Components values — the library resolves them by name at runtime, so
    deleting them would have changed the bottom navigation. And `mipmap/ic_launcher_round` is a
    product call, not dead code: the manifest declares no `android:roundIcon`, so nothing uses it
    today, but adding one is a decision rather than a cleanup. **Deleted despite my own check
    saying otherwise:** `R.string.ok` and `R.string.cancel` — my grep matched
    `android.R.string.ok`, the framework's, not ours.

    Safe because nothing in this app resolves a resource by name at runtime: the only
    `getIdentifier` calls are `UserHandle.getIdentifier()`. Every reference is compile-checked, so
    the build linking cleanly is proof. Verified on hardware: all three tabs walked, 0 crashes,
    0 `NotFoundException`.

16. **`PrivilegedFirewallService` no longer schedules an "initial" rule application.** (2026-08-25)
    It never ran: `startMonitoring()` collects the Room rules Flow and the state monitors, all of
    which emit immediately and cancel the pending job inside its 300ms debounce — timed on hardware
    at 66ms and 106ms. It was not merely dead: `currentNetworkType` is still `NetworkType.NONE` at
    that point, and `isBlockedOn(NONE)` blocks, so on a device slow enough for the monitors to take
    over 300ms it applied a full over-block of every rule before correcting itself. The rules Flow
    emission is the guaranteed trigger.

# Reference — backend capability matrix

| Dimension | iptables | VPN | ConnectivityManager | NetworkPolicyManager |
|---|---|---|---|---|
| WiFi | yes | yes | approximate | no — metered data only |
| Mobile | yes | yes | approximate | yes, while metered |
| Roaming | yes | yes | approximate | approximate |
| Screen-off | yes | yes | yes | yes (native behaviour) |
| LAN | yes | **no** | **no** | **no** |

"approximate" = the backend re-reads the per-network rule on every network change, but the block it
installs applies to every network until the next recalculation.

`lanBlocked` is read in exactly one backend: `IptablesFirewallBackend`. On the other three the LAN
toggle is stored, shown, and never enforced.

---

---

# Closed 2026-08-25

Retired deliberately, not fixed. Each was judged not worth carrying: no observed failure, no
user-visible effect, or a settled decision already covers it. All recoverable from git history.

- **Lockout scenario 4.** Root not ready at boot, or a third-party VPN connected, so
  `startFirewall()` fails and the block stays. The app lifts it on that path, but lifting also needs
  root — when root is the missing thing, the script's own ~120-second timer is the only way out, and
  on an encrypted device the app's recovery does not run until the user unlocks. **Accepted
  2026-08-25:** the timer is the backstop and that is deemed sufficient.
- **No `-w` on any iptables command.** Real, but no such failure has ever appeared in a log, and
  adding it touches every command. `BootProtectionManager` already uses `-w 5` if it is ever needed.
- **Scroll jump during a backend failure.** Never reproduced. A suspicion, not a finding.
- **`commit()` return value unchecked** in both durable writes. Disk-full territory only.
- **`originalPolicyLock` is redundant** now that the backend mutex is process-wide. Kept on purpose:
  it enforces the invariant at the exact read-modify-write, on the one path that has already
  destroyed a real user policy.
- **`BootWorker`'s preference-gated `resetIptablesPolicies()`.** Harmless and idempotent.
- **"Restarting…" screen consistency.** The enable/disable toggles have no such screen; the
  scenario 5 recovery does. The toggles sit behind a warning the user has just read.
- **P1-24's remaining half.** `FirewallManager.isActive()` reaches `ActivityManager` on the main
  thread from `MainActivity:205` and `FirewallTileService:80`. Cold-start jank is **not attributed**
  to it, so the work could be done and change nothing.
- **Cross-cutting drift** — see *Known and accepted* below, kept as traps rather than tasks.
- **Project health**: no test source set and CI off are settled decision 7; release builds are
  unminified (R8) and F-Droid reproduces them as-is; the production keystore is gitignored,
  untracked and backed up off-machine.
