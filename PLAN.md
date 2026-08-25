# De1984 — Open Findings

What is **still wrong or still undecided**. Closed findings are not here.

The full audit log — 3,527 lines, 44 fixes, 47 hardware verifications, every session from
2026-08-22 to 2026-08-24 — lives in git and nothing was lost:

```
git show f8f45d2:PLAN.md
```

**Trust order: current code and tests first, this file second.** Every claim below was true when
written. Verify against code before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NEEDS-RUNTIME` = needs a real device.
`INFERRED` = strongly implied by code, not directly observed.

Last distilled: 2026-08-25 · against v2.6.4 (versionCode 35)

---

# 1. Boot protection (P0-1)

Part A (self-healing script) and Part B (forced reboot on both toggles) are both implemented and
hardware-verified. What remains:

- **Lockout scenarios 4 and 5 are still live**, now bounded to ~120s by the self-heal timer rather
  than permanent. Scenario 5's switch is still hard-disabled and its help text tells the user to run
  `su`, which is exactly what they lost. No in-app recovery action exists there.
- `BootReceiver`'s boot coroutine has `try/finally` and **no catch**, so a throw from `startFirewall`
  skips the block clear entirely.
- `clearBootBlockIfInstalled` calls `forceRecheckRootStatus()` unconditionally; libsu is configured
  with a 30s timeout, inside a BroadcastReceiver's ~10s budget.
- Two expiry timers stack on a re-run and the earliest wins, silently shortening protection.
- `deleteBootScript` discards the teardown Result and returns success regardless.
- `LOCKED_BOOT_COMPLETED` does nothing in release: the receiver is `directBootAware` but
  `<application>` is not, and `AppLogger.init` touches CE storage.
- Clear-app-data with no root still shows "unavailable" rather than "stuck" — unfixable without root,
  since `/data/adb` cannot be read.
- `BootWorker:91-105` still has a preference-gated `resetIptablesPolicies()` that is redundant.
  Harmless and idempotent, but it is a second way to do the same thing. Not removed: in the case
  "preference true, script absent" the two differ, and that difference has not been reasoned through.

**The lock screen makes this worse.** The boot script runs at `post-fs-data`, before decryption. The
app's recovery runs on `BOOT_COMPLETED`, which on an encrypted device fires only **after the user
unlocks**. A phone that reboots overnight has no app network until morning; a user who cannot unlock
never recovers at all. The 120-second timer is the only backstop.

**Still open sub-decisions:**
- Scenario 5 — root lost, switch force-disabled. Does a recovery action there also reboot?
- Is a brief "Rebooting…" toast wanted, or does that count as a delay?

---

# 2. NetworkPolicyManager

## Uninstall leaves apps blocked permanently — CONFIRMED DEFECT, hardware

Blocked `com.aurora.store` (UID 10272), uninstalled De1984 **without stopping first**, rebooted.

| Moment | `netpolicy.xml` |
| --- | --- |
| Before anything | UID 10272 absent |
| Blocked, firewall running | `uid-policy uid="10272" policy="262144"` |
| After `adb uninstall` | still present |
| **After reboot** | **still present** |

**The stop path is NOT at fault.** Stopping normally restores correctly. The defect is precisely:
**stop cleans up, uninstall does not.** It is self-perpetuating — reinstall and De1984 reads the
current (blocked) state as that UID's "original" and preserves it from then on.

A `service.d` script mirroring the boot-protection pattern could fix it for root users only.
**Decision 2026-08-24 (Doru): do not build it now. Document the defect and warn the user instead.**
That warning now lives in FIREWALL.md, "What survives uninstalling De1984".

Manual recovery — `remove` alone is refused when the UID is not on that list, so it takes the pair:

```
adb shell cmd netpolicy add    restrict-background-blacklist <uid>
adb shell cmd netpolicy remove restrict-background-blacklist <uid>
```

## The degraded-blocking honesty gap — needs a product decision

On a ROM **without** `POLICY_REJECT_ALL` the backend degrades to metered-background-only and the UI
still says Blocked. The degradation is logged loudly and correctly, but nothing surfaces it to the
user. Options: warn on the backend picker and the firewall screen, refuse to run the backend at all,
or accept log-only. Not implemented. Documented in FIREWALL.md section 4 under "Known gap".

## Sweep can race an in-flight apply

`cleanupAllBackends()` can still race an in-flight `applyRules` for **iptables and
NetworkPolicyManager**. Fixed for ConnectivityManager by the process-wide mutex; the other two have
the same shape and pre-date that work. No `-w` on any command. A real fix needs the privileged
service to acknowledge the stop before the sweep begins.

---

# 3. Firewall health and error reporting

- **`FirewallHealth.Down` and `FirewallHealth.StopFailed` overwrite each other, and the order is
  racy.** Killing Shizuku produces both. Observed both orders across runs. They make contradictory
  claims: "your apps are unblocked" versus "some apps may still be blocked". For NetworkPolicyManager,
  whose policies persist in `/data/system/netpolicy.xml`, StopFailed is the more truthful of the two.
  Needs a precedence rule.
- **A silent unblocked state.** `SettingsViewModel.restartFirewallIfRunning` calls `stopFirewall()`
  then `startFirewall(newMode)`. A failure returns through `startFirewallInternal`, which sets
  `FirewallState.Error` but never publishes `FirewallHealth.Down` and never sets `_isFirewallDown`.
  The firewall is off, apps are unblocked, and only a Settings error string is shown. The comment
  above it — "FirewallManager will set isFirewallDown=true to track the error state" — is factually
  wrong.
- **`FirewallUiState.error` is written and never read.** No UI surface renders it. Either wire it or
  delete it; today it is a silent hole that makes "we set an error" look like "the user was told".
  (`SettingsUiState.error` does render.)
- **Scroll jump during a real backend failure on device.** Not the banner, not a state change.
  Suspect the work-profile package query failing while Shizuku is down. Needs a device repro.

---

# 4. Performance and architecture

- **The duplicate apply.** Two backend instances each run the full `applyRules` for the same rule
  change: two passes of ~16 s over 87 uids. Correct now that they share a lock, but it doubles the
  cost and is the reason the race existed. The real fix is one backend instance per process, which
  touches `FirewallManager` and `PrivilegedFirewallService` lifecycle.
- **Every rule change re-enumerates all 466 packages.** Recorded 2026-08-22, not fixed.
- **`clearInstalledAppsCache()` on the UI path defeats the firewall's cache.** Dropping it would let
  the cache survive, but **work-profile package events reach neither receiver**, so the UI's clear is
  currently the only thing that notices a work-profile install between TTL expiries. Removing it
  without replacing that coverage would be a correctness regression. A real fix needs work-profile
  aware invalidation.
- **`startMonitoring()` has zero callers**, and the `monitoringJob` / `ruleChangeMonitoringJob` pair
  exists only for it. `stopMonitoring()` still has 5 callers, so it stays either way. Roughly 35 lines
  of dead machinery in the file that runs the firewall. Not deleted: the demolition should be
  deliberate.
- Dead English constants in `Constants.BackendMonitoring` (`NOTIFICATION_TEXT_SUCCESS_*`,
  `TOAST_SUCCESS_*`, `NOTIFICATION_TITLE_SUCCESS`) — 0 usages.
- `commit()`'s return value is not checked in either durable write. Disk-full territory only.

---

# 5. Captive portal

**NOT FIXED — reinstall or Clear Data destroys the true original.** `KEY_ORIGINAL_CAPTURED` lives in
the app's own SharedPreferences and `allowBackup=false`. After a reinstall the flag is gone, so the
next capture records De1984's **own** current values as pristine.

The mitigation proposed earlier — refuse to capture when the current values match a De1984 preset —
**does not work**, and the test device proves why: its genuine ROM default is
`http://cp.cloudflare.com`, which *is* the CLOUDFLARE preset. Refusing would break legitimate first
capture on exactly the ROMs this app targets.

