# De1984 — Findings & Fix Plan

Discovery log. **Nothing here is fixed.** Each item is to be re-evaluated before any change.

Coverage: all 87 Kotlin files read end to end (verified by set-difference against `find`), all 122 XML
resources, the manifest, all 7 locales, `dev.sh` in full, every Gradle/CI file, `FIREWALL.md` in full,
and 5 real user bug-report logs. Plus a 3-agent adversarial pass on boot protection and orphan state.

Totals returned: **43 critical · 124 high · 117 medium · 10 low** (some readers hit an output cap, so
these are floors, not ceilings).

Status key: `VERIFIED` = read in code, line cited. `NEEDS-RUNTIME` = needs a real device.
`INFERRED` = strongly implied by code, not directly observed.

Last updated: 2026-08-22 · against v2.6.2 (versionCode 33), commit `baf6b4e`

---

# P0 — Can leave a device or the project permanently broken

## P0-1 Boot protection: five ways to permanently kill app networking — VERIFIED

**What is written:** `/data/adb/post-fs-data.d/de1984_boot_protection.sh`, mode 755, outside the app
sandbox. Root only. `data/common/BootProtectionManager.kt:135`, path at `utils/Constants.kt:140`

**What it does every boot:** creates iptables + ip6tables chain `de1984_boot`; ACCEPTs loopback and
uids 0/1000/1010/1016/1051; `DROP`s everything else; inserts itself at the head of `OUTPUT`.
`data/common/BootProtectionManager.kt:96-131`

**Only remover:** `resetIptablesPolicies()` (`:190-224`), two callers only (`BootReceiver.kt:174`,
`BootWorker.kt:97`), both requiring ALL THREE: pref `firewall_enabled` true, `startFirewall()` success,
pref `boot_protection` true.

| # | Trigger | Outcome | Status |
|---|---|---|---|
| 1 | Firewall OFF + boot protection ON + reboot | Boot restore returns at `BootReceiver.kt:124` / `BootWorker.kt:48-51` before the reset. All user apps offline every boot. | VERIFIED |
| 2 | Uninstall De1984 | No uninstall hook exists anywhere; no Magisk module, no `uninstall.sh`. Script runs forever. | VERIFIED |
| 3 | Clear app data | `boot_protection` pref → false (`Constants.kt:128`); reset skipped, script still runs. | VERIFIED |
| 4 | Root not ready at boot, or third-party VPN connected | `startFirewall()` fails → reset never runs. | VERIFIED |
| 5 | Root lost after enabling | `bootProtectionAvailable=false` forces the switch `isChecked=false, isEnabled=false`. User can never turn it off. | VERIFIED |

**The app's own recovery advice is half-broken.** `res/values/strings.xml:773` (the enable-warning
dialog) offers two routes. Route 1 "Settings → Disable Boot Protection" is exactly the control that is
force-disabled in scenario 5 — `ui/settings/SettingsFragmentViews.kt:504-508`. Route 2 gives correct
ADB+root commands, but needs a PC and root, and is only shown once, before enabling.

**Turning the setting OFF does not unblock the running device.** `setBootProtection(false)` calls
`deleteBootScript()` — an `rm -f` only. `resetIptablesPolicies()` is not on that path. The live chain
survives until reboot. `data/common/BootProtectionManager.kt:165`

**`resetIptablesPolicies()` always returns success** even when nothing ran. `executeCommand` returns
`(-1, "No root or Shizuku access")` at `:239`; exit codes at `:197-219` are logged and discarded; `:222`
returns `Result.success`. Logs claim the chain was removed while the device is still blocked.

**Removal is single-shot and unverified.** The script's `iptables -I OUTPUT` is unguarded and can link
twice; the reset issues exactly one `-D` per family, every command ends in `|| true`.

**`isBootProtectionEnabled()` reads the real on-disk truth and has ZERO callers.** Nothing ever
reconciles pref against disk. `data/common/BootProtectionManager.kt:46`

