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

`PackageMonitoringService` is the only code that sees other profiles. Three limits, all in code:

- It polls every **15 s** and acts only on `newPackages = current - lastKnown`
  (`checkForNewPackages`). A **disable** in another profile is never noticed — additions only.
- It is started from `MainActivity.kt:165` alone, so nothing watches other profiles until the user
  opens the app.
- Manifest receivers only receive user-0 events. The code says so itself at
  `PackageMonitoringService.kt:112`: *"a manifest PACKAGE_ADDED receiver in user 0 never does"*.

### 61b. `getInstalledApplicationsAsUser` returns 0 for the work profile — `VERIFIED 2026-08-25`

Observed twice: `Profile 10 (Work): ... returned 0 packages` from the hidden API, before a Shizuku
fallback found 216. A wrong answer from a function the firewall trusts. It is also why
`AndroidPackageDataSource.hasNetworkPermissions` must stay a per-package call, and why the
`getPackageInfoAsUser` cache is capped near a 49% hit rate.

### 61c. Firewall takes too long to become active — `NOT VERIFIED`

Reporter measured 1 m 35 s and compared AFWall+ at 6 s for 181 apps. The v2.6.6 chain-resync and the
`getPackageInfoAsUser` cache should both help. **Nobody has re-measured since.** Measure before
building anything.

### 61d. List position resets — same as #73 below.

---

# Next

## #73 — Firewall list jumps to top — `VERIFIED 2026-08-27`

Reported standalone and again inside #61. Likely cause: `FirewallFragmentViews.kt:232` builds a
**new** `LinearLayoutManager`, and line 516 reassigns the adapter. Both discard scroll position. No
list in the app sets stable IDs. **Cause not confirmed on a device.**

## #71 — Small filter improvements — `VERIFIED 2026-08-27`

Three asks, all real, all small:

1. **Tick icon on chips you cannot uncheck.** `filter_chip_item.xml` uses
   `Widget.Material3.Chip.Filter`, which draws a checked icon. `checkedIconVisible` is never set to
   false. Type filters are one-of-N — `FilterChipsHelper.setupMultiSelectFilterChips` enforces it
   with `isUpdatingProgrammatically` — so the tick marks a state the user cannot leave.
2. **Gaps too wide.** Double-spaced by accident: the ChipGroup sets `chipSpacing="7dp"`
   (`fragment_firewall.xml:69`, `fragment_packages.xml:71`) **and** every chip carries
   `layout_marginEnd="7dp"` (`filter_chip_item.xml`). Real gap is 14dp.
3. **Filters not remembered.** `FirewallFilterState` lives only in `_uiState` and
   `pendingFilterState`. Nothing writes it to disk, so every launch is "All + Internet".

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
- **#77 — Landscape.** `FIXED 2026-08-27`, unreleased. Both `screenOrientation="portrait"` locks
  removed from the manifest. **Not verified on a device — no phone was connected.** The issue title
  asks for a *setting*; this fix follows the system auto-rotate instead. Confirm that is enough
  before closing.
- **#57 — Firewall profile system.** Gated on #61 by a public reply already posted.

---

# Closed here

- **#84 — iptables rules not applied for all apps.** Duplicate DROP rules accumulated in the chain,
  so allowing an app removed one copy and left the rest. Fixed in v2.6.6 by rebuilding the chain to
  match the rules exactly and reading it back from the kernel. Closed 2026-08-27. The reporter's own
  case was **never reproduced here** — reopen if it returns.
