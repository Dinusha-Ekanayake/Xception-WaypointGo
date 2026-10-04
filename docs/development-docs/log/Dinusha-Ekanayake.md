# Development log: @Dinusha-Ekanayake

@Dinusha-Ekanayake's entries, newest first. Only @Dinusha-Ekanayake adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-04 - docs: README for the judges, and the named demo accounts in a fresh copy

`docs/readme-deliverables` · @Dinusha-Ekanayake

The README opens with the deliverables the booklet lists, each linked, the deployed address with its four accounts, and both ways to run. `docker compose up` now also creates those named accounts and the four Peliyagoda loaders with their PIN, from `scripts/demo-accounts.csv` through the backend's own commands (`scripts/seed-demo-accounts.sh`); no backend code changed.
Left: the seeded day's outlet and vehicles are still granted to `store_manager@` and `driver@waypoint.local` only, so the named store manager and driver sign in with no outlet and no vehicles.
Verified: a fresh checkout built and started with `docker compose up --build`; init seeded 120 outlets, 60 vehicles and 85 orders, and each named account signed in through the stack.

---

## 2026-10-04 - feat: the attention watch, increment 1 (issue #268): the rule, the tables, the job and the read

`feat/268-attention-watch` · @Dinusha-Ekanayake

A scheduled job in `intelligence` watches every live trip each minute through Planning's and Execution's contracts and records what needs the dispatcher: a failed stop, a stop that will miss its window, a stop running late, a delivery with no proof. `AttentionPolicy` is the one definition of those rules (pure, the time a parameter); thresholds are data per depot with defaults in code; an item is raised once, refreshed, then cleared, never deleted; a heartbeat says when the watch last looked, and the read marks the list stale after three missed runs. `GET /api/ml/attention?depot=&date=` under `ml:Read`, another depot is 403. Plan in [PLAN.md](../../issues/268-attention-watch/PLAN.md).
Left for increment 2: acknowledge (command, action catalogue row, authorisation test), reminders and their events through the outbox, the notification subscriber, and the Live screen reading from the endpoint.
Verified: 11 domain and mapping unit tests, `ModuleBoundaryTest` and `EventCatalogueTest` pass. The two integration tests compile but were NOT run: no database was available locally, so the SQL and the migration are unproven until CI runs them.

---

## 2026-10-04 - feat: an optional language model rewords the plan explanations

`feat/explain-groq` · @Dinusha-Ekanayake

With `GROQ_API_KEY` set, the frontend's own server route (`app/explain/route.ts`) asks Groq to say an explanation more naturally. It is shown above the rule-based facts in the three pop-ups and marked as AI-worded. The route checks the session, sends only the facts of the one plan or order, keeps one answer per plan version (server memory, then the browser), and refuses an answer that names a figure the facts do not hold or that fails in any way: the rule-based text is always there. Blank key (the default) sends nothing. No backend change.
Why: the user asked for friendlier wording; the decision to send plan facts to an outside model is theirs and is opt-in per deployment.
Verified: see the PR. `npm test` 281; the explain and plan specs pass with no key. The live call to Groq was not exercised here.

---

## 2026-10-04 - feat: a generated plan explains itself at once, and each deferred order has a question mark

`feat/explain-auto` · @Dinusha-Ekanayake

The plan's explanation opens by itself when a plan is generated (the button stays, to open it again). Each order in the plan view's Deferred list has a question mark that opens why it was not placed. Both pop-ups sit in the middle of the screen from a tablet up (`Sheet` `centered`). Rule-based, frontend only.
Not built: wording by an outside language model (Groq). It was stopped before any plan data left the system; the explanations stay rule-based.
Verified: see the PR. `npm test` 281; `explain.spec.ts` and the generate spec in `plan.spec.ts` cover the new behaviour.

---

## 2026-10-04 - fix: the store manager has no "Switch user" button

`fix/store-no-switch-user` · @Dinusha-Ekanayake

The store's top bar and sidebar no longer show "Switch user": Sign out is already in the account menu behind the profile picture. `ShellActions` takes `switchUser` (default on), so the other roles keep the button; the sync badge and the role switcher of an account with several roles still show for the store.
Verified: typecheck, `npm test`. Browser suites in CI.

---

## 2026-10-04 - feat: suggested steps and a ready message on what needs the dispatcher (issue #269, first slice)