**Allow-list problems.** Exempt uids are 0/1000/1010/1016/1051 + loopback only.
- `AID_RADIO` (1001) and the networkstack uid are absent → cellular data setup and connectivity
  validation are dropped during the boot window.
- uid 2000 (adb shell) is dropped → **wireless ADB recovery does not work** while the chain is live.
- De1984's own uid and Shizuku's uid are dropped by the chain De1984 installs.
- Comments are wrong: 1016 is `AID_VPN` (media is 1013); 1051 is `AID_DNS` (gps is 1021).

**Enable path leaves an orphan on partial failure.** `createBootScript` writes with a truncating `echo`
redirect (`:135`), never reads the file back, and on `chmod` failure returns failure **without deleting
the file** (`:147-154`). Pref stays false, UI shows OFF, script sits on disk.

**NEEDS-RUNTIME:** does `netd` flush `OUTPUT` later in boot? `post-fs-data` runs before `netd`. If netd
rebuilds the filter table without `--noflush`, the chain is destroyed early — protection is largely
inert AND the lockout never happens. Nothing in the repo settles this. **This single test decides how
severe P0-1 really is.**

**Manual recovery (root + PC required):**
```
adb shell
su
rm -f /data/adb/post-fs-data.d/de1984_boot_protection.sh
for t in iptables ip6tables; do
  $t -D OUTPUT -j de1984_boot 2>/dev/null
  $t -F de1984_boot 2>/dev/null
  $t -X de1984_boot 2>/dev/null
done
reboot
```

## P0-1 DECIDED FIX DIRECTION (Doru, 2026-08-22)

**Chosen: self-healing script AND forced reboot on both toggles.**
Rationale given: maximum certainty that on-disk state and live state always agree.
Trade-off accepted knowingly: users lose unsaved work, calls and downloads on every toggle.
(I recommended self-healing without a forced reboot; Doru chose the stronger option.)

### Part A — make the artifact self-healing (does the heavy lifting)
1. **Self-expire inside the script.** Background a subshell that tears the chain down after ~120s
   regardless of what happens to the app. De1984 may still remove it sooner.
   Kills lockout scenarios 1, 2, 3, 4 and 5.
   `NEEDS-RUNTIME:` confirm a backgrounded subshell survives Magisk `post-fs-data` mode. If it does
   not, move the expiry job to a `service.d` script or an init property trigger.
2. **Self-delete if the APK is gone.** First line of the script:
   `[ -d /data/data/io.github.dorumrr.de1984 ] || { rm -f "$0"; exit 0; }` — kills scenario 2.
3. **Move the reset out of `startFirewall().onSuccess`.** Rule becomes: if the chain exists, remove it —
   independent of `firewall_enabled`, `boot_protection` and the start result. Kills 1, 3, 4.
4. **Read the disk, not the pref.** Wire up `isBootProtectionEnabled()` (currently zero callers) so the
   switch can never disagree with `/data/adb/post-fs-data.d/de1984_boot_protection.sh`.
5. **Call `resetIptablesPolicies()` on the disable path** (today it is `rm -f` only).
6. **In-app recovery action** that runs the teardown even when the switch is greyed out (scenario 5).
7. **Verify the write.** Read the script back after `echo`, and delete the orphan if `chmod` fails.
8. **Fix the allow-list.** Add `AID_RADIO` (1001) and the networkstack uid; decide on uid 2000 (adb),
   De1984's own uid and Shizuku's uid. Correct the wrong comments (1016 is AID_VPN, 1051 is AID_DNS).
9. **Make chain linking idempotent.** Guard `iptables -I OUTPUT -j de1984_boot` with a `-C` check, and
   make the reset loop until no jump remains instead of issuing a single `-D`.
10. **Stop returning success when nothing ran.** `resetIptablesPolicies()` must branch on exit codes and
    on the no-privilege `(-1, ...)` case.

### Part B — forced reboot on both toggles (decided)
- **Enable:** write script → read back and verify content → `chmod 755` → verify → then reboot.
- **Disable:** `rm -f` script → verify gone → remove the live chain → then reboot.
- Reboot via root, which is always present when this feature is available: `su -c "svc power reboot"`.
- Never reboot if the preceding step failed. A failed write must not be followed by a reboot.

