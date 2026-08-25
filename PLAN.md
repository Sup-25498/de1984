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

Last distilled: 2026-08-25 · against v2.6.4 (versionCode 35)

---

# 1. Boot protection (P0-1)

Part A (self-healing script) and Part B (forced reboot on both toggles) are both implemented and
hardware-verified. What remains:

- **Lockout scenarios 4 and 5 are still live**, now bounded to ~120s by the self-heal timer rather
  than permanent. Scenario 5's switch is still hard-disabled and its help text tells the user to run
  `su`, which is exactly what they lost. No in-app recovery action exists there.
- `clearBootBlockIfInstalled` calls `forceRecheckRootStatus()` when privilege is missing; libsu is
  configured with a 30s timeout, inside a BroadcastReceiver's ~10s budget. Not bounded: at boot the
  root wake genuinely can take seconds, and cutting it short would fail the lift it exists to do.
- `LOCKED_BOOT_COMPLETED` does nothing in release: the receiver is `directBootAware` but
  `<application>` is not, and `AppLogger.init` touches CE storage.
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
- `commit()`'s return value is not checked in either durable write. Disk-full territory only.

---

# 5. Multi-user and work profile

Settled as **best-effort**: not a guaranteed dimension, bugs there are real but not release blockers,
and the UI must not promise enforcement it cannot deliver.

- Work-profile package events reach neither receiver.
---

# 6. Widget and VPN permission

`VpnPermissionActivity` cannot be launched from `FirewallToggleReceiver`: Android 14 blocks it with
`BAL_BLOCK` (background activity launch). On a device needing VPN permission, the widget start
silently does nothing. Pre-existing — and it means the `VpnPermissionActivity` fixes made earlier are
correct but currently unreachable from the widget.

---

# 7. Backup and restore

**Restoring a backup chosen from the picker's SEARCH results fails:**

```
Failed to read backup file: com.android.externalstorage has no access to
content://media/external_primary/file/1000000143
```

Searching hands back a MediaStore URI the app cannot open; browsing to the file yields a
DocumentsProvider URI instead. **Which paths work is unconfirmed.** The app surfaces the error
clearly rather than failing silently, which is correct.

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
- **M108 StopFailed** cannot be forced here: revoking the Shizuku permission force-stops the app.
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
| P1-24 | **Partly fixed 2026-08-25.** The six public suspend entry points of `FirewallManager` (`startFirewall`, `stopFirewall`, `computeStartPlan`, `isIptablesAvailable`, `startVpnFallbackManually`, `checkBackendShouldSwitch`) now run on `Dispatchers.IO`; `MainActivity:873` reached `startFirewall()` from `lifecycleScope`, which is Main. **Still open:** the non-suspend `FirewallManager.isActive()` reaches `ActivityManager.getRunningServices` and is called on Main from `MainActivity:205` and `FirewallTileService:80`. Making it suspend changes its signature across the tile service, so it was left. Cold-start jank persists and its remaining source is **not attributed** — do not assume it is this. | `MainActivity.kt:205`, `FirewallTileService.kt:80` |

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
- Production keystore + `keystore.properties` in the working tree. Gitignored and untracked, but one
  `git add -f` from exposure. Off-machine backup confirmed 2026-08-22.
- RULES.md is stale: claims ProGuard enabled (off), API 21 (min is 26), Compose UI (XML views only).
