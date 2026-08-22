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

**RESOLVED ON HARDWARE 2026-08-22 — the chain SURVIVES `netd`.**
Tested on TrebleDroid GSI / LineageOS 21 / Android 14 / userdebug / Magisk root, using a harmless probe
that mirrors the real script exactly (create chain -> add rule -> `-I OUTPUT`) but with `RETURN` instead
of `DROP`, so nothing was blocked. After a full reboot:

```
-P OUTPUT ACCEPT
-A OUTPUT -j de1984_probe      <- survived, and it is FIRST
-A OUTPUT -j oem_out
-A OUTPUT -j fw_OUTPUT
-A OUTPUT -j st_OUTPUT
-A OUTPUT -j bw_OUTPUT
```

Identical on `ip6tables`. Two consequences:
1. `netd` does not flush the chain or its OUTPUT jump. **There is no self-healing.**
2. The jump sits ahead of Android's own `oem_out` / `fw_OUTPUT` / `st_OUTPUT` / `bw_OUTPUT`, so with
   `DROP` every non-exempt app's traffic dies before Android's rules are consulted.

**Every scenario in the table above therefore stands.** This was the one open question that could have
reduced the severity; it did not. Part A of the fix is required, not optional.

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

## P0-1 Boot protection — DECIDED FIX DIRECTION (Doru, 2026-08-22)

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

### Part B — forced reboot on both toggles (SETTLED, reaffirmed 2026-08-22 after hardware testing)

**Doru's rule: "You start it, confirm it, reboot. You stop it, confirm it, reboot."**
Always. No "reboot later" option, no optional offer. This is a decision, not a preference to revisit.

**Reboot is AUTOMATIC and IMMEDIATE on confirm (clarified 2026-08-22).** The existing warning dialog's
Continue button is the ONLY gate. After it, on success, the device reboots straight away:
- no second confirmation dialog
- no "Reboot now?" prompt
- no countdown, no delay, no snackbar to wait on
The only thing that stops a reboot is a FAILED apply step (see below) — that is an error path, not a
confirmation.

Backed by hardware: turning boot protection OFF removed the script but left the live chain dropping
traffic (335 -> 620 packets during the test). Only a reboot recovered the device.

#### ENABLE flow
1. User flips the switch ON.
2. Warning dialog appears. **It must state that the device will reboot immediately.**
3. User taps Continue.
4. Write the script -> **read it back and verify the content** -> `chmod 755` -> verify.
5. **If any step failed: revert the switch, show the error, and DO NOT REBOOT.**
6. Save the pref only after success.
7. **Reboot immediately** — `su -c "svc power reboot"`. Root is always present when this feature is
   available. No further user interaction.

#### DISABLE flow
1. User flips the switch OFF.
2. Warning dialog appears. **It must state that the device will reboot immediately.**
3. User taps Continue.
4. `rm -f` the script -> verify it is gone.
5. **Also call `resetIptablesPolicies()`** — belt and braces. If the reboot never happens (user pulls
   the battery, reboot command fails), the device still recovers. Consistent with the
   maximum-certainty intent, not a substitute for the reboot.
6. If script removal failed: revert the switch, show the error, DO NOT REBOOT.
7. Save the pref only after success.
8. **Reboot immediately** — same call, no further user interaction.

#### Prerequisites — Part B is UNSAFE until these land
- **Fix the dialog dismiss bug** (`ui/common/StandardDialog.kt` needs `setOnCancelListener`). Today a
  dismissed dialog leaves the switch showing the new state with nothing applied and no reboot. Observed
  live on this exact switch. Without this fix, Part B makes the lie worse, not better.
- **`resetIptablesPolicies()` must stop returning success when nothing ran.** It currently returns
  `Result.success` even with no privilege and zero commands executed.
- **The dialog must be readable.** The enable warning is already truncated on a 480px screen; adding a
  reboot announcement makes that worse. Fix the truncation before adding text.

#### Sub-decisions still open
- Scenario 5 — root lost, switch force-disabled. A recovery action is needed there regardless. Does
  that path also reboot? (Leaning yes, for consistency with the rule above.)
- Should Part A ship on its own first, so devices already carrying the script in the field self-heal
  before Part B lands? Part B only helps users who touch the toggle again.
- Micro-detail: is a brief "Rebooting..." toast wanted, or does that count as a delay? Current reading
  of the rule is NO delay of any kind — the screen simply goes.


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
   "P0-1 Boot protection — DECIDED FIX DIRECTION". Four sub-decisions remain open there.
2. Is work/clone profile a supported dimension, or best-effort?
3. What should "Block All" mean — wifi+mobile, or a true lockdown including LAN, roaming and screen-off?
4. Should controls the active backend cannot enforce be hidden, or shown with an explanation?
5. Should rules be portable across devices? (Backups store the uid captured at export time.)
6. Should the captive-portal controller stay, given P0-5 and P0-6?
7. Tests + CI before the next feature?

---

# P7 — Medium findings, adversarially verified (2026-08-22)

The first pass produced 117 medium findings with **no verification**. 111 were distinct. I sent 11
skeptics at them, each told to assume the claim was wrong, read the whole surrounding function, look
for other code that already handles the case, and default to REFUTED when uncertain.

**111/111 verdicts returned.** Result: 45 CONFIRMED · 48 PARTIAL · 16 REFUTED · 2 NEEDS-RUNTIME.
Re-judged severity: 4 high · 21 medium · 72 low · 14 not-a-defect.

**30 of 111 (27%) were dropped** as refuted or not-a-defect. 72 more were downgraded to low (real but
no meaningful user impact). **25 survived as worth acting on.** The unverified count was not trustworthy.

## Escalated to P1 — these were mislabelled medium

| ID | User impact | Where | Verdict |
|---|---|---|---|
| M107 NetworkPolicyManager currentBackend regression | Shizuku user on the NetworkPolicyManager backend restarts the app: turning the firewall off no longer stops it (cleanupAllBackends only clears iptables), so apps stay blocked. | `data/firewall/FirewallManager.kt:229` | CONFIRMED |
| M049 backends report success on failed rules | An app the user blocked keeps full network access while the UI shows the rule applied and no error is raised; on iptables it stays unblocked until the backend restarts. | `data/firewall/IptablesFirewallBackend.kt:733` | CONFIRMED |
| M040 PrivilegedFirewallService not idempotent | Switching between two privileged backends leaves the old iptables chains or netpolicy uid rules installed, and the later stop tears down the new backend instead, leaving stale blocks. | `data/service/PrivilegedFirewallService.kt:283` | CONFIRMED |
| M077 backup restore uses stale uid | Restoring a backup on another device (or after reinstalls) can block the wrong apps and leave intended apps unblocked on the root/Shizuku backends, silently. | `presentation/viewmodel/SettingsViewModel.kt:690` | CONFIRMED |

**P1-28 M107 NetworkPolicyManager currentBackend regression** is datable. `git log -L 226,232` shows
commit `4c6171c` ("Add Quick Settings Tile and Home Screen Widget") changed `currentBackend = npmBackend`
into a bare `currentBackend` while inserting `emitStateChangeBroadcast` on the line below. A hand-edit
slip during an unrelated feature. Kotlin only warns (UNUSED_EXPRESSION), so nothing caught it.

**P1-29 M049 backends report success on failed rules** is worse than first described. In
`data/firewall/IptablesFirewallBackend.kt:733-741` a non-zero exit is logged and deliberately ignored —
the comment says failed *deletes* are fine — but `blockedUids` is then updated **unconditionally**. A
failed DROP is recorded as applied and never retried. The intent was right; the granularity was wrong.

## Surviving medium findings (21)

| ID | User impact | Where | Verdict |
|---|---|---|---|
| M091 dev.sh emulator wait overruns its timeout | Emulator wait can hang ~9 minutes despite a stated 3-minute timeout while spamming a false 'Emulator is ready!' line. | `dev.sh:232-251` | CONFIRMED |
| M001 init races the start/stop lock | On startup, widget/tile/boot paths racing the 5-attempt init loop can leave the manager pointing at a replaced backend, so state shown and stopped is the wrong one. | `data/firewall/FirewallManager.kt:188` | CONFIRMED |
| M002 early-exit leaves stale down-state | Reachable via handleVpnConflictFallbackFailed (:2029), which nulls _activeBackendType but keeps currentBackend: a later start leaves UI showing no backend while firewall runs. | `data/firewall/FirewallManager.kt:423-425 and :445-463` | PARTIAL |
| M006 firewall list adapter rebuilt on every settings emit | Any settings-state change while the firewall list is visible jumps the list back to the top and reloads every visible icon from disk. | `ui/firewall/FirewallFragmentViews.kt:496` | CONFIRMED |
| M013 multi-select aggregates a partial subset | With a state filter or search active, the multi-select toggle can show a uniform state derived from part of the selection; the next tap applies to all selected apps. | `ui/firewall/FirewallFragmentViews.kt:1713-1716` | CONFIRMED |
| M031 package load caches empty results | A transient enumeration failure shows the empty-list state with no error, and new subscribers within the 1s TTL get the cached empty list instead of retrying. | `data/datasource/AndroidPackageDataSource.kt:281-283` | PARTIAL |
| M039 onLost reports NONE and over-blocks | After a WiFi-to-cellular handoff, apps blocked on only one transport are blocked on both until the next capability callback re-emits the real type. | `data/monitor/NetworkStateMonitor.kt:179-181` | CONFIRMED |
| M014 one-shot dialogs never cleared | Dismissing the import-preview dialog by tapping outside makes it pop up again every time the user returns to Settings, until Confirm/Cancel is pressed. | `ui/settings/SettingsFragmentViews.kt:556` | CONFIRMED |
| M094 CM backend does react to network changes | A granular rule left from VPN/iptables makes an app blocked on every network while on WiFi and fully allowed on mobile data, contradicting the documented all-or-nothing behaviour. | `data/firewall/ConnectivityManagerFirewallBackend.kt:266` | CONFIRMED |
| M111 CM OEM_DENY_3 toggled without capturing prior state | Uninstalling or force-stopping De1984 while the CM backend is active leaves blocked apps with no network until reboot; disabling chain3 could also clobber an OEM's own use of it. | `data/firewall/ConnectivityManagerFirewallBackend.kt:149` | PARTIAL |
| M099 widget/tile ignores sticky manual mode | Widget/tile start ignores a manually chosen VPN mode and uses a different backend; a failed start still records firewall_enabled=true, so boot restore thinks it was running. | `data/receiver/FirewallToggleReceiver.kt:90` | CONFIRMED |
| M047 superuser banner matches English text only | In any non-English locale, a firewall block/allow that fails for lack of root shows only a raw error line, never the superuser banner telling the user to grant root/Shizuku. | `app/src/main/res/values-ru/strings.xml:753` | CONFIRMED |
| M100 boot-protection UI trusts the pref not the disk | After an app-data wipe or external script deletion the switch shows a state that does not match /data/adb/post-fs-data.d; user cannot tell or clear it from the UI. | `presentation/viewmodel/SettingsViewModel.kt:91` | CONFIRMED |
| M076 bulk allow-all leaves roaming blocked | After switching the default policy to Allow All, apps that had roaming blocked stay blocked while roaming; the roaming toggle still reads ON. | `data/database/dao/FirewallRuleDao.kt:95` | CONFIRMED |
| M024 Packages list mixes languages | On a translated device the Packages list mixes languages: translated chips beside English Enabled/Disabled/Uninstalled badges, English empty state and English toasts. | `ui/packages/PackageAdapter.kt:235` | CONFIRMED |
| M050 work-profile VPN apps never detected as exempt | A VPN app installed only in the work profile is not recognised as a VPN, so Block All mode blocks it and work-profile VPN connectivity breaks. | `data/firewall/IptablesFirewallBackend.kt:874` | CONFIRMED |
| M027 work-profile enabled-state defaults to true without root | On Shizuku-only (unrooted) devices every work-profile app is shown as Enabled, and the detail sheet offers Disable for apps that are already disabled. | `data/multiuser/HiddenApiHelper.kt:574` | CONFIRMED |
| M055 stale Shizuku granted state | If Shizuku revocation does not kill the process, Settings keeps showing Granted and privileged actions fail until the app is restarted. | `data/common/ShizukuManager.kt:163` | NEEDS-RUNTIME |
| M098 VPN tunnel is IPv4 only | On an IPv6-capable network a blocked app may still reach the internet over IPv6 while the UI shows it as blocked. | `data/service/FirewallVpnService.kt:621` | NEEDS-RUNTIME |
| M072 reinstalled app silently reuses its old rule | Reinstalled app is silently blocked by an old rule; in iptables/NPM modes the stale uid never matches, so the UI shows Blocked while traffic is not blocked. | `domain/usecase/HandleNewAppInstallUseCase.kt:70-73` | CONFIRMED |
| M108 stop result discarded, OFF persisted before the stop | If the stop fails, prefs, widget and toggle all show OFF while backend rules may remain, with no error shown to the user. | `presentation/viewmodel/FirewallViewModel.kt:650-655` | CONFIRMED |