### Sub-decisions still open (to settle before implementing Part B)
- Does the user get a confirm dialog before the reboot, or is it truly immediate?
- If a confirm is shown and the user cancels, does the toggle revert (keeping disk and live state in
  agreement), or does the change stand and apply at the next natural reboot?
- What happens on the recovery action in scenario 5, where root exists but the switch is greyed out —
  does that path also reboot?
- Should Part A alone ship first, so devices already in the field self-heal before Part B lands?


## P0-2 NetworkPolicyManager blocks survive stop, reboot AND uninstall — VERIFIED

`stopInternal` clears only the in-memory `appliedPolicies` map; `setUidPolicy(uid, POLICY_REJECT_*)`
written at `:333` is never set back to `POLICY_NONE` anywhere in the tree.
`data/firewall/NetworkPolicyManagerFirewallBackend.kt:145-165`

Android persists uid policies in `/data/system/netpolicy.xml`. So blocked apps stay blocked forever
after the firewall is stopped or De1984 is uninstalled. Reboot does not help. **This needs only
Shizuku — no root — so it is easier to hit than P0-1.** The map holds exactly the uids changed, so
cleanup is possible and simply not done.

`cleanupAllBackends` only instantiates `IptablesFirewallBackend`, and its comment at
`data/firewall/FirewallManager.kt:668-671` falsely asserts the other backends leave no persistent
state. NPM is not mentioned at all.

## P0-3 Deadlock in the firewall failure-recovery path — VERIFIED

`handleBackendFailure` runs inside `startStopMutex.withLock` (`FirewallManager.kt:1107`) then calls
`startFirewall` (`:1163`, `:1226`), which takes the same non-reentrant `kotlinx.coroutines.Mutex`
(`:362`). The coroutine suspends forever **holding the lock**, so every later start/stop/toggle also
hangs for the rest of the process.

Matches user reports "Firewall Not Running But The Switch Was On" and "Not Responding" in
`/Users/doru/dev/phi/de1984-feedback-archive/`.

## P0-4 Any installed app can permanently disable the firewall — VERIFIED

`ui/widget/FirewallWidget.kt:96` writes `KEY_FIREWALL_ENABLED` straight from a broadcast extra. The
receiver is `android:exported="true"` on a custom, **unprotected** action
`io.github.dorumrr.de1984.FIREWALL_STATE_CHANGED` (`AndroidManifest.xml:225-230`).

Any app can broadcast a "Stopped" state → the flag is set false → `BootReceiver`/`BootWorker` skip
restoration at next boot → **the firewall silently never comes back**. Combined with P0-1 scenario 1,
a third-party app can put the device into the permanent-no-network state.

## P0-5 Command injection into a root shell from the captive-portal URL field — VERIFIED

`data/common/CaptivePortalManager.kt:368` builds `settings put global $key "$value"` and runs it via
`rootManager.executeRootCommand` or Shizuku. `isValidUrl` (`:384-399`) checks only the `http://` /
`https://` prefix and a non-empty host. A URL containing `$(...)` or backticks is expanded **as root**.
Self-inflicted, but a pasted "recommended settings" string from a forum is a realistic vector.

## P0-6 Captive-portal changes outlive the app, and reinstall destroys the real original — VERIFIED

`setSystemSetting` writes device-wide `Settings.Global` (`:113,:366-379`) while the only backup lives in
the app's own SharedPreferences (`:122-133`). Uninstall or Clear Data destroys the backup; the system
keeps the modified values. A user left on `mode=IGNORE` or a dead custom URL gets permanent
"no internet" WiFi with no in-app way back.

Worse: `captureOriginalSettings` skips only when its flag exists (`:115-118`). After a reinstall the
flag is gone, so it **stores the already-modified state as pristine**. `restoreOriginalSettings` then
restores the damage. The true original is unrecoverable.

