# De1984 — Work List

Open GitHub issues, ordered. This file is the queue, not an archive.

**Trust order: current code first, this file second.** Every claim below was checked against code on
the date shown. Verify before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NOT VERIFIED` = reasoned, no device run.

The old audit log — findings, hardware test notes, the backend capability matrix, the settled
product decisions, the multi-site invariant traps — was cleared on 2026-08-27. All of it is in git:

```
git show ac1ce21:PLAN.md      # the last full version
git log --follow -p PLAN.md   # everything removed, and when
```

Released: v2.6.6 (versionCode 37), commit `4c6e9f23`, tag `v2.6.6`.

---

# Now

## #61 — Multiuser and work profile

Open since 2025-12-01. 20 comments. The reporter's last word (2025-12-17) was *"all the other
issues persist"*. Too old to stay an umbrella — split it before writing more code.

**Shipped and confirmed by the reporters:** work profiles are detected and listed, external
enable/disable is picked up for user 0, the landscape crash is gone, the work-app popup freeze is
gone, list scrolling is smooth.

**Still open, four pieces:**

### 61a. Work-profile status never refreshes by itself — `FIXED 2026-08-27`, one gap left

**The bug.** `PackageMonitoringService` compared a set of `(packageName, userId)` pairs. Disabling an
app does not remove it from the device, so that set never changed and an enable or disable was
invisible — it could only ever see apps appearing. Meanwhile `ACTION_PACKAGE_CHANGED`, which covers
this for user 0, never reaches another profile.

**Fixed** by also tracking which packages are disabled per profile
(`HiddenApiHelper.readDisabledPackagesFresh`, `PackageMonitoringService.checkForEnabledStateChanges`).
One `pm list packages -d --user N` per profile per poll, not one call per package — that is what
makes it affordable inside the 15-second loop.

Three traps closed while writing it:

- A failed read used to be indistinguishable from "nothing is disabled". `queryDisabledPackages` now
  returns **null on failure**, and a profile whose read fails is skipped rather than recorded.
  Without that, one bad shell call reads as every disabled app having just been enabled.
- `disabledPackagesCache` had no lock and now has two threads. `disabledPackagesLock` added.
- `installedAppsCache` was `@Volatile` only, which publishes the reference and not the contents,
  with four threads reaching `.clear()`. `installedAppsLock` added — taken **only on its own**,
  never while holding `networkPackagesLock`, because the reverse nesting is real and would deadlock.
- Profiles that disappear are pruned from `lastKnownDisabled`, so a work profile removed and
  re-created on the same userId does not report a change that never happened.

**Verified on hardware 2026-08-27** (TrebleDroid GSI, Android 14, work profile as user 10): disabling
NewPipe in user 10 from outside the app logged *"1 newly disabled, 0 newly enabled"* within 20 s;
re-enabling logged the reverse; 40 s idle produced **0** spurious events; 0 crashes. Re-run after the
lock changes with the same result.

**Still open — nothing watches other profiles until the app is opened.** The service is started from
`MainActivity.kt:165` alone. `START_STICKY` restarts it after a kill, but nothing bootstraps it at
boot, so a work-profile app installed or disabled while De1984 is closed is unnoticed until you next
open it. Fixing it needs a boot hook or a scheduled worker.

**One correction kept from the audit:** `PackageMonitoringService` is not the only code that
*enumerates* profiles — `HiddenApiHelper.getUsers` has 12 call sites. It is only the code that
*watches* them over time. And `PackageChangedReceiver.kt:71` does derive a `userId` from `EXTRA_UID`,
so it is not blind to other profiles — the gap is delivery, not handling.

### 61b. `getInstalledApplicationsAsUser` returns 0 for the work profile — `VERIFIED 2026-08-25`, downgraded `2026-08-27`

Observed twice: `Profile 10 (Work): ... returned 0 packages` from the hidden API, before a Shizuku
fallback found 216. It is why `AndroidPackageDataSource.hasNetworkPermissions` must stay a
per-package call, and why the `getPackageInfoAsUser` cache is capped near a 49% hit rate.

**Downgraded on 2026-08-27 after reading the code.** The blast radius is much smaller than first
written: `HiddenApiHelper.kt:295-373` has three strategies, not one — hidden API, then root shell,
then Shizuku. `cacheInstalledApps` is called only on a non-empty result, so the final `emptyList()`
is never cached. And `deleteRulesByUserId` / `deleteRule` have **zero call sites**, so no bad read
can remove a rule. Worst case is a transient empty list, not lasting damage. Fix it for
correctness, not urgency.

**But read *Block All fails OPEN* below before trusting that.** "A transient empty list" sounds
harmless; in Block All mode it means nothing gets blocked while the user believes everything is. The
downgrade is right about lasting damage and wrong about the moment itself.

### 61c. Firewall takes too long to become active — `MEASURED 2026-08-27`, partly fixed

Reporter measured 1 m 35 s and compared AFWall+ at 6 s for 181 apps.

**The condition matters.** All of the expensive work sits inside `if (isBlockAllDefault)`. On a
device set to "allow all" it never runs at all — which is why an earlier 1.62 s measurement here was
meaningless. The reporter suspected this themselves: one of their log files was named *"Firewall
Block All By Default May Have Something To Do With It"*.

**Measured on hardware** (110 packages with network permissions across 2 profiles, 103–106 rules
written, Block All on):

| | start time | getPackageInfo cache |
|---|---|---|
| before both fixes | 8.35 s | 53 hit / 587 miss |
| after the cache-key fix | — | 622 hit / **0 miss** |
| after the exempt-uid fix | **7.34 s** (repeated 7.35, 7.34) | 1018 hit / 0 miss |

**Fix 1 — the cache that never worked.** `getPackageInfoAsUser` keys on `"userId:flags:packageName"`,
so the flags are part of the key. The cached sweep asked with `GET_PERMISSIONS or GET_SERVICES`
(4100); all **seven** copies of `hasVpnService` asked with `GET_SERVICES` alone (4). Different key,
guaranteed miss, one binder call per app inside a filter over every package. The comment at
`HiddenApiHelper.kt:433` claimed the flags were chosen so the entry IS shared — **the code never did
that**, and trusting the comment would have hidden it. All seven now ask for both.

**Fix 2 — the same rule computed twice, opposite ways round.** Block All built the critical/VPN uid
set when "allow critical" was ON; `isUidExempted` re-derived the same test per uid when it was OFF,
each call reopening SharedPreferences and rescanning every package. The two sets are complements, so
one pass now serves both (`uidsWithCriticalOrVpn`, `allowCriticalEnabled`), at all three call sites.

**Still open: where the other 7 seconds go.** The two fixes together bought ~1 s of 8.35 s. The
O(n squared) scan was never the bulk — 110 packages is only ~12,000 comparisons. **The dominant cost
has not been found yet.** Measure before changing anything else.

### 61d. List position resets — `CANNOT REPRODUCE 2026-08-27`, see #73 below.

Three of four triggers verified on hardware. Only lock/unlock is untested, and it needs a human.

---

# Next

## #73 — Firewall list jumps to top — `CANNOT REPRODUCE 2026-08-27`

Reported standalone and again inside #61. **Three of four triggers tested on hardware and all hold
the scroll position:** switching away and back (HOME), switching tabs, and making a rule change from
a list row. That third one also covers the #61 reporter's separate complaint that *"when changes are
made in firewall, the page refreshes and reloads to the top"*.

**Why it is already fixed.** A guard exists whose own comment describes this bug: *"Nothing this
screen renders has changed. Rebuilding here reset the scroll position and re-read every visible icon,
on every unrelated settings write."* It fired in the log as *"nothing relevant changed - leaving the
list alone"*. The adapter reassignment at `FirewallFragmentViews.kt:516` — which two earlier versions
of this entry named as the cause — sits **behind** that guard and is never reached. Both reporters
were on v2.6.1/v2.6.2 in December; the fix landed after them.

Tab switching cannot lose the position for a second reason: `MainActivity.loadFragment` uses
`add`/`show`/`hide`, never `replace`, so the fragment view is not destroyed.

**Untested: lock/unlock**, which is the reporter's headline case. It could not be driven from adb —
the test device has a pattern lock. Needs a human to scroll, lock, unlock and look.

## Landscape state loss — no issue number, ours — `VERIFIED 2026-08-27`

Not a reported issue. Opened by our own #77 fix and found by the audit that followed it. Rotation is
now handled in place (`configChanges` on both activities), so the everyday trigger is gone — but a
rebuild still happens on a **language change** and on **dark mode**, and the app has an in-app
language switcher. So these are still reachable, just rarer.

**Fixed 2026-08-27:** the launch intent is no longer replayed on a rebuild
(`MainActivity.onCreate`), and `vpnPermissionContext` now survives one
(`KEY_VPN_PERMISSION_CONTEXT`). Those two were the dangerous pair — the second left the firewall
down after the user granted VPN permission.

**Still open, all reachable via the language switcher:**

- No fragment overrides `onSaveInstanceState` — grep count 0. A 40-app multi-select is lost.
- `PackagesFragmentViews` has **no** `onDestroyView` or `onDestroy` at all, so the batch-uninstall
  `progressDialog` (created at :1211) leaks its window on a rebuild.
- Every dialog in the app is a plain `Dialog`/`BottomSheetDialog`, never a `DialogFragment`, and
  `currentDialog` is not dismissed on destroy.
- `setupMainUI` calls `setupBottomNavigation()` before restoring the saved tab, and
  `MainActivity.kt:400` force-selects the Firewall tab — a wrong-tab flash on every rebuild.
- Edge-to-edge consumes only `systemBars.top`; no left/right inset is applied anywhere, so a
  3-button navigation bar overlays the toolbar in landscape.

## #91 — Quick tile opens the whole app to stop the firewall — `VERIFIED 2026-08-27`

`FirewallTileService.kt:90` logs *"Firewall is ON, opening app for stop confirmation"*, then
launches `MainActivity`. The reporter wants the tile to stop the firewall directly and say so in a
notification. The slow-start half of the report may be helped by v2.6.6 — **not re-measured**.

---

# Found while auditing, not yet acted on

## Block All fails OPEN when a package read fails — `VERIFIED 2026-08-27`

`getInstalledApplicationsAsUser` returns `emptyList()` on total failure (`HiddenApiHelper.kt:372`)
rather than signalling an error. In Block All mode the backend blocks what it enumerates, so an empty
enumeration means **nothing is blocked** — the user believes everything is blocked and it is not.

The window is not rare: `INSTALLED_APPS_CACHE_TTL` is 5 s, so the re-fetch that could fail happens
constantly. This is **not** caused by any recent change; three receivers plus a UI path already
cleared that cache from their own threads.

This is stronger than 61b's downgrade assumed. 61b concluded the worst case was "a transient empty
list"; this is what a transient empty list actually costs in Block All mode.

## `networkPackagesCache` is cleared without its lock — `VERIFIED 2026-08-27`, deliberately not fixed

`clearInstalledAppsCache` nulls `networkPackagesCache`/`networkPackagesCacheTime` while every writer
holds `networkPackagesLock` (`HiddenApiHelper.kt:419`, `:451`). A sweep already inside the lock
finishes afterwards and republishes its pre-clear result with a fresh timestamp, so the stale list
survives one more TTL — defeating the refresh that was just requested.

**Taking the lock would be worse than the bug.** That block is held for the whole computation,
measured at 9,499 ms for 466 packages, and two callers of `clearInstalledAppsCache` are
BroadcastReceivers, where a wait that long is an ANR. The correct fix is a **generation counter** the
sweep checks before publishing. Left as a task rather than done badly; there is a comment at the call
site saying so.

---

# Needs an answer before any work

## #97 — Zorin Connect blocked while allowed

**Only the iptables backend reads `lanBlocked`** — 84 mentions in `IptablesFirewallBackend`, **zero**
in `VpnFirewallBackend`. The LAN toggle also says *"Requires root access"*. Zorin Connect needs the
desktop to reach **into** the phone, and a VPN-based firewall cannot pass incoming connections at
all. Ask the reporter which backend is active and whether they have root or Shizuku before treating
this as a bug.

---

# Backlog — feature requests

- **#96 — Filter for bloatware apps**, plus exporting package names to a text file.
- **#83 — Act on the new-app notification.** Half exists: `PackageMonitoringService` already sends
  the notification. The ask is allow/block buttons on it, and tapping through to that app's rules.
- **#57 — Firewall profile system.** Gated on #61 by a public reply already posted.

---

# Closed here

- **#77 — Landscape mode.** Both `screenOrientation="portrait"` locks removed (commit `6597752`),
  then `configChanges` added to both activities so a rotation is handled in place instead of
  rebuilding the activity. **Verified on hardware 2026-08-27** (TrebleDroid GSI, Android 14):
  display reached `854x480`, five tab switches, 0 crashes; after `configChanges` the rotation
  produced **no** `MAINACTIVITY CREATED`/`DESTROYED` at all and the active filter survived it.
  Safe because the app has no `layout-land`, no `values-land`, no `swXXXdp` folder, and never reads
  `Configuration.orientation`. The issue title asks for a *setting*; this follows the system
  auto-rotate instead — **confirm that is enough before closing on GitHub**. See *Landscape state
  loss* above for what the change exposed.

- **#71 — Small filter improvements.** All three: the checked icon is hidden
  (`checkedIconVisible="false"`), the accidental double gap is gone (the chip's own
  `layout_marginEnd` removed, `chipSpacing="7dp"` on the group kept), and both screens remember
  their filters through `utils/FilterPrefs.kt`. **Verified on hardware 2026-08-27** — set "User",
  left the screen, returned: filter and chips both held.

  Three bugs the follow-up audit found in that new code, all fixed before it shipped: the packages
  flow re-emits, so the filter captured when the load job started went stale and was being written
  back over the user's live selection; the deep-link path passes `foundPkg.type.toString()`
  ("USER", the enum name) which no chip mapper reads back, and persisting it would have made a
  wrong-looking chip permanent; and restoring the "Uninstalled" state filter would have emptied the
  Packages screen at every launch, because `PackagesViewModel` collects plain `invoke()` which never
  contains uninstalled system packages. The last two are now normalised at the `FilterPrefs`
  boundary, so any future caller is covered too.

- **#84 — iptables rules not applied for all apps.** Duplicate DROP rules accumulated in the chain,
  so allowing an app removed one copy and left the rest. Fixed in v2.6.6 by rebuilding the chain to
  match the rules exactly and reading it back from the kernel. Closed 2026-08-27. The reporter's own
  case was **never reproduced here** — reopen if it returns.