`feat/269-suggestions` · @Dinusha-Ekanayake

Each card under Live's "Needs you" opens **Suggested steps**: the playbook for that situation (window closing, driver offline, not delivered, proof owed, an issue) and a message filled with the vehicle, the store, the expected arrival and the window, which opens in the trip's thread for the dispatcher to edit and send. Playbooks are data in `dispatcher/data/playbooks.ts`, matched by scenario, so the same situation gets the same advice; a message with a blank it cannot fill is not offered. Frontend only, no model.
Left for #269 and #268: playbooks an administrator can edit, the server-side watch with reminders and a heartbeat. Both need backend work.
Verified: see the PR. `npm test` 275; new `e2e-dispatcher/suggestions.spec.ts`. Not seen on the preview: no trip was on the road there today.

---

## 2026-10-04 - fix: sign-in opens the account's own role on every address

`fix/signin-opens-own-role` · @Dinusha-Ekanayake

The page for choosing a role is gone, and so is the "All roles" button on sign-in. The shared address (`preview.waypointgo.live`, and the bare name on production) now shows the sign-in itself, and an email and password open the account's own workspace on whatever address they were entered. A role address still opens on its role for an account that holds it; any other account gets its own workspace there instead of the wrong-address page. Removed `RoleLanding.tsx`, `WrongAddress.tsx` and `roleCards.ts`. Frontend only: the session stays on the address it was made on.
The "Connect AI" button is also gone from every role's settings and the shell strip, with the lookup that fed it and its browser spec (`e2e-dispatcher/mcp.spec.ts`). The MCP service and its consent screen are unchanged; `McpConnect.tsx` stays for `scopeWords`, its button unused.
Why: a person should not have to pick a role before signing in; the account already says which one it is.
Left: the helpers in `hostRole.ts` that only those pages used (`sharedHomeFor`, `sharedHostFor`, `hostForRole`, `isPreviewHome`, `ROLE_ADDRESSES`) are still there with their tests, unused by the shell.
Verified: typecheck, `npm test` 279. Not yet seen on the preview.

---

## 2026-10-04 - feat: the whole plan explains itself, and a kept order says it stays deferred

`feat/explain-plan` · @Dinusha-Ekanayake

"Explain this plan" on the Plan screen: what the plan carries (orders, trips, vehicles), the orders left off grouped by the rule that decided each, how many were decided by hand, the planner's own notes (second pass, cost stage, estimated times) and what comes next. Counted from the plan itself, frontend only, no model. An order that can no longer be placed (kept deferred, or a final plan) now reads "it stays deferred" instead of "checking every vehicle".
Why: after generating, the dispatcher wants the plan in a few sentences, not only one order at a time.
Verified: see the PR. On the VPS preview's published plan for Mon 5 Oct both pop-ups read correctly. `npm test` 271; `e2e-dispatcher/explain.spec.ts` covers both.

---

## 2026-10-04 - feat: a deferred order explains itself (issue #267)

`feat/267-explain-deferral` · @Dinusha-Ekanayake

"Explain this decision" on a deferred order in Plan opens a pop-up: what happened, the rule in words with the planner's reason, what stopped it and what was fine, where it could still go, and what the dispatcher can do. Frontend only: every fact comes from the planning module (the deferral's rule and reason, its checks, and the places from `preview/placements`), so there is one definition of every rule, no model and nothing stored. Issue #266 (record the facts) was closed as already met by planning.
Why: a dispatcher has to answer "why was my order deferred" in a sentence.
Verified: see the PR. `npm test` 268 (new `dispatcher-explain.test.ts`); new `e2e-dispatcher/explain.spec.ts`.

---

## 2026-10-04 - feat: usability round 2, plain words, field errors, accessibility and dispatcher search