Also: `restoreOriginalSettings` uses `httpUrl?.let` (`:266,:274`) and never issues
`settings delete global`, so on a device where the key was never set, De1984's URL is left in place
while the call reports success.

## P0-7 `./dev.sh create-keystore` run twice destroys your release signing key — VERIFIED

`dev.sh:654-655` backs up to `${KEYSTORE_PATH}.backup` before overwriting. A **second** run overwrites
that backup with the first run's replacement key. The original is gone.

Losing `release-keystore.jks` means **no further De1984 updates can ever be published** — F-Droid and
IzzyOnDroid users would need a manual uninstall/reinstall.

**Off-machine backup CONFIRMED by Doru, 2026-08-22.** Severity drops from critical to a dev-workflow
warning: `create-keystore` should refuse to overwrite an existing `.backup`, or timestamp it.

---

# P1 — Wrong results the user will notice

| ID | Finding | Evidence | Status |
|---|---|---|---|
| P1-1 | `currentBackend` never assigned for NetworkPolicyManager — bare expression, no `=`. State says Running, manager holds nothing: health monitoring, applyRules and stop all no-op; `supportsGranularControl()` falls back to true so the granular sheet is shown for an all-or-nothing backend. | `FirewallManager.kt:229` | VERIFIED |
| P1-2 | Manual VPN mode health check never verifies anything — it increments the success counter without calling `isActive()`. A killed VPN service is never detected. | `FirewallManager.kt:984-988` | VERIFIED |
| P1-3 | Privilege-gain switch stops VPN first, and on failure only logs: `isFirewallDown` stays false, no notification, no broadcast, and `break` ends health monitoring. Firewall off, silently, no recovery. | `FirewallManager.kt:1009-1022` | VERIFIED |
| P1-4 | `Error` state has **no UI consumer at all**. `firewallState`, `isFirewallDown`, `backendHealthWarning` are read by nothing. The user cannot tell "I turned it off" from "protection collapsed". | `MainActivity.kt`, `FirewallViewModel.kt:123-132` | VERIFIED |
| P1-5 | Init Case C not implemented: a detected running backend is adopted as Running without ever reading `KEY_FIREWALL_ENABLED`. Orphaned services keep enforcing while the user believes the firewall is off. | `FirewallManager.kt:167-243` | VERIFIED |
| P1-6 | `migrateRulesToSimple` permanently rewrites partial rules to block-all on any granular→simple switch, including automatic privilege-loss fallback. Switching back does not restore. | `FirewallManager.kt:843-877`, trigger `:475` | VERIFIED |
| P1-7 | Bulk "Allow All" clears wifi+mobile but not roaming; single-app `allowAll()` clears all three. Roaming is derived as `roaming OR mobile`. | `FirewallRuleDao.kt:95` vs `FirewallRule.kt:43,66` | VERIFIED |
| P1-8 | "Block all networks" leaves LAN open — `setAllNetworkBlocking` creates the rule without `lanBlocked`. | `AndroidPackageDataSource.kt:1301` | VERIFIED |
| P1-9 | Batch confirmation counts only **visible** selections but uninstalls **every** selected package. User is told "3", loses more. | `PackagesFragmentViews.kt:1253,1294,1302` | INFERRED |
| P1-10 | Package safety DB fails open and caches the empty result **permanently**. One parse error → every app becomes unknown → all uninstall rails silently downgrade. | `PackageSafetyLoader.kt:55-66` | VERIFIED |
| P1-11 | Work-only apps get a UID fabricated from `packageName.hashCode()`, then fed to the firewall backends as a real uid. | `HiddenApiHelper.kt:438` | INFERRED |
| P1-12 | ConnectivityManager backend keys policy by package name only, ignoring `userId`. | `ConnectivityManagerFirewallBackend.kt:296` | VERIFIED |
| P1-13 | Failed rule writes are never reverted — the "revert by reloading" path replays the already-mutated cache. | `FirewallViewModel.kt:315` | VERIFIED |
| P1-14 | `PrivilegedFirewallService.onDestroy` cancels the coroutine that tears iptables down, leaving DROP rules on the device. | `PrivilegedFirewallService.kt:169` | INFERRED |
| P1-15 | Both services return `START_STICKY` but `stopSelf` on the null redelivered intent — after a process kill the firewall fails open. | `FirewallVpnService.kt:140`, `BackendMonitoringService.kt:81-100` | INFERRED |
| P1-16 | iptables DROP rules accumulate across restarts: `blockedUids` is per-instance, `startInternal` never flushes the chain. An app shown Allowed can stay blocked by a duplicate rule. | `IptablesFirewallBackend.kt:91,500,712` | INFERRED |
| P1-17 | iptables `stop()` is fire-and-forget `startService` returning success unconditionally. If the service is dead, DROP rules stay while the log says "stopped successfully". | `IptablesFirewallBackend.kt:118-138` | INFERRED |
| P1-18 | ConnectivityManager per-package denies are never reverted on stop — only the global chain is switched off and the cache cleared. | `ConnectivityManagerFirewallBackend.kt:137-150,322-324` | VERIFIED |
| P1-19 | VPN tunnel is IPv4-only (address `10.0.0.2/24`, route `0.0.0.0/0`, no IPv6). Blocked apps reach the network over IPv6. | `FirewallVpnService.kt:619-624` | VERIFIED |
| P1-20 | Widget/tile toggle always starts `FirewallMode.AUTO`, ignoring a sticky manual choice, and marks the firewall enabled even when the start failed. | `FirewallToggleReceiver.kt`, `VpnPermissionActivity.kt:76-82` | VERIFIED |
| P1-21 | User apps uninstall permanently with one tap; the Uninstalled filter and Reinstall are gated on `type == SYSTEM`. Batch-of-50 is one ordinary button while one ESSENTIAL app requires typing "UNINSTALL". Friction is inverted. | `PackagesViewModel.kt:195`, `PackagesFragmentViews.kt:1122-1129,1292-1306,1054-1069` | VERIFIED |
| P1-22 | Settings "import uninstalled apps" applies **no criticality check** — filters only on "is installed", forces `userId=0`, batch-uninstalls. | `SettingsViewModel.kt:888-892,948-977` | VERIFIED |
| P1-23 | Nothing survives reinstall. `allowBackup=false`, both backup XMLs exclude everything, Room destructive fallback with only 4→5 and 5→6 written, schema export off and `app/schemas/` empty. | `AndroidManifest.xml:49`, `De1984Dependencies.kt:103-176` | VERIFIED |
| P1-24 | All backend work runs on the caller's dispatcher — no `withContext` anywhere. UI callers use `lifecycleScope`, so `su` probes, iptables `isActive()`, `VpnService.prepare` and Room queries run on the main thread on every `onResume`. | `FirewallManager.kt:362,2320`; `MainActivity.kt:255,284,825,857` | VERIFIED |
| P1-25 | `catch (e: Exception)` swallows `CancellationException` in six places, converting a cancelled switch into a failure **after** the new backend already started. | `FirewallManager.kt:580,630,828,885,1080,1760` | INFERRED |
| P1-26 | Backend switches are non-atomic outside `startFirewall`: privilege change stops old first (code comment admits a 1–2s gap); `restartFirewallIfRunning` does stop + `delay(500)` + start, so picking a backend in Settings unblocks every app for at least half a second. | `FirewallManager.kt:2244-2258`, `SettingsViewModel.kt:557` | VERIFIED |
| P1-27 | Under iptables, state monitoring never starts, so `currentNetworkType` stays `NONE` and `isScreenOn` stays true for the whole session. Network-conditional rules are evaluated against a network the device is never on. | `FirewallManager.kt:141-142,207,565-572,1754` | INFERRED |

