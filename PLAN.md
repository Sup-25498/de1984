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

### 61a. Work-profile status never refreshes by itself — `VERIFIED 2026-08-27`

`PackageMonitoringService` is the only code that *watches* other profiles over time:

- It polls every **15 s** and acts only on `newPackages = current - lastKnown`
  (`checkForNewPackages`). The set is `(packageName, userId)`, so an enable or disable never
  changes membership and is **never noticed** — additions only. This is the actual bug.
- It is started from `MainActivity.kt:165` alone. `START_STICKY` restarts it after a kill, but
  nothing bootstraps it at boot, so nothing watches other profiles until the user opens the app.
- Manifest receivers are registered for user 0 only.

**Two corrections from the 2026-08-27 audit.** It is *not* the only code that enumerates profiles —
`HiddenApiHelper.getUsers` has **12** call sites, including all three backends,
`AndroidPackageDataSource.kt:108` and `FirewallVpnService.kt:494,682`. And
`PackageChangedReceiver.kt:71` does read `EXTRA_UID` and derive a `userId`, so it is not blind to
other profiles when an event is actually delivered to it — the gap is delivery, not handling.

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

### 61c. Firewall takes too long to become active — `NOT VERIFIED`

Reporter measured 1 m 35 s and compared AFWall+ at 6 s for 181 apps. The v2.6.6 chain-resync and the
`getPackageInfoAsUser` cache should both help. **Nobody has re-measured since.** Measure before
building anything.

**A named cost centre, found 2026-08-27 by reading code against its own comment.** The
`getPackageInfoAsUser` cache is keyed `"userId:flags:packageName"` (`HiddenApiHelper.kt:840`), so
the flags are part of the key. The cached sweep at `HiddenApiHelper.kt:440` asks with
`GET_PERMISSIONS or GET_SERVICES` = **4100**. Every `hasVpnService` asks with `GET_SERVICES` = **4**
— `AndroidPackageDataSource.kt:843`, `NetworkPolicyManagerFirewallBackend.kt:836`,
`IptablesFirewallBackend.kt:1153`, `ConnectivityManagerFirewallBackend.kt:641`,
`FirewallVpnService.kt:912`. Different key, guaranteed miss, one binder call per app per pass — and
it runs inside a filter over every package.

The comment at `HiddenApiHelper.kt:433` claims those flags were chosen so the entry IS shared.
**The code has never done that.** Trusting the comment would have hidden this.

Second cost centre: `isUidExempted` scans all packages and re-opens SharedPreferences per app.

Fix by making the five sites ask for `GET_PERMISSIONS or GET_SERVICES` — asking for more is safe,
the answer still carries `services`. **Time it before and after.**

### 61d. List position resets — same as #73 below.

---

# Next

## #73 — Firewall list jumps to top — `VERIFIED 2026-08-27`

Reported standalone and again inside #61.

**Cause corrected 2026-08-27.** An earlier entry here blamed a rebuilt `LinearLayoutManager` at
`FirewallFragmentViews.kt:232`. That was wrong: `setupRecyclerView()` is called once, from line 150,
so the LayoutManager is built once. The real cause is `FirewallFragmentViews.kt:516` reassigning
`binding.packagesRecyclerView.adapter`, which resets `lastSubmittedPackages` at :517 and drops the
scroll position. It is guarded only by `iconsChanged`.

This also explains the reporter's own words — *"as it does in Packages screen"*.
`PackagesFragmentViews.setupRecyclerView` (line 172) builds its adapter once and **never reassigns
it**, which is exactly the difference between the two screens. Neither sets stable IDs.
**Not confirmed on a device.**

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