`feat/usability-round2` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md) section 8, frontend only (no backend or API change):
- Words: one `friendlyError` for network and server failures instead of raw messages; a refusal shows the rule's name (`ruleLabel`), the id behind Details; the error page says what happened, not a status code; pending changes are named; the admin screens show no raw dates, codes or ids.
- Forms: an error sits under its field and is announced with it (store issue report, profile, store details; driver delivery report; dispatcher issue reason; loader PIN).
- Access: small dispatcher controls reach 40 px without looking bigger; the sync pill announces a change of state, not the clock; pending and held counts are announced; the review dialog and the trip thread trap focus and close with Escape; a skip link; the last buttons show busy; notification lists and the driver's first screen show placeholder rows.
- Search: the dispatcher's header search (`/`, Ctrl+K) over the day's loaded orders, vehicles, trips, issues and depots (Figma has a search; the README departure is updated).
Why: people saw codes and server text, a form did not say which field was wrong, and small targets were hard to hit.
Not done: the console's 404s for "nothing yet" need the endpoints to answer 204, a backend change left to the module owners.
Verified: see the PR.

---

## 2026-10-04 - feat: UX polish 3 to 5 of 5, feedback, screen transitions, pinned actions and undo

`feat/ux-polish-feedback` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md) section 7, the last three parts:
- Feedback: every button gives under a press; a command in flight shows a spinner and "Sending…" (`busy` on each role's buttons); a loader line cannot be ticked twice while its tick is sending; loading lists show placeholder rows (`SkeletonRows`); errors offer "Try again" (`Notice` `onRetry`); a count badge pops when it rises; no transition is longer than 250 ms.
- Transitions: screen changes crossfade, and a detail slides in and back out (`withTransition`, the View Transitions API, instant without it or under reduced motion). The trip, the stop and the next delivery move from list to detail.
- Actions: the driver's Confirm and Save proof and the store's receipt submit stay pinned on phones (README departure); a cleared signature can be undone for 5 s (`useUndo`; the voice-note undo was dropped in favour of the new voice UI on `dev`); the dispatcher's sidebar starts as the rail below 1280 px until the dispatcher chooses.
Why: taps gave no sign of work, loading and errors left no next step, and long phone forms hid their button.
Verified: see the PR. On the VPS preview data: the sidebar is 84 px at 1100 and 260 px at 1440; store tabs crossfade and Track slides forward; no horizontal scroll in any role. `npm test` 231; all five browser suites (the forecast countdown is the known flaky test, 28 of 28 on repeat).

---

## 2026-10-04 - feat: UX polish 2 of 5, every screen keeps its place

`feat/ux-polish-state` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md) section 7: tabs, filters and searches that were lost when a screen unmounted are kept for the browser tab and the signed-in account (`usePersistentState`), in dispatcher Orders, Live, Issues, the plan board filter and the fleet table, the loader's board filter and the store's Orders and Deliveries. Scroll position comes back on return (`useScrollMemory`): dispatcher screens and its fleet table, store tabs, the loader's board, the driver's home and route lists; a form or detail opens at the top. The store's top-level tab is not kept across a reload, so a reload still opens Home.
Why: going to Overview and back reset the dispatcher's filters and scroll; the store kept the last tab's offset on a new tab.
Verified: see the PR. On the VPS preview data: the store's Orders list comes back at 500 px with its filter, Home opens at the top; the dispatcher's fleet table comes back at 700 px at 1440 and 390 px. `npm test` 227; all five browser suites, with new kept-state specs for dispatcher and store.

---

## 2026-10-04 - feat: UX polish 1 of 5, one motion and one overlay behaviour in every role

`feat/ux-polish-motion` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md) section 7: one curve and three lengths (150, 200, 250 ms) in `shared/ui/theme.css`; every sheet, dialog and drawer rises or slides in, closes with Escape, keeps focus inside, gives it back and holds the page still (`useOverlay`); a phone's bottom sheet follows a swipe down and resists a pull up (`useSheetDrag`). The store's dialogs gain the Escape and focus handling they lacked. Toasts share one timing (4 s, 6 s with Undo); the driver's sheets no longer hold an action back for their animation. Reduced motion now stops animations too, and `animate-fade-in` and `animate-in`, used but never defined, work.
Why: overlays behaved five different ways, and a sheet on a phone could only be closed by its button.
Verified: see the PR. On the VPS preview data: the store's notifications sheet rises in at 390 px, a 200 px swipe closes it, a 45 px drag springs back; at 1440 px it slides from the side and Escape returns focus to the bell. `npm test` 216; all five browser suites, with a new store overlay spec.

---

## 2026-10-04 - fix: UX Tier 2 (next working day, departure day, store phone header, counts, empty states)