Full verdicts, including the 72 downgraded and the 30 dropped, are in
`/private/tmp/claude-501/-Users-doru-dev-phi-de1984/fdaaee55-3651-40f7-8e98-1a7463e5d26f/scratchpad/verdicts.json`.
That path is session-scoped; ask me to re-export if it matters later.

---

# HARDWARE VERIFICATION — 2026-08-22

Device: TrebleDroid vanilla GSI, LineageOS 21.0, **Android 14 / API 34**, `userdebug`, **Magisk root**,
work profile present (user 10), **no Shizuku**. De1984 debug build updated to **v2.6.2 (code 33)** via
`./dev.sh update` (data preserved).

Measurement instrument: **iptables packet counters**. Per-uid synthetic probes (`su <uid> -c curl`) do
NOT work on Android — `ip rule` routes apps by an fwmark that netd assigns per app, and a raw `su`
process has none. Counters measure real app traffic instead.

## CONFIRMED ON HARDWARE

### P0-1 Boot protection — every claim proven

**(a) The chain survives `netd`.** A harmless probe (same structure, `RETURN` instead of `DROP`)
installed in `post-fs-data.d` survived a full reboot on both iptables and ip6tables, and sat **first in
OUTPUT**, ahead of `oem_out` / `fw_OUTPUT` / `st_OUTPUT` / `bw_OUTPUT`. There is no self-healing.

**(b) Scenario 1 reproduced end to end.** Firewall OFF + boot protection ON + reboot. The app's own log:
```
BootWorker: Firewall was enabled before boot: false
BootWorker: FIREWALL WAS NOT ENABLED | Skipping firewall restoration after boot
```
That is `data/worker/BootWorker.kt:48-51` returning before the reset at `:97`. Counters on the live
`de1984_boot` DROP rule climbed **212 -> 246 -> 261 -> 335 -> 609 -> 620 packets** over a few minutes.
Real app traffic, really dropped, on every boot, with no in-app way out. WiFi still shows connected
(uid 1010 is exempt) so the phone *looks* healthy.

**(c) Disabling boot protection does NOT unblock the running device.** Turning the setting off removed
the script from disk but left the live chain in OUTPUT, still dropping (335 -> 620 during the test).
The disable dialog does say "this change will take effect on your next reboot" — the messaging is
honest, the behaviour is not. Confirms the need for Part B, or at minimum a `resetIptablesPolicies()`
call on the disable path.

**(d) Script write integrity — DOWNGRADE.** The `echo`-redirect write produced a correct 42-line,
1599-byte, `-rwxr-xr-x root root` script. The partial-write risk is theoretical on this device, not
observed. Keep the readback fix, lower its priority.

**(e) The enable dialog truncates the recovery instructions.** On a 480px-wide screen the message is cut
at "2. If that doesn't work, use ADB:" — the actual commands are below the fold with no scroll cue.
Recovery option 1 is the one proven unreachable when root is lost. So at the moment of accepting the
risk the user can only see the route that does not work. `res/values/strings.xml:773`.

### P0-2 NetworkPolicyManager orphan policies — proven, no root needed
Applied a uid policy the way the NPM backend does. `/data/system/netpolicy.xml` grew 788 -> 808 bytes
immediately. Policy survived **a reboot**, then survived **removing the app entirely**. Nothing in
Android cleans it up and no Android UI exposes it. Only Shizuku is required to reach this state.

### P0-4 Exported widget receiver — proven
An external broadcast flipped the persisted `firewall_enabled` pref, false -> true and back to false.
`dumpsys` confirms **no permission** on the receiver. Nuance: the *implicit* form is ignored (Android 8+
implicit-broadcast restrictions), so an attacker must name the component explicitly — which is public in
an open-source app. Any installed app, zero permissions.

### P0-5 Captive portal command injection — proven
Fed the exact command shape a crafted URL `http://x.com$(touch /data/local/tmp/INJECTED)`. Result:
`-rw-r--r-- 1 root root ... /data/local/tmp/INJECTED`. Executed **as root**. The stored setting reads
back as plain `http://x.com`, so the injection leaves no trace in the UI. `isValidUrl` passes it.

### P0-6 Captive portal orphan state — proven from live device values
| Setting | Real system value on device | What De1984 stored as "original" |
|---|---|---|
| `captive_portal_mode` | **unset (null)** | **1** |
| `captive_portal_use_https` | **unset (null)** | **false** |

`getSystemSetting` returns null for an unset key, and capture stores a fabricated default instead.
`restoreOriginalSettings` writes mode **unconditionally**, so "restore" would create a setting the
device never had.

**NEW — only 3 of the 6 captive-portal keys are ever written.** `SYSTEM_KEY_FALLBACK_URL`,
`SYSTEM_KEY_OTHER_FALLBACK_URLS` and `SYSTEM_KEY_USE_HTTPS` are captured into the backup but **no code
path writes them** — not apply, not restore, not reset. The backup looks more complete than it is.

**NEW — `resetToDefaults` writes Google's `connectivitycheck.gstatic.com`.** On this LineageOS device
the real default is Cloudflare. "Reset to defaults" silently moves a privacy-focused user onto Google,
inside a privacy app. `data/common/CaptivePortalManager.kt:302-314`.

### NEW P1 — every confirmation dialog can be dismissed without reverting
`ui/common/StandardDialog.kt:36-66` builds with `cancelable = true` but wires the cancel callback only
to `setNegativeButton`. There is **no `setOnCancelListener`**, and `showConfirmation` never passes the
`onDismiss` hook that exists at `:62`. Tapping outside or pressing Back dismisses without running the
revert.

Observed live: the boot-protection switch showed **ON** while nothing had been written and the pref key
did not exist. Log shows the enable dialog displayed with neither "User confirmed" nor "User cancelled"
following it. **This affects every caller of `showConfirmation`, including the destructive package
dialogs.** Needs a sweep.

### Multi-profile uid confirmed
The same app is uid **10275** in the personal profile and **1010275** in the work profile
(`userId * 100000 + appId`). Any code keying on package name alone, or on a `hashCode()`-fabricated uid,
targets the wrong app. Supports P1-11 and M050.

## NARROWED

**M098 VPN IPv6 leak — scope reduced.** On the **iptables** backend IPv6 is correctly blocked: the live
chain carried a v6 catch-all DROP plus `fc00::/7` and `fe80::/10` for the blocked uid. The device has
real global IPv6 (`2a02:c7c:...`). The leak claim applies **only to the VPN backend**, which still needs
its own test.

## STILL UNTESTED ON HARDWARE
- P0-3 deadlock in `handleBackendFailure` (needs root revoked mid-session)
- M098 IPv6 leak on the **VPN** backend specifically
- P1-28 M107 NetworkPolicyManager `currentBackend` regression (needs Shizuku installed)
- Work-profile behaviour with apps actually present in user 10
- M055 stale Shizuku granted state (needs Shizuku)

---

# IMPLEMENTED — 2026-08-22 (Part B only)

**Committed by: nobody yet.** Changes are in the working tree, uncommitted, on `main`.

## What changed (4 files, minimal)

| File | Change |
|---|---|
| `ui/common/StandardDialog.kt` | Added `setOnCancelListener` in `show()` so dismissing by outside-tap or Back runs the caller's cancel callback. Guarded on `cancelable && onNegativeClick != null`. Button presses are unaffected (`setOnCancelListener` does not fire for those). |
| `data/common/BootProtectionManager.kt` | `createBootScript()` now reads the script back and compares it to what was written, removing it and failing on mismatch; a failed `chmod` now deletes the orphan instead of leaving it. `deleteBootScript()` now verifies the file is gone via `isBootProtectionEnabled()` and then calls `resetIptablesPolicies()`. New `rebootDevice()` running `svc power reboot`. |
| `presentation/viewmodel/SettingsViewModel.kt` | `setBootProtection()` saves the pref then reboots immediately on success. On failure it sets an error and does not reboot; the switch reverts on its own because `updateUI()` re-reads `state.bootProtection`. |
| `res/values/strings.xml` | Both warning messages now open with the restart notice so it clears the fold. New `boot_protection_reboot_failed`. Recovery option 1 no longer says "-> Reboot" since the app now does it. |

Side effect, deliberate and checked: routing dismiss to cancel also repairs 6 other call sites — the
backend dropdown revert, both critical-package switches, and two `clearImportPreview()` calls (which is
finding M014). Every `onCancel` in the tree is a revert, a log, or `clearImportPreview()`; none have
side effects, and `PackagesFragmentViews` / `MainActivity` pass none at all.

## Verified on hardware (TrebleDroid GSI, Android 14, Magisk root)

- **Dismiss fix**: tapping outside the enable dialog now reverts the switch to OFF. Before the change
  it stayed showing ON with nothing applied.
- **Enable -> reboot**: confirmed, device restarted immediately.
- **Boot with firewall ON**: script installed the chain, firewall came up on iptables, and the chain was
  removed cleanly (`Unlinked / Flushed / Deleted`, all exit 0, both v4 and v6).
- **Disable -> teardown -> reboot** on a device already blocked (334 packets dropped): full trace
  completed in **414 ms** from confirm to reboot. Script removed, deletion verified, chain torn down on
  v4 and v6, device came back with `HTTP=204`.
- `isBootProtectionEnabled()` now has a real caller and is doing real work.

## NOT fixed by this change

**P0-1 Boot protection scenario 1 is still live.** Firewall OFF + boot protection ON + reboot still
leaves the device blocked with no in-app recovery. Reproduced on this build:
`BootWorker: FIREWALL WAS NOT ENABLED | Skipping firewall restoration after boot`, 334 packets dropped.
That is **Part A**, which has not been implemented.

## NEW finding from this session — the lock screen makes P0-1 Boot protection worse

Observed with the device at `RUNNING_LOCKED`, never unlocked since boot:
- `de1984_boot` chain live and dropping (60 -> 96 packets)
- `BootWorker` had **not run at all** — zero log entries

The boot script runs at `post-fs-data`, before decryption. The app's recovery runs on `BOOT_COMPLETED`,
which on an encrypted device fires only **after the user unlocks**. So the block starts at boot and the
fix cannot begin until someone enters a PIN. A phone that reboots overnight has no app network until
morning; a user who cannot unlock never recovers at all.

`BootReceiver` is `directBootAware="true"` and does listen for `LOCKED_BOOT_COMPLETED`, but it
immediately reads credential-encrypted SharedPreferences that do not exist before unlock, so that path
can only fail silently. Earlier tests hid this because the device had already been unlocked — the log
line even reads "BOOT_COMPLETED (after user unlock)".

**This is the strongest argument yet for Part A:** a self-healing script is the only mechanism that can
recover a locked device, because it runs in the same early stage that caused the problem.