---

# P2 — FIREWALL.md: what to keep, change, and add

## Keep (CONFIRMED against code)
AUTO priority iptables > CM > VPN · sticky manual mode · atomic start-new-then-stop-old **inside
`startFirewall` only** · fallback on first health-check failure with no retry of the failed backend ·
VPN inverted `addAllowedApplication` logic · zero-blocked-apps means no tunnel raised ·
establish-new-before-closing-old on network change · iptables OUTPUT-only custom chain `de1984_output`
for v4+v6 with diff-based batched updates · shared-UID exemption for system-critical and VPN apps ·
CM requires Shizuku + Android 13 and is all-or-nothing, with the UI swapping to a simple sheet ·
states Stopped/Starting/Running/Error · transitions Stopped→Starting→Running, Starting→Error,
Running→Stopped, Error→Starting, Error→Stopped · the four persistence keys · detection order
VPN→iptables→CM→NPM with 5 attempts · Cases A, B, D.

## Change (doc is wrong)
| Doc says | Code does |
|---|---|
| Health/privilege ladder 1s→5s→10s→30s, reset to 1s | Two tiers: 15s initial, 60s after 10 successes; failure resets to 15s — `Constants.kt:180-182` |
| Root check `su -c id` with 3s timeout | libsu on a **cached** shell running `id`, no timeout — `RootManager.kt:104-140` |
| iptables availability = `iptables -L` | `iptables --version`, **plus** it requires Shizuku in root mode when no root — `IptablesFirewallBackend.kt:456-470` |
| Dropdown shows only available backends | All 5 always listed; unavailable ones greyed with a reason dialog — `SettingsFragmentViews.kt:598-615` |
| Toggle ON only when Running | ON for Running **or** Starting — `FirewallViewModel.kt:128` |
| Toggle disabled during Starting/Switching | Never disabled; the switch stays live |
| "Firewall not running" error indicator in UI | No UI reads `Error`/`isFirewallDown`/`backendHealthWarning`. Only ACTIVE and OFF badges exist |
| Fallback notification persistent until resolved | `setAutoCancel(true)` — it dismisses on tap |
| CM ignores network changes | `applyRules` calls `rule.isBlockedOn(currentNetworkType)` and reapplies on every network change — `:266-277` |
| Channel `firewall_service` | Does not exist. Real: `firewall_privileged_channel`, `firewall_vpn_channel`, `firewall_alerts_channel`, `backend_monitoring_channel`, `vpn_fallback_channel`, `backend_failure_channel`, `boot_failure_channel` |
| Monitoring notification times out after 5 min | 10 min, and only when Shizuku is absent — `Constants.kt` BackendMonitoring |
| No notification on automatic switch | Automatic switches DO notify ("Firewall Upgraded") |
| Roaming cannot be blocked independently | Only half enforced — unblocking Mobile leaves Roaming blocked |