`fix/ux-tier2` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md) U8 to U11 (U7 is covered by the Overview's "Tomorrow's plan" card already on `dev`, which was kept): the loader's board names the departure day when it is after tomorrow; the store's phone header keeps "Synced HH:MM" whole below 440 px (Store chip hidden, 40 px buttons); counts read "(n)" in dispatcher Issues and publish audience and store Orders and Deliveries; empty states say what happens next.
Why: on a Sunday every role read "0" with no pointer forward, and the store's sync time was cut to "Sync…" on a phone.
Verified: see the PR. On the VPS preview data: the store pill unclipped at 360, 390, 440 and 1440 px. `npm test` 213; all five browser suites.

---

## 2026-10-04 - fix: deadline-day UX, Tier 1 (driver first screen and next trip, store Next delivery, plain wording, edge header, README)

`fix/ux-tier1` · @Dinusha-Ekanayake

From [UX-PLAN.md](../../ux/UX-PLAN.md): the driver's Home draws at once and looks a week ahead in parallel (7.6 s to under 2 s), and names a published trip still waiting for the loader; the store's Next delivery card shows the next planned or confirmed delivery when nothing is on the way today (Figma 11:112937); pending notices speak to the user, not about modules; the edge sends `Cache-Control: no-transform` so Cloudflare stops injecting a script our CSP blocks; the README's departures are current.
Why: judges walk these screens on production today; the booklet asks for the store's expected arrival and judges the driver on a phone.
Verified: see the PR. On the VPS preview data: driver Home in 1.8 s with "Next trip Mon 5 Oct · VEH035 · departs 04:36"; the store card "Next delivery · Mon 5 Oct · Expected in your window 05:00-07:30". `npm test` 213; driver and store suites. The edge header takes effect with the next production deploy.

---

## 2026-10-04 - ci: the shell and every role's browser suite run in CI (issue #120)

`chore/120-browser-suites-ci` · @Dinusha-Ekanayake

A "Browser suites" job in `checks.yml` builds once and runs the shell, dispatcher (1440 px), driver (393 px), loader (393 px) and store (1440 px) suites as separate steps, each running even after another failed; one retry in CI, failures annotated on the pull request, each suite's report and traces kept 7 days. It also gates the preview and production deploys. The shell's CSP test answers its own API, as it failed on `dev` with no backend.
Why: a screen could break with every check green.
Verified: see the PR. A throwaway broken loader test turned the job red with only "Loader (393 px)" failed, the line annotated and the report attached; reverted, then green. About 4.5 minutes a run. [walkthrough](../../issues/120-browser-suites-ci/WALKTHROUGH.md)
Open: making it a required check is a repository setting.

---

## 2026-10-04 - feat(dispatcher): late risk per trip and stop, the forecast error, counts in brackets, phone widths (issue #119)

`feat/119-forecast-late-risk` · @Dinusha-Ekanayake

On the teammates' Plan and Forecast screens, kept as built: a published plan tags each trip from 20% late risk and every stop with its chance (`<1%` for a tiny one, `· estimate` when the predictor was off); the Forecast shows the demand model's measured error from the registry, total and chilled, never per brand. Live, Orders and Vehicles counts read `(9)` as Isuru asked. Every dispatcher screen fits from 1440 down to 360 px.
Why: #119 asked for late risk per stop and trip and the forecast fallback; the dispatcher overflowed on phones.
Verified: see the PR. `npm test` 170, the dispatcher suite 42; on the VPS preview database the real registry and a published Peliyagoda plan, and no overflow at five widths. [walkthrough](../../issues/119-dispatcher-forecast-late-risk/WALKTHROUGH.md)
Open: the preview's model service answers 500, so plans are scored by the estimate (#16).

---

## 2026-10-03 - feat(execution): the run sheet carries a stop's recorded units

`fix/partial-units-and-preview-cleanup` · @Dinusha-Ekanayake

`RunSheetStopView` gains `deliveredUnits` (additive), read from `delivery_records.delivered_units`; the frontend mirror follows, the phone's projection of a record still on the phone sets it, and the driver's run complete shows "Units delivered" counted by it.
Why: a partial delivery is recorded as a stop total, so the phone could not count it (open item of #114).
Verified: see the PR. `ExecutionIntegrationTest` asserts the units on the run sheet (runs in CI; no local database here); `npm test` 168.

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