There is no reliable in-app fix: nothing the app owns survives uninstall, and writing a marker into
`Settings.Global` would add the very device-wide state the finding is about. The workable options are
UI, not logic — warn at capture time when the values match a preset, and let the user view and edit
the stored original. Both are new features; not implemented.

---

# 6. Multi-user and work profile

Settled as **best-effort**: not a guaranteed dimension, bugs there are real but not release blockers,
and the UI must not promise enforcement it cannot deliver.

- **`PackageAddedReceiver` never receives `PACKAGE_ADDED` on this ROM.** Reproduced twice with real
  uninstall + reinstall cycles. The system *did* broadcast it — another app logged it at the same
  instant. Manifest, `QUERY_ALL_PACKAGES` and process liveness all ruled out. **Root cause not
  established.** Worked around through `PackageChangedReceiver`, which is what re-points a reinstalled
  app's uid today.
- Work-profile package events reach neither receiver.
- `PackageMonitoringService.processNewPackage` computes `uid = userId * 100000 + 0` for work-profile
  apps, because `getApplicationInfoAsUser` returns null there and `appId` falls back to 0. Harmless
  today — `createDefaultFirewallRule` re-reads the uid itself — but the value is wrong and is passed
  around.
- `SmartPolicySwitchUseCase` matches critical packages by `packageName` alone, ignoring `userId`, so a
  VPN app present in two profiles gets one copy restored.