## Missing (doc describes something that does not exist)
- **`Switching(from,to)` state** and all its transitions and UI. A transition is represented as
  `Starting(oldBackend)`, so the UI cannot tell "starting" from "switching".
- **Init Case C** (stop an orphaned backend when the firewall should be off).
- **"User cannot interact during Starting/Switching."**
- **"NO gap where apps are unblocked"** — true only inside `startFirewall`; three other paths have gaps.

## Add (real behaviour the doc never mentions)
1. **NetworkPolicyManager backend** — a fourth backend, Shizuku + reflection over
   `INetworkPolicyManager.setUidPolicy`, offered in Settings, never chosen by AUTO, silently degrades
   to metered-only blocking, and never cleans up (P0-2).
2. **Third-party VPN conflict handling** — VPN-state monitor via reflected `VpnTransportInfo`
   sessionId; refuses to start without privilege; auto-switches in AUTO; respects manual VPN choice.
3. **Privilege-gain upgrade** — health loop force-rechecks root/Shizuku while on VPN in AUTO and posts
   "Firewall Upgraded". Manual VPN mode deliberately skips this.
4. **Boot protection** — the whole feature (P0-1).
5. **LAN blocking** — iptables-only, RFC1918 + `fc00::/7` + `fe80::/10`, shown-but-disabled elsewhere.
6. **Screen-off blocking** (`blockWhenBackground`) on every backend, reapplied on each screen change.
7. **Widget + Quick Settings tile**, and the `FIREWALL_STATE_CHANGED` broadcast contract.
8. **`KEY_ALLOW_CRITICAL_FIREWALL`** — a user setting that removes the shared-UID exemption the doc
   presents as unconditional.
