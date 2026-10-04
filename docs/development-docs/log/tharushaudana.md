# Development log: @tharushaudana

@tharushaudana's entries, newest first. Only @tharushaudana adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-05 - fix(flow): the main flow runs end to end without anyone filling the gaps by hand

`fix/main-flow-gaps` · @tharushaudana

Walking the main flow (order, plan, defer, publish, load, release, deliver, receive, PIN) found four gaps. The cutoff never closed a day, so no plan was drafted unless the dispatcher pressed Close: the 16:00 job now closes each depot's day and Planning drafts it, unplaceable orders deferred (R-ORD-15). The driver could not see today's trip before release, and had no way to say they were at the depot, so Start trip jumped straight to the first store: Home now shows today's waiting trip with "I'm at the depot" (`delivery:ArriveAtDepot`, R-EXE-24), and the dock board marks that trip "Driver at the dock" and lists it first for the loader to take (R-LOD-12). The store was never told the truck had arrived: an arrival now notifies its store manager to unload and check (R-EXE-25). Edge cases FLOW-01 to FLOW-03.
Screens also needed a manual reload to see each other's changes (the driver's run was read only after the phone wrote). Every live read now polls every 2 s while on screen (driver run, loader board and manifest, dispatcher day, store orders, receipts and deliveries), re-reads at once on returning to the screen or coming back online, never flashes a loading state for a background read, and never cancels a read still on its way; the driver's week look-ahead stays at about 30 s.
Verified: `EventCatalogueTest`, `ModuleBoundaryTest`, backend unit tests, `npm test` (281), typecheck, build, the driver check-in on the build.
Open: the new integration paths (cutoff close, the loader board join) run in CI only; no database here.

---

## 2026-10-05 - feat(live): vehicles tracked every five seconds, pushed to the map, and drawn as in Figma

`feat/live-vehicle-tracking` · @tharushaudana

The live map was a round arrow per vehicle, snapped to eight directions and polled every 15 s, from a phone that kept a fix every 30 s or 150 m, started before the trip and stopped at the last stop. Now the phone records every five seconds from Start run until it is back at the depot (R-EXE-23), each stored fix is pushed to the dispatcher and store maps over a server-sent event stream, the truck faces the way it moved (R-EXE-22), and a chosen vehicle shows its whole run path from the trip's start, growing live. Trucks and depot pills follow Figma 189:21746. No new table: `execution.vehicle_positions` already held the trail. Detail in the [walkthrough addendum](../../issues/161-live-map/WALKTHROUGH.md).
Verified: `PositionPolicyTest`, `ModuleBoundaryTest`, `npm test`, typecheck, build, dispatcher live map suite.
Open: on a phone the recording stays foreground only.

---

## 2026-10-05 - feat(admin): the demo clock takes a date, and steps by the hour and the day

`feat/demo-clock-date-shift` · @tharushaudana

The demo control room set only a time on the demo day. It now shows the demo date and time and how far it is from real time, steps it by -1 day, -1 h, -15 min, +15 min, +1 h and +1 day, adds "Next day 05:00", and sets any depot date and time. Each is one `demo:SetClock` with the reason, as before. The clock still stays within 7 days of real time (`DemoSettings`): the screen offers only that window and the server now says so in words instead of "Invalid demo clock, interval or speed".
Verified: `DemoDomainTest`, `npm test` (with `admin-demo-clock.test.ts`), `tests/e2e/demo-control-room.spec.ts` (3).

---

## 2026-10-05 - fix(messaging): voice notes replay on an iPhone

`fix/136-voice-iphone` · @tharushaudana