- ConnectivityManager's record is keyed by package name across profiles. **Left, decided:** the
  command only acts in the current user context, so cross-profile entries are phantoms and unblocking
  them is a no-op. Changing the on-disk format needs a migration riskier than the bug.

---

# 7. Widget and VPN permission

`VpnPermissionActivity` cannot be launched from `FirewallToggleReceiver`: Android 14 blocks it with
`BAL_BLOCK` (background activity launch). On a device needing VPN permission, the widget start
silently does nothing. Pre-existing — and it means the `VpnPermissionActivity` fixes made earlier are
correct but currently unreachable from the widget.

---

# 8. Backup and restore

**Restoring a backup chosen from the picker's SEARCH results fails:**

```
Failed to read backup file: com.android.externalstorage has no access to
content://media/external_primary/file/1000000143
```

Searching hands back a MediaStore URI the app cannot open; browsing to the file yields a
DocumentsProvider URI instead. **Which paths work is unconfirmed.** The app surfaces the error
clearly rather than failing silently, which is correct.

---

# 9. Not verifiable on the current test device

- `cmd connectivity set-chain3-enabled` is absent on this ROM, so the **ConnectivityManager backend
  cannot be exercised here at all**. Everything about it is code-review only.
- **M108 StopFailed.** Forcing a real teardown failure needs the backend to break while the process
  lives. Revoking the Shizuku permission force-stops the app. Code-verified only.
- **Ethernet mapping in `NetworkStateMonitor.networkTypeOf`** stays reasoned rather than proven —
  a consequence of the "no tests for now" decision, accepted knowingly.
- P0-3 deadlock in `handleBackendFailure` (needs root revoked mid-session).
- M098 IPv6 leak on the **VPN** backend specifically.
- M055 stale Shizuku granted state.
- Work-profile behaviour with apps actually present in user 10.
- The `StandardDialog` change touches 20 call sites; only the boot-protection dialog was hand-tested.
  The other switch dialogs and the package-management dialogs have not been re-tested.

---

# 10. Awaiting a decision

## Trade-offs already taken — Doru should confirm these stand

1. **Flipping the Default Policy now also resets per-app roaming and LAN.** `blockAllApps` /
   `allowAllApps` already overwrote every enabled rule's wifi and mobile; extending them to roaming
   and LAN is what closes M076 and follows from the Block All decision. Per-app roaming and LAN
   choices that used to survive a policy flip no longer do.
2. **A failed widget/tile start no longer arms automatic recovery.** It used to write
   `KEY_FIREWALL_ENABLED=true` even on failure. That write is also the M099 defect. The cost: granting
   Shizuku after a failed widget start no longer auto-starts the firewall; the user must tap again.
3. **The two non-granular backends block on ALL networks if a rule blocks on any.** Chosen 2026-08-24
   to close M094. The cost, accepted knowingly: switching FROM iptables or VPN TO one of these
   backends blocks more apps than before, because a per-network choice they cannot honour is resolved
   toward blocking. LAN is excluded from the predicate.

## Still unanswered

- **Tests and CI.** Zero test files under `app/src/test` and `app/src/androidTest`. Settled
  2026-08-24 as "no, not for now"; revisit before the next feature, not before the next bug fix.

---

# 11. Translations

- The 7 shipped locales were machine-translated and **need a native review**, especially the strings
  added during the firewall work.
- A large amount of user-facing copy lives hardcoded in `Constants.kt` and can never translate,
  despite shipping 7 locales.

---

# 12. Corrections to the record