## SHIP BLOCKER — translations are stale

| Locale | enable msg | disable msg | `boot_protection_reboot_failed` |
|---|---|---|---|
| `values` (en) | updated | updated | present |
| ro, pt, zh, it, fr, ru | **old text** | **old text** | **missing** |

A non-English user taps Continue and the device reboots **with no warning**, because their translated
string still says nothing about restarting. On a safety feature that is a bad surprise. Must be fixed
before release. Decide whether to hand-translate one sentence per locale or have them done properly.

Also now unused: `boot_protection_enabled_success` and `boot_protection_disabled_success` (0 references)
in all 7 locales — the device reboots before either could be shown.

## Still untested for regressions
The `StandardDialog` change touches 20 call sites. Compile passes and the boot-protection dialog was
verified by hand. The other switch dialogs (both critical-package toggles, the backend dropdown) and the
package-management dialogs have NOT been re-tested.

---

# PART A IMPLEMENTED AND TESTED — 2026-08-22

Uncommitted, on `main`. Together with Part B this closes **P0-1 Boot protection**.

## What changed

| File | Change |
|---|---|
| `utils/Constants.kt` | New `SELF_HEAL_TIMEOUT_SECONDS = 120`, and `DE_DATA_DIR_RELEASE` / `DE_DATA_DIR_DEBUG` pointing at `/data/user_de/0/...` (device-encrypted, readable before unlock). |
| `data/common/BootProtectionManager.kt` | Boot script now (a) deletes itself and exits if neither De1984 package dir exists, (b) guards the OUTPUT jump with `-C` so a re-run cannot stack a second jump, (c) backgrounds a subshell that lifts the block after the timeout. New `clearBootBlockIfInstalled()` which wakes root first, refuses rather than lying when it has no privilege, and tears the chain down when the script is present. |
| `data/worker/BootWorker.kt` | Calls `clearBootBlockIfInstalled()` **before** the `wasEnabled` check, so the block is lifted regardless of firewall state or start outcome. |
| `data/receiver/BootReceiver.kt` | Same lift added to the firewall-not-enabled branch and to the `onFailure` path. |

## Verified on hardware

**A1 self-expiry — WORKS.** The backgrounded subshell survives Magisk `post-fs-data` (observed as
PID 989, reparented to init). Timeline watched live:
```
t+20s  subshell alive (01:38)   -A OUTPUT -j de1984_boot
t+40s  subshell alive (01:58)   -A OUTPUT -j de1984_boot
t+60s  subshell GONE            -A OUTPUT -j oem_out      <- block lifted itself
```
**The device self-heals with no app involvement.** This is the only mechanism that can rescue a locked
device or one where De1984 is gone, because it runs in the same early stage that caused the problem.

**A3 early lift — WORKS, after fixing a bug in the first attempt.**
```
21:22:25.360  No privilege yet - requesting root before checking boot protection
21:22:26.077  Boot protection enabled: true
21:22:26.262  Boot protection script is installed - lifting its block
21:22:26.460  Removing boot protection iptables rules...
21:22:27.627  FIREWALL WAS NOT ENABLED | Skipping firewall restoration after boot
```
Note the order: lifted BEFORE the early return that used to strand it. Also fires on
`MY_PACKAGE_REPLACED`, so an app update clears a stale block too.

**Bug found in my own first attempt, now fixed:** at boot the app has not yet asked Magisk for root, so
`hasRootPermission` was false, `executeCommand` returned `-1` without running anything, and
`isBootProtectionEnabled()` reported `false` for a script plainly on disk. `clearBootBlockIfInstalled()`
now wakes root first, and returns a failure rather than a false "not enabled" when it cannot check.

**Underlying flaw this exposed:** `isBootProtectionEnabled()` cannot distinguish "script absent" from
"cannot check". Worked around at the one call site that matters; the function itself still conflates
the two.

## A2 self-delete-on-uninstall — VERIFIED ON HARDWARE

Uninstalled De1984, then rebooted:
- The script **survived the uninstall** (`-rwxr-xr-x root root`, still in `post-fs-data.d`) - Android
  runs no code on uninstall, confirming there is no hook to rely on.
- `/data/user_de/0/io.github.dorumrr.de1984.debug` was gone.
- On the FIRST boot afterwards: `post-fs-data.d` was **empty**, no `de1984_boot` chain existed, no
  leftover subshell, and network was fine (`HTTP=204`).

The orphaned-script problem is solved at the source. A user who uninstalls gets exactly one boot where
the script runs, sees the app is gone, deletes itself, and exits without blocking anything.

All three Part A mechanisms (A1 self-expiry, A2 self-delete, A3 early lift) are now proven on hardware.

## Regression sweep of the StandardDialog change — 1 real regression, mine, fixed

4 agents over 21 call sites.

**HIGH, introduced by my first version:** `showRestoreOptions`
(`ui/settings/SettingsFragmentViews.kt:1390`) puts **"Replace All"** in the negative slot - a
destructive action, not a cancel - and offers no Cancel button. Routing dismiss to `onNegativeClick`
meant tapping outside to escape the restore dialog opened "⚠️ Replace All Rules?" instead. One more tap
wipes every rule.

**Root cause:** `show()` inferred "negative button == cancel". Wrong for any caller using that slot for
a second action.

**Fix:** cancel is now explicit. `show()` takes its own `onCancel`; `showConfirmation` passes it through
because there the negative button genuinely is cancel. All 5 direct `show()` callers pass none, so they
behave exactly as before. This also removed a low-severity double-callback at `ui/MainActivity.kt:677`.

**Improved by the change (6 sites):** backend-mode dropdown revert, both critical-package switches,
boot protection switch, and both `clearImportPreview()` calls - the latter being finding **M014**.

**Untouched (9 destructive package dialogs):** they pass no `onCancel`, so uninstall / disable /
force-stop dismissal behaviour is byte-for-byte unchanged.

**Latent, not changed:** `showTypeToConfirm` (the type-"UNINSTALL" dialog) builds its own `AlertDialog`
and bypasses `StandardDialog` entirely, so cancel semantics differ between the two helpers.

## Translations — done, needs a native review
All 7 locales now carry the restart warning and `boot_protection_reboot_failed`. Recovery option 1 no
longer says "-> Reboot" since the app now does it. Romanian was reviewed by Doru. **ro/pt/zh/it/fr/ru
were written by me and match existing tone, but have not been checked by native speakers.** Reviewed
with Doru side by side on 2026-08-22. Two worth a second look: Italian `quando continui` (informal
*tu* - matches the rest of that file, but confirm it is the intended register) and French
`lorsque vous continuerez` (future tense reads stiff; `lorsque vous continuez` may be better).

Only new text was written. The long body of the enable dialog is untouched in all 7 languages; the only
other edit was deleting the trailing "-> Reboot" step from recovery option 1, since the app now reboots
by itself and instructing the user to do it was wrong.

Now unused in all 7 locales: `boot_protection_enabled_success`, `boot_protection_disabled_success`.

## Open decisions
- ~~Is `SELF_HEAL_TIMEOUT_SECONDS = 120` right?~~ **SETTLED 2026-08-22: 120 seconds confirmed by Doru.**
- Scenario 5 (root lost, switch greyed out) still has no in-app recovery action.

---

# QUICK WINS BUNDLE — implemented and tested 2026-08-22

## P1-1 M107 currentBackend regression — FIXED
`data/firewall/FirewallManager.kt:229` now assigns `currentBackend = npmBackend` instead of being a
bare expression. Restores what commit `4c6171c` accidentally deleted.
**Runtime test not possible on this device** - the NetworkPolicyManager backend needs Shizuku, which is
not installed. Code-verified only.

## P0-4 exported widget receiver — FIXED AND VERIFIED
`ui/widget/FirewallWidget.kt` no longer writes `KEY_FIREWALL_ENABLED` from the broadcast extra. The
receiver must stay exported for `APPWIDGET_UPDATE`, and the custom action carries no permission, so any
app can send it - it therefore must never mutate state. Display still uses the extra, which is harmless.

Verified on hardware with the exact spoof that worked before:
```
BEFORE  firewall_enabled = false
        FirewallWidget: STATE CHANGE BROADCAST RECEIVED
        FirewallWidget: Derived isEnabled from broadcast: true
AFTER   firewall_enabled = false     <- unchanged
```
The `SharedPrefs updated:` log line is gone. Checked first that nothing depends on this write: the flag
has four other writers (FirewallViewModel, FirewallToggleReceiver, VpnPermissionActivity,
FirewallManager) covering every genuine state change.

## P0-5 captive portal command injection — FIXED AND VERIFIED
Two layers in `data/common/CaptivePortalManager.kt`:
1. `setSystemSetting` now single-quotes the value with `'\''` escaping. Inside single quotes the shell
   expands nothing.
2. `isValidUrl` rejects shell metacharacters and whitespace.

Verified on hardware, same payload both ways:
| Command shape | Result |
|---|---|
| Old, double-quoted | `/data/local/tmp/OLD_PWNED` created - **INJECTED** |
| New, single-quoted | **blocked**, stored literally as `http://x.com$(touch ...)` |

The test deliberately bypassed `isValidUrl` to prove the quoting holds independently.

## Scenario 5 (root lost after enabling) — HANDLED HONESTLY, not "fixed"

**There is a catch-22 and it cannot be engineered away.** `/data/adb` is root-only: verified that
without root the app cannot even list it, let alone read or delete the script. A "Remove boot
protection" button would be a button that cannot work.

What the app CAN know is that its own `boot_protection` preference says it installed one. So
`ui/settings/SettingsFragmentViews.kt` now, when privileges are gone but the preference is true:
- shows the switch as **ON** (showing OFF would be a lie - the script is almost certainly still there)
- replaces the description with `settings_boot_protection_stuck`, which states the situation, notes the
  block now lifts itself after 2 minutes each boot, and gives the exact ADB commands to remove it.

Added in all 7 locales.

**Residual risk accepted:** if the user clears app data, the preference is lost and the app cannot warn
at all. The 120-second self-expiry is the only protection in that case, and it is sufficient to stop
the device being unusable.

---

# P0-2 AND P0-3 IMPLEMENTED — 2026-08-22

## P0-3 Deadlock in the failure-recovery path — FIXED
`startFirewall` is now a thin wrapper over a new private `startFirewallInternal(mode)`, matching the
`stopFirewall` / `stopFirewallInternal` pattern the file already used. The two calls inside
`handleBackendFailure` (`FirewallManager.kt:1163`, `:1226`) now reach the internal form, so the
non-reentrant `startStopMutex` is taken exactly once.

Traced every `startFirewall(` call site in the tree. Only those two ran under the lock. The other
in-class callers — `initializeBackendState`, `startBackendHealthMonitoring`,
`startVpnPermissionMonitoring`, `handleVpnConflict`, `handlePrivilegeChange` — are either non-suspend
launchers or are reached from monitoring collectors, never from inside `startStopMutex`.

**Not yet reproduced on hardware.** Forcing a backend failure needs a privileged-service kill.

## P0-2 NetworkPolicyManager orphan policies — FIXED AND VERIFIED ON HARDWARE
`stopInternal` now calls a new `clearBlockedUidPoliciesInternal()` which writes `POLICY_NONE` back for
every uid the backend blocked, instead of only clearing the in-memory cache.

The uid list is now also mirrored to SharedPreferences (`Constants.Settings.KEY_NPM_BLOCKED_UIDS`).
Without that, `appliedPolicies` is empty in any fresh process, so a crash or a backend switch could
never clean up. `FirewallManager.cleanupAllBackends` now instantiates the NPM backend and calls the
new public `clearOrphanedPolicies()`, and its false comment about NPM leaving no persistent state is
gone.

Only uids De1984 itself blocked are ever reverted. This is deliberate: `POLICY_REJECT_METERED_BACKGROUND`
is the same value Android's own "Restrict background data" writes, so clearing by scan would silently
undo the user's own Settings choices.

