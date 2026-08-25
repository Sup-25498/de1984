# De1984 — Open Findings

What is **still wrong or still undecided**. Closed findings are not here.

Anything fixed, or decided and left alone, has been removed. Nothing is lost — the full 3,527-line
audit log and every closed finding live in git:

```
git show f8f45d2:PLAN.md      # the original log
git log --follow -p PLAN.md   # what was removed, and when
```

Settled decisions are not findings; they are kept under **Reference** below because future work needs
them.

**Trust order: current code and tests first, this file second.** Every claim below was true when
written. Verify against code before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NEEDS-RUNTIME` = needs a real device.
`INFERRED` = strongly implied by code, not directly observed.

Last distilled: 2026-08-25 · against v2.6.5 (versionCode 36)

---

# 1. Boot protection (P0-1)

Part A (self-healing script), Part B (forced reboot on both toggles) and the scenario 5 recovery are
implemented. What remains:

- **The scenario 5 recovery is NOT verified on hardware.** `NEEDS-RUNTIME`. Staging it needs boot
  protection installed *and* root revoked at the same time; the test device has root and no script,
  so the branch never renders there. Build, lint and the hidden-by-default state are verified. The
  removal itself, the "root is really gone" message and the restart screen are code-review only.
- **Lockout scenario 4 is still live**, bounded to ~120s by the self-heal timer rather than
  permanent. Trigger: root not ready at boot, or a third-party VPN connected, so `startFirewall()`
  fails. The app now lifts the block on that failure path — but lifting it *also* needs root, so
  when root is the thing that is missing, the script's own timer is the only way out.
- `clearBootBlockIfInstalled` calls `forceRecheckRootStatus()` when privilege is missing; libsu is
  configured with a 30s timeout, inside a BroadcastReceiver's ~10s budget. Not bounded: at boot the
  root wake genuinely can take seconds, and cutting it short would fail the lift it exists to do.
- `BootWorker:91-105` still has a preference-gated `resetIptablesPolicies()` that is redundant.
  Harmless and idempotent, but it is a second way to do the same thing. Not removed: in the case
  "preference true, script absent" the two differ, and that difference has not been reasoned through.

**The lock screen makes this worse.** The boot script runs at `post-fs-data`, before decryption. The
app's recovery runs on `BOOT_COMPLETED`, which on an encrypted device fires only **after the user
unlocks**. A phone that reboots overnight has no app network until morning; a user who cannot unlock
never recovers at all. The 120-second timer is the only backstop.

**Open decision.** The normal enable/disable toggles restart with no "Restarting…" screen; the
scenario 5 recovery has one. The toggles sit behind a warning dialog the user has just read, which is
why they were built that way. Making it one rule for all three would edit settled decision 1.

---

# 2. Firewall health and error reporting

- **Scroll jump during a real backend failure on device.** `NEEDS-RUNTIME`. Not the banner, not a
  state change. Suspect the work-profile package query failing while Shizuku is down. Needs a repro.
- **No `-w` on any iptables command.** `IptablesFirewallBackend` builds every command without the
  xtables lock-wait flag — verified 2026-08-25 across all of `createCustomChains`,
  `deleteCustomChains`, `blockApp`, `unblockApp` and the batch paths. The process-wide mutex added
  on 2026-08-25 fixes contention *inside* De1984 only; another app or the system touching iptables
  at the same moment still fails outright. **No such failure has been observed in any log**, so this
  is recorded, not fixed — adding `-w` touches every command and should be backed by evidence.
  (`BootProtectionManager` already uses `-w 5`, so the pattern exists if it is ever needed.)

---

# 3. Performance and architecture

- **The duplicate apply.** `NEEDS-RUNTIME` to change safely. Traced precisely on hardware
  2026-08-25, and the earlier note was only half right:

  1. `FirewallManager.applyRulesToBackend(newBackend)` runs on **FirewallManager's own** instance
     during `startFirewallInternal`.
  2. `PrivilegedFirewallService` applies on **its** instance, triggered by `startMonitoring()`
     collecting the Room rules Flow and the network/screen monitors — both emit immediately.

  Measured: pass 1 ~1.0s, pass 2 ~235-466ms, both writing the identical policies. The `initial`
  schedule that used to make it look like three was dead and has been removed (settled decision 15).

  **Why it has not been removed.** FirewallManager's pass is what makes a failed apply fail the
  start: `startFirewallInternal` returns `START_FAILED` if it throws. The service's pass is
  fire-and-forget and never reaches the start result. Dropping FirewallManager's pass would let a
  start report success over rules that were never written — the exact class of bug the health work
  has been removing. Dropping the service's is not possible either: its monitors emit on collect,
  which is them doing their job.

  **The real fix** is the ownership change: the service becomes the single backend owner, and the
  start path awaits its first apply result over the callback channel that already exists
  (`handleStopFailureFromService`, `handleBackendFailureFromService`). That needs a timeout policy on
  the start path, where both "treat a timeout as success" and "as failure" are wrong some of the
  time. It should be a focused session, and the ConnectivityManager half cannot be verified on the
  test device at all.
- **Every rule change re-enumerates all 466 packages.** Recorded 2026-08-22, not fixed.
- **`clearInstalledAppsCache()` on the UI path defeats the firewall's cache.** Dropping it would let
  the cache survive, but **work-profile package events reach neither receiver**, so the UI's clear is
  currently the only thing that notices a work-profile install between TTL expiries. Removing it
  without replacing that coverage would be a correctness regression. A real fix needs work-profile
  aware invalidation.
- `commit()`'s return value is not checked in either durable write. Disk-full territory only.
- **Follow-up candidate, not a defect:** `NetworkPolicyManagerFirewallBackend.originalPolicyLock` is
  now a second guard over the same window as the process-wide `mutex`. Kept because it enforces the
  invariant at the exact read-modify-write, and that path has already destroyed a real user policy
  once. Removing it is a cleanup.

---

# 4. Multi-user and work profile

Settled as **best-effort**: not a guaranteed dimension, bugs there are real but not release blockers,
and the UI must not promise enforcement it cannot deliver.

- Work-profile package events reach neither receiver.

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

# The 2026-08-22 P1 catalogue — RE-VERIFIED 2026-08-25

All 26 entries re-checked against code at v2.6.4 / versionCode 35. Line numbers from the original
audit had all shifted, so each was verified by pattern, not by line.

**20 fixed · 4 were never defects · 1 still live · 0 unresolved.** Closed entries are not listed;
`git show f8f45d2:PLAN.md` and the log hold them.

## Still live — 1

| ID | Finding | Evidence today |
|---|---|---|
| P1-24 | **Partly fixed 2026-08-25.** The six public suspend entry points of `FirewallManager` (`startFirewall`, `stopFirewall`, `computeStartPlan`, `isIptablesAvailable`, `startVpnFallbackManually`, `checkBackendShouldSwitch`) now run on `Dispatchers.IO`. **Still open:** the non-suspend `FirewallManager.isActive()` reaches `ActivityManager.getRunningServices` and is called on Main from `MainActivity:205` and `FirewallTileService:80`. Making it suspend changes its signature across the tile service, so it was left. Cold-start jank persists and its remaining source is **not attributed** — do not assume it is this. | `MainActivity.kt:205`, `FirewallTileService.kt:80` |

# The rest of the 2026-08-22 catalogue — NOT re-verified

## Built but unreachable

- Global `BlockAllAppsUseCase` / `AllowAllAppsUseCase` / `GetBlockedCountUseCase` /
  `GetFirewallRuleByPackageUseCase`: wired in DI, **zero UI callers**.
- The dead global bulk excludes the **soft** `SYSTEM_RECOMMENDED_ALLOW` tier but **not** the
  untouchable `SYSTEM_WHITELIST` — the two-tier model inverted. Wiring it up would block SystemUI.
- "Threats" filter + badge translated into all 7 languages, no Kotlin behind it.
- Four settings read at startup, never written by any UI: auto-refresh, show system apps, dark theme,
  refresh interval.
- "Open source licenses" returns a "coming soon" message from a function nothing calls.
- Entire second navigation stack unused: `activity_main.xml`, `nav_graph.xml`, `drawer_menu.xml`,
  `popup_menu.xml`, `activity_test_views.xml`.
- ~90 unreferenced translated strings, 11 unreferenced drawables.
- `wouldBackendChange`, `dismissVpnConflictNotification` — zero callers.

## Cross-cutting rules that can drift apart

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

## Project health

- **No test source set at all.** `app/src/` contains only `main`.
- CI disabled since 2025-11-04 — `.github/workflows/build.yml-temporary-disabled`.
- Release builds unminified and unshrunk (R8 issue).
- Production keystore + `keystore.properties` in the working tree. Gitignored and untracked, but one
  `git add -f` from exposure. Off-machine backup confirmed 2026-08-22.
- RULES.md is stale: claims ProGuard enabled (off), API 21 (min is 26), Compose UI (XML views only).