**The backup is NOT lossy.** Earlier the backup JSON was reported as dropping `lanBlocked`,
`blockWhenBackground`, `userId` and `enabled`. **That was wrong, and it changed a decision.**
kotlinx.serialization omits any field equal to its default; the restore fills them back from the same
defaults. Proven by setting `lanBlocked` and `blockWhenBackground` to true on one rule and
re-exporting — both appeared in the JSON immediately.

Same failure mode as the M047 false finding: **absence was read as loss without checking whether the
value was simply the default.** Twice in one session.

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

**17 fixed · 2 were never defects · 3 still live · 4 unresolved.**

Five more were fixed on 2026-08-25: P1-2, P1-10, P1-11, P1-14, P1-22. See "Closed" below.

## Still live — 3

These three were left deliberately: each is a refactor of core firewall code with real regression
risk, not a contained fix.

| ID | Finding | Evidence today |
|---|---|---|
| P1-23 | **Nothing survives reinstall.** `allowBackup="false"`, `exportSchema = false`, `fallbackToDestructiveMigration()` with only two migrations written. Partly mitigated by the JSON rules backup, which is manual and opt-in. | `AndroidManifest.xml:49`, `De1984Database.kt:13`, `De1984Dependencies.kt:140-141` |
| P1-24 | **Partly fixed 2026-08-25.** The six public suspend entry points of `FirewallManager` (`startFirewall`, `stopFirewall`, `computeStartPlan`, `isIptablesAvailable`, `startVpnFallbackManually`, `checkBackendShouldSwitch`) now run on `Dispatchers.IO`; `MainActivity:873` reached `startFirewall()` from `lifecycleScope`, which is Main. **Still open:** the non-suspend `FirewallManager.isActive()` reaches `ActivityManager.getRunningServices` and is called on Main from `MainActivity:205` and `FirewallTileService:80`. Making it suspend changes its signature across the tile service, so it was left. Cold-start jank persists and its remaining source is **not attributed** — do not assume it is this. | `MainActivity.kt:205`, `FirewallTileService.kt:80` |
| P1-26 | **Backend switches are non-atomic outside `startFirewall`.** `restartFirewallIfRunning` stops then starts, so picking a backend in Settings unblocks every app in between. | `SettingsViewModel.kt:548` |

## Unresolved — 4, need a closer look than a grep

| ID | Finding | Why it is unresolved |
|---|---|---|
| P1-3 | Privilege-gain switch stops VPN first and on failure only logs | The surrounding code was heavily rewritten by the `reportStartFailure` work. Whether this specific exit now reports down was not established. |
| P1-9 | Batch confirmation counts only visible selections but uninstalls all selected | `PackagesFragmentViews.kt:1133` uses `selectedPackages.size`. Whether that set is the visible subset or the full selection needs the selection-mode code read end to end. |
| P1-13 | Failed rule writes revert by reloading, which may replay the mutated cache | `onFailure` calls `loadNetworkPackages()`. If that re-reads the repository the revert is correct; if it serves a cache the finding stands. Not settled. |
| P1-21 | Uninstall friction is inverted — batch-of-50 is one button, one ESSENTIAL app needs typing "UNINSTALL" | A product judgement as much as a defect. Needs a decision, not a grep. |

## Closed — 12 fixed