9. **`SYSTEM_RECOMMENDED_ALLOW`** — auto-created allow rules on every startup. The use case cites
   FIREWALL.md as its source; the doc never mentions the list.
10. **Captive portal controller** — an entire feature area.
11. **Fifth persistence key `KEY_VPN_INTERFACE_ACTIVE`**, which gates `VpnFirewallBackend.isActive()`.
12. **`KEY_FIREWALL_ENABLED` has four writers** (ViewModel, toggle receiver, widget, VpnPermissionActivity),
    not just the manager.

---

# P3 — Scripts

`dev.sh` (1,037 lines), read in full.

| Command | What it does |
|---|---|
| `build` | `./gradlew assembleDebug --no-daemon` |
| `install [device\|emulator]` | check adb → check device → device info → build → **uninstall** → install → launch → app info |
| `update [device\|emulator]` | same but `adb install -r` — **preserves data**, use this for iterating |
| `release` | validate keystore.properties → check keystore → `assembleRelease` → `apksigner verify` → print SHA256 |
| `emulator [name]` | finds SDK, prefers a Pixel 9a AVD, waits ≤180s for `sys.boot_completed` |
| `screenshot` | `adb exec-out screencap` → `screenshots/` → opens it |
| `logs` | `adb logcat -c` then a filtered live tail — this produced the archived feedback logs |
| `create-keystore` | prompts, `keytool -genkey` RSA 2048 / 10000 days, writes keystore.properties |
| `populate-keystore-properties` | prompts, verifies with `keytool -list`, writes keystore.properties |
| `validate-keystore-properties` | checks the 4 keys exist and are non-empty |
| `fdroid` | removed; errors and points at a doc that does not exist |

**Traps:**
- `install` uninstalls the **production** package too (`io.github.dorumrr.de1984`), not just `.debug`.
  On a daily-driver phone this wipes real rules, and per P1-23 there is no backup. `dev.sh:328-345`
- `emulator` always passes `-wipe-data` (factory reset), and `check_device` auto-calls it when nothing
  is attached — so a bare `./dev.sh install` can silently wipe the emulator. `dev.sh:222`
- `create-keystore` twice destroys the original key backup (P0-7). `dev.sh:654-655`
- `get_production_sha256` passes the store password on the `keytool` command line — briefly visible in
  `ps`. `dev.sh:788`
- `APP_VERSION` is grep/sed-parsed out of `app/build.gradle.kts`; a format change breaks every APK path.

---

# P4 — Built but unreachable

- Global `BlockAllAppsUseCase` / `AllowAllAppsUseCase` / `GetBlockedCountUseCase` /
  `GetFirewallRuleByPackageUseCase`: wired in DI, **zero UI callers** — `De1984Dependencies.kt:284-301`
- The dead global bulk excludes the **soft** `SYSTEM_RECOMMENDED_ALLOW` tier but **not** the untouchable
  `SYSTEM_WHITELIST` — the two-tier model inverted. Wiring it up would block SystemUI.
  `FirewallRepositoryImpl.kt:154,160`
- "Threats" filter + badge translated into all 7 languages, no Kotlin behind it — `strings.xml:82,99`
- Four settings read at startup, never written by any UI: auto-refresh, show system apps, dark theme,
  refresh interval — `SettingsViewModel.kt:81-84,251-262,582-585`
- "Open source licenses" returns a "coming soon" message from a function nothing calls — `:589-594`
- Entire second navigation stack unused: `res/layout/activity_main.xml`, `res/navigation/nav_graph.xml`,
  `res/menu/drawer_menu.xml`, `res/menu/popup_menu.xml`, `res/layout/activity_test_views.xml`