On preview an iPhone (iOS 18.7) played a voice note once, then showed "Could not play". Safari plays and rewinds media through byte ranges; the audio was always answered `200` in full and the proxy dropped `Range`, so the rewind at the end failed. Now the audio answers ranges with `206`, the proxy forwards `Range`, `Accept-Ranges` and `Content-Range`, a replay whose rewind is refused reloads the note, and an interrupted start (pressing another note) is no longer a failure. Phones record MP4/AAC where they can, which every phone plays (MSG-15).
Verified: `npm test` (243), driver suite replay spec, dispatcher 63, store 56, loader 31; the range case in `MessagingIntegrationTest` runs in CI.
Open: notes already recorded as WebM on Firefox depend on Safari's WebM support; to be checked on the iPhone after deploy.

---

## 2026-10-05 - feat(messaging): resolve reports, real voice in the inbox, signs on the line

`feat/136-voice-fixes` · @tharushaudana

The dispatcher resolves a report from the thread with a note (`message:Resolve`, R-MSG-07); resolving its issue resolves it too. Its warning sign then leaves the timeline; the report stays, marked. The sign waves gently, sits centred on the run's line, and replaces the stop's dot when the report is about a store on the trip. Every timeline run has a messages icon. A voice note's waveform travels with the audio and is stored, so every phone draws the real bars; the driver's inbox plays a voice message's own audio instead of reading the notification aloud. The report bubble lost its border artifact. The driver's Messages button is an icon in the top bar.
Why: user review of preview on 2026-10-05.
Verified: `MessagePolicyTest`, `ModuleBoundaryTest`, `EventCatalogueTest`; `MessagingIntegrationTest` adds resolving, issue resolution, waveform and outlet cases (CI); `npm test`; the four role suites.
Open: a resolved report does not notify its reporter.

---

## 2026-10-04 - feat(frontend): voice notes recorded and played as in a messaging app

`feat/136-voice-ui` · @tharushaudana

The thread's "Voice" button is a mic: hold to record and let go to send, slide up to lock and talk hands free (then stop, listen, send), slide left to cancel; a tap says how. A keyboard press starts a locked recording. A sent voice note is a card in the bubble's colours (teal for your own, ink for others, red for a report): a play button, a waveform that fills as it plays (tap to seek), the time and 1x/1.5x/2x, instead of the browser's black player. Bars come from the levels heard while recording, or from decoding the audio; audio the browser cannot decode keeps steady placeholder bars. One note plays at a time. Labels are in the loader's Sinhala and Tamil too.
Why: the user asked for WhatsApp-like voice and a card that matches the UI.
Verified: `npm run typecheck`, `npm test` (229, adds `messaging-waveform.test.ts`), driver suite (26, `voice.spec.ts` holds, slides, locks, cancels and plays real audio from the fake microphone), dispatcher (57), loader (31), store (55; one receipt flake passed on rerun).
Open: try it on a real phone once the microphone header (`microphone=(self)`) reaches the edge with the next production deploy.

---

## 2026-10-04 - feat(messaging): voice notes offline, on a shared loader device, and kept for 400 days

`feat/136-messaging` · @tharushaudana

Voice notes now work end to end. Deployed, the site had denied the microphone (`Permissions-Policy`) and the playback of a fresh recording (CSP `media-src`); both fixed. With no signal a message, voice included, is kept on the device and shown as waiting: the audio is uploaded first and the message waits for it (`waitsFor` in the shared queue and the service worker, MSG-10). The driver, the loader and the store all keep what they write. On a shared loader device a message is written as the loader who entered their PIN (MSG-12), and the thread reads in Sinhala and Tamil. Audio is cleared after 400 days, and the message stays (P-33, MSG-11).
Why: voice notes were untested in a browser and would not have worked on the deployed site; the loader and the store could not write offline.
Verified: `npm run typecheck`, `npm test` (206), `MessagePolicyTest`, `ModuleBoundaryTest`, `EventCatalogueTest`, `e2e-driver/voice.spec.ts` recording real audio with Chromium's fake microphone, and the loader and driver message specs. `MessagingIntegrationTest` (9) runs in CI.
Open: recording on real phones (Android Chrome, iPhone Safari) once preview has it.

---