### Hardware proof — 2026-08-22, Android 14 / LineageOS / Shizuku-as-root
Selected the NetworkPolicyManager backend, blocked `com.aurora.store` (uid 10269), started the firewall.

Before stop:
```
UID=10269 policy=1 (REJECT_METERED_BACKGROUND)
<set name="npm_blocked_uids"><string>10269</string></set>
```
Only the one blocked uid was recorded. The 79 "allow" writes in the same pass were correctly ignored.

After stopping the firewall:
```
Policy for UIDs:  (10269 absent)
<set name="npm_blocked_uids" />
```
Log:
```
21:57:29.582  FirewallManager: Cleaning up all backend types to ensure no orphaned rules...
21:57:29.709  NetworkPolicyManagerFirewall: stopInternal: Cleaning up
21:57:29.747  NetworkPolicyManagerFirewall: Cleared policy for UID 10269
21:57:29.759  NetworkPolicyManagerFirewall: ✅ Cleared 1 UID policies
21:57:29.774  FirewallManager: NetworkPolicyManager cleanup completed
21:57:29.783  NetworkPolicyManagerFirewall: ✅ Cleared 1 UID policies
```
Both paths ran — `cleanupAllBackends` on a throwaway instance and `stopInternal` on the live one. The
double revert is harmless (writing `POLICY_NONE` twice) but wasteful; worth collapsing.

The four pre-existing `REJECT_ALL` policies on the device were left untouched, confirming the design
choice to revert only uids De1984 itself blocked.

**Still unfixable from inside the app:** uninstall. Android exposes no uninstall hook, so a user who
uninstalls with the firewall running keeps the policies. Only a manual `cmd netpolicy` or a reflash
clears them.

---

# NEW P0-8 — the NetworkPolicyManager backend can only ever block metered background data

**Corrected 2026-08-22 after hardware testing.** The first write-up claimed the backend writes `0x4`
and thereby grants an allowance. That is wrong: `0x4` is never reached.

`testPolicySupport` (`NetworkPolicyManagerFirewallBackend.kt:646-653`) probes support by writing
`setUidPolicy(0, POLICY_REJECT_ALL)` — **to uid 0**. Android's `NetworkPolicyManagerService` rejects
policies on any non-app uid, so the probe throws on every device and every Android version. The catch
branch then pins `blockingPolicy = POLICY_REJECT_METERED_BACKGROUND` permanently.

Proven on hardware (Android 14, SDK 34, LineageOS, Shizuku as root):
```
21:53:39.266  Testing policy support on this device...
21:53:39.315  ⚠️  POLICY_REJECT_ALL not supported on this device
21:53:39.612  ✅ Using policy: POLICY_REJECT_METERED_BACKGROUND (blocks Mobile only, WiFi NOT blocked)
```
The same run threw `IllegalArgumentException: cannot apply policy to UID 1001001` and `UID 1001002` for
real work-profile system uids, which is the identical rejection the uid-0 probe hits.

Result on the device, with Aurora Store shown as Blocked in De1984:
```
UID=10269 policy=1 (REJECT_METERED_BACKGROUND)
```
So the app is blocked on **metered background only**. WiFi is fully open. Foreground mobile is fully
open. The UI says Blocked.

The constant `POLICY_REJECT_ALL = 0x4` (`:55`) is separately wrong — this ROM decodes `0x4` as
`ALLOW_METERED_BACKGROUND` and `REJECT_ALL` as `262144` (`0x40000`) — but that is currently masked by
the broken probe. Fixing the probe without fixing the constant would make the backend write an
allowance to every blocked app.

## Reachability — VERIFIED during the 2026-08-22 audit
`FirewallManager.selectBackend(FirewallMode.AUTO)` is iptables -> ConnectivityManager -> VPN, with no
NPM branch at any Android version (`FirewallManager.kt:791-820`). **AUTO never selects this backend.**
It is reachable only by manual selection, which `SettingsFragmentViews.kt:766` offers whenever Shizuku
is present, with no Android version gate.

So the blast radius is users who deliberately choose NetworkPolicyManager in Settings. For them the
firewall reports Running, the UI shows apps as Blocked, and only metered background data is actually
blocked. Still a real defect - the app offers a backend that cannot do what its UI claims - but it is
not on the default path for anyone.

**Not fixed.** Needs a decision: probe with a real app uid and read back with `getUidPolicy`, use
`0x40000`, or retire the backend.

## Also observed in the same run — needs its own entries
- `applyRules` took **16,531 ms** for 79 uids (`PrivilegedFirewallService` TIMING log), and ran
  **twice concurrently** (threads 8662 and 9481 both applied all 79).
- **8 errors** per pass: the backend tries to set policies on work-profile system uids
  (1001001, 1001002, ...) instead of skipping non-app uids.

---

# NEW P0-9 — the NetworkPolicyManager backend destroys pre-existing network policies — VERIFIED

Every uid the backend decides is "allowed" gets `setUidPolicy(uid, POLICY_NONE)`
(`NetworkPolicyManagerFirewallBackend.kt:428`). `POLICY_NONE` is not "De1984 has no opinion" — it is
"clear whatever policy exists", including one the user set in Android Settings or the ROM set itself.

Proven on hardware. Policy table before De1984's NPM backend ran:
```
UID=10212   policy=262144 (REJECT_ALL)              io.github.dorumrr.happytaxes
UID=10276   policy=262144 (REJECT_ALL)              io.github.dorumrr.privacyflip
UID=1010103 policy=4      (ALLOW_METERED_BACKGROUND) com.android.providers.downloads (work profile)
```
De1984's log, one pass later:
```
21:54:43.797  Applied policy for io.github.dorumrr.happytaxes (UID 10212, no rule (default policy)): policy=ALLOW
21:54:43.952  Applied policy for io.github.dorumrr.privacyflip (UID 10276, no rule (default policy)): policy=ALLOW
21:54:44.017  Applied policy for com.android.providers.downloads (UID 1010103, no rule (default policy)): policy=ALLOW
```
All three are now absent from the table. **Destroyed, not recorded, not restorable.**

Note `POLICY_REJECT_METERED_BACKGROUND` (0x1) is exactly what Android's own user-facing "Restrict
background data" switch writes, so this silently reverses that switch for every app without a De1984
rule.

The damage happens on **start**, not stop, so the P0-2 cleanup does not help. Fixing it needs the
backend to read each uid's current policy with `getUidPolicy` before its first write, store the
originals, and only ever clear policies it set itself.

---

# NEW P2 — the backend picker offers ConnectivityManager where it cannot run

`SettingsFragmentViews.kt:740` gates ConnectivityManager on `hasShizuku && isAndroid13Plus` only. It
never calls `checkAvailability()`.

On the test device `cmd connectivity help` lists only `help` and `airplane-mode` — no
`set-chain3-enabled`, no `set-package-networking-enabled`. The backend cannot work here.

`ConnectivityManagerFirewallBackend.checkAvailability()` (`:414-418`) does detect this correctly, so
selecting the backend fails cleanly rather than silently. The defect is the picker presenting it as
available. Low blast radius, wrong signal to the user.

---

# ADVERSARIAL AUDIT OF THE P0-2 / P0-3 FIXES — 2026-08-22

21 agents, 4 review lenses, every finding put to a skeptic. **17 raised, 9 survived, 8 refuted.**
The 9 collapse into 4 distinct defects. All 4 are now fixed.

## A. Cross-instance race on the original-policy record — FIXED
Raised by 3 lenses independently (deadlock, lifecycle, regression). Four instantiation sites exist:
`FirewallManager.kt:226`, `:845`, `PrivilegedFirewallService.kt:263`, and the cleanup one at
`FirewallManager.kt:685`. Each instance has its own `mutex`, so nothing serialised the
load-mutate-save of the shared prefs key across instances.

**Independently reproduced on hardware before the audit landed**, which is what makes this certain:
```
22:04:15.845  thread 9978  applyRules  →  "skipped 86 unchanged"   (warm cache, instance A)
22:04:16.136  thread 9966  applyRules  →  "skipped 0 unchanged"    (cold cache, instance B)
22:04:31.917  thread 9978  happytaxes → BLOCK   (A reads original 262144, writes 1)
22:04:31.920  thread 9966  happytaxes → BLOCK   (B reads 1, records 1 as the "original")
```
uid 10212's real `POLICY_REJECT_ALL` was replaced in the record by De1984's own blocking value, 3 ms
apart. The audit's worse variant — cleanup instance and service `applyRules` interleaving so a uid is
reverted out of the record while still blocked in `netpolicy.xml` — is the same root cause.

Fix: one process-wide `originalPolicyLock` in the companion object, taken by both `applyRules` and
`clearBlockedUidPoliciesInternal`. Lock order is always instance `mutex` first, then the shared lock.

## B. The revert loop ran on the main thread — FIXED
Raised by 3 lenses. `FirewallManager` contains no `withContext` at all, and `stopFirewall` is entered
from `FirewallViewModel` and `SettingsViewModel:563` on `viewModelScope` (`Dispatchers.Main.immediate`).
`clearBlockedUidPoliciesInternal` hopped to IO only inside `getNetworkPolicyManager()`, so
`initializeReflection()` and one blocking binder call **per uid** ran on the UI thread — while holding
`startStopMutex`, blocking every other start, stop and toggle.

This was introduced by the P0-2 fix: `cleanupAllBackends` previously only fired intents. With
block-all-by-default and a few hundred uids, at the per-call cost implied by the measured 16 s
`applyRules`, this is an ANR.

Fix: `clearBlockedUidPoliciesInternal` now wraps its whole body in `withContext(Dispatchers.IO)`.

## C. The fresh-process sweep skipped NetworkPolicyManager — FIXED
`De1984Application.cleanupOrphanedFirewallRules` swept iptables and ConnectivityManager and ended with
`// NetworkPolicyManager doesn't need cleanup (no persistent state)` — false, and the same false claim
that was corrected in `FirewallManager` but not here.

This mattered: `clearOrphanedPolicies` was reachable **only** from `cleanupAllBackends`, i.e. only from
a user-initiated stop. The whole point of mirroring the uid list to disk was fresh-process recovery —
a crash, or a stop that failed because Shizuku was down — and no fresh-process path ever called it. The
persistence was dead weight.

Fix: the sweep now builds an NPM backend and calls `clearOrphanedPolicies()`, reporting success and
failure separately.

## D. A failed revert was logged as success — FIXED
`clearOrphanedPolicies` returns `Result.failure` rather than throwing when the binder is gone, so the
surrounding `try/catch` never saw it and `"NetworkPolicyManager cleanup completed"` was logged either
way. Fix: `.onSuccess` / `.onFailure`.

## Notable refutations
- *"Stop silently erases the user's own Restrict background data setting"* — **refuted**, because the
  P0-9 read-before-write fix had already landed. Good confirmation that P0-9 closes it.
- *"getUidPolicy failure now silently disables all blocking while reporting success"* — refuted; the
  code counts it as an error and refuses to write.
- *"A mid-loop kill orphans uids because they reach the persisted set only after the loop"* — refuted.

## Still open from this run — NOT fixed
**The duplicate apply.** Two backend instances each run the full `applyRules` for the same rule change:
two passes of ~16 s over 87 uids. Correct now that they share a lock, but it doubles the cost and is
the reason the race existed. The real fix is one backend instance per process, which touches
`FirewallManager` and `PrivilegedFirewallService` lifecycle. Recorded, not attempted.

---

# P0-9 VERIFIED ON HARDWARE — 2026-08-22

Android 14 / SDK 34 / LineageOS / Shizuku running as root / NetworkPolicyManager backend.

Baseline before the run — 7 policies, none of them De1984's:
```
UID=10201 262144   UID=10246 262144   UID=1010103      4
UID=10203 262144   UID=10276 262144   UID=1010246 262144
UID=10212 262144   <- io.github.dorumrr.happytaxes, the test subject
```

