# Development log: @Dinusha-Ekanayake

@Dinusha-Ekanayake's entries, newest first. Only @Dinusha-Ekanayake adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-03 - fix: a fresh install walks the whole judge path (issue #114)

`fix/114-fresh-install-check` · @Dinusha-Ekanayake

Ran `docker compose down -v && docker compose up --build` from a Windows clone and walked all eleven README steps in a browser. Fixed what stopped it: `compose-init.sh` checked out with CRLF so init died at `set -eu` (`.gitattributes` now keeps scripts and nginx files LF); the dispatcher's empty plan day names the seeded day; the dock board and the driver look up to a week ahead for the first day with work, since the seed always lands after today. The driver's live wiring is @Oxshadha's from `dev` (#117); this adds only the look-ahead and the remembered run day on top of it.
Why: the issue's "done when" had never been run; on install day the seed's day is never today.
Verified: see the PR. Fresh stack: 85 orders, idempotent rerun, plan 73/11/1, release with a shortfall, offline partial delivery applied once, store receipt partial, Live and Issues. `npm test`, the dispatcher plan spec and the loader suite.
Open: a partial stop's delivered units are not on the run sheet.

---

## 2026-10-03 - feat: push alerts, the driver's notification feed, and loader notifications in Sinhala and Tamil (issue #118)

`feat/notifications-push-driver-i18n` · @Dinusha-Ekanayake

The three gaps #153 left. Push: a switch in each role's settings subscribes this device (VAPID key from `push-config`, `notification:Subscribe`), says why when it cannot, and the service worker shows each push and opens the app on a tap. Driver: Isuru's Figma feed and driving-mode badge now carry the driver's real notifications and count; his sample feed stays without an account. Loader: each notification keeps the facts its message was filled from, and the loader fills the same templates in Sinhala and Tamil.
Why: phones should alert with the app closed; the driver and the loader's languages were the last roles without their notifications.
Verified: see the PR. `NotificationConsumersIntegrationTest` (facts in the inbox), `loader-messages.test.ts`, `push-keys.test.ts`, the loader suite with the Settings alerts row; driver Home compared with Figma 83:1996.
Open: push needs VAPID keys on the server. The driver browser suite fails 13 of its tests on `dev` itself since the driver rework; they test the old screens.

---

## 2026-10-03 - feat: notifications inbox and last sync in each role UI (issue #118)

`feat/118-notifications-inbox` · @Dinusha-Ekanayake

The dispatcher, loader and store manager screens now show their notifications, each against its Figma frames: the dispatcher's panel and Overview card, the loader's bell with an unread dot and an inbox, the store manager's drawer, phone sheet and Home card. One shared data hook (`useInbox`) carries the live count from `/api/notifications/stream`, with a "live updates paused" state and polling after 40 s of silence, plus the list, read state and an offline copy; each role draws its own UI (#14). "Synced HH:MM" is a button in every role: it sends what waits and reads again (the dispatcher, online only, reloads the screen). Routing version 2 tells the depot's other loaders of a release (R-NOT-10) and each outlet its stop and expected arrival (R-NOT-11). The stream now stays open through the Next proxy and nginx.
Why: the booklet's handoffs (p6, p9) were invisible; the issue's priorities were the store's deferral notice and expected arrival, and the loader's release.
Verified: see the PR. `NotificationConsumersIntegrationTest`, `notifications-inbox.test.ts`, and a `notifications.spec.ts` in the loader, dispatcher and store suites; each screen compared with its Figma frame.
Open: push opt-in and service worker push handlers; the driver's feed (#21); the dispatcher's Reply (no messaging between roles).

---

## 2026-10-03 - fix(offline): Background Sync with no page open, older held writes, and Windows service worker builds

`fix/sync-open-items` · @Dinusha-Ekanayake

The two gaps #109 left, and a build bug it surfaced:
- With no page open, the service worker drains each account's queue itself (`scripts/sw-drain.mjs`), under the device id the page now keeps in the snapshot store and by the page's rules. A loader queue still waits for a page, which replays its offline operator switches first (A-39).
- A write held before answers carried a version looks it up (`GET /api/sync/{operationId}`, owner only) before a discard or redo, so the server settles it too.
- `build-sw.mjs` wrote Windows paths (`/.next\static\...`) into the precache list, so on a Windows build every precache request 404'd and the worker never installed. That was the `e2e-driver/day.spec.ts` failure seen only on Windows.
Why: the open items recorded when #28 closed.
Verified: see the PR. `SyncIntegrationTest` reads one operation (owner only); `sw-drain.test.ts` and `background-sync.spec.ts` (the real worker draining IndexedDB); `held.spec.ts` discards an older held write after looking its version up; driver suite 10 of 10 on Windows.
Open: a loader queue still waits for an open page.

---

## 2026-10-03 - feat(sync): review held offline writes, Background Sync, dark components and a gallery (issue #28)

`feat/sync-followups` · @Dinusha-Ekanayake

