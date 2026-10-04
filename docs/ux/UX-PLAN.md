# UX improvement plan: Hackathon deadline day

Written 2026-10-04, 09:45 Sri Lanka time. The Hackathon closes **today, 23:59**, and "code pushed after the deadline will not be considered" (booklet p13). This plan is therefore a deadline-day plan. It closes gaps against the Day 5 design and removes friction a judge would meet. It adds no new features.

## 1. Ground rules (what we may and may not do)

From the Challenge Booklet and the kick-off session:

| Rule | Source | What it means for UX work |
| --- | --- | --- |
| The Day 5 design is the implementation specification; fidelity is 10% of the Hackathon score | Booklet p12-13 | Move screens **towards** Figma, never away from it |
| Departures from the design must be documented and justified in the README | Booklet p10, kick-off ("minimal undocumented changes") | Any change that is not in Figma gets a README line, or it does not ship |
| Judges assess the driver and loader on phone-sized screens | Booklet p12 | Driver and loader are checked at 393 px first |
| A judge completes the numbered walkthrough on a fresh install and on the public URL | Booklet p12 | The walkthrough path is the priority path; anything off it waits |
| Degradation, offline and recovery: 10% | Booklet p13 | Offline and failure states must say what happened and what to do |
| Functional completeness across all four roles: 20%; demo video: 10% | Booklet p13 | Every role's main task works end to end, and it looks right on video |
| Code after 23:59 is not considered; the deployment stays live for review | Booklet p13 | A release to `main` and a production check happen before a freeze, not at 23:55 |

Out of scope today, whatever its UX value: features absent from Figma (for example voice notes, the QR scan backend, a live store map), visual redesigns, new roles' consoles, and anything that needs a migration.

## 2. What a judge will meet

Judges open the **production** URL (`*.waypointgo.live`), which runs `main`. `main` was released 2026-10-03 23:31 and is **69 commits behind `dev`**: late risk per trip and stop, the browser suites in CI, messaging, the role apps and the driver on live data are all missing from production. **The biggest UX improvement available today is releasing `dev` to `main` and checking it.**

## 3. Findings (preview, 2026-10-04 09:40, VPS data)

Measured on the preview, each role at its target device size (dispatcher 1440 px, loader 393 and 1280 px, driver 393 px, store manager 1440 and 390 px), and compared with Figma.