Blocked `happytaxes` (uid 10212, original `262144`) and `com.aurora.store` (uid 10269, no policy),
then started the firewall:
```
22:15:03.693  Applied policy for io.github.dorumrr.happytaxes (UID 10212, has rule): policy=BLOCK (REJECT_METERED (Mobile only))
22:15:03.707  Applied policy for com.aurora.store (UID 10269, has rule): policy=BLOCK (REJECT_METERED (Mobile only))
22:15:03.712  ✅ Applied 2 policies, skipped 0 unchanged, left 85 foreign policies alone, 0 errors
```

WiFi then dropped, which made every rule inapplicable and triggered the restore path:
```
22:15:16.423  State changed: network=NONE, screen=true - scheduling rule application
22:15:17.690  Applied policy for io.github.dorumrr.happytaxes (UID 10212, has rule): policy=RESTORED
22:15:17.703  Applied policy for com.aurora.store (UID 10269, has rule): policy=RESTORED
```
Policy table afterwards is **byte-identical to the baseline**, `UID=10212 policy=262144`, and the
record is empty.

**This is the decisive comparison.** The identical restore on the previous build produced `1` —
De1984's own blocking value, recorded as the "original" by the cross-instance race. On this build it
produced `262144`, the true original. The shared `originalPolicyLock` closes it.

Three claims proven in one run:
- **Read-before-write records the real value.** Restore returned `262144`, which is only possible if
  `262144` was what got recorded.
- **Foreign policies are untouched.** `left 85 foreign policies alone` — the pre-fix build wrote to all
  87 and destroyed 3.
- **`0 errors`.** The 8 `cannot apply policy to UID 100xxxx` failures per pass are gone, because the
  backend no longer writes to uids it does not own.

Also observed: the apply loop now takes ~20 ms instead of writing 79 policies. The `applyRules`
16.5 s figure was dominated by package enumeration, but the write phase is now negligible.

Fix C (`De1984Application` orphan sweep) verified in the same session:
```
22:12:06.068  De1984Application: Cleaned up orphaned NetworkPolicyManager policies
```
with the 7 existing policies untouched, because De1984's record was empty.

## NEW P1 — a network transition unblocks every app until the next apply lands
When `networkType` is `NONE`, no rule matches, so every blocked app is restored to its original
policy. That is harmless while there is no network. The problem is the other edge: on reconnect,
`State changed` schedules a debounced (300 ms) rule application, and the apply itself is not instant.

Between the network becoming usable and the apply completing, every app the user believes is blocked
has full network access. Measured on this device: `22:15:02.672 State changed: network=WIFI` ->
`22:15:03.712 Applied` — roughly **1 second** of open access on a light rule set. The same
state-change-then-apply pattern is used by the ConnectivityManager backend.

Not fixed. Needs a decision: keep apps blocked across `NONE` transitions rather than restoring them,
or block first and relax afterwards.

---

# NEW P0-10 — one dropped network latches the firewall off, on every backend — VERIFIED

`NetworkStateMonitor.observeNetworkType()`'s `onLost` callback
(`data/monitor/NetworkStateMonitor.kt:176-179`) hardcodes the answer:

```kotlin
override fun onLost(network: Network) {
    AppLogger.d(TAG, "📡 SYSTEM EVENT: Network lost - type: NONE")
    trySend(NetworkType.NONE)
}
```

`onAvailable` and `onCapabilitiesChanged` both call `getCurrentNetworkType()`. `onLost` does not. It
never asks whether another network is still up, so **any** secondary network going away reports the
device as offline while WiFi is connected and validated.

`FirewallRule.isBlockedOn` returns `false` for `NetworkType.NONE`
(`domain/model/FirewallRule.kt:44`), so at that moment **every rule stops blocking**. All three
consumers of the flow are affected — `FirewallManager.kt:934`, `PrivilegedFirewallService.kt:383`,
`FirewallVpnService.kt:202` — which means **all four backends**, iptables included.

The flow ends in `.distinctUntilChanged()`, so recovery needs a *changed* value. WiFi was already up
and stays up, so nothing new is emitted. The state latches.

## Proven on hardware, 2026-08-22
System view — WiFi never dropped:
```
NetworkAgentInfo{network{101} ni{WIFI CONNECTED} created=2026-08-22T20:26:44Z
  Score(... EVER_VALIDATED&IS_VALIDATED) firstValidated 40096 lastValidated 40096
  nc{[ Transports: WIFI Capabilities: INTERNET&VALIDATED&NOT_METERED ... ]}
```
De1984's view at the same moment:
```
22:15:16.158  📡 SYSTEM EVENT: Network capabilities changed - type: WIFI
22:15:16.416  📡 SYSTEM EVENT: Network lost - type: NONE
22:15:16.436  📡 SYSTEM EVENT: Network lost - type: NONE
   (no network event of any kind after this)
22:15:17.690  Applied policy for io.github.dorumrr.happytaxes (UID 10212, has rule): policy=RESTORED
22:15:17.703  Applied policy for com.aurora.store (UID 10269, has rule): policy=RESTORED
```
Meanwhile the app kept reporting itself fine, once a minute:
```
22:18:33.273  ✅ SERVICE: Health check passed - NETWORK_POLICY_MANAGER is healthy (consecutive successes: 11)
22:18:34.481  ✅ Health check passed: NETWORK_POLICY_MANAGER backend is healthy (consecutive successes: 11)
```
with `firewall_enabled=true`, `privileged_service_running=true`, and **no policy applied to either
blocked app**. State held for over three minutes and was still holding when observed.

**The health check cannot catch this.** It only tests that the backend's binder is reachable
(`checkAvailability()`), never that the rules are actually in force. A firewall that is blocking
nothing passes it every time.

Very likely the root cause of the archived user reports **"Firewall Not Running But The Switch Was On"**
and **"Not Responding"** in `/Users/doru/dev/phi/de1984-feedback-archive/` — those were previously
attributed to P0-3 Deadlock alone.

## FIXED 2026-08-22 — both parts, on Doru's decision

**Part 1 — `onLost` asks what is left.** New private `networkTypeExcluding(lost)` skips the departing
network (ConnectivityManager can still name it as `activeNetwork` for a moment) and falls back to the
remaining `NET_CAPABILITY_INTERNET` networks. `getCurrentNetworkType()` keeps its exact previous
behaviour; both now share a `networkTypeOf(capabilities)` helper.

**Part 2 — `NetworkType.NONE` no longer lifts blocks.** `FirewallRule.isBlockedOn` returns
`wifiBlocked || mobileBlocked || blockWhenRoaming` for `NONE` instead of `false`.

Part 2 turned out not to be a new design at all. `FirewallVpnService` **already** did exactly this,
locally, at two sites, with the same reasoning in its comment:
```kotlin
// When network is NONE (e.g., at boot), block if app has ANY blocking rules
currentNetworkType == NetworkType.NONE -> rule.wifiBlocked || rule.mobileBlocked
```
The other three backends never got it. One rule, two places, drifted — the multi-site invariant
problem. Both local workarounds are now removed and the rule lives once, in `FirewallRule`.
`blockWhenRoaming` is included where the VPN version omitted it; that is a small widening, in the
safe direction, so a roaming-only rule is also held across a network gap.

Checked every other `NetworkType.NONE` site: the remaining ones are just initial values of
`currentNetworkType` in `FirewallManager`, `PrivilegedFirewallService` and `FirewallVpnService`, which
this change makes safer — rules are now in force at startup rather than lifted. No test references
`isBlockedOn` or `NetworkType.NONE`.

### Hardware verification
**Part 2 — PROVEN.** Dropped WiFi with the firewall running:
```
22:25:22.015  📡 SYSTEM EVENT: Network lost - remaining type: NONE
22:25:22.369  Rules count: 12, networkType: NONE, screenOn: true
22:25:26.165  ✅ Applied 0 policies, skipped 87 unchanged, left 0 foreign policies alone, 0 errors
```
`Applied 0 policies` on a `NONE` pass. The identical event on the previous build produced
`Applied 2 policies ... policy=RESTORED`, unblocking both apps. Policy table confirmed both still
blocked (`UID=10212 policy=1`, `UID=10269 policy=1`) throughout.

**The reconnect gap is closed too.** On WiFi return: `✅ Applied 0 policies, skipped 87 unchanged` —
no re-block pass at all, because the block never lifted. The ~1 s window in the P1 above is gone.

**Part 1 — code path live, non-`NONE` return NOT verified on hardware.** The new
`Network lost - remaining type: ...` line proves the new code runs, but it returned `NONE` because
only one network existed at that moment. Three attempts to stage two simultaneous networks failed:
this device tears down the cellular data network whenever WiFi is up, so `dumpsys connectivity` never
showed more than one `NetworkAgentInfo`.

That teardown **is** the trigger. When WiFi connects, the cellular data network is dropped and
`onLost` fires for it while WiFi is perfectly healthy — which is why this latched on a device that
never lost connectivity. It also means the bug fires for most users on most WiFi connects, not in some
rare corner.

To verify later: a device or emulator that holds WiFi and cellular data at once, or a second WiFi/VPN
network. Expect `Network lost - remaining type: WIFI`.

## Original fix direction (superseded by the above)
`onLost` should ask the system what is left, excluding the network that just went away, exactly as the
other two callbacks do. Care is needed because `activeNetwork` can still name the lost network for a
moment, so the check must skip it and fall back to the remaining networks that carry
`NET_CAPABILITY_INTERNET`.

Worth deciding at the same time: whether `NetworkType.NONE` should unblock at all. Blocking rules
could simply be left in force when there is no network — nothing can connect anyway, and it removes
both this failure and the reconnect window in the P1 above.

---

# VERIFICATION AUDIT OF THE SESSION COMMITS — 2026-08-22

Scope: commits `38f0a77`, `3847322`, `96f5a6c`, `0298bc9` (22 files) plus every caller reached from
them. 17 agents over 5 lenses, each finding put to a skeptic. **12 raised, 3 survived, 9 refuted.**
Two more were found by direct reading. All 5 are fixed.

## A. Boot-protection preference was written with apply(), then the device rebooted — FIXED
Found by direct reading, not by the fan-out. `SettingsViewModel.saveSetting` used `editor.apply()`,
which hands the disk write to a background thread. `setBootProtection` then ran `svc power reboot`
immediately. Nothing guarantees that thread finishes first, so the preference could disagree with the
boot script actually on disk — the exact mismatch the forced reboot exists to eliminate.

Fix: `saveSetting` gained `durable: Boolean = false` (`commit()` instead of `apply()`), used only by
the boot-protection write. The other 13 call sites are unchanged.

## B. Unblock dropped the record before the write could fail — FIXED
`applyRules` did `originalPolicies.remove(uid)` and then wrote. A throwing `setUidPolicy` — which the
device demonstrably produces (`cannot apply policy to UID 100xxxx`) — left the uid holding De1984's
blocking policy with its record already gone. Fix: read, write, then remove.
`clearBlockedUidPoliciesInternal` already had the correct order.

## C. The record was persisted only after the whole write loop — FIXED (audit, high)
The in-memory order was right but the durable order was inverted: N binder writes, then one
`apply()`. A process death mid-loop left uids blocked with nothing on disk. The next run would then
read De1984's own blocking value back as that uid's "original" and restore the block forever.

Fix: a pre-pass reads and records every original **before** any policy is written, and flushes it with
`commit()`. The write loop now refuses to write any uid it has no record for. A uid already blocked in
this process (`appliedPolicies[uid] == true`) is skipped by the pre-pass, so De1984's own value can
never be read back as an original.

## D. `NetworkType.NONE` also means "transport I do not recognise" — FIXED (audit, high)
**A regression introduced by 0298bc9.** `networkTypeOf` maps anything that is not WiFi or cellular to
`NONE`, and `observeNetworkType` filters only on `NET_CAPABILITY_INTERNET`. So an Ethernet dock, a USB
or Bluetooth tether, or an Android TV box reports `NONE` **while fully online**. With
`isBlockedOn(NONE)` now holding blocks, every rule carrying any flag would block totally and
permanently on such a device, with no network event able to correct it. Before the commit those three
backends blocked nothing there.