- ~90 unreferenced translated strings, 11 unreferenced drawables, `item_footer.xml`, `item_library.xml`
- Batch uninstall/reinstall renders hardcoded English from `Constants.kt:52-82` while full translations
  exist and are referenced nowhere
- `wouldBackendChange` (`FirewallManager.kt:2310`) — zero callers, comment admits it is obsolete
- `dismissVpnConflictNotification` (`FirewallManager.kt:1509`) — zero callers
- `isBootProtectionEnabled` (`BootProtectionManager.kt:46`) — zero callers
- A large amount of user-facing copy lives hardcoded in `Constants.kt` and can never translate,
  despite shipping 7 locales

---

# P5 — Cross-cutting rules that can drift apart

1. `userId = 0` is a **Kotlin default parameter** repeated across ~15 signatures. There is no
   multi-profile policy; every new call site silently targets the personal profile.
2. "Is this a VPN app" is re-implemented as a private `hasVpnService` in five files.
3. The screen-off rule is written out six times across four backends.
4. Roaming is derived on read but the flags are written independently by SQL — storage can hold a state
   the read model calls invalid.
5. Backend monitoring is wired three different ways for the same rules; `FirewallManager` runs a health
   loop for backends `PrivilegedFirewallService` already monitors.
6. The two protection tiers are membership tests on two hardcoded sets, and consumers pick different
   sets for the same intent.
7. `KEY_FIREWALL_ENABLED` has four writers.

---

# P6 — Project health

- **No test source set at all.** `app/src/` contains only `main`.
- CI disabled since 2025-11-04 — `.github/workflows/build.yml-temporary-disabled`.
- Every release hand-built via `./dev.sh release`.
- Release builds unminified and unshrunk (R8 issue) — `app/build.gradle.kts:74-75`.
- Room schema export off, `app/schemas/` empty — migrations cannot be reviewed or diffed.
- Production keystore + `keystore.properties` in the working tree. Gitignored and untracked (verified
  with `git ls-files`), but one `git add -f` from exposure. Off-machine backup confirmed 2026-08-22.
- Dormant since 2025-12-15 (~8 months).
- `docs/` deleted 2026-08-22 (4 stale WIDGET_*.md planning files). The 5 user feedback logs it held
  were preserved at `/Users/doru/dev/phi/de1984-feedback-archive/`.
- Docs referenced but never existing: `FIREWALL_BACKEND_RELIABILITY_PLAN.md`, `PRD.md`,
  `FDROID_REPRODUCIBLE_BUILDS_EXPLAINED.md`.
- RULES.md is stale: claims ProGuard enabled (off), API 21 (min is 26), Compose UI (XML views only).

---

# Runtime tests still required

1. **Does `netd` flush `OUTPUT` after `post-fs-data`?** Decides the true severity of P0-1.
2. Does the `handleBackendFailure` deadlock fire, or is the scope cancelled first?
3. Do NetworkPolicyManager uid policies really persist in `/data/system/netpolicy.xml` after uninstall?
4. Does IPv6 leak past the VPN tunnel on a dual-stack carrier?
5. Does a work-profile enable/disable act on the personal copy?
6. Does the widget show stale ON/OFF after a process kill?
7. Can a third-party app really flip `KEY_FIREWALL_ENABLED` via the exported receiver? (P0-4)

---

# Product decisions needed before fixing

1. ~~Should boot protection stay at all, and under what safety contract?~~ **SETTLED 2026-08-22:**
   it stays, with a self-healing script plus a forced reboot on both toggles. See
   "P0-1 DECIDED FIX DIRECTION". Four sub-decisions remain open there.
2. Is work/clone profile a supported dimension, or best-effort?
3. What should "Block All" mean — wifi+mobile, or a true lockdown including LAN, roaming and screen-off?
4. Should controls the active backend cannot enforce be hidden, or shown with an explanation?
5. Should rules be portable across devices? (Backups store the uid captured at export time.)
6. Should the captive-portal controller stay, given P0-5 and P0-6?
7. Tests + CI before the next feature?