| ID | What fixed it |
|---|---|
| P1-2 | **Fixed 2026-08-25.** A `vpnIsHealthy()` guard runs `checkAvailability()` then `isActive()` before either VPN branch can increment the counter, and routes failure through the same `handleBackendFailure` path every other backend uses. |
| P1-10 | **Fixed 2026-08-25.** The empty result is cached only after `MAX_LOAD_ATTEMPTS` (3) consecutive failures, so one transient asset read no longer downgrades every package to UNKNOWN for the process. Verified on device: 4,983 packages loaded first try. |
| P1-11 | **Fixed 2026-08-25.** Work-only apps now get their real uid from a cached `pm list packages -U --user N`. When that cannot be answered the uid is `HiddenApiHelper.UID_UNKNOWN` (-1), which fails `isFirewallableAppUid`, so no backend writes a rule against a guessed number. Verified on device: 216 work-profile apps resolved, 0 fell back. |
| P1-14 | **Fixed 2026-08-25.** `stopFirewall()` records its teardown job and `onDestroy` waits up to 5s for it before cancelling the scope. `serviceScope` is on Dispatchers.IO, so the bounded wait cannot deadlock the main thread it blocks. |
| P1-22 | **Fixed 2026-08-25.** Import now excludes De1984 itself, system-critical packages and ESSENTIAL packages, and the confirmation dialog lists what it refused rather than dropping them silently. |
| P1-4 | `MainActivity.renderFirewallHealthBanner` + `FirewallHealthPresenter`. Health is rendered, and the banner is deliberately not dismissible. |
| P1-5 | `De1984Application.cleanupOrphanedFirewallRules()` sweeps orphaned rules when `KEY_FIREWALL_ENABLED` is false. **Partial:** it clears rules, does not stop the service and does not touch VPN. |
| P1-7 | `allowAllApps` / `blockAllApps` now set wifi + mobile + roaming + **lan**, with a comment stating the rule. |
| P1-12 | ConnectivityManager keys by `"$packageName:$userId"`. |
| P1-15 | `FirewallVpnService` returns `START_NOT_STICKY` on every failure path; `BackendMonitoringService.onStartCommand` uses `when (intent?.action)`, so a null redelivered intent no-ops instead of calling `stopSelf`. |
| P1-16 | `stopInternal` deletes the chains and then **asks the kernel** rather than trusting exit codes. Process-kill leftovers are covered by the startup sweep. |
| P1-17 | `stop()` is still fire-and-forget by design, but the real teardown now calls `reportTeardownFailure`, which raises `FirewallHealth.StopFailed`. The lie the finding described — "stopped successfully" over live DROP rules — is gone. |
| P1-18 | `stopInternal` calls `restoreBlockedPackages()`. |
| P1-19 | The tunnel adds `fd00:1984::2/64` and routes `::/0`. |
| P1-20 | `FirewallToggleReceiver` and `VpnPermissionActivity` both use `getCurrentMode()`, and write `KEY_FIREWALL_ENABLED=true` only on success. |
| P1-25 | `ErrorHandler.handleError` re-throws `CancellationException` before anything else, marked CRITICAL in a comment. |
| P1-27 | Network and screen monitoring moved into `PrivilegedFirewallService.startMonitoring()`, which runs for iptables. (`FirewallManager.startMonitoring()` is the one with zero callers — see section 4.) |

## Never defects — 2

- **P1-6 `migrateRulesToSimple` rewrites partial rules to block-all.** This is the documented,
  deliberate conservative rule for granular → simple transitions. "Switching back does not restore"
  is still true and still accepted. Recorded in FIREWALL.md section 3.
- **P1-8 "Block all networks leaves LAN open".** Two different operations were being compared.
  `updateAllNetworkBlocking` backs the **Internet Access** toggle — WiFi, Mobile, Roaming — and its
  DAO comment says so. **Block All** in the policy sense goes through `blockAllApps` /
  `FirewallRule.blockAll()`, which do include LAN. Consistent with the settled decision.

---

# The rest of the 2026-08-22 catalogue — NOT re-verified

## dev.sh traps

- `install` uninstalls the **production** package too, not just `.debug`. On a daily-driver phone this
  wipes real rules, and per P1-23 there is no backup. `dev.sh:328-345`
- `emulator` always passes `-wipe-data`, and `check_device` auto-calls it when nothing is attached —
  so a bare `./dev.sh install` can silently wipe the emulator. `dev.sh:222`
- `get_production_sha256` passes the store password on the `keytool` command line — briefly visible in
  `ps`. `dev.sh:788`
- `APP_VERSION` is grep/sed-parsed out of `app/build.gradle.kts`; a format change breaks every APK path.

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
- Room schema export off, `app/schemas/` empty — migrations cannot be reviewed or diffed.
- Production keystore + `keystore.properties` in the working tree. Gitignored and untracked, but one
  `git add -f` from exposure. Off-machine backup confirmed 2026-08-22.
- RULES.md is stale: claims ProGuard enabled (off), API 21 (min is 26), Compose UI (XML views only).

## Superseded

The old "P2 — FIREWALL.md: what to keep, change, and add" drift list is **removed as stale**.
FIREWALL.md was substantially revised on 2026-08-24 and 2026-08-25, including the NetworkPolicyManager
section that list asked for. Re-check it section by section against `Primary code paths` rather than
against that list.