Fix: an internet-capable transport that is neither WiFi nor cellular now maps to `WIFI` — unmetered
and not cellular, so the WiFi rules govern it. `TRANSPORT_VPN` is left mapping to `NONE` deliberately,
to keep the VPN backend's behaviour byte-identical. `isWiFi()`, `isMobile()`, `isRoaming()` and
`isConnected()` have no callers outside the monitor, so nothing else is affected.

**Not verified on hardware** — the test device has no Ethernet.

## E. BootReceiver's success path still keyed the lift on the preference — FIXED (audit, high)
`clearBootBlockIfInstalled()` exists because clearing app data resets `KEY_BOOT_PROTECTION` to false
while leaving the script on disk. `BootReceiver` used it on the failure path and the
firewall-not-enabled path, but its **success** path still read the preference and called
`resetIptablesPolicies()` only if it was true. On Android 11 and below, or via the
WorkManager-unavailable fallback, an app-data clear would leave the `de1984_boot` DROP chain up until
the script's own 120 s timer fired — no network for two minutes after every boot.

Fix: that path now calls `clearBootBlockIfInstalled()` like the other two. `BootWorker` was already
correct — it lifts unconditionally before anything else.

### Verified live after the fixes
```
22:46:40.054  ✅ Applied 2 policies, skipped 0 unchanged, left 85 foreign policies alone, 0 errors
22:46:40.656  De1984.BootReceiver: Lifting any boot protection block after successful start
22:46:40.695  De1984.BootReceiver: ✅ Boot protection block lifted (or none present)
```
The record survived the reinstall intact — `10212:262144`, `10269:0` — and the five foreign policies
were untouched.

## Refuted, worth recording
- *"Removing the pref-gated reset breaks the protected window"* — refuted; `BootWorker` lifts before
  the firewall starts by design, decided earlier in the session.
- *"`blockWhenRoaming` widening blocks a reachable rule state"* — refuted as harmful; widening is in
  the safe direction for a firewall and the state is rare.
- *"In-flight NonCancellable applyRules can re-block uids cleanupAllBackends just reverted"* —
  refuted; the shared `originalPolicyLock` serialises them.
- *"Service cancels its own VPN-fallback coroutine via onDestroy"* — refuted.

## Left open deliberately
- `BootWorker:91-105` still has a preference-gated `resetIptablesPolicies()` that is now redundant,
  because line 58 already lifted unconditionally. Harmless and idempotent, but it is a second way to
  do the same thing. Not removed: in the case "preference true, script absent" the two differ, and
  that difference has not been reasoned through.
- `commit()`'s return value is not checked in either durable write. Disk-full territory only.

---

# FIREWALL.md — VERIFIED DRIFT, PROPOSED WORDING AWAITING APPROVAL

Not written. These are proposals only.

## D1 — Manual Mode lists 3 backends; the app offers 5
`FIREWALL.md:35-45` names VPN, iptables and ConnectivityManager. `SettingsFragmentViews.getAllBackends()`
returns **AUTO, VPN, ConnectivityManager, iptables and NetworkPolicyManager**. There is no
`## 4. NetworkPolicyManager Backend` section either, although the picker offers it whenever Shizuku is
present, with no Android version gate.

`FIREWALL.md:35` currently reads:
> **Important**: The dropdown should only show backends that are currently available on the device.

The code shows every backend and marks the unavailable ones with a requirement line explaining what is
missing. That is better UX than hiding them; the document is what is out of date. Proposed replacement:
> **Important**: The dropdown lists every backend. Ones that cannot run on this device are shown
> disabled, with a line stating what they require, so the user can see why a backend is unavailable
> rather than wondering where it went.

Proposed additions to the **Available backends check** list (`FIREWALL.md:37-40`):
> - **AUTO**: Always available (always shown, and is the default)
> - **NetworkPolicyManager**: Requires Shizuku. Available on every Android version.

and to **User selection** (`FIREWALL.md:42-45`):
> - **Force NetworkPolicyManager**: Only use NetworkPolicyManager (only selectable if Shizuku is
>   available). See the limitation in P0-8 — this backend currently blocks metered background data
>   only; WiFi is never blocked.

## D2 — the AUTO section is correct, no change needed
`FIREWALL.md:11-30` describes iptables -> ConnectivityManager -> VPN. `selectBackend(FirewallMode.AUTO)`
matches exactly, with no NPM branch. **Verified accurate.**

## D3 — no-network behaviour is not covered anywhere
`FIREWALL.md` never mentions `NetworkType.NONE` or what happens with no network. It is now
load-bearing: blocks are held rather than lifted. Proposed new subsection under **Firewall State
Machine**:
> ### No network
> When the device has no usable network, blocking rules stay in force rather than being lifted.
> Nothing can connect with no network, so holding them costs nothing, and lifting them opened a window
> on reconnect where every app was unblocked until the next rule pass landed. Transports the app has
> no separate switch for — Ethernet, USB and Bluetooth tethering — are treated as WiFi, not as
> "no network".

## D4 — boot protection is undocumented, including the forced reboot
Neither `FIREWALL.md` nor `README.md` mentions boot protection. It now **reboots the device
immediately** on both enable and disable, straight after the confirmation dialog, with no second
prompt. That is a large, surprising behaviour with no documentation anywhere. Recommend a short
section; wording to be drafted once Doru confirms where it belongs.

---

# P0-8 FIXED AND VERIFIED ON HARDWARE — 2026-08-22

Two defects, one masking the other.

**The probe could never succeed.** `testPolicySupport` wrote `setUidPolicy(0, ...)` — to **uid 0** —
and treated "did not throw" as support. Android's `NetworkPolicyManagerService` rejects a policy on
any non-app uid, so it threw on every device and every Android version. The catch branch then pinned
`blockingPolicy = POLICY_REJECT_METERED_BACKGROUND` permanently.

**The constant was the opposite of a block.** `POLICY_REJECT_ALL` was `0x4`, which is AOSP's
`POLICY_ALLOW_METERED_BACKGROUND` — an allowance. Had the probe ever succeeded, every app the UI
showed as Blocked would have been granted a metered-background allowance instead.

## The fix
- `POLICY_REJECT_ALL` is now `0x40000`, the value LineageOS-family ROMs use and their dumpsys decodes
  as `REJECT_ALL`. `0x4` is kept, named `POLICY_ALLOW_METERED_BACKGROUND`, so it cannot be mistaken
  for a blocking value again.
- `testPolicySupport` is gone. `calibrateBlockingPolicy` runs once, on the **first uid actually being
  blocked** — a real app uid whose original policy the pre-pass has already recorded, so the
  calibration write is recoverable and the real blocking write follows immediately.
- Calibration checks the value was **stored**, by reading it back with `getUidPolicy`, not merely that
  the call did not throw. A ROM can accept the call and store something else.
- If a ROM ever stores `POLICY_ALLOW_METERED_BACKGROUND` in response to a blocking write, that is
  logged as an error and rejected — the historical trap, guarded explicitly.

## Verified on hardware — Android 14 / LineageOS / Shizuku as root
```
22:51:17.311  ✅ POLICY_REJECT_ALL is supported - blocking WiFi and Mobile
22:51:17.326  Applied policy for io.github.dorumrr.happytaxes (UID 10212, has rule): policy=BLOCK (REJECT_ALL (WiFi+Mobile))
22:51:17.345  Applied policy for com.aurora.store (UID 10269, has rule): policy=BLOCK (REJECT_ALL (WiFi+Mobile))
22:51:17.359  ✅ Applied 2 policies, skipped 0 unchanged, left 85 foreign policies alone, 0 errors
```
Policy table, `com.aurora.store`:
```
before: UID=10269 policy=1      (REJECT_METERED_BACKGROUND)   <- metered background only
after:  UID=10269 policy=262144 (REJECT_ALL)                  <- WiFi and Mobile
```
**This device could always do full blocking.** The backend never tried, purely because of the broken
probe. Every NetworkPolicyManager user on a ROM that supports `REJECT_ALL` was silently getting
metered-background-only blocking while the UI said Blocked.

Foreign policies untouched (`left 85 ... alone`), zero errors.

## Still open — needs a product decision
On a ROM **without** `POLICY_REJECT_ALL`, the backend still degrades to metered-background-only and
the UI still says Blocked. The degradation is now logged loudly and correctly, but nothing surfaces it
to the user. Options: show a warning on the backend picker and the firewall screen, refuse to run the
backend at all, or accept the log-only behaviour. Not implemented; an unwired `blocksAllNetworks()`
helper was written and then removed rather than left as dead code.

---

# DUPLICATE BACKEND MONITORING — FIXED AND VERIFIED — 2026-08-22

`FirewallManager.startMonitoring()` observed network state, screen state and the rules repository, and
applied rules to its **own** backend instance. `PrivilegedFirewallService` observes the same three
signals and applies rules to **its** instance. Both instances are of the same backend class, so every
rule change ran two full passes over every uid — measured at ~16 s each — and it is what let two
instances race over the shared policy record.

The codebase already had the rule, at `FirewallManager.kt:207`:
```kotlin
// Note: iptables backend uses PrivilegedFirewallService for monitoring, so don't call startMonitoring() here
```
It was applied to iptables and never extended, although `ConnectivityManagerFirewallBackend` and
`NetworkPolicyManagerFirewallBackend` both start the same service (`:74`/`:121` and `:115`/`:163`).
The same one-rule-in-two-places drift as `FirewallVpnService`'s `NetworkType.NONE` workaround.

Fix: the exclusion now covers all three privileged backends. Three call sites — `:220`, `:233`, and the
`if` block in `startFirewallInternal`.

## Verified on hardware — one toggle of com.aurora.store
```
22:59:17.725  Debounce START (300ms): source=flow
22:59:17.812  Debounce START (300ms): source=broadcast
22:59:18.137  Debounce END: +345ms
22:59:27.526  Applied policy for com.aurora.store (UID 10269, has rule): policy=RESTORED
22:59:27.537  ✅ Applied 2 policies, skipped 0 unchanged, left 85 foreign policies alone, 0 errors
```
Two schedule triggers collapsed into **one** pass by the service's own debounce. One `Applied` line
where there used to be two. `FirewallManager` emits no `Rules changed in repository` or
`State changed` at all any more.

The two passes still seen at app start are not duplication: one is the service's debounced initial
apply, one is the single `applyRulesToBackend` during the atomic backend switch.

The same toggle also proved the full P0-2 / P0-9 / P0-8 chain end to end: Aurora was restored to its
exact original (absent from the policy table, back to `0`), its entry was dropped from
`npm_original_policies`, HappyTaxes kept its true `262144`, and the 85 foreign policies were untouched.

## Left in place — needs a decision
`startMonitoring()` now has **zero callers**, and the `monitoringJob` / `ruleChangeMonitoringJob` pair
exists only for it — `stopMonitoring()` still has 5 callers, so it stays either way. Roughly 35 lines
of now-dead machinery in the file that runs the firewall. Not deleted: the behaviour change was worth
proving first, and the demolition should be deliberate.

## NEW P2 — every rule change re-enumerates all 466 packages
Observed in the same run:
```
22:59:18.058  HiddenApiHelper: Cleared installed apps cache
22:59:27.526  (apply completes)                                   ~9.4 s later
```
The package cache is cleared immediately before each apply, so `applyRules` re-scans every package on
every rule toggle. At app start the same scan measured `getInstalledApplicationsAsUser took 6000ms`
for the work profile alone and `getPackages COMPLETE - Total time: 8150ms for 466 packages`. This, not
the policy writes, is what makes a rule change feel slow. Pre-existing; not touched.

---