| # | Role | Finding | Evidence | Severity |
| --- | --- | --- | --- | --- |
| F1 | All | `main` is 69 commits behind `dev`; production lacks today's work | `git rev-list origin/main..origin/dev` | Critical |
| F2 | Driver | With no released trip, the first screen stays on "Loading today's run…" for **7.6 s**. The day look-ahead (#114) reads seven days one after another before drawing Home | Network timeline: 7 sequential `run-sheets` and `vehicles` reads | High |
| F3 | Driver | Home says "No trip planned for VEH035 today" while Monday's trip for VEH035 is published and waiting for the loader | Home text against the published plan | High |
| F4 | Store manager | Home shows a delivery only when it is on the way today ("Nothing is on the way to you today"), although Monday's delivery is planned for 05:00. Figma 11:112937 is built around a **Next delivery** card: driver, vehicle, large ETA, a progress tracker, the shortfall and Track or Receive. The booklet: the store manager "needs an expected arrival time to schedule staff" | Preview Home against Figma 11:112937 | High |
| F5 | All | Every page logs a console error: the Cloudflare Insights beacon is blocked by our own Content-Security-Policy. A judge with devtools open sees errors on every screen | Browser console on all four roles | Medium |
| F6 | Dispatcher | Overview shows developer wording to users: "Reference data serves only the vehicles available on a day, so the workshop list waits on a fleet status read." | Overview, Vehicles card | Medium |
| F7 | Dispatcher | Overview on a non-operating day reads "Today: 0" everywhere, with no pointer to the next day with work (the Plan screen already offers "Plan Mon 5 Oct") | Overview on Sunday | Medium |
| F8 | Loader | "Tonight's departures" does not say which day the trips leave; on a Saturday or Sunday they are Monday's | Dock board header | Medium |
| F9 | Store manager | At 390 px the "Synced" pill is cut to "Sync…", and the bottom bar is icons only | Phone Home | Low-medium |
| F10 | All | README "Departures from the Designathon design" is out of date: it lists late risk as not built (it is, #119) and predates messaging (#214) and the role apps (#203) | README against `dev` | Medium (fidelity score) |
| F11 | Dispatcher | 13 links and buttons on Overview are under 40 px tall (desktop mouse use, above the 24 px WCAG 2.2 minimum) | Target size scan | Low |

Already sound, and to be kept as is: no horizontal overflow on any role at 360 to 1440 px; 24-hour depot time everywhere; offline queues on loader, driver and store with visible "saved on this phone" states; the loader's Figma layouts at phone, tablet, desktop and terminal; the dispatcher's late risk and forecast fallbacks; four role browser suites gating every deploy.

## 4. The plan

Each item names its Figma frame or booklet line, the role owner, and how it is checked. Times are Sri Lanka time today.

### Tier 0: release path (owner: whoever releases; start now, finish by 20:30)

| Item | Do | Check |
| --- | --- | --- |
| R1 | Agree a **code freeze at 20:00** on `dev` | Team message |
| R2 | Release PR `dev` to `main` at 20:00. CI (now including the browser suites) must be green | PR checks |
| R3 | After the production deploy, a judge pass on `*.waypointgo.live`: the README walkthrough steps 1 to 11, with the driver and loader at 393 px | A checklist in the release PR |
| R4 | If a step fails on production, fix forward on `dev` before 22:30, or roll the release back; no pushes after 23:30 | |

### Tier 1: walkthrough blockers and fidelity gaps (by 15:00)

| Item | Role and owner | Change | Figma or booklet | Check |
| --- | --- | --- | --- | --- |
| U1 | Driver (Dinusha, #114 code) | Draw Home at once with today's state; look ahead for the next run **in parallel** (one batch of reads), and switch when found | Booklet p6: the driver works offline with intermittent signal; a 7 s blank screen reads as broken | First paint under 1.5 s on the preview; driver suite green |
| U2 | Driver (Isuru, Oxshadha) | When the next trip is published but not released, say so: "Next trip Mon 5 Oct · VEH035 · waiting for the loader to release it" | Figma "Driver: Home" (83:1996) shows the run waiting | Preview with Monday's published plan |
| U3 | Store manager (store owner) | Home's **Next delivery** card for a planned delivery: the planned or expected arrival, vehicle, the progress tracker (Order confirmed, Loaded, Left warehouse, Arriving, Received) from data we already have; driver name and voice message stay out (README departure) | Figma 11:112937; booklet p6 "needs an expected arrival time" | Preview: OUT001 shows Mon 5 Oct, VEH037, 05:00 |
| U4 | All (any) | Stop the console errors: switch off Cloudflare Web Analytics for the domain, since it is not part of the product. The alternative is adding its origin to the CSP, which needs a security review, so prefer switching it off | Clean console on video and for judges | Console empty on all roles |
| U5 | Dispatcher (Ransika, Mihiran) | Overview: replace the developer sentence with what a dispatcher can act on ("Workshop vehicles: see Vehicles"), or hide the card's body | Figma 189:10617 has no such text | Overview read aloud makes sense to a dispatcher |
| U6 | README (Dinusha) | Update "Departures from the Designathon design": late risk built (#119), messaging (#214), role apps (#203), the store's Next delivery card scope, and the driver look-ahead | Booklet p10 and p12 | A reviewer can map every Figma frame to built, departed with reason, or out |

### Tier 2: friction and consistency (by 19:00, only if Tier 1 is done)

Done 2026-10-04 (`fix/ux-tier2`). U7 is met by the Overview's "Tomorrow's plan" card already on `dev`. U9 keeps "Synced" with its time on phones; the bottom bar stays icon-only as in Figma, and every icon already has a screen-reader name. U10 covers the dispatcher's Issues and publish audience and the store's Orders and Deliveries.

| Item | Role | Change | Figma or booklet | Check |
| --- | --- | --- | --- | --- |
| U7 | Dispatcher | Overview on a non-operating day: one line pointing to the next day with orders, reusing the Plan screen's look-ahead | Booklet p6: the dispatcher plans the next run | Overview on Sunday |
| U8 | Loader (Dinusha) | Add the departure day under "Tonight's departures" when it is not tomorrow ("Mon 5 Oct") | Figma 11:57924 header keeps its layout; this is one line of text | Board on Sunday |
| U9 | Store manager | Phone header: give the "Synced" pill room (time only, as on the loader), and labels or accessible names on the bottom bar icons | Figma 11:125924 (phone) | 390 px screenshot; screen reader names |
| U10 | All | Counts read "(n)" in every role's filters, as the dispatcher and loader now do | Isuru's request; one style across roles (Day 5 criterion: consistency across roles) | Visual sweep |
| U11 | All | Empty and loading states say what will happen next ("appears when the plan is published", "when the loader releases"), never a bare "0" or a spinner alone | Booklet p4: problems include not knowing progress | Sweep of each role's empty state |

### Tier 3: after the deadline (do not start today)

Logged for the semifinal and Grand Finale, where the deployment must stay live but code changes are not judged:
- target sizes on the dispatcher;
- a full accessibility pass (focus order, contrast in dark mode);
- the driver's Figma states still marked `fixme` in the browser suite (#21);
- Sinhala and Tamil review by a native speaker;
- the late-risk model service fix on the VPS (#16).

## 5. Demo video (10% of the Hackathon score)

Record on **production** after R3, never on localhost:
- dispatcher at 1440;
- loader on a tablet and on a phone (393);
- driver on a phone (393), including one offline delivery and its sync;
- store manager's Next delivery card and receipt.

Show one degradation per role: shortfall at loading, driver offline, a deferred order with its reason, and the model fallback labelled "estimate". Keep it 5 to 8 minutes, as the booklet asks.

## 6. How every change is verified

- **Real data:** on the VPS preview database. Where data is missing, seed it through the app's commands only.
- **Device sizes:** each changed screen at its target size: driver and loader 393 px, dispatcher 1440 px, store manager 1440 and 390 px.
- **Figma:** compared with the named frame, read only. The Figma file is never edited.
- **CI:** the role browser suites run in CI and must stay green; a new behaviour gets a spec in its role's suite.
- **Departures:** any departure from Figma is written into the README in the same PR.

## 7. Tier 3: polish (after the deadline)

One behaviour per kind of interaction, built once in `frontend/src/shared/ui/` and used by every role, without changing a Figma screen. Motion is 150 to 250 ms, never holds an action back, and stops under reduced motion.

| PR | Scope | State |
| --- | --- | --- |
| 1 | Motion tokens; one overlay behaviour (enter and exit, Escape, focus, page lock, swipe to close on phones); toast timing and Undo | Done (`feat/ux-polish-motion`) |
| 2 | Keep filters, tabs and scroll position when leaving and returning to a screen | Done (`feat/ux-polish-state`) |
| 3 | Pressed state on every button, a spinner while sending, skeletons instead of bare "Loading…", "Try again" on every error | Done (`feat/ux-polish-feedback`) |
| 4 | Screen transitions (View Transitions API, with an instant fallback) and three shared-element moves | Done (`feat/ux-polish-feedback`) |
| 5 | Primary actions pinned on phones, local Undo (signature), dispatcher sidebar folded by default on narrow desktops | Done (`feat/ux-polish-feedback`) |