Decision D-O: only an operation's owner reviews it (R-EXE-16). `sync:Discard` drops a conflict or refusal with a reason; `sync:Resolve` settles a conflict as `RESOLVED` and names the redo the device queued ahead of it on the current version (R-EXE-17). The review panel's "Send again" could never work (a replayed id gets its first answer), so it became "Redo on the current version", offered where a role registers a resolver (the loader does), and "Discard…" with a reason. Batches go in recorded order. The service worker answers Background Sync by asking an open page to drain (A-39). `waypoint.sync.time_to_drain` (EXE-02). Shared components moved onto the `go-card` token and GO icons invert under any `.go-dark`, so dark mode works outside the loader; `/gallery` shows them light and dark in development.
Why: the last open items of #28; the audit in the [plan](../issues/028-offline-sync-followups/PLAN.md) found the rest already built.
Verified: see the PR; `SyncIntegrationTest` and `OperationOutcomeTest` cover the new rules, `held.spec.ts` the loader's redo, discard and Background Sync, `sync-review.test.ts` the device side.
Open: the driver (#21) can register its own resolver to offer redo. Writes held before this change have no server version, so they are dropped on the device only.

---

## 2026-10-02 - feat(loader): tablet, desk and terminal layouts from Figma

`feat/loader-wide-layouts` · @Dinusha-Ekanayake

From 768px the loader follows Figma 07 (tablet), 09 (portrait tablet) and 10 (desk and terminal) instead of stretching the phone layout:
- the departures board is a table (vehicle, route, departs, load against capacity, loader, status, action); search and filters sit beside the title in landscape;
- sign-in keeps the crew list and a PIN keypad side by side; the fourth digit signs in, a keyboard types into it, and the offline PIN check works the same;
- the workspace fills the screen instead of stopping at 1280px.
`ReadyTripView` gains `weightCapKg` and `volumeCapM3` (additive) for the load column. The phone layout is unchanged.
Why: the booklet names the shared dock tablet as the loader's device, and Figma has a frame set for each size.
Verified: backend `mvn verify` (628, no skips); `npm test` (54), typecheck, build; loader browser suite 9 of 9, with the new `wide.spec.ts` at 1280x800, 1920x1080 (offline keyboard sign-in) and 768x1024; each size rendered and compared with its Figma frames.
Open: Figma's tablet top bar has text "Lock" and "Switch user" buttons and the language switch on sign-in; ours keeps the icon buttons and Settings.

---

## 2026-10-02 - feat(loader): match the loader to Figma, with dark mode and settings

`feat/loader-figma`, restored by `fix/restore-loader-figma` after the revert in #91 · @Dinusha-Ekanayake

Compared every frame of Figma "08 Loader · Phone" with the build and closed the gaps:
- dark mode on the go-dark tokens; a Settings screen (Appearance Light/Dark, Language සිං / த / EN), with the device's sign-out kept there;
- Device locked and Unlock with PIN; crew search with "No matching employees"; four-box PIN with Incorrect PIN and the paused countdown;
- the dock pill and list, "No trips at this dock", "was just taken" (409 R-LOD-11);
- the loaded and saved-offline sheets, centred out-of-sequence and hand-back cards ("Next up"), and Report an issue with icons and two-line pickers;
- release with loaders, minutes, the flagged-stop note and capacity bars; "on pace" from the hold time and departure;
- every loader string through the SI/TA dictionary.

Kept from the backend where Figma differs: the required reason on an issue (`loading:Shortfall` requires it), and "item" over "package" (decision 2026-10-01).
Why: the booklet judges fidelity to the Day 5 design on phone-size screens.
Verified: typecheck, `npm test` (48), build; loader browser suite 4 of 4 and dispatcher 11 of 11; screenshots of each screen in light and dark compared with the Figma frames at 393x852.
Open: Figma's notifications bell (loader notifications are #14) and the optional issue photo (Loading has no upload endpoint). Sinhala and Tamil need a native speaker.

---

## 2026-10-02 - feat(execution): deliveries product by product, proof in the database, scope fix

`feat/execution` · @Dinusha-Ekanayake

On the merged module: a delivery can be recorded product by product (`execution.delivery_lines`, `DeliveryLines`; the total stays what Ordering reads), proof bytes are kept in the database by default (`DatabaseProofStore`, `PROOF_STORE`), and a nightly job clears them past retention and keeps the row and hash. Found while doing it: a driver holding a depot grant, as `demo-accounts` gives, could read every vehicle's stops in the depot; depot-wide reads now need a dispatcher, admin or auditor role (`20261002T0900`). Details in the [walkthrough](../issues/012-execution/WALKTHROUGH.md#follow-up-2026-10-02).
Why: owner's decisions on #12 (per-line partials, Neon storage, retention); EXE-13 says a driver's scope is one vehicle on one date.
Verified: see the pull request; the scope test was run without its migration and failed, then passed with it.
Open: ETA through #16 needs a travel-time method agreed with #16. Offline timing stays as A-31.

---

## 2026-10-01 - feat(loader): switch loaders by PIN while offline

`feat/loading` · @Dinusha-Ekanayake

A shared loader device keeps its crew list with a PBKDF2 verifier per member (`iam.users.pin_offline_verifier`, migration 1510), written when the PIN is set or entered online, never the PIN. Offline, the PIN is checked on the device with WebCrypto, with the same five-try pause, and the switch or lock is logged. On reconnect `POST /api/session/operator/offline` replays the switches into the operator history (marked `offline`, audited) before the sync queue sends anything, so queued work lands under the loader who recorded it. The list expires after 12 hours and sign-out wipes it. R-IAM-25 to 27, IAM-OFF-01.
Why: switching was the last part of the dock flow that needed a connection (decision 2026-10-01).
Verified: `OfflineOperatorTest` (6, including the RFC 7914 PBKDF2 vector and a vector shared with the browser test), boundary and loading domain tests; frontend typecheck, build, `npm test` (18), mocked loader browser tests at 393x852 (4 pass, including wrong PIN, offline unlock, and replay before sync). The new integration test compiles but was not run: no test database here.
Open: run `LoadingIntegrationTest` against a database. A four-digit PIN is recoverable from its verifier; accepted and recorded in R-IAM-27.

---

## 2026-10-01 - feat(loader): switch the loader between English, Sinhala and Tamil

`feat/loading` · @Dinusha-Ekanayake

A language picker in the loader's top bar switches every loader screen between English, Sinhala and Tamil. The strings are hardcoded in `frontend/src/roles/loader/data/strings.ts`, keyed by the English text, so switching works offline; a missing translation falls back to English. The choice is kept per device and sets `<html lang>`.
Why: dock crews read Sinhala or Tamil first (decision 2026-10-01).
Verified: typecheck, build, `npm test` (16 pass), mocked loader browser tests at 393x852 (3 pass, including `language.spec.ts`: switch, reload, switch again).
Open: Sinhala and Tamil are drafts; a native speaker should review them. Server error messages stay in English.

---

## 2026-10-01 - feat(loading): unlock a trip after 30 idle minutes

`feat/loading` · @Dinusha-Ekanayake

A hold now lapses 30 minutes after the holder's last accepted command (`loading.sessions.holder_active_at`, migration 1509), and another loader's take replaces them. The dock board shows a lapsed hold as free to take. Replaces forced takeover. R-LOD-11 and LOD-09 updated.
Why: a loader who walks away must not block a trip until a dispatcher intervenes (decision 2026-10-01).
Verified: `LoadingSessionTest` (26) and `ModuleBoundaryTest` pass; frontend typecheck and `loader-hold.test.ts` pass. The new integration test compiles but was not run: no test database here.
Open: run `LoadingIntegrationTest` against a database.

---

## 2026-10-01 - feat(loader): load item by item, as the Day 5 design does

`feat/loading` · @Dinusha-Ekanayake

The loader UI now reads the full Loading contract: `shared/domain/loading.ts` had fallen behind `LoadingViews.java` and lacked items, order references, windows, vehicle capacity, holder and dock. Items are ticked and undone one by one (`lineNo`), an issue names an item or the whole order, capacity bars compare against the vehicle's limits, and the dock board has the dock selector, All/Available/Mine/In use and a holder per trip. Added hold to release (04), undo toast (E5), out-of-sequence warning (E6), hand-back dialog (E7), issue saved offline (E8) and the released screen (E11); sheets close on Escape and keep focus inside. `typecheck` now runs with `--incremental false`: with TypeScript 7 a cached `tsconfig.tsbuildinfo` passed files it had not rechecked.
Why: the booklet judges the loader on fidelity to the Day 5 design at phone size, and the item-by-item decision of 2026-10-01.
Verified: `npm run typecheck`, `npm test` (13 pass), `npm run build`; mocked loader browser tests at 393x852 (2 pass: offline item check syncs once under the operator with its `lineNo`; release needs the three checks and a hold). Not verified against a live backend; the database suites were not rerun after merging `dev`.
Open: run the backend suite and the live loader browser test against a database; Planning's two integration test classes do not skip without `TEST_DATABASE_URL` because the guard sits on their abstract base class.

---

## 2026-10-01 - feat: add the loader backend and shared-device flow

`feat/loading` · @Dinusha-Ekanayake

Loading now builds scoped live manifests, retains safe checks across plan revisions, limits issue reports to Damaged, Doesn't fit and Missing, and releases only after doors sealed, orders secured and driver present are confirmed. Shared-device PIN switching records operator history and attributes queued commands to the person active when they were recorded. The loader screens use those contracts and the shared sync queue.
Why: issue #10 needs a traceable dock workflow that remains correct through a plan change or an offline period.
Verified: the full backend suite passed 231 tests with zero failures, errors or skips after focused red/green tests covered both fixture fixes. A separate disposable PostgreSQL 18 database imported reference data, provisioned a test PIN and built one manifest from synthetic demand. The live 393x852 browser flow passed sign-in, PIN failure and success, loading, offline check, sync, three-check release and lock. The 768x1024 locked tablet state had no horizontal overflow. The latest frontend run passed seven unit/boundary tests, typecheck, production build and two mocked browser tests. No external warehouse data was changed.