# P0-6 CAPTIVE PORTAL — 3 BUGS FIXED, 1 UNFIXABLE IN-APP, 1 WITHDRAWN — 2026-08-22

## Withdrawn: "resetToDefaults silently sends privacy users to Google" — NOT A DEFECT
The earlier entry was wrong. The UI says Google three times:
```
settings_captive_portal_reset_google   = "Reset to Google"
dialog_captive_portal_reset_title      = "Reset to Google Defaults?"
dialog_captive_portal_reset_message    = "...reset captive portal settings to Google's default servers."
```
and the function is named `resetToGoogleDefaults`. Nothing is silent and nothing is mislabelled. No
change made.

What genuinely does **not** exist is a "reset to this device's own defaults", which would simply
`settings delete global` the three keys and let the ROM's built-in values apply. That is a new
feature, not a fix, so it is not implemented — raised for Doru.

## FIXED 1 — capture invented values for keys that were never set
`captureOriginalSettings` went through `getCurrentSettings()`, whose line 55 does
`?: Constants.CaptivePortal.DEFAULT_MODE`, and whose `useHttps` is a non-null Boolean. An unset key
was therefore stored as a made-up default.

Proven live on the test device:
```
device:  captive_portal_mode      = null    captive_portal_use_https = null
app had: captive_portal_original_mode = 1   captive_portal_original_use_https = false
```
Tapping "Restore original" would have **created a `captive_portal_mode` the device never had**.

Fix: capture now reads the raw system values directly with `getSystemSetting`, which already returns
null for an unset key, and stores them under two new string keys (`KEY_ORIGINAL_MODE_RAW`,
`KEY_ORIGINAL_USE_HTTPS_RAW`) that can express "unset". The four URL keys were already `String?` and
were already correct.

## FIXED 2 — restore could never un-set anything
There was no `settings delete global` anywhere in the file. `restoreOriginalSettings` wrote the mode
unconditionally and used `httpUrl?.let` / `httpsUrl?.let`, so a URL that was unset originally was
skipped — leaving De1984's own URL in place while the call reported success.

Fix: new `deleteSystemSetting(key)`. Restore now walks a list of key/value pairs and deletes the key
when the recorded value is null, writes it otherwise.

## FIXED 3 — only 3 of the 6 captured keys were ever written
`SYSTEM_KEY_FALLBACK_URL`, `SYSTEM_KEY_OTHER_FALLBACK_URLS` and `SYSTEM_KEY_USE_HTTPS` were captured
into the backup but no code path wrote them — not apply, not restore, not reset. The backup looked
more complete than it was. Restore now covers all six.

## Also removed — a dead public API that fabricated
`getOriginalSettings()` had **zero callers** and read the legacy Int/Boolean keys, so after the capture
change it would have returned `DEFAULT_MODE` and `true` for every fresh backup. 28 lines removed rather
than fixed.

`DEFAULT_MODE` now survives in exactly three correct places: the UI display path, the legacy-backup
fallback, and the explicit "Reset to Google" action. Nothing writes a substitute into a backup.

## Backwards compatibility
A backup written before this change is in the legacy Int/Boolean format. `originalRawMode()` and
`originalRawUseHttps()` read those verbatim — exactly what the old restore would have written — so an
existing backup behaves as it did. It does not become correct, because the fabricated values are
already baked in; only a fresh capture can fix that.

## NOT FIXED — reinstall or Clear Data destroys the true original
`KEY_ORIGINAL_CAPTURED` lives in the app's own SharedPreferences and `allowBackup=false`. After a
reinstall the flag is gone, so the next capture records De1984's **own** current values as pristine.

The mitigation proposed earlier — refuse to capture when the current values match a De1984 preset —
**does not work**, and the test device proves why: its genuine ROM default is
`http://cp.cloudflare.com`, which *is* the CLOUDFLARE preset. Refusing would break legitimate first
capture on exactly the ROMs this app targets.

There is no reliable in-app fix: nothing the app owns survives uninstall, and writing a marker into
`Settings.Global` would add the very device-wide state the finding is about. The workable options are
UI, not logic — warn at capture time when the values match a preset, and let the user view and edit
the stored original. Both are new features; not implemented.

## Verification
Compiles; installed; no crashes; the existing backup was correctly left untouched because
`hasOriginalSettings()` is true. **The new capture path is code-verified only** — proving it needs the
captured flag cleared so a fresh capture runs, which would overwrite Doru's existing backup. Not done
without his say-so.

---

# AUDIT ROUND 2 — 4 CODE DEFECTS FIXED, 1 DOC FINDING PENDING — 2026-08-22

Scope: commits `c5ee247` and `a661f7c`. 12 agents, 4 lenses, each finding put to a skeptic.
**8 raised, 5 survived, 3 refuted.** Four were code and are fixed; one is documentation and needs
Doru's approval. Two more were found by direct reading before the fan-out returned.

## FIXED (found by direct reading) — capture without privileges would wipe the user's config
`captureOriginalSettings` had **no privilege check** and is called unconditionally from
`SettingsViewModel:1029` whenever the captive-portal screen opens. Without root or Shizuku the
`settings get global` command cannot run, so `getSystemSetting` returns null for **every** key -
identical to a key being genuinely unset. The backup would record "all six unset", and a later
restore, which *does* require privileges, deletes a key recorded as unset. All six keys gone.

Before the raw-capture change this path stored `mode=DEFAULT_MODE` and skipped null URLs, so it was
wrong but harmless. The raw-capture change turned it destructive. **A regression introduced by
a661f7c.** Fix: capture now requires privileges and refuses otherwise.

## FIXED (found by direct reading) — frozen network and screen state
Removing `startMonitoring()` left `currentNetworkType` and `isScreenOn` with **no assignment
anywhere** - read only by `applyRulesToBackend`. Frozen at `NetworkType.NONE`, and
`isBlockedOn(NONE)` now blocks, so every backend switch over-blocked until the service corrected it.

Fix: both fields deleted; `applyRulesToBackend` reads the live state via
`networkStateMonitor.getCurrentNetworkType()` and `screenStateMonitor.isScreenOn()`. The
`screenStateMonitor` dependency is back, but fed on demand rather than by a duplicate monitoring loop,
so the duplication fix stands. (The fan-out raised this too and its own skeptic refuted it — correctly,
because it read the code after the fix had landed.)

## FIXED — calibration could latch the weakest policy forever
`calibrateBlockingPolicy` was handed whatever uid came first in `desiredPolicies` with
`shouldBlock=true`. That map is built from every package across every profile with **no uid-range
filter**, so system uids are in it — proven on hardware earlier in the session by
`cannot apply policy to UID 1001001` and `UID 1001002`. `getUidPolicy` does *not* throw for those, so
the pre-pass records them and `isOurs` is true, which bypasses the bail-out. `setUidPolicy` then
throws, the catch returned `POLICY_REJECT_METERED_BACKGROUND`, and `policyTested` latched — restoring
the exact permanent degradation P0-8 was meant to end.

Fix: calibration skips any uid whose appId is outside `10000..19999`, and a thrown write now returns
**null** meaning "inconclusive", which does not latch — the next uid is tried instead.

## FIXED — read-back proved storage, not enforcement
AOSP's `setUidPolicy` does not validate the policy bits: it stores the int and `getUidPolicy` hands it
straight back. `POLICY_REJECT_ALL` does not exist in AOSP at all. So on stock AOSP, writing `0x40000`
and reading `0x40000` back was **guaranteed** to succeed while nothing enforced it — every "blocked"
app would have had full network access. Worse than the old fallback, which at least blocked metered
background data.

Fix: `blockingPolicy` now starts at `POLICY_REJECT_METERED_BACKGROUND` — the value every ROM enforces
— and is upgraded only when the ROM's own `dumpsys netpolicy` decoder **names** the value `REJECT_ALL`
for that uid. A ROM prints that name only for a constant it implements.

Verified live on LineageOS:
```
23:25:42.707  ✅ POLICY_REJECT_ALL is supported - blocking WiFi and Mobile
23:25:42.727  happytaxes (UID 10212, has rule): policy=BLOCK (REJECT_ALL (WiFi+Mobile))
```

### The negative case is proven too, without a stock device
The discriminator rests on one property: a ROM's dumpsys decoder names only the constants it
implements. Tested directly by writing a value **no** ROM defines (`0x80000`) to a spare app uid:
```
UID=10212 policy=262144 (REJECT_ALL)     <- constant this ROM implements -> NAMED
UID=10269 policy=524288 (80000)          <- value it does not know       -> raw hex, NO NAME
```
The spare uid was restored to no policy afterwards and confirmed clean.

### Then confirmed on real stock AOSP — Pixel 7 AOSP 13 (API 33) emulator
Wrote `262144` to an app uid on a stock AOSP image and read the same dumpsys:
```
UID=10100 policy=262144 (40000)                  <- stock AOSP: stored, but NOT named
UID=10055 policy=4 (ALLOW_METERED_BACKGROUND)    <- 0x4 IS named, in AOSP itself
```
against LineageOS on the physical device:
```
UID=10212 policy=262144 (REJECT_ALL)             <- named
```

Three things are settled by this:
1. **The audit's finding was right.** Stock AOSP *stores* `0x40000` and hands it back unchanged, so a
   read-back check alone would have passed there and left every "blocked" app fully open.
2. **The discriminator works in both directions.** Running the exact string test the code performs:
   stock AOSP's line contains `UID=10100 ` but not `REJECT_ALL` -> false -> keeps
   `POLICY_REJECT_METERED_BACKGROUND`. LineageOS's line contains both -> true -> uses `REJECT_ALL`.
3. **`0x4` really is `POLICY_ALLOW_METERED_BACKGROUND` in AOSP**, printed by AOSP's own decoder. The
   original `POLICY_REJECT_ALL = 0x4` would have granted an allowance to every app shown as Blocked.

The emulator's test policy was reverted and the emulator shut down.

## FIXED — a default-policy change still ran two full passes
`SettingsViewModel.setDefaultFirewallPolicy` calls `triggerRuleReapplication()` **and** broadcasts
`FIREWALL_RULES_CHANGED`, which `PrivilegedFirewallService` and `FirewallVpnService` already turn into
a pass. The duplication removed everywhere else survived on this one path.

Fix: `triggerRuleReapplication` still clears this instance's cache — that matters for the next backend
switch — but no longer schedules its own pass. That orphaned `scheduleRuleApplication()`,
`ruleApplicationJob` and `RULE_APPLICATION_DEBOUNCE_MS`, all removed.

## FIXED — a legacy backup would write a key the old restore never touched
The old capture stored `useHttps` as `raw?.toIntOrNull() == 1`, so an **unset** key became `false`, and
the old restore never wrote `captive_portal_use_https` at all. The new six-key loop would have written
`captive_portal_use_https=0` on a device that never had the key, turning off the HTTPS portal probe.
The claim in the KDoc that "nothing gets worse" was true for `mode` and **false** for this key.

Fix: a legacy backup (neither raw key present) restores exactly the three keys the old code did.

## Refuted, worth recording
- *"Pre-upgrade backups turn ambiguous nulls into `settings delete`"* — refuted; the legacy path never
  reaches the delete branch for the affected keys.
- *"FIREWALL.md:521 describes the NetworkPolicyManager health check as a shell command"* — refuted; the
  quoted text does not say that.

---

# FIREWALL.md — ONE VERIFIED DRIFT, WORDING AWAITING APPROVAL

## D5 — line 314 says the ConnectivityManager backend ignores network changes
Current text at `FIREWALL.md:314`:
> Network changes have no effect on blocking decisions since all apps are either blocked everywhere or
> allowed everywhere. The backend does not recalculate rules when switching between WiFi/Mobile/Roaming.

Contradicted by the code: `PrivilegedFirewallService:383-392` observes the network type and calls
`applyRules` on every change, and `ConnectivityManagerFirewallBackend:271` evaluates
`rule.isBlockedOn(networkType)`.