## 2026-10-04 - feat(frontend): the trip's thread on every role's screen

`feat/136-messaging` · @tharushaudana

One shared thread view (`shared/ui/TripThread.tsx`) with @mentions, reports and voice notes. Dispatcher: report signs on the Live timeline open the thread at the report, the bell's Reply opens it, and the trip page's "Send an update", "Notify store" and voice now write on it. Driver: Messages with a count of new ones; typed messages keep on the phone with no signal (MSG-10). Loader: Messages on the load sheet. Store: a message notification or a delivery's Message opens the thread. Detail in the [walkthrough](../../issues/136-messaging/WALKTHROUGH.md).
Why: "Exception · click to open" opened nothing, and Notify store, Send an update and Voice were drawn but disabled.
Verified: `npm run typecheck`, `npm test` (201), `npm run build`, browser suites: dispatcher 47, driver 22, loader 30, store 43, all passed.
Open: calls; what each outlet was told per stop; the loader and the store write online only; threads for issues, orders and deliveries.

---

## 2026-10-04 - feat(messaging): a thread per trip for the dispatcher, loaders, driver and stores

`feat/136-messaging` · @tharushaudana

New module `messaging` (ADR-004, #135 decided): a trip's thread opens with its published plan. The dispatcher reads everything; the loaders, the driver and the stores read broadcasts, what is for them and their own (R-MSG-01). The dispatcher writes to any of them; everyone else writes to the dispatcher, and the driver also to its stops (R-MSG-02). Every raised issue about the trip lands on it as a report for the dispatcher alone, once (R-MSG-03, R-MSG-05). Messages and reports may be voice notes (R-MSG-06). `message.posted` reaches the dispatcher's bell for every message, and the others only for what is theirs (routing v4, R-NOT-14).
Why: the timeline's warning signs opened nothing, and nobody could write to anyone; decisions of 2026-10-04 on #136.
Verified: `MessagePolicyTest` (13), `ModuleBoundaryTest`, `EventCatalogueTest`; `MessagingIntegrationTest` runs in CI on PostgreSQL.
Open: the role screens (next PR); threads for issues, orders and deliveries; how long voice notes are kept.

---

## 2026-10-04 - feat: the dispatcher's Live tab as in the Figma frames

`feat/dispatcher-live-figma` · @tharushaudana

Needs you (cards most urgent first and the trip board), the timeline, the map with the vehicle panel, and the trip page, from one join of run sheet, loading trip and position (`data/liveDesk.ts`). The header no longer wraps; the map no longer repeats the header's filters; the offline count works. Detail in the [#19 walkthrough](../../issues/019-dispatcher-ui/WALKTHROUGH.md).
Why: Live differed from the Figma in layout and shapes, and had no trip page or recommended actions.
Verified: `npm run typecheck`, `npm test` (149), `npm run build`, dispatcher browser suite (26 passed; the 3 Forecast failures are already on `dev` since 86e6d57).
Open: store and driver messages (Notify store, Send an update, voice, calls, what each outlet was told) need a Notification command; they are drawn and disabled.

---

## 2026-10-03 - feat: the Forecast chart scales to demand, and says how many refrigerated vehicles a day needs

`feat/forecast-dispatcher-scale` · @tharushaudana

`focusScale` draws fleet capacity on the chart only when it is within 1.6 times the busiest week; otherwise the bars follow demand with round ticks and the fleet is stated on the top edge with the peak week's share. The chilled strip does the same and adds the refrigerated vehicles an average day needs under each bar. With the Figma's numbers the chart is unchanged.
Why: on preview the fleet (about 12,150 m³ a week at Peliyagoda) is ten times a week's demand, so every bar was a sliver and the chilled shares (5-16%) were unreadable.
Verified: `npm run typecheck`, `npm test` (129), `npm run build`, dispatcher browser suite (25). Screenshots with preview-like and Figma-like numbers.
Open: nothing.

---

## 2026-10-03 - feat: notifications coloured by tone, issues in red, and an unread count on every bell

`feat/notification-tones-badge` · @tharushaudana

`toneOf` makes anything about an issue urgent, and `TONE_STYLE` gives each tone one edge, tint, dot and label colour, used by the dispatcher, loader and store manager inboxes; info is blue, so only good news is green. `CountBadge` puts the unread count on each bell (99+ above 99) in place of the red dot.
Why: every row read green whatever it said, and only two bells showed that anything was unread, not how much.
Verified: `npm run typecheck`, `npm test` (127), `npm run build`, dispatcher (24), loader (18) and store (32) browser suites.
Open: the driver screens still show a sample count; they get the real one with the driver inbox (#21).

---

## 2026-10-02 - fix(deploy): start the model service

`fix/deploy-start-ml` · @tharushaudana

`deploy.sh` now starts `ml` with the other services.
Why: the preview deploy built the model service image (#103) but `up` names its services, and `ml` was missing, so it never ran and every plan fell back to the deterministic estimate.
Verified: not until the next preview deploy; the script is checked only by deploying.
Open: nothing.

---

## 2026-10-02 - fix(reference): import road conditions in one statement

`fix/reference-import-bulk-series` · @tharushaudana

`ReferenceVersionWriter.writeSeries` inserts traffic speed and road conditions as one statement each over arrays, not one per row.
Why: the preview deploy of #103 failed in `init`: about 11,000 road-condition rows, one round trip each, ran past the import's 15 s transaction deadline on the VPS database. The import rolled back whole and nothing was replaced.
Verified: compile; CI imports the reference data in every integration test.
Open: nothing.

---

## 2026-10-02 - feat(intelligence): serve the Datathon models and score published plans

`16-intelligence` · @tharushaudana

Intelligence module and a Python model service (`ml-server/`, models in Git LFS). Published plans are scored per stop (service minutes, P(late)) and stored with their model; a weekly job forecasts ten weeks of demand. Model registry commands, supply probability (R-RCP-06), a training export, and a deterministic fallback everywhere. Traffic speed and road conditions are now reference data.
Why: issue #16; the Datathon models were trained but nothing in Waypoint used them, and every plan said "without predictor". Decisions in [the plan](../issues/016-intelligence/PLAN.md); rules R-ML-01 to 06, cases ML-01 to 08, A-36 to A-38, P-28, P-29.
Verified: `ml-server` pytest (11, including exact equality with the vendored inference and the Task 2A submission); 17 domain tests, `ModuleBoundaryTest`, `EventCatalogueTest`, application start; frontend typecheck. The backend integration tests need CI's database.
Open: the screens (#18, #19, #22); `git-lfs` on the VPS; no retraining pipeline; road conditions past 2026-06-28.

---

## 2026-10-02 - feat(notification): route events to people, with an inbox and web push

`14-notification` · @tharushaudana

Notification module, backend only: 18 event consumers, a versioned routing table (`notification.routing_rules`), inbox and unread count, a live count over server-sent events, `MarkRead`, `MarkAllRead`, `Subscribe`, `Unsubscribe`, and a push job with retry and dead letter. Web push encryption and VAPID are written on the JDK. Identity gains a dated `recipientsFor`, so tomorrow's plan reaches tomorrow's driver.
Why: issue #14; the store, driver and dispatcher screens had nothing behind their notification placeholders. Decisions are in [the plan](../issues/014-notification/PLAN.md); rules R-NOT-06 to 09, cases NOT-01 to 09, A-34, A-35, P-27.
Verified: domain and crypto tests (27, including the RFC 8291 vector), `ModuleBoundaryTest`, `EventCatalogueTest`, frontend typecheck and `npm test`. The 26 database integration tests pass in CI (`mvn verify`, 681 tests, none skipped).
Open: each role UI places its own inbox, badge and push opt-in (#18, #19, #21); no admin API for a new routing version; dock and next planned date are missing from their events.