The outcome is *usually* the same, because only a single Block Network toggle is offered and
`migrateRulesToSimple` flattens granular rules. But a non-uniform rule can survive: when the firewall
is restarted through `SettingsViewModel.restartFirewallIfRunning`, `stopFirewall` nulls
`currentBackend` first, so `FirewallManager` sees `wasGranular=false` and skips the migration.

Proposed replacement:
> Rules are re-applied on every network change: PrivilegedFirewallService observes the network type
> and calls `applyRules`, which evaluates `rule.isBlockedOn(networkType)`. The result is normally
> identical, because this backend offers only a single Block Network toggle and granular rules are
> flattened by `migrateRulesToSimple` when switching from VPN or iptables. A rule that is still
> non-uniform — for example one that survived a restart where the migration did not run — will
> therefore change behaviour between WiFi and Mobile.

The two lines below it ("Switching between WiFi and Mobile has no effect...") carry the same claim and
would need the same treatment.

---

# GAP 3 — Ethernet mapping: STILL UNVERIFIED, and why

`NetworkStateMonitor.networkTypeOf` maps an internet-capable transport that is neither WiFi nor
cellular nor VPN to `NetworkType.WIFI`, so an Ethernet dock or TV box is not reported as "offline".
That branch has never executed under test.

Attempted with the Pixel 7 AOSP 13 emulator. It reports only:
```
Transports: CELLULAR
Transports: CELLULAR|WIFI
Transports: WIFI
```
No `ETHERNET`, so it is not a test bed for this branch. Stopped there rather than spending more, as
agreed.

Remaining options, none taken: a device with a USB-C Ethernet dock or an Android TV box; or making
`networkTypeOf` internal and adding the project's first unit test, which needs Robolectric or
mockito-inline added to a build that currently has no test dependencies at all.

The branch is four lines and has been reviewed, but it is **reasoned, not proven**.

---

# P0-6 CLOSED END TO END ON HARDWARE — 2026-08-22

Full round trip on the physical device, driven by a real user action rather than a staged one.

**1. Capture**, with the format marker added minutes earlier:
```
captive_portal_original_raw_format = 1
captive_portal_original_http_url   = http://cp.cloudflare.com
captive_portal_original_https_url  = https://cp.cloudflare.com
captive_portal_original_mode_raw       ABSENT
captive_portal_original_use_https_raw  ABSENT
captive_portal_original_fallback_url   ABSENT
```
Device truth at that moment: `mode`, `fallback_url`, `other_fallback_urls` and `use_https` all `null`,
the two URLs Cloudflare. The capture matched it exactly. The old code recorded `mode=1` and
`use_https=false`, both invented.

**2. Doru applied the GrapheneOS preset.** Only the two URL keys changed; `captive_portal_mode` stayed
`null`, confirming `applyPreset` does not create keys either.

**3. Restore Original:**
```
Restoring captive_portal_mode: was unset, deleting
Restoring captive_portal_http_url: http://cp.cloudflare.com
Restoring captive_portal_https_url: https://cp.cloudflare.com
Restoring captive_portal_fallback_url: was unset, deleting
Restoring captive_portal_other_fallback_urls: was unset, deleting
Restoring captive_portal_use_https: was unset, deleting
Original settings restored successfully
```
Device afterwards is byte-identical to before the preset was applied, `captive_portal_mode` still
`null`. **The old code would have created `captive_portal_mode=1` at this point** - a setting the
device has never had - and would never have touched the other three keys at all.

All three fixed defects are proven by this single run: no fabricated values, deletion of keys that were
unset, and all six keys covered instead of three.

## Also fixed during this test — legacy detection was wrong
`isLegacyBackup` inferred the backup format from whether the raw keys were present. SharedPreferences
**removes** a key when the stored value is null, so a correct fresh capture on a device where `mode` and
`use_https` are both unset leaves neither raw key behind and was misread as a pre-raw backup - which
would have skipped three keys on restore. Harmless on this device, wrong on a device where
`fallback_url` was set.

Found by running the test, not by reading. Fixed with an explicit
`KEY_ORIGINAL_RAW_FORMAT` marker written at capture time.

## Settled: Cloudflare was the ROM default, not a De1984 change
The capture ran before any preset was applied and recorded Cloudflare, and this is a LineageOS-family
build, which defaults to `cp.cloudflare.com`. So the earlier worry that the true original might already
have been lost does not apply to this device.

---

# FIREWALL.md DELIBERATE SWEEP + BACKLOG — 2026-08-22

## The sweep: 21 claims challenged, 15 were wrong
28 agents, one per document section, each finding put to a skeptic. **15 confirmed, 6 refuted.** All 15
written, plus 4 more leftovers the mechanical pass missed and 2 leaked agent notes I had to clean out
of the file by hand - worth remembering that applying generated replacement text needs a read-through,
not just a diff-stat.

The worst of them:
- **`FirewallState.Switching` does not exist.** Zero hits in the codebase. The document listed it as
  state 4 and used it in three transitions and two UI rules. `FirewallState` is only `Stopped`,
  `Starting`, `Running`, `Error`.
- **Every health-check number was wrong.** Document said 1s -> 5s -> 10s -> 30s with a reset to 1s.
  Code has exactly two intervals, `15_000L` and `60_000L`, one step-up at 10 successes, reset to 15s.
  The device log agrees with the code.
- **`su -c id`** - the document prescribed exactly what `RootManager.verifyRootWithCachedShell` goes
  out of its way to avoid. Following the document would reintroduce the Magisk toast spam bug.
- **`iptables -L`** appears nowhere in the code; the probe is `iptables --version`.
- **The migration rule contradicted itself.** "1-2 networks blocked -> fully blocked" and "1-2 networks
  allowed -> fully allowed" describe the same state. Code resolves it as fully blocked, always.
- **"VPN: always available, no special requirements"** - false. `startFirewallInternal` refuses to
  start at all when another VPN holds the slot and there is no privileged access.
- **"Automatic backend switch: silent, no notification"** - it posts "Firewall Upgraded".

## Also fixed in code — six log lines that lied about their own intervals
`(30 seconds)` where the value is 15s, `(5 minutes)` where it is 1 minute, in both health loops and
two comments. Behaviour-neutral, but actively misleading: **that log misled me earlier in this same
session** when I read "5 minutes" and believed it. They now print the interpolated value only.

## P0-7 — `./dev.sh create-keystore` can no longer destroy the signing key
`cp "$KEYSTORE_PATH" "${KEYSTORE_PATH}.backup"` overwrote any existing backup, so a second run replaced
the backup of the original with the first replacement key. The same bug existed for
`keystore.properties`.

Both now refuse to touch an existing `.backup` and fall back to a timestamped name. Proven with a
two-run simulation: after run two, `k.jks.backup` still contained `ORIGINAL KEY` and the second copy
went to `k.jks.backup.20260822-234651`.

## The slow rule toggle — 9,200 ms -> 2,796 ms, measured
The dominant cost was never the policy writes. It was rebuilding "which packages request a network
permission": one `getPackageInfoAsUser` binder call per package, 466 of them, uncached - and the same
18-line filter was **copy-pasted into four places** (NPM, iptables, ConnectivityManager, and the VPN
service has its own variant).

Three fixes, each measured on the device:
1. **One cached helper**, `HiddenApiHelper.getPackagesWithNetworkPermissions`, replacing the three
   identical privileged-backend copies. (The two other `getInstalledApplicationsAsUser` sites in
   iptables are *not* the same thing - they need every package for the shared-uid exemption - and were
   correctly left alone.)
2. **Stampede protection.** Two backend instances both asked at startup and both paid full price:
   `9499ms` each. Now one computes and the other reuses it - a result produced *after* a caller
   started waiting is by definition fresher than that caller, so the TTL is ignored for it.
3. **Real invalidation, then a longer TTL.** The TTL was 5 s while the work it guarded took 5-9 s, so
   it could never serve a hit. `PackageAddedReceiver` and `PackageChangedReceiver` now drop the caches
   on add, change and removal - `PACKAGE_REMOVED` and `PACKAGE_FULLY_REMOVED` were not being listened
   for at all before - which makes a 60 s TTL safe.

```
before:  Found 110 packages in 9499ms  x2 concurrent   ->  applyRules 9200ms
step 2:  Found 110 packages in 4764ms  + "Reusing ..."  ->  applyRules 3806ms
step 3:  Found 110 packages in 3284ms  + "Reusing ..."  ->  applyRules 2796ms
```

**Not yet measured: a repeat toggle inside the 60 s window**, which is where the TTL should now give a
near-instant apply. Needs one more rule toggle on the device.

---

# PICK UP HERE — next session

Doru will install on a real Android device, then we resume.

## Test 1 (decides everything): does `netd` wipe the boot chain?

`post-fs-data` runs **before** `netd`. If `netd` rebuilds the filter table without `--noflush`, it
destroys `de1984_boot` early — meaning **P0-1 Boot protection** barely protects anything AND the
lockout mostly cannot happen. Nothing in the repo settles this. One test answers it.

**Needs:** a rooted device (Magisk/KernelSU/APatch), USB cable, `adb` on the Mac.
**Warning:** this deliberately puts the device into the risky state. Keep the USB cable attached — per
**P0-1 Boot protection**, wireless ADB does not work while the chain is live (uid 2000 is dropped).

```
# 0. from the repo, put a debug build on the device (keeps data)
cd /Users/doru/dev/phi/de1984
./dev.sh update

# 1. in the app: grant root, turn the FIREWALL ON, then turn BOOT PROTECTION ON

# 2. confirm the script landed
adb shell su -c 'ls -l /data/adb/post-fs-data.d/de1984_boot_protection.sh'
adb shell su -c 'cat /data/adb/post-fs-data.d/de1984_boot_protection.sh'

# 3. reboot and wait for the device to come back
adb reboot
adb wait-for-device
sleep 45

# 4. THE ANSWER — is the chain still there after netd started?
adb shell su -c 'iptables -S OUTPUT | grep de1984_boot'
adb shell su -c 'iptables -S de1984_boot'
```

**Reading the result**
| Output | Meaning |
|---|---|
| Rules printed | The chain survives netd. **P0-1 Boot protection is real and severe.** Part A becomes urgent. |
| Nothing printed | netd wiped it. Boot protection is largely inert — the feature does not work, and the lockout risk is small. Different problem, much lower severity. |

**Cleanup, always run this afterwards:**
```
adb shell su -c 'rm -f /data/adb/post-fs-data.d/de1984_boot_protection.sh'
adb shell su -c 'for t in iptables ip6tables; do $t -D OUTPUT -j de1984_boot 2>/dev/null; $t -F de1984_boot 2>/dev/null; $t -X de1984_boot 2>/dev/null; done'
adb reboot
```

## Test 2: reproduce the worst path of P0-1 Boot protection
Only if Test 1 printed rules. Firewall **OFF**, boot protection **ON**, reboot → check whether normal
apps have internet. Expected per code: they do not, on every boot, permanently.

## Test 3: P0-2 NetworkPolicyManager orphan policies
Pick NetworkPolicyManager in Settings, block an app, stop the firewall, uninstall De1984, reboot.
Then: `adb shell su -c 'cat /data/system/netpolicy.xml'` — check whether the uid policy is still there.

## Test 4: P0-4 exported widget receiver
```
adb shell am broadcast -a io.github.dorumrr.de1984.FIREWALL_STATE_CHANGED --es firewall_state "Stopped"
adb shell run-as io.github.dorumrr.de1984.debug cat shared_prefs/de1984_prefs.xml | grep firewall_enabled
```
If the flag flipped to false, any installed app can permanently disable the firewall.

## Then
- Walk through the 117 medium findings not yet reviewed.
- Walk through the resources/localisation audit and the user-log evidence mapping.
- Settle the 4 open sub-decisions under **P0-1 Boot protection — DECIDED FIX DIRECTION**.
