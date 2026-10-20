# Development log: @Oxshadha

@Oxshadha's entries, newest first. Only @Oxshadha adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-21 - feat: live refresh phase 1, every screen on the change stream

`dev` · @Oxshadha

- The loader's dock board and load sheet polled every 2 s and never listened to the change stream; trip messages polled every 10 s and the store's issues every 60 s. All four now re-read on the depot's (or the store's outlets') signal (F-07b). The safety-net poll is 60 s while the stream is up and at least 10 s while it is down (was 15 s and 2 s), so an idle loader tablet goes from about 60 reads a minute to about 1.
- A dispatcher's command that went through tells every screen on the page showing its topic to read at once (`announceChange`), so their own change never waits for the signal or a poll.
- `useResource` read nothing when a read was switched on under the same key (the Issues screen's Resolved tab) until the next poll; it now reads at once. The 2 s poll had hidden it.
- Plan for the next phases: [docs/issues/live-refresh/PLAN.md](../../issues/live-refresh/PLAN.md). Open: the folder takes an issue number once the issue exists.

---

## 2026-10-20 - fix: one temperature badge in every role

`dev` · @Oxshadha

- Each role drew "chilled" its own way (a blue text pill and a dot on the dispatcher's screens, an outlined text chip in the store). `TemperatureBadge` and `RefrigeratedBadge` in `src/shared/ui/Temperature.tsx` use the loader's Figma icons: the thermometer for chilled goods, the box for ambient, the snowflake for a refrigerated vehicle. The dispatcher's orders, order drawer and plan board and the store's Home, Track and Deliveries use them; the loader's badge already had these icons and is unchanged. `Icon` takes an optional size for small pills. The loader's "Take trip" stays on one line in the trip table (asked for by @Oxshadha, a one-cell change), and the dispatcher's order states are short enough for their column ("In draft", "Delivered · awaiting store").

---

## 2026-10-20 - fix: field flow bugs from a demo walk-through

`dev` · @Oxshadha

- Resolving a report in a trip's messages left its issue open, because R-MSG-07 only went from issue to report. A report made from an issue now carries `issueId` (`MessageView`), and the dispatcher's Mark resolved sends `issue:Resolve` with the issue's version, then resolves the report at once. The store was never told an issue was resolved: MODULES.md listed Notification as a consumer of `issue.resolved`, but none existed. `OnIssueResolved` and routing version 14 tell the outlet's store managers (R-NOT-19).
- The driver's "21 to review": the demo drive stamped positions with the demo clock, which the server refuses as more than five minutes ahead (DEMO-12), and each refused batch was held for review. Points now carry real time, and a refused position batch leaves the phone (`outcome.ts`, `sw-drain.mjs`), including ones already held.
- The dispatcher's sidebar foot ran off the screen under the demo bar (`lg:h-dvh` under the bar); the dispatcher, admin and store shells now subtract `--demo-banner-h`. The publish step counted only outlets whose order waits, so it said "0 store managers" when every order was on a trip; it now counts every outlet on a trip too (R-NOT-15).
- The loader's "Arrived only" was an independent switch styled like the one-of-five filters, so two chips looked picked; it is now one of them. A loader's message notification now opens the trip's messages at that message, to hear and answer it.
- Wording: reconstructed product ids read "Fresh dry goods 263 (inferred)" in notes, issues and the thread; issue detail names the order by its reference and drops internal ids; the admin profile drops the account id and row version; the orders table keeps the state, at risk and issues as separate pills. The error pages lay out in two columns on a wide screen, say "this device", and use the sign-in's sun and moon toggle (`ThemeToggle`). Decide's "How the engine decides what waits" is folded to one line until opened.
- Open: the loader tablet shifted sideways on one Android tablet but fits at every width from 768 to 1280 px in a browser, so it is not reproduced; the store timeline now completes "Arriving" on the stop's arrival, and any other timeline fault needs a reproduction. Order references stay `WPO-` and 12 characters (they are sent to the warehouse); a shorter format is a decision for the team.

---

## 2026-10-10 - feat: demo sign-in for judges; Decide never shows an order id

`dev` · @Oxshadha

- The sign-in page offers "Use a demo account" (dispatcher, driver, store, loader) and a pick fills the form; the loader's PIN screen offers "Use demo PIN". It is read from `GET /api/session/demo` (identity `DemoSignInQuery`), which offers nothing unless `DEMO_SIGN_IN_ACCOUNTS` and `DEMO_SIGN_IN_PASSWORD` are set and demo mode is on, and ends on its own after `DEMO_SIGN_IN_UNTIL`. An account is offered only while the password still signs it in, and only a single field role, or an administrator when `DEMO_SIGN_IN_ADMIN=true` (off by default, as it changes accounts and policies and the list is public); an auditor or operations account never. Each serve is counted (`waypoint.demo.signin.served`); the answer is `no-store` and the service worker keeps nothing under `/api/`.
- Both password fields in the shell have a show/hide button.
- Decide showed some deferred orders as raw ids: orders of other days were read one request each, and on a large plan the browser queued them past the 8 s read timeout and dropped them. They are now read in batches of 200 (`GET /api/orders/by-id`, scope by row-level security as for a single read), and an order that still cannot be read says "Order not found".
- Open: "Use demo PIN" needs native review in si and ta.

---

## 2026-10-09 - fix: the pilot KPIs are a card on Overview, not a sidebar tab (B-05)

`dev` · @Oxshadha

- The sidebar keeps the dispatcher's daily work. The pilot KPIs, a weekly task for the pilot's weeks, are a card on Overview (the latest week's headline figures beside the app's counts); "Record or view all weeks" opens the full page at `#/kpis`, which has a Back.

---

## 2026-10-09 - decision: FF-01 done as two screens; the report button says Send

`dev` · @Oxshadha

- @jv-ransika chose to keep the loader's and the store's report screens apart, sharing the photo flow (roadmap 04 FF-01, done). A report always goes to the dispatcher, so both buttons say "Send" instead of "Send to dispatcher", which also stops the store's button wrapping on a phone.

---

## 2026-10-09 - feat: the driver has no chat, a store writes to its driver (R-MSG-16)

`dev` · @Oxshadha

- Decision with @Oxshadha: the driver app stays simple. The Messages screen is gone; the top bar's bell opens Home, where every message plays (voice with its transcript, text read aloud), from the phone's copy with no signal. The driver writes only reports, typed or by voice.
- A store manager writes to the dispatcher, the trip's common room, or its own driver while its delivery is to come ("Your driver"); never privately to another store. R-MSG-02 amended, R-MSG-16, MSG-27.
- The loader hears a voice message in Notifications (play button and transcript, `VoiceNotice` now in `shared/ui`), and a reported issue can carry an optional voice note, posted on the trip's thread to the dispatcher after the report.

---

## 2026-10-09 - feat: transcripts and listening for every reader of a voice note; the driver hears messages on Home

`dev` · @Oxshadha

- A voice note's machine transcript now shows wherever the note is read: the driver's Home (a play button and the words, no waveform, `VoiceNotice`, read through `GET /api/threads/{id}/messages/{id}/transcript`), and the driver's, loader's and store's threads. R-MSG-15 updated.
- The driver's and store's threads were English in Sinhala and Tamil: they now pass the role's translator, and the thread's words are kept once in `shared/messaging/threadStrings.ts` (the loader's reviewed rows plus 22 new ones, marked for the native-speaker review).
- `.env.example` documents `TRANSCRIBE_MODEL` and `PUBLISH_RISK_REVIEW`.

---

## 2026-10-09 - feat: every file in the encrypted bucket, the risk review before Publish, one photo flow (M-01, T1-01, FF-01)

`dev` · @Oxshadha

- M-01: with `OBJECT_STORE=s3`, proofs, issue photos, voice notes, Fuel Passes and profile photos go to the bucket, encrypted on the server first (AES-256-GCM, `S3_ENCRYPTION_KEY`, object key bound in; a changed object is refused). Each module declares its table (`MediaTable`); `MediaBackfillJob` moves older files, reads them back, compares, and clears the database copy 30 days later. Deployment steps for a private bucket and a bucket-only token in deployment.md. R-PLT-08, PLT-20.
- T1-01: the Publish step lists the draft's stops at 35% or more (the tuned estimate, "estimate: learning"), each kept or swapped with a reason (`ml:RecordRiskReview`, append only). Never blocks Publish. R-ML-25, ML-37.
- FF-01: one photo flow for every report sheet (`shared/media/useTakenPhotos.ts`), each role keeping its own look and command; the store's report sheet no longer leaks previews. One visual sheet would change the loader's look (J.V.). M-02 needed nothing more; its status corrected.

---

## 2026-10-09 - fix: MCP for the dispatcher only; the driver can install the app

`dev` · @Oxshadha

- Decision: an AI assistant over MCP is the dispatcher's. The driver, loader and store manager roles lose `WaypointMcpReader` and `WaypointMcpWriter` (migration `20261009T1300`), so they are refused; an administrator can still grant one named person. MCP tests that used those roles now grant their test user first (`support/McpGrants`). The button follows the permission: `GET /api/mcp/access` answers from the policy, and `McpSettingsRow` in every role's Settings shows only on a yes, so an administrator's grant or block shows on the next visit. R-IAM-40, SEC-48.
- Install: Chrome reports every role address installable (own name, three icons, active worker, no manifest errors). The driver's Settings had no "Install app" (`showInstall={false}` with no other way); it now has the shared row under Language (`SettingsPanel` `installT`, `rows`), in Sinhala and Tamil as the loader says it.

---

## 2026-10-09 - feat: proofs in the S3-compatible object store when OBJECT_STORE=s3 (M-01, first step)

`dev` · @Oxshadha

- `platform/storage/S3Objects`: put, get, delete over path-style HTTPS with Signature V4 written out (`SigV4`, tested against AWS's published example) instead of the AWS SDK. `ObjectStoreProperties` refuses to start with the store half configured and never prints the secret.
- `S3ProofStore` takes new proofs under `proofs/`; older ones are still read from the database and a purge clears both. Live check on the team's account: one bucket, `storage`; a round trip matched. Other media stay in the database for now. EXE-44; roadmap 02 M-01 updated.

---

## 2026-10-09 - feat: voice notes written down by Gemini for the dispatcher (FF-05)

`dev` · @Oxshadha

- A posted voice note is queued (`messaging.voice_transcripts`) when `TRANSCRIBE_ENGINE=gemini`; a job on its own bulkhead sends the audio with no transaction open (`GeminiTranscription`, key in a header, nothing logged), three tries, and the words are cleared with the audio. Off by default; production waits for the DPA (P-PD-1).
- The dispatcher's thread shows "Machine transcript, check audio · Sinhala" and the words, or "No transcript, play audio". Phone screens unchanged. R-MSG-15, MSG-26; roadmap 04 FF-05 updated.
- Default model `gemini-3.8-flash` (`TRANSCRIBE_MODEL`): exact on a synthetic note in about 70 s, where the lite models were 2.5 s but misheard a town; `gemini-2.5-flash` is retired for new keys.

---

## 2026-10-09 - feat: the ready replies' order learned from what dispatchers take (AI-J5)

`dev` · @Oxshadha

- A post from a ready reply now records the replies that were on screen and whether the second and third were swapped on purpose (`messaging.reply_uses.shown`, `explored`; refused when the pick is not at its rank). Picks alone cannot say what was passed over.
- `ReplyRanker`: logistic regression on every reply shown with its place as a separate feature, read as the dispatcher through Messaging's new `ReplyUseQuery`; `GET /api/ml/reply-ranking`. The composer reorders only the replies the rules offer, swaps 2 and 3 one time in ten, and says "Suggestions: rules only" until 200 posts in 28 days. R-ML-24, ML-36; roadmap 06 updated.

---

## 2026-10-09 - feat: profile photos, shown to the people who work with that person today (FF-07)

`dev` · @Oxshadha

- An administrator adds a member's photo from their Edit sheet with how the person consented (`PUT /api/accounts/{id}/photo/{photoId}`, `iam.profile_photos`, shrunk to WebP in the browser, 256 KB at most) and removes it on request (`iam:RemoveProfilePhoto`: the image is deleted, who, when and why are kept). P-PD-1.
- Seen only by the person, an administrator, someone sharing a depot with them today, and the store their delivery reaches on its day (`/api/execution/deliveries/{id}/driver-photo`); anyone else gets 403, audited. The store's home shows the driver's photo beside the name, initials otherwise (`PersonPhoto` in `shared/ui`). R-IAM-39, SEC-47; roadmap 04 FF-07 updated.
- Not on the dispatcher, driver or loader screens yet; database storage until M-01.

---

## 2026-10-09 - feat: the pilot KPI baseline, week by week (B-05)

`dev` · @Oxshadha

- The dispatcher's new Pilot KPIs screen records a depot's week of the eight B-05 KPIs from run sheets, phone logs and Finance's write-off sheet (`ml:RecordKpiWeek`, `ml.kpi_entries`, append-only: a correction is a new row beside the first). Beside each recorded figure, what the app counted from its own finished stops (Fresh on time, before 08:00, stops recorded on the phone).
- Only the depots the dispatcher oversees, read and written (RLS by depot; a refusal is 403 and audited). R-ML-23, ML-35; roadmap 01 B-05 updated.

---

## 2026-10-09 - feat: the reload time measured; the dispatcher connects an assistant

`dev` · @Oxshadha

- R-PLN-47's 20-minute reload was a placeholder. The System constraints screen now shows each depot's measured reload beside it: the median and the minutes 3 in 4 stay within, from Loading's released trips 2 and later over 4 weeks, ten at least (`ReloadMeasure`, `LoadingQuery.reloadSamples`), read as the same person so a depot outside their scope stays out. A-26 updated.
- Issue #177: the "Connect AI assistant" sheet existed but no screen used it; the dispatcher's Settings now show it, with the address the server names (`/api/oauth/protected-resource`), and hide it where MCP is not set up.
- `.env.example` and compose carry the object store (`S3_*`) and transcription (`TRANSCRIBE_ENGINE`, `GEMINI_API_KEY`, Azure) settings; empty keeps today's behaviour.

---

## 2026-10-09 - feat: the loader and the store manager complete in Sinhala and Tamil (FF-00)

`dev` · @Oxshadha

- Scope revised: the loader and the store are completed rather than removed, since a half-translated screen is worse than either language. The dispatcher stays English.
- Loader: the sync state, two labels and four texts that were English; two Sinhala rows that dropped the number of PIN tries fixed. Store: about 300 texts on 25 screens; the helpers that build a sentence with a value take the screen's language; unit and line counts through `useCounts()`.
- `tests/role-strings.test.ts` (shared parser `tests/i18n-keys.ts`) holds both roles to the driver's rule; `e2e-store/language.spec.ts` receives a delivery in each language. Browser: store 65, loader 33, driver 48. CI still not started (billing).

---

## 2026-10-08 - feat: the driver works in Sinhala and Tamil (FF-00)

`dev` · @Oxshadha

- Scope decided: Sinhala and Tamil for the driver only; the loader keeps its drafts, the store's stay unrequired, the dispatcher stays English.
- Every driver screen follows the language already chosen in Settings: 337 texts in `roles/driver/data/strings.ts`, the loader's wording where the English matches. Only words change, no layout or flow (role UI rule: a translation, not a redesign). Text compared in code stays English and is translated where drawn; a stop's delivered tick reads a flag now, not its status text.
- Guarded by `tests/driver-strings.test.ts` (every translated text has both rows, values kept) and `e2e-driver/language.spec.ts` (a stop worked in each language, the same commands sent). Drafts for the native speaker. Left: ready replies in the reader's language.

---

## 2026-10-08 - fix: the model service image installs no GPU library

`dev` · @Oxshadha

- Found on the fresh-install walk (B-03): on Linux `xgboost` 3.2.0 requires `nvidia-nccl-cu12` (a 352 MB download) that a CPU-only service never loads. Linux now installs `xgboost-cpu` 3.2.0, the same release and module without it; macOS keeps `xgboost`, which has no such dependency there.
- Not yet verified by building the image: the walk stopped because this machine's disk is full, and CI is blocked by the account's billing. Both checks are on the B-03 list.

---

## 2026-10-08 - test: the Live read at 1,280 trips; one stream per session decided against

`dev` · @Oxshadha

- `LiveReadLoadTest`: 1,280 trips (10,240 stops) over the depots in the database, read as the Live screen reads them: on two depots of 640 trips, one depot p95 836 ms and 3.6 MB, both together 837 ms. Row-level security is half the time (15 ms without, 338 ms with); hoisting the role check saved 42 ms, so the policies stay. Detail in [B-04](../../roadmap/01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline).
- F-07's "one stream per session" is not built ([03](../../roadmap/03-INFRA-DECISIONS.md#one-live-stream-per-session-not-now-decided-2026-10-08)): production is HTTP/2, a tab holds at most three streams, and each already beats within 25 s.

---

## 2026-10-08 - fix: the driver and the store within their request budget (B-04)

`dev` · @Oxshadha

- Measured every role on the dev stack (table in [B-04](../../roadmap/01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline)). A driver with nothing released today made 85 requests a minute against the gate of 60: the week ahead was probed every 30 s. Now every 2.5 minutes (37 a minute); the smallest change in the driver's data layer, no change to its screens, with a browser test for that day.
- The store made 109 a minute. It now watches its outlet on the change stream: `outlet=` with `receipt:Read` and the outlet scope, 403 and audit outside it; a change naming the outlet re-reads, as its topic only (PLT-19). 29 a minute.

---

## 2026-10-08 - feat: alerts reach a channel; table growth watched (B-04)

`dev` · @Oxshadha

- Alertmanager in the observability profile: grouped by alert and environment, critical repeated hourly, `info` kept on its page, the rest of an environment held back while its backend is down. It posts to a Slack-compatible webhook from `ALERT_WEBHOOK_URL` in `.env`, empty until the team names a channel (with the H-03 secrets). Checked live: a warning reached a local listener, an `info` alert did not, and `DeadLetters` was held back behind `BackendDown`.
- `TableSizeJob` publishes rows and bytes hourly for the eight tables that grow every day, from the catalogue, partitions summed; alerts `TableGrowing`, `RetentionBehind`, `DiskFillingUp` and the three assistant alerts `mcp/README.md` asked for; two dashboard panels.
- CI's compose job now runs `promtool test rules` and `amtool` on any change under `observability/`.
- Fixed: `JobFailing` named the scrape job instead of the failing one (Prometheus keeps the backend's `job` tag as `exported_job`); the test had fed the unrenamed label. Detail in [B-04](../../roadmap/01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline).

---

## 2026-10-08 - fix: one rule, one number; the position lane proved in a browser (F-03)

`dev` · @Oxshadha

- R-PLN-43 named two rules. The published plan's 16:00 edit window is now R-PLN-48 in the catalogue, its domain rule, its test and every comment; R-PLN-43 stays the suggested stop order.
- F-03 was built without its browser test: `position-lane.spec.ts` queues 2,000 positions and a trip start offline, and the start lands first, then every fix. STATUS corrected: retraining exists (MF-05) and missing arrivals are filled from positions.
- Rechecked: FF-06 is settled (the fuel log dropped by decision, the Fuel Pass built); F-07 is built but for folding notifications and positions into one stream.

---

## 2026-10-08 - feat: a second trip waits for the drive back and a reload (R-PLN-47)

`dev` · @Oxshadha

- Rule set version 2 (from 2026-10-09): `trip.return.counted` = 1 and `trip.reload.min` = 20 (placeholder), editable on System constraints; `TripTimeline` starts a second trip after the drive back from the first trip's district and the reload. Windows and shown arrival times read it; budgets keep the booklet's formula. The booklet's own parameters, used for Task 2B, keep A-26.
- Replay of 80 stressed days: 4,891 served against 4,975 on the booklet's clock, the difference being second-trip stops that would have arrived after their windows.
- Task 2B write-up: section 6 now measures one reefer back (80 or 81 orders against 74); appendix F re-times the allocation with the drive back (20 second-trip stops late) and says why the submission keeps the booklet's model.

Why: a plan must not promise a second Fresh delivery the truck cannot make before 08:00.
Verified: TripTimelineTest, the planning and S1 tests, the seeded rule set case, the replay, the full suites before the push.

---

## 2026-10-08 - feat: why an order cannot go (AI-2) and what a vehicle back would change (AI-1)

`dev` · @Oxshadha

- Specifications first, in roadmap 06. AI-2: `FitDiagnosis` names the closest place (room and time before structural rules) and the smallest change, a swap the engine checked; `GET /api/plans/preview/fit`; the explanation sheet adds "Closest it came" and "Smallest change" (R-PLN-45, PLN-45).
- AI-1: `GET /api/plans/{planId}/what-if?vehicle=` runs the day twice with no transaction open and lists the difference; `/what-if/vehicles` gives the choices; a card on Decide (R-PLN-46, PLN-46). On S1 one reefer truck back serves 80 or 81 orders instead of 74.
- Both are worded by code; the gateway may reword them under the allow-list (new keys `closest`, `change`, `whatIf`, `nowServed`, `nowDeferred`) with every number checked (R-ML-10).

Why: the solver decides and computes; a model only puts its answer into words.
Verified: FitDiagnosisTest 3, FitDiagnosisPeakDayTest, WhatIfPeakDayTest, the two planning cases, plan-view Node and explain browser tests, the full suites before the push.

---

## 2026-10-08 - feat: deferral priority version 2, chosen by a replay of 80 days (R-PLN-21)

`dev` · @Oxshadha

- Longest since last served moves from the last tie-break to just after Fresh and chilled. `PriorityReplayTest` replays the 40 busiest days of each depot (real orders and history, S1-like workshop losses): 4,975 served against 4,974, different on 3 of 80 days, never by more than one order; waiting time before Fresh was worse on 10 days and not adopted. On S1: 74 served and 118.8 m3 chilled against 73 and 109.2.
- Migration closes version 1 on 2026-10-09 and adds version 2; `PriorityPolicy.DEFAULT_KEYS` matches it, so the product and the Task 2B submission use one order. The Task 2B write-up is rebuilt on the new allocation, each deferral checked against the data.

Why: one policy for the product and the submission, chosen on evidence wider than one day.
Verified: the planning tests, the replay, the official checker on the new CSV, the full suites before the push.

---

## 2026-10-08 - feat: workshop clash warning (T2-02) and the deferral order on Decide (T2B-01)

`dev` · @Oxshadha

- T2-02: the vehicle drawer books a workshop day up to 10 weeks ahead and warns when that day's forecast week would be left short, chilled for a reefer; advice only (R-FLT-05, PLN-43).
- T2B-01: `GET /api/plans/policies/{id}`; the Decide step lists the plan's own priority order in plain words (R-PLN-21a, PLN-44).

Why: a workshop booking on a short week should be a choice made knowingly; the deferral order shown must be the one applied.
Verified: forecast and plan-view Node tests, the workshop and Decide browser specs, the planning policy case, the full suites before the push.

---

## 2026-10-08 - feat: likely-late notices, the dispatcher first (T1-02)

`dev` · @Oxshadha

- `LateNoticePolicy` (domain): told once at P(late) 0.5 or more; the store only when the flag's precision lower bound (Wilson 95%) is 80% on 30 or more flagged stops over 28 days, at most 2 a week; else the dispatcher (R-ML-22, ML-34).
- `LateNoticeJob` every 10 minutes, `ml.late_notices`, event `stop.likely_late` (catalogued), routing version 13.
- τ frozen once per model from its backtest (`LateThreshold`, `LateThresholdJob`, `ml.late_thresholds`), 0.5 until then; the notice says "expected about 07:40" (`ExpectedArrival`: planned arrival plus the predicted extra unloading before it) when that is after the window.

Why: a store warned by a flag that is often wrong stops reading warnings; the dispatcher filters until the flag has earned it.
Verified: LateNoticePolicyTest 3, LateThresholdTest 3, the two new intelligence cases, the event catalogue, the full backend suite before the push.

---

## 2026-10-08 - feat: the forecast against the 8-week average (T2-03, second slice)

`dev` · @Oxshadha

- `ForecastBaseline` (domain): the mean of the 8 weeks before, from weeks with orders, 4 or more; the nightly judging stores it beside each week (`baseline_total_m3`). The accuracy read gives both errors on the same brand-weeks; the Forecast panel says better or worse than the 8-week average and adds a column (R-ML-21, ML-33).

Why: a forecast is only worth its cost if it beats averaging the last weeks.
Verified: ForecastBaselineTest 2, the judging and screen cases, forecast Node and browser tests, the full suites before the push.

---

## 2026-10-08 - feat: unloading range for the driver, a range on the weekly forecast (MF-06)

`dev` · @Oxshadha

- The driver's run sheet carries the unloading range; the next stop reads "15 to 24 min to unload" (J.V. agreed).
- `ForecastRange` (domain): pooled log-ratios of the last 26 finished weeks, 30 or more, 10th to 90th percentile; each brand's week carries it, and the Forecast screen shows "likely 443 to 590" beside the next and peak weeks (R-ML-20, ML-32).
- Store home in Tamil: the header's chips wrap instead of squeezing the label to one letter a line, and the progress steps no longer run into each other.

Why: a forecast or an unloading time is a range in practice; one number reads as a promise.
Verified: ForecastRangeTest 3, the new intelligence case, forecast and driver Node and browser tests, store 63, driver 44, the full backend suite before the push.

---

## 2026-10-08 - feat: a range around the unloading time (MF-06, first slice)

`dev` · @Oxshadha

- `ServiceRange` (domain): the 10th to 90th percentile of the predictor's own misses before the plan's day, outlet then depot then all, 30 or more each, else none (R-ML-20, ML-31). Stored on the prediction, released onto the delivery, read by the store as "unloading 15 to 24 min".
- Not yet: the forecast's range; the driver screen (needs J.V.); si and ta wording needs the native-speaker review.

Why: one number reads as exact; a range says how much to plan for.
Verified: ServiceRangeTest 3, the new intelligence and execution cases, store Node and browser tests, the full suites before the push.

---

## 2026-10-08 - feat: how fresh the road conditions are, on the Plan screen (MF-07)

`dev` · @Oxshadha

- `ReferenceQuery.roadConditionsUntil`; the predictions read carries it; the late risk adds "road data ends Sun 28 Jun" on a later plan, or "no road data" (R-ML-19, ML-30).

Why: after 2026-06-28 every plan is scored without road conditions, and the screen should say so.
Verified: the scoring case; Node plan views; the dispatcher browser suite; the full backend suite before the push.

---

## 2026-10-08 - docs: Task 2B method and the Task 1 leakage check, for the Datathon write-up

`dev` · @Oxshadha

- `docs/datathon/TASK-2B-POLICY.md` gains appendices: how the allocation is made with no trained model (the four engine stages, with a flow diagram), the rules and the stricter product rules, assumptions and parameters, the measured experiments (70, then 73 served; 16 to 13 vehicles, 724.8 to 610.6 L), and how to reproduce it. Numbers rerun today; the official checker passes.
- `docs/datathon/LEAKAGE-CHECK.md`: method, result and findings of the leakage check, with the AI tool disclosure note the team needs.

---

## 2026-10-08 - feat: reference features, replay against what answered, scheduled retraining (MF-05)

`dev` · @Oxshadha

- Eight reference features known at planning (allowance, allowance before, traffic at the hour, calendar): Datathon dry run AUC 0.954, log loss 0.176, near the Datathon pipeline without road data (0.168).
- The export carries `answeredBy` and `answeredLateProb`; `retrain` replays the candidate against them and will not hand over one that is worse. `training.run` is the cron job on our own server; it never edits the served manifest.
- Not yet: a run on our own deliveries (needs pilot labels).

Why: a model has to beat what is in use on the same stops, not only a rule of thumb.
Verified: ml-server 25 with the pinned versions; IntelligenceIntegrationTest 39; the full backend suite before the push.

---

## 2026-10-08 - feat: retraining on what the plan knew (MF-05, second slice)

`dev` · @Oxshadha

- Scoring keeps each route as the model read it (`ml.scoring_inputs`); `GET /api/ml/training/routes` pairs it with the outcomes of the plan that ran (R-ML-18, ML-29).
- `ml-server/training`: `fetch`, `retrain` (ten planning-time features, last 6 weeks held out, must beat the slack alone), and the service serves a `planning` model through the same feature code. Dry run on the Datathon data: AUC 0.941, log loss 0.227 against 0.285 for the slack alone.
- Not yet: reference features, a scheduled run, replay through the backtest.

Why: a model must learn only what a plan knows when it is published, and be served from the same features it learned on.
Verified: the new and changed intelligence cases on a fresh test database; ml-server 21 with the pinned versions; the full backend suite before the push.

---

## 2026-10-08 - feat: the leakage check, measured on the Datathon data (MF-05, first slice)

`dev` · @Oxshadha

- `ml-server/training/leakage.py` scores the late-risk features on route folds, the last 6 weeks in date order, and the same without road conditions, beside the slack alone, with findings in words (R-ML-17, ML-28). Runs locally on the licensed data; nothing leaves the machine.
- Result: the split is not the problem (0.977 against 0.975 AUC); the road index is (log loss 0.133 to 0.168 without it, 26% worse). The audit's suspicion of the split is corrected in roadmap 05.
- Not yet: retraining on our own export.

Why: the 0.979 had to be measured, not argued about, before any model is trusted.
Verified: test_leakage 5; the full ml-server suite with the pinned versions; the report on the full Datathon training data.

---

## 2026-10-08 - feat: a model is backtested on what it predicted (MF-04, first slice)

`dev` · @Oxshadha

- `Backtest` (domain) judges each model's stored predictions over the last 8 finished weeks against the built-in estimate: better only when Brier and log loss are both lower on 200 or more labels each (R-ML-16, ML-27). `GET /api/ml/models/backtest`, read in the reader's depots only; the admin Models screen shows a line per late-risk model and says so when the backtest cannot be read.
- Not yet: replaying a candidate that never answered on stored plan features, and freezing τ from it.

Why: training figures flatter a model (the leaked 0.979); the figure that counts is how it did on our own stops.
Verified: BacktestTest 3, the new intelligence case on a fresh test database, admin-models Node and browser tests, the full backend suite before the push.

---

## 2026-10-08 - feat: why a stop is at risk, in plain words (MF-08, first slice)

`dev` · @Oxshadha

- `LateReasons` (domain) gives up to three reasons per stop, strongest first: a tight window, far down the trip, the store's late record from the outcomes kept beside predictions (R-ML-15, ML-26). The predictions read returns them with each stop; the Plan screen shows "Why: only 14 min before the window closes · late on 3 of its last 6 visits" under an amber or red stop.
- Not yet: start delay as a reason.

Why: "Late 41%" alone does not tell a dispatcher what to change; a reason does.
Verified: LateReasonsTest 5 and the new intelligence case on a fresh test database; Node plan views (reasonText new); browser dispatcher; the full backend suite before the push.

---

## 2026-10-08 - fix: notifications as in the Figma, on every role and screen; images checked

`dev` · @Oxshadha

- Every inbox (driver feed, store sheet on phone, tablet and desktop, loader inbox on phone and tablet, dispatcher panel) now follows the Figma notification: one card colour (the canvas tint on a sheet, white on the driver's canvas), one accent dot and label (teal by day, green at night), red only for an alarm, grey once read. No kind colours, fills or edges: `noteColor()` and three theme tokens (`--color-go-note-accent`, `-alarm`, `-read`); the old multicolour `TONE_STYLE` is removed.
- Images: the sign-in skylines and the error trucks are WebP and load only for the current theme; photos are WebP from the phone's encoder; icons SVG; install icons and the Fuel Pass QR stay PNG by necessity. Two unused sample PNGs removed; photo thumbnails load lazily and decode off the main thread.

Why: one system should read the same everywhere, and colour should mean something only when it is an alarm.
Verified: browser driver 43, dispatcher 85, store 62, loader 33; Node notifications-inbox (noteColor new), assets-size and sw-cache; typecheck; build; screenshots of all four roles, phone, tablet and desktop.

---

## 2026-10-08 - fix: one notification palette and wording for every role

`dev` · @Oxshadha

- The driver's home feed had its own colours and words (`driverKind`: "Run published", "Vehicle loaded", hard-coded hex); it now uses the shared kinds and tones of `shared/notifications/inbox.ts` like the store and the loader: the kind's colour, a coloured edge, a tint while unread, as theme variables so night mode takes the same palette's night shades. The play button stays.

Why: a driver, a loader and a store manager reading the same event should see the same colour and the same word.
Verified: browser driver 43 (one spec updated to the shared wording); typecheck; build; screenshots in day and night.

---

## 2026-10-08 - feat: the Forecast screen shows how the last weeks were forecast (T2-03)

`dev` · @Oxshadha

- `GET /api/ml/forecast/accuracy?depot=&weeks=` reads the judged weeks of MF-03 (the forecast made before each week, beside what was ordered, per brand) for the reader's depots, 403 outside scope. The Forecast screen gains "How the forecast did": each finished week, forecast against ordered, off by how much (marked over 25%), and the figure over all of them; several depots are summed.
- Not yet: the comparison with the 8-week same-weekday mean, which needs both forecasts kept for the same week.

Why: a dispatcher deciding on hires should see how far last month's forecasts were right before trusting next month's.
- The loader's inbox now uses the shared phone list scroll too (agreed with J.V. Ransika), so the driver, store and loader inboxes feel the same.
Verified: dispatcher 85, loader 33; Node 374 (combineAccuracy new); the accuracy endpoint and its 403 on a fresh test database; screenshots of the Forecast panel and the loader inbox; the full backend suite before the push.

---

## 2026-10-08 - feat: finished stops step back like a dial; one shared list scroll for the phones

`dev` · @Oxshadha

- Driver route: finished stops above the card are dulled with a small tick and when they were done ("Delivered 05:57"); rows above and below shrink and fade the further they are from the card, like a dial (`dialStyle`). Agreed with J.V. Ransika for the driver UI.
- The rubber-band scroll, the edge fades and the rows easing out were copied in two driver screens; they are one component now, `shared/ui/scrollReveal.tsx`, used by the driver's home feed and stops and by the store's notifications on the phone. The loader's inbox can take it with J.V.'s agreement. The iOS-style card stack and the message threads were left as they are.

Why: one place for the phone lists' feel keeps the roles consistent; the driver's eye goes to the stop in hand.
Verified: browser driver 43 and store 62; Node tests (dialStyle new); typecheck; build; screenshots of every changed screen, day and night.

---

## 2026-10-08 - feat: the driver sees how long a stop is expected to take (T1-04)

`dev` · @Oxshadha

- The driver's next stop reads "40 units · about 18 min to unload" from the plan's prediction (R-EXE-30); nothing extra when there is none. The smallest change: the existing cargo line, no new layout. Agreed with J.V. Ransika, as the driver UI changes only with his agreement.

Why: the driver plans the unload and the next leg with it, as the store plans its staff.
Verified: driver browser suite 43 (unload-time.spec.ts new); Node driver-stop-view and wording tests; typecheck; build; screenshots of the driver and store cards.

---

## 2026-10-08 - feat: arrivals the driver did not tap are read from the GPS (MF-02)

`dev` · @Oxshadha

- A stop with no trustworthy arrival tap is labelled from the vehicle's GPS at an outlet with an exact location: the first good fix within 150 m between leaving the previous stop and finishing this one (`label_source = geofence`); a trustworthy tap still wins (R-ML-12, ML-25). New contract read `ExecutionQuery.vehicleFixes`.
- Coverage depends on exact outlet points: the imported reference has none yet, only district centres.

Why: rushed drivers skip the tap, so missing labels lean late; the GPS recovers many of them honestly.
Verified: the full backend suite, run before the push; OutcomeLabelTest 5 and the new execution case on a fresh test database.

---

## 2026-10-08 - chore: a repeatable live check of one delivery day across the roles

`dev` · @Oxshadha

- `scripts/live-check/live_check.py` (README beside it) drives a real day through the command endpoint of a local stack (plan, publish, load, release, positions, arrival, delivery, receipt, messages, the first stop's late estimate) and times how each step reaches the dispatcher, the driver, the stores and the loader: PASS within 3 s, SLOW, FAIL, exit 0 only with no FAIL.
- Local only unless `--allow-remote` (it resets a day, grants scopes and moves the demo clock). It respects the demo clock's 7-day limit and says when the near days are used up.

Why: the browser suites mock the API, so only a run on the real stack proves the roles stay in sync; the first run found the six issues fixed in `7225a47`.
Verified: on the local stack before `7225a47`, 67 PASS, 1 SLOW, 5 FAIL, every non-pass one of the issues that commit fixes; a full run on current code waits for a fresh demo day.

---

## 2026-10-08 - feat: forecast error and prediction drift (MF-03, second slice)

`dev` · @Oxshadha

- `ModelQualityJob` (nightly 03:50): each of the last four finished weeks' demand forecast, as made before the week began, beside what was ordered (`ml.forecast_accuracy`, WAPE gauge per depot); and the PSI of the last 7 days' predicted late probabilities against the 28 before (`ml.prediction_drift_daily`, gauge per depot and model). Alerts `ForecastOff` and `PredictionsDrifted`, tested with promtool (R-ML-13, ML-24).
- Not yet: drift on input features (only predictions are stored).

Why: forecast and drift are known before late labels arrive, so a broken model shows up in days, not weeks.
Verified: the full backend suite, the Node tests and every browser suite, run before the push; ModelMetricsTest 6, the new intelligence case, promtool test rules.

---

## 2026-10-08 - fix: what the live check of one delivery day found

`dev` · @Oxshadha

- A full day driven through the real APIs (plan, publish, load, release, positions, arrival, delivery, receipt, messages) synced every role within about a second. It found these, now fixed:
  - the first stop's store never heard the vehicle was late before it arrived; now it is expected now once its planned time passes (R-EXE-29, EXE-43);
  - a store was told "104 minutes late" for an arrival inside its window; the delay now counts from the window opening when the plan was earlier;
  - the dispatcher's live stream sent nothing for up to 25 s after opening; it now answers at once;
  - a browser closing a live stream was logged as an ERROR; now debug and a counter;
  - delivery, arrival-estimate and loading-started changes named no depot, so every depot's board re-read; the handler now names it (`EventPublisher.publish(actor, event, depot)`);
  - a finished plan draft now signals its depot's board.
- By design, not changed: the loader operator's "since" uses the security clock; an arrival replayed without its device time waits for the 2-minute refresh.

Why: a dispatcher at scale and the store at the first stop rely on these live updates.
Verified: the full backend suite, the Node tests and every browser suite, run before the push; execution 38, loading 22, planning queue 7, outbox 5 on a fresh test database.

---

## 2026-10-08 - feat: predicted minutes in arrival times (T1-04); a fallback that learns; the model hand-off guide

`dev` · @Oxshadha

- T1-04: at release each stop takes the plan's predicted minutes (model, else fallback); the arrival estimate uses them and the store sees "unloading about N min" (R-EXE-30, EXE-42).
- The fallback, used whenever no model answers, now blends outlet history with the allowance and is calibrated nightly on its own labelled stops with a shrunk per-outlet offset; under 200 labels it is the documented rule (R-ML-14, ML-23, `FallbackTuningJob`, `ml.fallback_tuning`).
- `ml-server/README.md` "Handing over a new model": where the team's files go, the `inference.py` contract (the submission columns), the steps to wire and activate, and what happens when it fails.

Why: the product must run well with or without the team's models; the Datathon models connect, but production cannot rely on them.
Verified: the full backend suite, the Node tests and every browser suite, run before the push; FallbackTuningTest, EtaPropagationTest, the new integration cases on a fresh test database.

---

## 2026-10-07 - chore: the first alert rules (B-04, MF-03)

`dev` · @Oxshadha

- `observability/prometheus/rules/waypoint.yml`, loaded by both Prometheus configs and mounted by `compose.yaml`: backend down; outbox lag and dead letters; failing jobs; database pool waits; model calibration outside 0.8 to 1.2, Brier above 0.25, label coverage under 90%, service-minute error; unread driver messages. Tested with promtool (`observability/prometheus/tests/`).
- Not yet: Alertmanager or any paging, table growth, panels, a promtool step in CI.

Why: the audit found no alert rules, and the trust gates mean nothing unless a breach is seen.
Verified: `promtool test rules` and `promtool check config` on both configs; `docker compose config`.

---

## 2026-10-07 - feat: the dispatcher sees whether the driver has read a message

`dev` · @Oxshadha

- The driver's message notification now names its message; `NotificationQuery.driverReads` answers when it was read, and the dispatcher's thread shows "Seen HH:MM" or "Not seen yet" on messages for the driver (R-MSG-14, MSG-25). Older messages show no mark.

Why: with the unseen alert (R-NOT-18) the dispatcher also needs to see, per message, what the driver has and has not read.
Verified: the full backend suite and every browser suite, run before the push; messaging 21 and notification 21 on a fresh test database; Node 371.

---

## 2026-10-07 - feat: each model's day is measured (MF-03, first slice)

`dev` · @Oxshadha

- `ModelMetricsJob` (03:20 nightly, last three days) writes `ml.model_metrics_daily` per depot, day and model: Brier, log loss, calibration slope and intercept (Newton fit, only on 30+ labelled stops with both outcomes), service-minute MAE; gauges per depot and model, NaN when not measured (R-ML-13, ML-22).
- Not yet: forecast WAPE, PSI drift, alert rules.

Why: the trust gates (05) need Brier, calibration and error measured on our own outcomes before any model is on by default.
Verified: the full backend suite on a fresh test database, run before the push (ModelMetricsTest, the new intelligence case).

---

## 2026-10-07 - feat: outcomes kept beside predictions (MF-02, first slice)

`dev` · @Oxshadha

- `OutcomeJob` reads Execution's actuals for today and yesterday every 10 minutes and keeps each stop in `ml.delivery_outcomes` with the newest prediction for its order; the late label comes only from an arrival recorded on time, else none (R-ML-12, ML-21); label coverage per depot is a gauge.
- Not yet: filling missing arrivals from positions with a geofence; weekly volumes; the daily metrics that read this (MF-03).

Why: no model can be measured or trusted without its outcomes, and a skipped tap must not count as "on time".
Verified: the full backend suite on a fresh test database, run before the push (OutcomeLabelTest, the new execution case, boundaries).

---

## 2026-10-07 - feat: Needs you holds at a thousand trips

`dev` · @Oxshadha

- Live "Needs you" folds a trip's cards under its most urgent one ("+N more on this trip"), shows the first twelve trips with "Show N more trips", and counts the items in its heading (`groupByTrip`, `NEEDS_SHOWN`).
- Not yet: a load test of the Live reads at 1,280 trips, and a cap on the attention watch's alerts per dispatcher.

Why: at 80 to 1,280 trips a dispatcher acts on trips, not lines; one long list hides the worst one.
Verified: Node 371; build; browser dispatcher 83 (`needs-scale.spec.ts` new).

---

## 2026-10-07 - feat: a ready reply's use is recorded (FF-08)

`dev` · @Oxshadha

- A post sent from a ready reply carries its id, version, rank in the list and whether it was edited; kept in `messaging.reply_uses` (append-only, RLS with the message) and counted by `waypoint.message.reply_used` (R-MSG-13, MSG-24).
- Not yet: a screen or report of the measures (share from a template, edits, rank taken).

Why: FF-08's KPIs (70% of replies from a template) and the later ranker (AI-J5) need the use logged from day one.
Verified: messaging 20 and policy 19 on a fresh test database, boundaries; Node 370; build; browser dispatcher 82.

---

## 2026-10-07 - feat: arrival times by propagation (T1-03, first slice)

`dev` · @Oxshadha

- The plan's minutes at each stop now travel with `plan.published`/`plan.revised` and `trip.released` (optional field; older events read as before) into `loading.stops` and `execution.delivery_records` (expand-only migrations).
- `EtaPolicy.propagate` works out each stop still to come from the last actual arrival or departure plus the planned drive, never before now; run at each arrival, each recorded delivery and every 2 minutes by `EtaRefreshJob`, telling a store only on a 10-minute move (R-EXE-29, EXE-41). A vehicle held up on the road now shows late before it arrives anywhere.
- Not yet: service minutes from history (T1-04), `ml.eta_predictions` and its endpoint.

Why: the estimate only carried the delay seen at the last arrival, so a long unload or a stop on the road was invisible until the next arrival.
Verified: backend 1,145 on a fresh test database, none skipped (with the unseen-message escalation).

---

## 2026-10-07 - feat: the dispatcher hears of a message the driver has not seen

`dev` · @Oxshadha

- A job in Notification finds drivers' message notifications unread after 10 minutes and tells the depot's dispatchers once (routing v12, `message.unseen`; R-NOT-18, MSG-23). No new command: opening Messages already reads them. The driver's phone chimes again every 5 minutes, three times, while one stays unread (sound only, no layout change).
- Not yet: a "Seen" mark on each message in the dispatcher's thread.

Why: a driver who is driving cannot open Messages; the person who can act is the dispatcher, by phone.
Verified: backend on a fresh test database (see the T1-03 entry above for the combined run); browser driver 42, dispatcher 82; Node 370; build.

---

## 2026-10-07 - feat: a loader leaves the driver a note about the load

`dev` · @Oxshadha

- A loader may now write to the trip's driver, typed or a voice note of up to 2 minutes, from the load sheet's Messages (R-MSG-12, MSG-22; R-MSG-02 amended). The driver hears it like any message, offline too; no store reads it. The loader screen gains only the "Driver" choice in the existing send-to row and a reworded subtitle (si, ta), the smallest change for a requested flow.
- Not yet: playing a note when the driver reaches the stop it is about; that needs a stop on the message.

Why: phoning a driver on the road is unsafe, and the loader knows where the goods sit.
Verified: backend 1,135 on a fresh test database, none skipped; typecheck; build; browser loader 33.

---

## 2026-10-07 - feat: the driver hears messages with no signal

`dev` · @Oxshadha

- The driver's phone keeps the audio of the trip's newest messages while online (voice notes and the server's spoken copy), and Listen, Play messages and the voice note player use the kept copy first (R-MSG-11, MSG-21). At most 60 clips; cleared at sign-out.

Why: drivers lose signal in valleys, and they listen only when stopped, often there.
Verified: Node 369; typecheck; build; browser shell 20, driver 42, loader 32, store 62, dispatcher 82.

---

## 2026-10-07 - feat: messages spoken on our own server; the driving screen as designed

`dev` · @Oxshadha

- A speech service (`speech/`, Piper, GPL-3.0 as its own process) speaks every typed message in English, Sinhala or Tamil; the backend queues it, a job speaks it once per text and language outside any transaction, and the audio follows the message's read rule (R-MSG-10, MSG-20). Off unless `SPEECH_URL` is set; compose profile `speech`. Listen and Play messages play it first, else the phone's voice.
- The driving screen matches the Figma: the place and the brand from the outlet, "Next", the time in the app font, the distance from the phone to the store; no theme button. Values come from the run sheet, Reference and the live position.

Why: iPhones have no Sinhala voice; a server voice works on every phone and offline once fetched.
Verified: backend 1,133 on a fresh test database, none skipped; Node 368; build; browser shell 20, driver 41, loader 32, store 62, dispatcher 82; compose memory limits with and without the speech profile.

---

## 2026-10-07 - feat: every message can be listened to; the driver plays new ones when stopped

`dev` · @Oxshadha

- A typed message has a Listen button that reads it with the phone's voice in its own language (from the script); with no voice for it the screen says so, never an English voice for Sinhala. The driver's old read-aloud always used US English; fixed (R-MSG-09, MSG-19).
- "Play new messages" on the driver's Messages screen, only when not covered for driving: the unheard ones in order, voice notes as recordings, typed ones read aloud.
- Server-made audio for every language (Piper, then a cloud voice after the agreement) is the next step.

Why: field staff listen more easily than they read, and a driver should catch up on what came while driving in one tap.
Verified: backend 1,133 on a fresh test database, none skipped; Node 368; build; browser shell 20, driver 41, loader 32, store 62, dispatcher 82; compose memory limits with and without the speech profile.

---

## 2026-10-07 - feat: Jev in shadow guesses issue types (AI-J1)

`dev` · @Oxshadha

- `DecisionPort` with a Jev adapter on OpenRouter's Decisions API, pinned to `typesafe/jev-1.13`, off unless `JEV_ENABLED=true` (R-ML-11, ML-20).
- Each new issue a person raised in their own words is queued once; a job asks Jev outside any transaction which type the masked words describe, options shuffled per issue, and the guess is kept beside the real type in `ml.jev_shadow`. Nobody sees it; it measures Jev before it may advise.

Why: the plan's gate for Jev needs its record on our own data, not the vendor's claims.
Verified: backend 1,130 on a fresh test database, none skipped; Node 365; build; browser shell 20, driver 40, loader 32, store 62, dispatcher 82.

---

## 2026-10-07 - feat: the language model gateway; plan explanations go through it (AI-0)

`dev` · @Oxshadha

- One backend gateway for language models (R-ML-10): port `LanguageModel`, an OpenAI-compatible adapter for Groq or OpenRouter, off unless `AI_ENABLED=true`. Facts are allow-listed and masked (phones, identity numbers, e-mails, links), the instruction is fixed, the reader must be able to read the plan, calls are capped a day and recorded in `ml.ai_calls`, and an answer with a figure the facts do not hold is dropped.
- The plan and order explanations now go through it; the frontend route that sent browser-built facts straight to Groq is removed, and the key moved from the web container to the backend.
- Preview deploys only when the workflow is run by hand; a push to `dev` runs the checks (J.V. Ransika approves deploys).

Why: the roadmap's AI-0 before any AI use; the old route could send anything a signed-in user posted.
Verified: backend 1,133 on a fresh test database, none skipped; Node 368; build; browser shell 20, driver 41, loader 32, store 62, dispatcher 82; compose memory limits with and without the speech profile.

---

## 2026-10-07 - feat: the driving screen comes on by itself while moving

`dev` · @Oxshadha

- The Figma driving screen (next stop, nothing to tap) now opens when the vehicle moves and closes after 90 seconds standing still, so a traffic light does not flicker it; the slider still closes it and holds it off until the next stop and movement (R-EXE-28, EXE-38).
- While it is on, new notifications chime in Waypoint's own tones: a changed plan always, a message at most every 5 minutes. Nothing is spoken.

Why: the product owner's Figma plan; a driver should not have to remember to open it.
Verified: Node 362; build; driver browser suite 40, none failed. On `dev`; the preview deploys when the workflow is run by hand.

---

## 2026-10-07 - fix: the attention watch sees live trips; the system asks stores by itself (FF-09, FF-08)

`dev` · @Oxshadha

- The attention watch saw nothing in production: a scheduled job has no actor, so its plan and run sheet reads returned no rows while its heartbeat said it had looked (ML-16). It now reads as the system. A stop past its closed window with no delay seen yet was not flagged (ML-17).
- The watch now rings the dispatcher (`attention.raised` for a window at risk, `attention.reminded` capped; routing version 11), and Issues asks the store by itself, once per stop, never a mall, off by `decision.auto_ask` (R-ISS-15).
- Each case shows "What happened" from the record alone; the dispatcher's message box offers ready replies filled by code (FF-08 first version).

Why: the watch is what lets one dispatcher cover many trips; checking it end to end found it blind.
Verified: backend 1,122 on a fresh test database, none skipped; Node 361; build; browser shell 20, driver 38, loader 32, store 62, dispatcher 82. On `dev`; the preview deploys when the workflow is run by hand.

---

## 2026-10-07 - feat: live decisions between the dispatcher and a store (FF-09)

`dev` · @Oxshadha

- From a "Needs you" card the dispatcher asks one store one question instead of phoning: "Can someone receive at about HH:MM?" (never a mall) or, for goods that stayed on the vehicle (loaded last stop first), the next store "can the crew unload them at your dock to reach your goods, and reload them?". Every name and number comes from the run sheet (R-ISS-12).
- The store answers Yes or No at the top of its home, with an optional typed or spoken reason (the voice note goes to the dispatcher on the trip's thread). The answer is due 10 minutes before arrival; with none, the dispatcher is told to phone and records the answer by phone (R-ISS-13, R-ISS-14).
- A Yes tells the driver on the trip's thread and settles the window-at-risk item. Routing version 10. Goods are never held at another store, so no chilled hold time is needed (P-37).

Why: the product owner's requirement that live problems are decided by the people concerned without phone calls; reviewed by research, design and critique ([roadmap](../../roadmap/04-FIELD-FEATURES.md#ff-09-live-decisions)).
Verified: backend 1,117 on a fresh test database, none skipped; Node 358; build; browser shell 20, loader 32, store 62, dispatcher 80 (the offline-plan test passed on rerun); driver 36 of 38: the two voice playback tests fail on this machine on `dev` without these changes too, and passed in CI at `a038e30`.

## 2026-10-07 - feat: the driver's messages are covered while the vehicle moves (FF-04b)

`dev` · @Oxshadha

- R-EXE-28: while a trip is recorded and the vehicle moves (8 km/h or more over the last three readings), the Messages screen is covered and cannot be used; a draft waits underneath. With no location the driver taps "I have stopped" (2 minutes). Report problem is never covered.
- A phone standing still that repeats its last location counts as stopped; one that goes quiet for 30 seconds is unknown.

Why: Motor Traffic Act s.152A bans phone use while driving; [roadmap FF-04](../../roadmap/04-FIELD-FEATURES.md#ff-04-live-trip-channel).
Verified: Node 353; build; browser shell 20, driver 38, loader 32, store 60, dispatcher 78. Backend unchanged.

## 2026-10-07 - feat: the trip's channel for the dispatcher and the stores still waiting (FF-04)

`dev` · @Oxshadha

- A trip's thread has a channel (R-MSG-08). It opens when the driver starts the trip, after loading; a store is on it while any of its orders on the trip is open, then read only, never back. The driver and the loaders are not on it.
- A post naming another store's code or an order number is asked about first (send to the dispatcher only, or to every store); the dispatcher can take a post off the channel with a reason (`message:Redirect`), the message itself never changes.
- Routing version 9: a channel post notifies only the stores still waiting. The driver's gate on writing while moving is FF-04b, next.

Why: Phase 3 of the [roadmap](../../roadmap/04-FIELD-FEATURES.md#ff-04-live-trip-channel); stores phoned the dispatcher for news every store on the trip needed.
Verified: backend 1,108 on a fresh test database, none skipped; Node 350; build; browser shell 20, driver 36, loader 32, store 60, dispatcher 78.

## 2026-10-07 - feat: Operations inquiries and write-off approval (FF-03)

`dev` · @Oxshadha

- New roles `ops_inquiry` (depot operations) and `ops_head` (Head of Distribution) with an Inquiries workspace: the depot's damaged, short and disputed goods with photos, history and write-offs.
- `issue:RecordInquiry` records what an inquiry found and any write-off value; `issue:ApproveWriteOff` lets someone else approve or reject it: never the recorder or the issue's decider, above LKR 25,000 or a Tech line only the Head (R-ISS-11, ISS-17). The dispatcher's policy denies both.
- The limit is a parameter until Finance confirms it (P-DG-1). The loader's Add photo has a camera icon.
- Decided with the product owner: the driver takes no photo and no signature; the store manager's PIN at handover is the proof. Photos are for loaders and store managers, so the blur hint is off the driver's proof screen.

Why: Phase 3 of the [roadmap](../../roadmap/04-FIELD-FEATURES.md); damaged goods had evidence but no recorded decision on the loss.
Verified: backend 1,102 on a fresh test database, none skipped; Node 349; build; browser shell 20, driver 36, loader 32, store 59, dispatcher 76.

---

## 2026-10-07 - feat: a loader's photo of a damaged line reaches the issue (FF-02)

`dev` · @Oxshadha

- The loader's issue sheet takes an optional photo (the shared encoder, with the blur hint); it is kept on the tablet, sent to the order's issue photos, and named by the shortfall, so the issue shows it whichever arrives first (R-ISS-10, ISS-16).
- Loaders may attach issue photos for their depot's orders (new policy version); a driver of the same depot is refused and audited. The shortfall event now carries the photo id it used to drop.

Why: Phase 3 of the [roadmap](../../roadmap/04-FIELD-FEATURES.md); a loader had no way to record that a line left the dock damaged.
Verified: backend 1,095 on a fresh test database, none skipped; Node 346; build; browser shell 17, driver 36, loader 32, store 59, dispatcher 76.

---

## 2026-10-07 - feat: one photo encoder, and a blur and darkness hint (M-02)

`dev` · @Oxshadha

- Every photo goes through `shared/media/photo.ts`: 1600 px, WebP where the browser really makes it (Safari falls back to JPEG), under 3 MB, EXIF and GPS dropped. The store and driver copies now use it.
- The store's report and receive screens and the driver's proof screen say when a photo looks blurry or dark; it can still be kept (EXE-40). Voice notes record at 32 kbps in Opus and 48 in AAC (Chromium fails below that), a quarter to a half of the default size.

Why: Phase 2b of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); two copies of the same encoder, JPEG only, and unreadable evidence found only after it was sent.
Verified: Node 346; typecheck; build; browser shell 17, driver 36, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - feat: the dispatcher's board updates on change, not every 2 s (F-07b)

`dev` · @Oxshadha

- Every committed event signals its topic and depot to open boards over `/api/changes/stream` (no data, scope checked, 403 and audit for another depot); a read re-reads on its topics and polls every 15 s as a safety net, back to 2 s if the stream goes quiet (PLT-18).
- Still to do: the notification and position streams as topics on the same connection.

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); the board read about ten things every 2 s per depot.
Verified: backend 1,093 on a fresh test database, none skipped; Node 342; build; browser shell 17, driver 36, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - fix: an outlet or calendar edit reaches planning without a restart (F-10)

`dev` · @Oxshadha

- In-place reference edits bump `ref.generation`; every process reloads when it moves (within 30 s, at once where the edit was made), after the commit rather than inside it (REF-08).
- Planning's reference cache keys on the generation through `ReferenceQuery.generation()`, never Reference's tables.

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); planning kept an outlet's old window until a restart, and other replicas kept it for good.

---

## 2026-10-07 - ops: retention catches up in one night, with indexes for it (F-12)

`dev` · @Oxshadha

- Retention repeats its batch, each its own transaction, until one comes back short (up to 200 a night) for receipts, outbox, consumed events, job runs, position thinning and position payloads; a larger backlog is counted and continued next night (PLT-17).
- Partial indexes on published outbox events and unredacted position payloads; no partitioning beyond the audit log, recorded in SYSTEM-ARCHITECTURE §9.3.

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); one batch a night never caught up with a day's positions.
Verified: on the combined dev, backend 1,088 on a fresh test database, none skipped; Node 340; build; browser shell 17, driver 36, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - ops: a memory ceiling on every container (F-15)

`dev` · @Oxshadha

- Every service in both compose files has a memory ceiling; the backend's heap is 75% of its ceiling and it exits on running out so Docker restarts it. Production, preview and the log store fit one 16 GB server at about 12.4 GB.
- CI fails a service without a ceiling (`deploy/vps/check-memory-limits.py`); the budget is in [deployment.md](../../deployment.md#operational-limits).

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); without a ceiling the JVM sized itself from the whole host and one service could starve the rest.
Verified: the merged VPS configuration parses with every profile; the check passes, and fails when a ceiling is removed.

---

## 2026-10-07 - fix: a planning worker that lost its claim writes nothing (F-11)

`dev` · @Oxshadha

- The claimed attempt is the claim token; done, requeue and failed apply only while the job runs under it, and the plan write rolls back with a refused done (PLN-42).
- Integration tests now take a whole week each, with a free week either side, so tests no longer share a vehicle's weekly fuel quota and change each other's results by run order.

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); a slow worker could overwrite the result of the worker that took over its job.
Verified: backend 1,094 on a fresh test database, none skipped (one transaction timeout under load, green alone); Node 340; build; browser driver 36, shell 17, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - feat: positions sent every 10 s, merged offline, lightly audited (F-08)

`dev` · @Oxshadha

- The phone still records a fix every 5 s but sends every 10 s; offline, new fixes join the last unsent batch, up to 100 points (EXE-39, R-EXE-23 amended).
- An accepted position batch is audited without a copy of its points; a refused one keeps the full audit (`CommandHandler.auditsState`).

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); positions were most of the day's commands, receipts and audit rows.
Verified: backend 1,094 on a fresh test database, none skipped (one transaction timeout under load, green alone); Node 340; build; browser driver 36, shell 17, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - feat: fewer requests from the phone, and 304 for unchanged reads (F-07a)

`dev` · @Oxshadha

- The driver screen re-reads only the run sheet every 2 s; vehicle and outlet details are kept 10 minutes. About 170 requests a minute on two stops (450 on twelve) down to about 31, held at 60 or fewer by a browser test.
- Polled reads carry an ETag and answer 304 when unchanged; sessions, credentials, evidence, streams and administration stay never-kept; sign-out clears the browser cache (PLT-16).
- Still to do: the dispatcher on server-sent events and one stream per session (F-07b).

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); mobile data and battery in the field.
Verified: backend 1,084 on a fresh test database, none skipped; Node 338; typecheck; build; browser shell 17, driver 36, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - feat: driver redo and tomorrow's map ahead (F-06)

`dev` · @Oxshadha

- A driver's stop write refused because the stop moved on can be redone on the stop's current version from "to review", not only discarded (EXE-38).
- From 16:00 the phone keeps the map of tomorrow's planned stops, read from the published plan, so it draws with no signal.

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); a refused stop write could only be thrown away.
Verified: Node 337; typecheck; build; driver browser 35 (13 existing fixme skips).

---

## 2026-10-07 - feat: a safer service worker (F-05)

`dev` · @Oxshadha

- A new version waits for the person to tap Reload instead of reloading under a half-filled form; only that tab reloads.
- Opening the app shows the kept shell after 6 s of a hanging network; one failed file no longer blocks the install.
- Background Sync waits for the open pages' drain results, so the browser retries while work is left (EXE-37).
- Per-role precache deferred: the roles share one bundle today ([02](../../roadmap/02-FOUNDATION-FIXES.md)).

Why: Phase 2 of the roadmap; the worker reloaded without asking and told the browser a sync was done before it was.
Verified: Node 335; typecheck; build; browser shell 17, driver 34, loader 31, store 59, dispatcher 76.

---

## 2026-10-07 - fix: no "server" on screen; secrets rotation runbook

`dev` · @Oxshadha

- Every message that said "the server" now says Waypoint or plain words ("Can't reach Waypoint", "Not set up yet"), with the Sinhala and Tamil strings; the wording test now fails on "server" on screen ([GLOSSARY](../../architecture/GLOSSARY.md)).
- The rotation runbook for every password and secret is in [deployment.md](../../deployment.md#rotating-passwords-and-secrets). Not rotated yet: it needs the VPS and the repository settings.

Why: field users read "server" as jargon; the old README exposed account passwords, so rotation is the only fix.
Verified: Node 330; typecheck; build; browser driver 34, loader 31, store 59, dispatcher 76, shell 17.

---

## 2026-10-07 - feat: the phone keeps less, and nothing after sign-out (F-04)

`dev` · @Oxshadha

- Saved reads: an unchanged answer is written again at most once a minute instead of on every poll; older than a week or beyond 400 per account, deleted once per sign-in.
- Signing out deletes every saved read, the Fuel Pass included; queued work stays for the same account.
- A queuing role warns when the phone is 80% full, and a save refused for space says so in plain words (EXE-36).

Why: Phase 2 of the [roadmap](../../roadmap/02-FOUNDATION-FIXES.md); a 2-second poll wrote to the phone every time and nothing was ever deleted.
Verified: Node 330; typecheck; build; browser driver 34 (13 existing fixme skips), loader 31, store 59, dispatcher 76, shell 17.

---

## 2026-10-07 - feat: the vehicle's Fuel Pass QR on the driver's phone (FF-06b)

`dev` · @Oxshadha

- The administrator uploads, replaces or withdraws each vehicle's official National Fuel Pass QR from the vehicle screen; the driver of that vehicle gets it with the run and can show it at a pump with no signal (R-EXE-27, EXE-34, EXE-35).
- The old Fuel QR drew a code from the vehicle id that no pump would accept; with no pass added, the popup now says to ask the depot.
- Signing out deletes the Fuel Pass from the phone, so a shared phone never carries the last driver's pass; the unused QR drawing package is removed.
- The fuel log (FF-06a) is dropped: the booklet's fuel rule is a planning constraint, and the government enforces the real quota.

Why: drivers carry no paper copy; the QR is a bearer credential, so only the assigned driver reads it, every read is audited, and the phone keeps it one day.
Verified: backend 1,080 on a fresh test database, none skipped; Node 326; typecheck; build; driver browser 33, dispatcher 76.

---

## 2026-10-06 - fix: a lost vehicle no longer discards the dispatcher's open revision

`dev` · @Oxshadha

- A vehicle lost while the dispatcher is revising that published plan is replanned inside their draft, keeping their edits; their stale screen is told on the next edit (PLN-41). Before, the draft was cancelled unseen.
- The automatic reefer swap after a lost vehicle was reviewed and deferred, with the conditions to build it ([02](../../roadmap/02-FOUNDATION-FIXES.md)).

Why: rule 8 and rule 9; a person's work is never discarded without being told.
Verified: backend 1,072 on a fresh test database, none skipped.

---

## 2026-10-06 - feat: tight windows shown before a plan is published (T1-01)

`dev` · @Oxshadha

- A draft plan shows each stop's minutes between planned arrival and window close: amber under 30, red under 15, on the board, the open trip and the Publish step, which lists the tight stops (chilled first) and never blocks.
- Read from the plan's own times, no model call; once the published plan is scored the model's late risk takes its place.
- The Publish step no longer says "server".

Why: the dispatcher needs to see lateness risk while the plan can still change; the model only scores a published plan ([02](../../roadmap/02-FOUNDATION-FIXES.md)).
Verified: Node 321, typecheck, build, dispatcher browser suite 76.

---

## 2026-10-06 - feat: Phase 1.5 (F-09a, MF-01, T2-01), store cutoff wording, Task 2B policy

`dev` · @Oxshadha

- **F-09a:** jobs that wait on the model service, the warehouse or web push run on their own thread, so they cannot delay the 16:00 cutoff.
- **MF-01:** an admin Models screen to put a model in use or retire it, and `ML_ALLOW_DATATHON=false` in production so competition-trained models never answer there (R-ML-09).
- **T2-01:** the fallback weekly forecast divides by the weeks of orders a depot really has, not eight (ML-15). Capacity counts only the vehicles free each day, so workshop bookings show. A Decisions card records hire, workshop, advise or accept decisions with a reason (`ml:RecordCapacityDecision`).
- **Store:** a moved order says why in plain words: after the 16:00 cutoff, or no deliveries that day.
- **Task 2B:** the written allocation policy for S1, checked against the engine output ([TASK-2B-POLICY](../../datathon/TASK-2B-POLICY.md)).

Why: Phase 1.5 of the [roadmap](../../roadmap/README.md); the cutoff wording named the wrong reason; Task 2B's written policy was missing.
Verified:
- Backend 1,069 on a fresh test database.
- Node 318.
- Dispatcher 75 and store 59 browser suites.
- The Models screen seen in a browser.

Open:
- T1-01 and FF-06a.
- "Based on N weeks of orders" on the Forecast.
- The plain-words pass on the other places that say "server".
- Some integration tests assume an empty database.

---

## 2026-10-06 - fix(security): proxy sample session; positions lane (F-03); WebP sign-in (M-03)

`dev` · @Oxshadha

**Security.** With the backend unreachable, `frontend/app/api/[...path]/route.ts` answered every visitor with the development sample session (every role, every depot), in production too. Sample data is now allowed only outside a production build, as the loader's sample data already was. A production build with no backend now answers `/api/session` with 503.
**F-03.** The page and the service worker send delivery, receipt and issue writes before positions, so a GPS backlog never holds up a business write.
**M-03.** The sign-in skylines are WebP: 1.4 MB down to about 70 KB each. A test guards `public/assets`.

Why: found while checking the sign-in screen; F-03 and M-03 are Phase 1.5 items.
Verified:
- Node 310.
- Shell, driver, loader and store browser suites.
- `/api/session` on a production build with no backend.

Open:
- Thinning the position backlog (F-08).
- Whether any deployment ever ran a non-production build, which would still show the sample session when the backend is down.

---

## 2026-10-06 - fix: Phase 1 baseline green, and offline retry (F-01, F-02)

`dev` · @Oxshadha

Baseline (Phase 1): backend 1,063 tests with `TEST_DATABASE_URL` set as in CI, and the Task 2B validator passing after restoring its data symlink, which the handover copy had flattened. Fixed every failure found ([01-FOUNDATION-FLOWS](../../roadmap/01-FOUNDATION-FLOWS.md#baseline-recorded-2026-10-06-local-dev-macos-docker)), including three product bugs:

- **Dispatcher Live** showed tomorrow's run.
- **Dispatcher search** missed the orders waiting for the plan.
- **Driver app** had lost its Messages button, so a driver could not open the trip's thread. Changed under the role UI rule, agreed with @jv-ransika.

F-01: a failed send waits a jittered backoff (capped at 20 s) or the server's Retry-After, and tries again on its own; the service worker respects the wait.
F-02: reads time out after 8 s and writes after 15 s, as an outage, so kept data shows quickly on a dead line.

Why: the safety net must be green before features, and EXE-04 claimed a backoff the client did not use.
Verified:
- Node 304.
- Browser suites: shell 17, dispatcher 74, driver 30 (13 skipped by design), loader 31, store 59.
- Backend handover tests 15 of 15.
- Typecheck and build.

Open:
- Why a command holds its connection long enough to starve a pool of 5 (F-09).
- Nine integration test classes need `TEST_DATABASE_URL` and ignore the Docker fallback.

---

## 2026-10-06 - chore: roadmap Phase 0, local part

`dev` · @Oxshadha

- **H-00:** merged the 19 unmerged commits of `claude/role-ui-intelligence` into `main` with no conflicts, and cut `dev`.
- **H-01:** restored the README from `961d89a` without the deployed accounts table, which held the production admin password.
- **H-02:** gitleaks scan of all history; the one finding, an RFC 8291 test vector, is in `.gitleaksignore`. Added a Secret scan CI job.
- **H-04:** STATUS refreshed.
- **H-05:** `deploy/vps/backup.sh` (daily dump, read back, rclone copy off the server) and its runbook in [deployment.md](../../deployment.md).

Why: Phase 0 of the [roadmap](../../roadmap/README.md).
Verified: gitleaks clean; workflow YAML parses; `bash -n` on the script. Nothing pushed.
Open:
- H-03 (GitHub Environments) needs admin on the repository.
- Rotating the admin password and setting a fixed `PROOF_URL_SECRET` need the server.
- The backup cron and the restore drill run on the server.

---

## 2026-10-06 - docs: handover audit and phased roadmap

`main` · @Oxshadha

Added [docs/roadmap/](../../roadmap/README.md):
- the audit at handover;
- the normal flows with technical and business baselines;
- the foundation fixes;
- the infrastructure decisions (no broker, no Redis, no partitioning; R2 behind per-module ports);
- the field features (shared damaged-goods report, ops inquiry, live trip channel, reply templates, transcription, Fuel Pass);
- the Datathon models in real decisions;
- the Jev and LLM features;
- the business policies;
- the business case.

STATUS points to it; the auditor console row and the EXE-04 claim are corrected there.
Why: the system was handed over and an intelligence layer was requested; the audit found foundation gaps that must be fixed first, and an unmerged branch.
Verified: links resolve, no em or en dashes, every tracker ID is defined. No code changed.
Open: rule and ADR proposals move into the canonical documents with the pull requests that build them; Phase 0 (H-00 to H-05) is next.

---

## 2026-10-04 - fix: driver arrival and problem reports are a slide, not a tap

`fix/field-ui-polish` · @Oxshadha

On the driver phone, Report problem on the trip card is a button. I've arrived sends only when the control is slid across. A problem row does the same, and sends that row. From the trip card the only row is Vehicle problem; a stop still has the delivery rows. A voice note starts on a tap and sends itself at 15 seconds. Report sent stays until Okay, or closes after a few seconds. A voice message's play button fills as it plays, and the words sit where the waveform was.
Why: a tap in the cab was recording arrivals and reports by mistake, and the home card's report looked like a link.
Verified: frontend typecheck and production build. Playwright driver handover, which slides I've arrived and then delivers.
Open: nothing.

## 2026-10-04 - fix: field phone layout for voices, sign-out, PIN and the map

`fix/field-ui-polish` · @Oxshadha

On the driver phone, scrolling the voice notifications folds the driver card up so the voices get the screen. Sign out is the last row in Settings, in red, opened from the profile picture. A wrong PIN shakes and the digits clear, on the loader phone and keypad and on the driver's store-manager PIN. The store manager's floating bar shrinks while the page is scrolled. The enlarged trip map has Back on the map itself.
Why: the voice list was stuck under a card that never moved, sign-out sat in the header, a wrong PIN left its dots filled, the store bar stayed full size over the page, and the enlarged map had no way back.
Verified: frontend typecheck and production build. Playwright: driver handover PIN clears, map Back, voice list folds the driver card; loader offline PIN clears; store phone bar shrinks on scroll.
Open: nothing.

## 2026-10-04 - feat(ordering): the shared-trip hint offers a day only when the order fits the trip (issue #199)

`feat/199-trip-room` · @Oxshadha

The Tech hint from #211 offered any nearby day with other bookings, even a trip already full. Now:
- **Planning** answers `PlanQuery.joinsTrip`. `TripRoom` (pure) packs the booked measured orders of the brand and district onto the day's available vehicles. It asks only the registry's own load rules (temperature, van access, weight and volume with the epsilon), so capacity keeps one definition (rule 5).
- **Ordering** passes the store's usual order (the median of its latest twenty, A-45) and drops days with no room (R-ORD-14).
- **Store:** the card says "room on the vehicle", estimated.
- **Degrading:** with no history, or Planning unable to answer, it falls back to bookings alone and claims nothing (ORD-22, ORD-23).
- **Style within its weekly run:** rejected, because R-ORD-11 holds it to its date.

Also fixed while proving the edge cases:
- A room check that failed partway left earlier days filtered while the card said no check ran. `RideAlong.suggestChecked` now drops the check for every day (ORD-23).
- #224's stop showed on the store's reads but not through the `OrderQuery.order` contract or the dispatcher's day board. All three now attach it (ORD-21).
Why: a shared-trip hint that points at a full trip moves the order for nothing.
Verified: `mvn verify` 1005 on a real test database, all pass but the local-only `SET ROLE k.e.oshada` identity test, and three that reuse fixed emails and codes, which pass on a fresh database; new `TripRoomTest` 6, `TripRoomIntegrationTest` 3, `RideAlongTest` 11; `npm test` 225, typecheck, build; Playwright store 55.

## 2026-10-04 - feat: a store hears when a day it booked turns busy (issue #224, slice 3)

`feat/224-outlook-warning` · @Oxshadha

`OutlookWatchJob` (Intelligence) runs hourly 06:00-15:00. It watches orders booked from tomorrow to 14 days ahead and not yet planned (`OrderQuery.openOrders`), using the same `DateOutlookQuery.assess` the store's date strip uses. When a day turns busy or at risk, and is worse than any warning already given for that order and day (`OutlookChangePolicy`, `ml.order_outlooks`), it records the warning and publishes `order.outlook_changed` in one transaction (R-ML-08, P-36). Notification routing version 6 tells the outlet "Fri 9 Oct is at risk" with the reason (R-NOT-16). It also fixes slice 1's store title, which showed an ISO date. The order sheet of an unplanned later order shows the same warning.
Why: the promise made at booking can break before the plan (workshop vehicles, more orders than forecast), and the store should hear it early, once.
Verified: `mvn verify` 991 on a real test database, 990 pass (the same local-only `SET ROLE k.e.oshada` identity failure as before); `npm test`, typecheck, build; Playwright store 52.
Open: the other routing templates still show ISO dates (`{serviceDate}`), as before this work.

## 2026-10-04 - feat: the delivery promise for a day a store orders ahead (issue #224)

`feat/224-delivery-promise` · @Oxshadha

A store ordering "next Friday" could pick only three days, never heard when the plan put its order on a vehicle, and had no idea whether a day was likely to hold. Built inside the existing modules:
- **Ordering:** records each order's stop and planned arrival from `plan.published` and `plan.revised` (`ordering.order_stops`; an older plan version never overwrites a newer one), shows them on `OrderView`, and offers `OrderQuery.bookedVolumes`.
- **Notification:** routing version 5 tells each outlet its stop (R-NOT-15).
- **Intelligence:** `DateOutlookPolicy` and `GET /api/ml/outlook` rate each day on track, busy, at risk, too early or closed. The rating comes from booked volume or the forecast share, set against the vehicles available that day; it is aggregate only, behind `ml:ReadOutlook` with an audited 403 for another outlet (R-ML-07, A-44, P-35).
- **Store:** a 28-day strip with the outlook, a warning and a suggested day that prefers #211's shared trip, and "Planned for {day} · stop n · planned arrival hh:mm" on the order.

Why: a day chosen ahead is a promise; the store should see how likely it is when booking, and its stop when the plan is made. Detail in [the walkthrough](../../issues/224-delivery-promise/WALKTHROUGH.md).

Verified:
- `mvn verify` 976 on a real test database: 975 pass. The one failure is `IdentityHardeningIntegrationTest.migrateLetsThePoolLogInAsWaypointAppWithItsOwnPassword`, which runs `SET ROLE` unquoted with the local owner name `k.e.oshada` (dots); it is untouched here and passes in CI.
- `npm test` 221, typecheck, build, Playwright store 50.

Open: the early warning when a booked day turns busy, and supply probability on the outlook basis. The 28-day horizon is the strip's, not a server refusal.

---

## 2026-10-04 - feat(planning): planning v2 (issue #219), a measured engine choice, the cost stage, GPS stops and a generation queue

`feat/219-planning-v2` · @Oxshadha

Every approach was measured on the same 62 days (S1, the 8 historic depot-days with 0, 25 and 40% of the fleet out, and seeded synthetic days up to 300 orders) under the production `ConstraintRegistry`: rules (E0), rules plus the exact reefer pass (E1), an exact MIP solved with CP-SAT, SCIP and HiGHS (E2), ALNS (E3), OR-Tools routing (E4) and the production chain (E5). Test scope only (`planning/bench`, OR-Tools as a test dependency), charts in `docs/issues/219-planning-v2/benchmark/planning-benchmark.ipynb`. E5 is best by rank on 58 of 62 days and matches the MIP's proven optimum on all 34 days it proved; routing is best on 13; HiGHS crashes with a hint and ignores its time limit. Production: `CostReplan`, an ALNS cost stage after the rules that never trades the served set (R-PLN-38), runs only on a day worth it and has its own budget (R-PLN-39), keeps the rules plan beside the draft as a `rules` snapshot for Compare (S1: 16 to 13 vehicles, 725 to 611 L, same 73 orders); exact outlet GPS orders stops, district points change nothing (R-PLN-40); `plan:Generate` queues a job and a worker runs the engine outside any transaction, one active job per day, SKIP LOCKED with a lease, rerun when demand moved (R-PLN-41); reference data cached by immutable version. The dispatcher follows the job, sees the cost note and opens Compare on the rules plan. The benchmark found two hangs in the reefer pass on large days (unbounded day listing, quadratic covered-day pruning; PLN-39), now bounded.
Why: planning is what the judges weigh most; the engine is now chosen by evidence, cheaper on every hard day, safe under concurrent use and bounded on large days.
Verified: `mvn verify` 932 with integration tests on a real test database before the last two fixes (planning suites rerun after them); Node 167; Playwright dispatcher plan specs 15; `check_allocation.py` passes on S1.
Open: outlet GPS needs real exact coordinates in `geo_points.csv`; the trip disruption channel is the next piece.

---

## 2026-10-03 - feat(frontend): the driver's Figma screens run on the real run sheet (issue #117)

`feat/117-driver-gaps` · @Oxshadha

The driver UI rebuild (#174) drew sample stops and never sent a command: no arrival, delivery, proof or problem reached the server, and all 13 driver browser tests failed. The Figma screens are now fed by `useDriver` through `data/stopView.ts` (run sheet to the screens' stop shape, trip status, run summary, sync label; pure and tested). Home shows the vehicle, the stop count and vehicle status, and keeps #184's inbox and driving-mode badge; a revised plan opens "Run sheet changed". The delivery form, proof, problem sheet, stop detail and refused files keep their working forms inside the frame. New: the store manager's handover PIN after saving a delivery (`receipt:VerifyHandover`, online only, Skip always there, wrong tries counted down). Restored: the shell's sync badge with its review panel, and MCP. Removed: the sign-in, QR and vehicle ID screens, fake map, fake fuel QR and every "Synced 05:31". The waiting screen no longer says the store confirms first.
Found running it against a real backend, all fixed: the phone worked from the first of the driver's 28 vehicles, not the one with a released trip (`todaysSheet`); a write tapped while the one before was in flight, or sent just before the signal went, named a stale version (EXE-29: writes in turn, and the server's answered version is kept, in the saved run too); writes recorded in the same millisecond went to the server in random order, so a proof could land before its record (EXE-30: a strictly increasing order per device, in the page's queue and the service worker's); Run complete called refused writes "waiting for the connection".
Why: the judge flow could not deliver anything on the driver phone, and #117 asked for the PIN and the inbox.
Verified: `npm test` 144, typecheck, build; Playwright driver 17 (13 rewritten, 4 new), loader 20, store 32; dispatcher 20 of 25, the five Live and Forecast failures arriving with today's `dev` (no dispatcher file changed here). Against a real backend on a fresh database seeded as `init` does: plan, publish, load and release through the API, then the browser trip: three stops, the second with the store manager's real PIN (a wrong one counted, the right one confirmed), the third with no signal, a reload and the sync; all three DELIVERED with proof on the server, applied once and in order.
Open: the driver phone asks for today's run, but the seed plans the first day still open for ordering (two days out), so the README's driver step shows no trip on the day; the language toggle in the Figma header changes nothing yet.

---

## 2026-10-03 - fix(frontend): every screen uses the glossary's words (issue #128)

`feat/128-apply-glossary` · @Oxshadha

The guardrail's baseline is empty: no retired word, raw code or 12-hour clock is left in screen text. Store manager: units, never cases or packages; product line where it means one product; Expected, never ETA; 16:00, never 4:00 PM; Regular delivery, never Regular run. Dispatcher and loader: "store" that meant the person reads store manager; "Refrigerated vehicles planned again". Loader: "Search vehicle, trip or loader" and "two units crushed", with Sinhala and Tamil changed to match (drafts for a native speaker). Driver: "No one at the outlet", Expected for ETA, run sheet for route, units in the demo cargo. Error screens: "Don't worry: your work is saved", with no em dash. Admin: plans, never route plans; "On a trip". Backend: a deferral reads "is not refrigerated"; notification text already followed the glossary, so no routing version. The guardrail is sharper: it reads JSX text on its own line and between tags, and ignores code, class lists and interpolated names.
Why: issue #126's glossary, applied; the store screens alone called one quantity three names.
Verified: `npm test` 125, typecheck, build; Playwright dispatcher 24, store 32, loader 16 of 18 (the same two failures as untouched `dev`); the driver suite fails all 13 on untouched `dev` too, since the driver UI rebuild (#174) removed the screens it drives; `ConstraintsTest`, `PeakDayAllocationTest`, `ScarceFleetReplanTest`, `PlanningRunTest`.
Open: the two proposed terms on #126; the landing page's role card keeps "store" for the store manager's own store.

---

## 2026-10-03 - feat(frontend): one wording layer and a guardrail against drift (issue #127)

`feat/127-wording-layer` · @Oxshadha

`frontend/src/shared/wording/` holds every time and date format (24-hour depot time), the glossary's count words and labels for raw codes. The eleven local formatters across the four roles and the shared UI are gone: role data modules re-export, screens import it, so `npm test` still loads data modules without path aliases. Two visible changes, both the glossary's: the dispatcher's date stamp and every "Synced" time read 24-hour. The store's notification times were in the phone's own zone and are now depot time. `tests/wording.test.ts` (from #134) fails on new retired words, raw codes, 12-hour clocks or dashes in screen text, and counts today's 53 leftovers in 27 files in `tests/wording-baseline.json`, which #128 works down.
Why: the same time was formatted eleven ways, two of them 12-hour and one in the device's zone, and nothing stopped a new screen adding a twelfth.
Verified: `npm test` 118, typecheck, build; Playwright dispatcher 23 and store 29 pass; loader 16 of 18 and driver 8 of 10, the four failures the same on untouched `dev` (loader inbox read state, driver unit stepper and arrival message).
Open: #128 applies the glossary across the screens and lowers the baseline to zero.
## 2026-10-03 - docs: README checked against a running system (issue #115)

`docs/115-readme-walkthrough` · @Oxshadha

Ran the judge path on a fresh PostgreSQL seeded exactly as the `init` service does (`migrate import-reference demo-accounts seed-delivery-day`), then every walkthrough step through the API as each role: 85 orders, close refused before the cutoff, generate and publish, loader PIN and reverse stop order, shortfall to Issues, release, the driver's offline batch applied once and ignored on resend, Live, and the store's dispute reaching Issues. All eleven steps hold. Fixed what did not: `ADMIN_PASSWORD` is read by nothing (`SEED_PASSWORD` sets every account, admin included, and the `.env.example` placeholder becomes the password if left), the Datathon models are served, Forecast and the role inboxes are built, and the walkthrough now says a partial delivery asks what happened to the missing goods.
Why: the README is what judges follow; three of its setup lines would have stopped them at sign-in.
Verified: the API run above against a local backend; no code changed.
Open: the browser pass of the same steps belongs with the demo recording (#122).

---

## 2026-10-03 - feat(mcp): rate limit per credential and per OAuth client (issue #139)

`feat/139-mcp-rate-limit` · @Oxshadha

MCP requests are counted per credential and per OAuth client in `iam.mcp_rate_windows` (migration `20261003T1600`), one atomic upsert per bucket per request, so every replica shares the count. Over the limit is `429` with `Retry-After` to the next minute; the adapter hands the assistant `retryAfterSeconds`. The first refusal per window is audited, a flood only counted; disconnecting is never limited; the retention job prunes old windows. Rule R-IAM-33, parameter P-30 (120 a minute per credential, 1200 per client), case SEC-39.
Why: the MCP specification requires servers to rate limit tool invocations, and nothing did.
Verified: `McpRateLimitIntegrationTest` (3, clock pinned mid-minute), `RateWindowTest`, all MCP integration tests and `ModuleBoundaryTest` on PostgreSQL, none skipped; `mcp` `npm test` 18.
Open: stacked on #140 (same filter); merge #140 first. A fixed window allows up to twice the limit across a minute boundary, accepted for one shared upsert per request.

---

## 2026-10-03 - feat(mcp): per-tool metrics, outcome audit and call logs (issue #140)

`feat/140-mcp-observability` · @Oxshadha

Every MCP request is counted and timed per tool and outcome (`waypoint_mcp_calls_total`, `waypoint_mcp_duration_seconds`), the tool named by the backend from the path (`McpReadPolicy.toolOf`) so a client cannot invent a metric label. An authorized read also writes an outcome audit row beside its authorization row under one correlation id; a failed outcome write never fails the read and is counted. The adapter logs one JSON line per tool call, without arguments, results or credentials. Runbook in [mcp/README.md](../../mcp/README.md#observing-it), case SEC-38.
Why: the MCP spec asks servers to log tool usage, and a read that was allowed but ended in 404 or an error was invisible.
Verified: `McpReadPolicyTest`, `McpConnectionIntegrationTest` (10) and `McpOAuthIntegrationTest` on PostgreSQL, none skipped; `mcp` `npm test` 17.
Open: per-client metric tags are deliberately absent (dynamic client registration makes them unbounded); per-client views come with #141.

---

## 2026-10-03 - docs: glossary, one term per concept (issue #126)

`docs/126-glossary` · @Oxshadha

`docs/architecture/GLOSSARY.md` names one term per concept with its meaning, its source field and the words it replaces: units (never cases or packages), product line, trip, stop, delivery, run sheet, manifest, outlet, refrigerated, deferred, expected arrival, 24-hour depot time, and labels for every raw code. AGENTS.md and the README link it.
Why: the same thing had up to four names across roles (store screens said cases and packages for `itemCount`), and the data already says which word is right.
Verified: every cited field checked against `frontend/src/shared/domain/`; word counts from visible text only.
Open: the team confirms the proposed terms on #126 (outlet or store on the store manager's screens); #127 builds the shared labels and formatters, #128 to #132 apply them.

---

## 2026-10-03 - docs: architecture diagrams and a generated data model (issue #121)

`docs/121-architecture-data-model` · @Oxshadha

`docs/architecture.md` draws what is built: actors and external systems, the containers `docker compose up` runs, the twelve modules and their events, the layers of a module, and one command end to end. `docs/data-model.md` lists every table, key and foreign key by schema and is generated from a database migrated with `migrations/` by `scripts/data-model.py`, so it cannot drift from the schema. Both are linked from the README. `docs/ai-disclosure.md` now lists the tools with their evidence and says the trained Datathon models are served, not trained.
Why: the booklet asks for an architecture diagram and a data model in `docs/`; the diagrams in SYSTEM-ARCHITECTURE.md show the target (object storage, CDN), not what runs.
Verified: all 61 migrations on `dev` applied to a fresh PostgreSQL 16; 95 tables, 106 foreign keys; all 19 Mermaid blocks parsed by Mermaid 11.
Open: every team member adds any other AI tool they used to the disclosure before submission.

---

## 2026-10-03 - feat(planning): re-plan the reefers after the first pass (issue #92)

`feat/engine-improvement` · @Oxshadha

A second engine pass, `ScarceFleetReplan`, clears the reefer days, lists every feasible day of one or two chilled trips through the registry and chooses one per reefer by rank, then places the rest by the first pass's insertion (now shared in `CheapestInsertion`). It is kept only when better by rank (R-PLN-32), is deterministic on a fixed node budget, and its summary is stored on the run and shown on the Plan screen. S1: 70 to 73 served, chilled 78.7 to 109.2 m³, no chilled order closing before 08:00 left. Details in the [walkthrough](../issues/092-engine-improvement/WALKTHROUGH.md).
Why: the greedy spent the reefers' 270 Fresh minutes on small far loads; one-order moves could not fix it.
Verified: `mvn verify` against a dedicated database, 731 of 732 pass; the one failure is `IdentityHardeningIntegrationTest` running `SET ROLE` with a local owner name containing dots, unrelated. `check_allocation.py` passes; `npm test` 55, typecheck, build, dispatcher Playwright 11.
Open: serving more chilled by count would need a policy change to R-PLN-21. Vans need no pass of their own (R-PLN-03 already routes van-only outlets, nothing to gain on S1); a day with more than 62 chilled candidates is searched on the top 62 and says so on screen.

---

## 2026-10-02 - feat(dispatcher): issues inbox, overview tiles, skipped outlets, weekly fuel (issue #19)

`feat/dispatcher-rest` · @Oxshadha

The dispatcher can now see, take, resolve, redeliver, record a replacement for, close and cancel issues (#13's commands, `rowVersion` on each). Overview reads today's orders, road, on-time rate and open issues; it lists skipped outlets with counts, and the vehicle drawer shows planned fuel against the weekly quota. Frontend only. Details in the [walkthrough](../issues/019-dispatcher-ui/WALKTHROUGH.md#second-slice-issues-overview-skipped-outlets-fuel-2026-10-02).
Why: the Issues backend was done, but no dispatcher could act on an issue on screen.
Verified: `npm test` 45 pass, typecheck and build green, dispatcher Playwright suite 11 pass (mocked API).
Open: assigning to someone else needs a staff read; interchange approval waits on #10, Forecast on #16, the sync conflict queue is not started.

---

## 2026-10-02 - fix(issues): drivers raise by the vehicle they drive today; assignees must work the depot (issue #13)

`feat/receipt-issues` · @Oxshadha

`IssueScope` now admits a driver for the depot of the vehicle they drive today, through Identity's `driverVehicleOn` (#5, R-IAM-13), so drivers need no depot grant and A-29 is withdrawn. `issue:Assign` requires the assignee to be scoped to the issue's depot through `scopeOf` (R-ISS-08). Both read only Identity's and Reference data's contracts.
Why: #5 shipped the vehicle-and-date scope this was waiting for.
Verified: on a fresh database, `TEST_DATABASE_URL=... mvn test`, 439 tests, 0 failures; frontend `npm run typecheck` and `npm test` (12) pass.
Open: nothing for #13 beyond the walkthrough's gaps owned by other modules.

---

## 2026-10-02 - test: cap the connection pool per cached test context

`feat/receipt-issues` · @Oxshadha

`src/test/resources/config/application.properties` sets the Hikari pool to 5 for tests only; production stays at 8. Spring caches one context per distinct test configuration, and each keeps its whole pool open. After #5 and this branch there are thirteen, which at 8 each need 104 connections, more than PostgreSQL's default 100, so the last test classes failed with "too many clients".
Why: merging `dev` after #5 turned 29 tests red on connection errors, not logic. CI's PostgreSQL has the same limit.
Verified: on a fresh database, `TEST_DATABASE_URL=... mvn test`, 437 tests, 0 failures.
Open: nothing.

---

## 2026-10-02 - fix(issues): redeliver only when nothing arrived; a redelivery keeps its priority (issue #13)

`feat/receipt-issues` · @Oxshadha

- `issue:ScheduleRedelivery` now answers only a failed delivery or a stock discrepancy. A disputed, damaged or late delivery is refused (ISS-08): a redelivery is the whole order and would ship the goods twice. A-24 is now decided.
- `Order.redeliveryOf` carries the original's skips plus one, so Planning serves the outlet first next time (ORD-15).
- Settled conflict C-7: a late arrival at a mall is a failed delivery, `mall_window_closed` (EXE-20). R-EXE-05 still holds elsewhere. #12 implements the detection.

Why: review of A-24 and of late deliveries to malls.
Verified: on a fresh database, `TEST_DATABASE_URL=... mvn test`, 391 tests, 0 failures.
Open: comment on #12 for EXE-20; partial redelivery as a follow-up for Ordering and Warehouse.

---

## 2026-10-02 - fix(issues): raise rights build on each role's current policy (issue #13)

`feat/receipt-issues` · @Oxshadha

Migration `20261002T0400` no longer restates the Loader, Driver and StoreManager policies. It builds each new version from the role's current default: it takes `issue:Raise` out of the statement that grants it and appends one typed raise statement, as `20261001T1501` does. Restating version 2 had silently dropped the store manager's `order:AcceptShortfall`, which #7 granted in between. The Receipt and Issues tests now switch off `ScheduledJobRunner` (from #7), so the real auto-close cannot fire mid-test; the jobs now run in the app on their crons.
Why: merging `dev` after #7 turned three Ordering tests red (403 on `order:AcceptShortfall`).
Verified: on a fresh database, `TEST_DATABASE_URL=... mvn test`, 390 tests, 0 failures; the store manager's default policy keeps `order:AcceptShortfall` and gains `RaiseStoreIssues`. Frontend `npm run typecheck` and `npm test` (12) pass.
Open: nothing new; see the walkthrough's gaps.

---

## 2026-10-02 - fix(issues): name the shortfall, announce escalation, correct the gaps (issue #13)

`feat/receipt-issues` · @Oxshadha

- `shortfall.resolved` gains an optional `shortfallId`, so Loading can resolve the one shortfall a replacement answers (ISS-07).
- New event `issue.escalated`, published once by `IssueEscalationJob` and routed to dispatchers in MODULES §9.
- A-29 is rewritten as temporary: the team's driver scope is vehicle and date (`driverVehicleOn`, #5).
- The walkthrough's gaps table is checked against each module issue's full description.

Why: review of the open gaps against the module issues.
Verified: `TEST_DATABASE_URL=... mvn test`, 349 tests, 0 failures; frontend `npm run typecheck` and `npm test` (12) pass.
Open: comments for #5, #10 and #19 (see the walkthrough), and the A-24 decision.

---

## 2026-10-02 - docs: receipt and issues walkthrough and registers (issue #13, step 5)

`feat/receipt-issues` · @Oxshadha

- **Walkthrough:** adds [the issue #13 walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md).
- **RULES:** R-ISS-01 to R-ISS-07 (lifecycle, resolution vocabulary, subjects, replacement and redelivery fit, redelivery once, escalation, raise rights as policy).
- **EDGE-CASES:** a test named for every RCP row and for the Issues halves of EXE-07, EXE-08, LOD-01 and STK-11; new RCP-09 and ISS-01 to ISS-06.
- **ASSUMPTIONS:** P-10 = 24 h, P-20 to P-23, A-29 and A-30.
- **MODULES:** §7 and §8 match the code.

Why: issue #13 closeout.
Verified: `TEST_DATABASE_URL=... mvn test`, 349 tests, 0 failures; frontend `npm run typecheck` and `npm test` pass.
Open: the walkthrough's "Known gaps" table (relay #6, Loading and Execution query beans for the custody view, partial redelivery, Notification).

---

## 2026-10-02 - feat(issues): commands, consumers, escalation and raise rights (issue #13, step 4)

`feat/receipt-issues` · @Oxshadha

- **Commands:** all seven `issue:*` commands through the bus. Scope is the issue's depot or outlet, read as the system, so out of scope is 403 plus audit.
- **Raise rights are policy data:** the resource is `wpt:issue:type:<TYPE>`, and migration `20261002T0400` publishes version 3 of the Loader, Driver and StoreManager policies, scoping `issue:Raise` by type.
- **Redelivery:** `issue:ScheduleRedelivery` emits exactly one `redelivery.requested`.
- **Replacement:** `issue:RecordReplacement` emits `shortfall.resolved`.
- **Consumers:** seven events each raise one issue (deduplicated by source key) at the policy's default severity. A partial or disputed receipt opens one investigation linked to the order, receipt, delivery and trip (R-RCP-07).
- **Escalation:** `IssueEscalationJob` stamps an unassigned issue once at its severity's deadline and refreshes the backlog gauges.

Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 349 tests, 0 failures; frontend `npm run typecheck` and `npm test` pass.
Open: step 5 (walkthrough and registers).

---

## 2026-10-02 - feat(receipt): confirm, dispute, delivery consumer and auto-close (issue #13, step 3)

`feat/receipt-issues` · @Oxshadha

- **Commands:** `receipt:Confirm`, `receipt:ConfirmPartial` and `receipt:Dispute` share one flow, `ReceiptAnswerHandler`. A missing delivery is 404 (RCP-04). Another outlet is 403 plus audit (RCP-05); scope is read as the system in its own transaction, so the bus can answer forbidden rather than absent.
- **Delivery consumer:** `delivery.completed` opens a PENDING receipt with the order's lines and the driver named, once per delivery.
- **Auto-close:** `ReceiptAutoCloseJob` closes silence after 24 h as the system (RCP-02). A shortage after auto-close is accepted as late and announced as `receipt.disputed` (RCP-08).
- **Ordering:** gains `OnReceiptDisputed`. A dispute marks the order RECEIVED; a late one leaves it UNCONFIRMED and is counted.
- **Migration** `20261002T0300` flips the receipt actions.

Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 338 tests, 0 failures.
Open: steps 4 and 5.

---

## 2026-10-02 - feat: receipt and issues schema and reads (issue #13, step 2)

`feat/receipt-issues` · @Oxshadha

Migrations `20261002T0100` (receipt) and `T0200` (issues): FORCE RLS by outlet, depot and system (issues also by who raised it), no DELETE, and effective-dated parameters that the runtime role cannot edit. Database CHECKs back the rules: an answered receipt has a person behind it, and the system never resolves an investigation. Adds the repositories and the reads `/api/receipts/pending`, `/{orderId}`, `/{orderId}/custody`, `/api/issues?depot=` (keyset, most severe first), `/by-subject`, `/{id}` and `/{id}/history`. Contract changes are additive: `ReceiptView` fields, `CustodyChainView` and `IssueHistoryView`, mirrored in the frontend.
Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 327 tests, 0 failures; `npm run typecheck` passes.
Open: steps 3 to 5.

---

## 2026-10-02 - feat: receipt and issues domains (issue #13, step 1)

`feat/receipt-issues` · @Oxshadha

Pure domains for both modules.
- **Receipt:** the `Receipt` aggregate, plus `ReceiptStateMachine` (PENDING to CONFIRMED, PARTIAL, DISPUTED or AUTO_CLOSED; AUTO_CLOSED still accepts a late shortage), `AutoClosePolicy` and `ReceiptParameters`.
- **Issues:** the `Issue` aggregate, plus `IssueLifecycle`, `ResolutionAction` (no RETURN) and `SeverityPolicy`. The system actor can never resolve a shortage investigation (R-RCP-07).

Why: issue #13. The decisions are in [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `mvn test -Dtest=ReceiptTest,ReceiptStateMachineTest,IssueTest,ModuleBoundaryTest`, 33 tests, 0 failures.
Open: steps 2 to 5 (schema, commands, consumers, jobs, docs).

---

## 2026-10-01 - docs(planning): walkthrough and register updates (issue #9, step 7)

`feat/planning-module` · @Oxshadha

Adds [the issue #9 walkthrough](../issues/009-planning/WALKTHROUGH.md). EDGE-CASES now names a test for every Planning row, adds PLN-18 and PLN-19, and supersedes POL-06 and POL-07 with what-if runs. RULES revises R-PLN-21 and closes C-2 and Q5. ASSUMPTIONS adds A-26 to A-28 and P-15 to P-19, and gives P-12 its value. MODULES describes Planning as built. Two tests were added so no row is left without one: `UnservableScreenTest` (PLN-09) and `aNonOperatingDayIsNotPlanned` (PLN-13).
Why: issue #9 closeout.
Verified: `TEST_DATABASE_URL=... mvn test`, 252 tests, 0 failures, 0 skipped; `check_allocation.py` on the peak-day CSV prints `FEASIBILITY: PASSED`.
Open: the walkthrough's "Known gaps" table (relay #6, what-if runs, authoring and replay commands, the explanation for a second skip, #16, #19).

---

## 2026-10-01 - feat(planning): revise, replan, previews and consumers (issue #9, step 6)

`feat/planning-module` · @Oxshadha

Adds `plan:Revise` and `plan:Replan`, `previewAssignments` and `previewInterchange` (also on `/api/plans/preview/*`), and the decision-7 consumers.
- Migration `20261001T0600` keys trips by `(plan_id, trip_id)`, so a trip keeps its id across versions while it carries the same orders, even on a substitute vehicle (PLN-04, R-LOD-06). It also allows one open draft per depot-day and records `revision_reason`.
- A revision carries the published plan's orders, drops cancelled ones and defers late arrivals under PLN-07. It is announced as `plan.revised`, and only new deferrals are announced again.
- Interchange is auto-published only when exactly that trip moved; a lost vehicle drafts a revision for the dispatcher.

Why: issue #9 step 6. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-6-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 250 tests, 0 failures, 0 skipped.
Open: step 7 (walkthrough and registers). The relay that delivers events to consumers is #6.

---

## 2026-10-01 - feat(planning): generate, override, defer and publish (issue #9, step 5)

`feat/planning-module` · @Oxshadha

`plan:Generate`, `plan:Override`, `plan:Defer` and `plan:Publish` now run through the bus, backed by the `PlanningRun` aggregate, the `PublicationGate` and `DemandFingerprint`. Each override or deferral writes the next draft version and cancels the one edited, so a stale second edit is refused with the successor's id and a diff (PLN-06). Publication refuses when demand, the reference version, the rule set or the policy changed, and re-runs the whole registry. It then emits `plan.published`, `order.deferred` and `order.unservable`. `ReferenceQuery.vehiclesOfDepot` was added, so the unservable screen sees workshop vehicles. Migration `20261001T0500` marks the four actions implemented.
Why: issue #9 step 5. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-5-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 236 tests, 0 failures, 0 skipped.
Open: step 6. Revise and Replan, `plan.revised`, stable trip ids across versions, previews, and consumers.

---

## 2026-10-01 - feat(planning): schema, repository and plan reads (issue #9, step 4)

`feat/planning-module` · @Oxshadha

Migration `20261001T0400` adds the planning tables with forced RLS. It also adds the triggers that freeze a published run and its children, one published plan per depot-day, effective-dated rule sets and priority policies (seeded with the booklet values), and `plan:Read` implemented. Adds `JdbcPlanRepository`, `PlanDataQuery` behind `PlanQuery` (previews throw until step 6), and read-only `/api/plans`. `FoundationIntegrationTest` now retires reference versions instead of deleting them, because a run holds a foreign key to the version it stamped.
Why: issue #9 step 4. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-4-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 216 tests, 0 failures, 0 skipped.
Open: step 5 (Generate, Override, Defer and Publish), with an open decision on overrides recorded in the plan. EDGE-CASES test columns for POL-04 and POL-08 get updated at step 7.

---

## 2026-10-01 - docs: record the revised warehouse API and wire its key

`feat/ordering-module` · @Oxshadha

The warehouse API now has two warehouses (KDY, PLG), requires `warehouse` on `POST /orders`, and answers a short order with a `202` partial reservation that expires. RULES §2 (contract, R-STK-08 to R-STK-10, lifecycle mapping), MODULES, A-07 and A-20 corrected; A-25 added. `WAREHOUSE_*` passed through both Compose files, documented in `.env.example` and [development.md](development.md#the-external-warehouse-api).
Why: the recorded contract said all-or-nothing and no TTL; both are now false.
Verified: read endpoints probed live with a key (200, 401 without); no order was placed.
Open: `StockPort` needs a depot parameter, and the #7 adapter must cancel a `202` and answer `Insufficient` (R-STK-08). Both owned by #7.

---

## 2026-10-01 - feat: build the Ordering module (issue #8)

`feat/ordering-module` · @Oxshadha

Ordering end to end: pure domain, `ordering` schema with forced row-level security (`20261001T0200`), `JdbcOrderRepository`, `OrderDataQuery` behind `OrderQuery`, read-only `/api/orders`, the `order:Place/Amend/Cancel/CloseForDay` handlers, twelve event consumers and the 16:00 `ordering.cutoff` job. Platform pieces it needed: outbox writer, consumer inbox, system actor with `app.actor_is_system()` (`20261001T0100`), `Database.readAs` so a contract read never switches its caller's role, the bus auditing scope denials raised inside the transaction, and `UnconfiguredStockPort` for a blank warehouse key. New event `order.auto_deferred`; Ordering now consumes `loading.started`.
Why: issue #8. The walkthrough is [docs/issues/008-ordering/WALKTHROUGH.md](../issues/008-ordering/WALKTHROUGH.md); decisions are in EDGE-CASES (ORD-03 revised, ORD-13, ORD-14) and ASSUMPTIONS (A-22 to A-24).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 170 tests, 0 failures, 0 skipped. Frontend `npm run typecheck` and `npm test` pass.
Open: no relay or scheduler runs the consumers and the job yet (#6); no real `StockPort` or `CatalogueQuery` (#7); partial redelivery (A-24).

---

## 2026-09-28 - feat: administer accounts, scope and the calendar over HTTP

`dev` · @Oxshadha

Eight identity command handlers in `IdentityCommandHandlers`: create, update, disable, reset password, grant and revoke scope, assign a driver and end an assignment. `AccountQuery` plus `AccountAdminController` and `ReferenceController` for the reads, both authorized through a new `RequestAuthorizer` port that identity implements, so a module's web layer never imports another module. `PolicyAdminController` now shares that guard instead of its own copy. `calendar:Override` is a command; its override lives in `ref.calendar_overrides` and is applied when a snapshot is loaded. Migration `009`.
Why: every account change was a host command, so nothing outside a terminal could make one, and the four read surfaces the frontend needs did not exist.
Verified: 97 tests green with `TEST_DATABASE_URL` set, twice in a row. `AdministrationIntegrationTest` proves over HTTP that a stale `expectedVersion` is `409`, an overlapping driver assignment is `409` naming R-IAM-13 while an abutting one is accepted, a scope naming `OUT999` is `404` rather than stored, an account cannot disable itself, a reset revokes live sessions, and a driver reading `/api/accounts` is `403` rather than an empty list.
Open: devices are still unbuilt, deliberately. Ordering is next.

**Writes and reads split deliberately.** Every change goes through `POST /api/commands`; the controllers are read only. A second write path would have no receipt, no version guard and no audit row, and it would be the one a client reached for. The consequence is that every account read returns `rowVersion`: a read surface that hides the version makes the version guard unusable.

**An override had to be a table, not a column.** `ref.calendar_days` is rewritten by every reference import, so an override stored there would vanish the first time the supplied CSV changed. It also has nowhere to put an actor or a reason, which architecture rule 8 requires of any override. Recorded as R-CAL-04, with R-IAM-13 through R-IAM-17 for the assignment, session and scope rules the handlers enforce.

---

## 2026-09-27 - feat: serve POST /api/commands and route the first commands through the bus

`dev` · @Oxshadha

Added `platform/web/CommandController` and an `ActorResolver` port that identity implements, so platform serves the endpoint without importing a business module. `CommandHandler` now declares its own `ModuleRole`, which keeps the web layer from knowing which module owns a kind. Converted `SetVehicleDayStatusHandler` and `ImportReferenceDataHandler` to handlers; the import keeps a separate CLI entry point because `import-reference` runs with no session and no receipt. Migration `008` corrects `iam.action_catalogue.implemented` to match what is actually enforced.
Why: the command bus, the idempotency guard and the fail-closed authorizer were wired and unit tested, and nothing served `/api/commands` although the frontend write path and the offline queue both post there. Ordering should not be the first thing to run a command.
Verified: 86 tests green with `TEST_DATABASE_URL` set. `CommandPathIntegrationTest` goes over HTTP with a real session cookie: a command posted twice applies once and the retry is answered from the receipt with the same shape, the same id with a changed payload is `409` and changes nothing, a driver posting `reference:Import` is `403` with the denial in `integration.audit_log` and no receipt, and an unsigned caller is `401`.
Open: nothing in this unit. Account administration and reference reads followed in the next entry.

**Two defects the first real dispatch exposed.** The fail-closed check was a chain of `Optional.map`, and mapping to null collapses to empty: with an authorizer present and the command allowed, the bus took the fail-closed branch and denied everything. A replay also returned the receipt's `result_body` as raw jsonb text, so a retry answered with a string where the first call answered with an object. Both are now unit tested in `CommandBusTest` without a database. A mechanism nobody has run is not a mechanism that works.

**The audit log would have stopped accepting writes on 1 November 2026.** `005` created two monthly partitions and said a scheduler would create more. There is no scheduler, and every command commits its audit row in the same transaction as the change, so a missing partition fails the command rather than losing the row. `008` extends the range to July 2027 and closes a related hole: the parent was append only but each partition carried UPDATE from the schema-wide grant.

---

## 2026-09-27 - docs: make the documentation set answerable

`dev` · @Oxshadha

Removed `plan.md`, which had been committed empty. Untracked `TEARDOWN-PLAN.md`, keeping the file on disk: the teardown happened, so it is a record rather than a plan. Replaced the README's flat list with a three-question map, and marked the four documents that predate the rewrite.
Why: "where is the plan we follow" had no clear answer, and four tracked documents still described the prototype deleted at `prototype-v0`.
Verified: 79 links across all tracked markdown, 0 broken.
Open: `docs/architecture.md`, `docs/data-model.md`, `docs/code-structure.md` and `enterprise-architecture-plan.md` describe the deleted prototype. They are now labelled as history; deleting or rewriting them is a separate decision.

`SYSTEM-ARCHITECTURE.md` stays at the repository root deliberately. It is the entry point the way `README.md` is, and `docs/architecture/` holds the detail behind it.

---

## 2026-09-27 - feat: policy administration, and the integration tests that were missing

`dev` · @Oxshadha

Added `PolicyAdminUseCase` and `PolicyAdminController` for authoring, versioning, attaching and detaching policy at runtime, and `FoundationIntegrationTest`: ten tests against a real PostgreSQL covering migrations, reference import, sign-in, Argon2 storage, policy decisions, runtime policy change, catalogue validation, row-level security and session revocation on disable.
Why: every earlier database claim in this log was verified by hand with curl and psql, never by a test. That is not the same thing, and the gap was mine.
Verified: 74 tests green with `TEST_DATABASE_URL` set, of which 64 are unit and 10 integration. Without that variable the integration tests skip, so `mvn package` alone does not prove the database paths.
Open: nothing in the foundation. Ordering is next.

**The integration tests found a real bug on their first run.** `Database.asModule` set the actor with `jdbc.update("SELECT set_config(...)")`, and calling a SELECT through `update()` throws. The actor was therefore never set through the Java path, so row-level security would have seen nobody and returned nothing for every request. Manual testing missed it because RLS was exercised through psql, where the plumbing is different. This is the argument for integration tests in one paragraph.

---

## 2026-09-27 - feat: row level security, and the docs it changes

`dev` · @Oxshadha

Migration `007_row_level_security.sql` adds `app.current_actor()`, `app.actor_has_depot` and `app.actor_has_outlet`, creates the `waypoint_ops` role, and enables RLS with `FORCE` on the two scope tables. Shipped the eleven doc edits this stage owes: policy as data in SYSTEM-ARCHITECTURE 6.2 and 6.8, FOUNDATION-PLAN 2.4, 2.6 and 2.9, MODULES section 2, a new `R-IAM-*` rule group, five new `SEC-*` edge cases, assumption A-21 and an Authorization section in AGENTS.md.
Why: the composition rule needs both halves. Policies decide actions, RLS decides rows, and without the second half a forgotten WHERE clause is a leak.
Verified against the live database: as `waypoint_ops` with the admin actor set, only Peliyagoda is visible; with the driver actor, only Kandy; with no actor, zero rows. `app.actor_has_depot` returns false for everything when no actor is set. Both tables report `relrowsecurity` and `relforcerowsecurity` true, with four policies in place.
Open: policy administration endpoints. A caveat worth knowing: a PostgreSQL superuser bypasses RLS whatever `FORCE` says, so the deployed owner must not be superuser.

---

## 2026-09-27 - feat: identity module, authentication and the decision point

`dev` · @Oxshadha

Policy parsing and repository, a policy cache, and `PolicyDecisionPoint` implementing `CommandAuthorizer`, which is what unblocks the command bus. Authentication: Argon2id hashing, server-side sessions with absolute and idle expiry, a database-backed login throttle, `/api/session` for sign in, resolve and sign out, and an `account-create` command for the first administrator.
Why: nothing can run until an authorizer exists, because the command bus fails closed by design.
Verified: 64 tests green. Live checks against a seeded database: unauthenticated `/api/session` is 401 problem+json; a wrong password and an unknown account return the identical message, with a dummy hash burned on the unknown path so timing does not enumerate accounts; a correct sign in returns the session and sets an HttpOnly cookie holding a 43 character opaque token; the cookie resolves the session. Passwords are stored as `$argon2id$v=19$m=16384...`.
Open: row-level security, the policy administration endpoints and the doc edits remain.

The `SeededPolicyTest` parses `006_iam_policies.sql` and evaluates the shipped documents rather than a copy of them, so the six role policies cannot drift from what the tests claim. It proves the dispatcher is fenced out of administration by an explicit Deny and the auditor writes nothing.

Two corrections. `SessionService` violated the rule against `*Service` names and the boundary test caught it, so it is `SessionRegistry`. `Clock` had no bean, so nothing that injected it could start; it is now registered in `platform/config/TimeConfig`.

---

## 2026-09-27 - feat: policy schema and evaluator

`dev` · @Oxshadha

Migration `006_iam_policies.sql` adds `action_catalogue`, `policies`, immutable `policy_versions` with one enforced default, and `policy_attachments`, seeded with six role policies as data. Added the pure evaluator: patterns with wildcards, six condition operators, and the ordering contract of default Deny, explicit Deny wins, then Allow.
Why: authorization becomes data an administrator edits at runtime rather than code that needs a release. Evaluation stays in process, so the ruling against an external policy service still holds.
Verified: 54 tests green, 18 on the evaluator alone. Migration applied to an empty database: six policies, each with one default version, each attached to its role, and 32 catalogued actions of which 3 are implemented.
Open: the decision point, authentication and row-level security are next. Nothing calls the evaluator yet, so the command bus still fails closed.

Two things the tests caught. A request with no specific resource was matching nothing at all; it is now normalised to `*`, which makes an unscoped request match only an unscoped grant rather than quietly satisfying a scoped one. A missing context key deliberately fails its condition rather than passing it, because treating absence as satisfied would grant access whenever a caller simply forgot to supply the value.

---

## 2026-09-27 - feat: reference read API

`dev` · @Oxshadha

Added the `ReferenceQuery` contract with its own view types, `ReferenceDataQuery` reading from cache for the current version and from the database for a historical one, `SetVehicleDayStatusHandler`, and `ReferenceBootstrap` which loads the current version once the application is ready.
Why: other modules must read reference data through a contract, never through its domain, or the boundary test will stop Planning before it starts.
Verified: 36 tests green, including 8 new window tests covering early arrival waiting, late measured against the close rather than the plan, and the mall window intersection. Booting against the imported database loads version d8a5ba89 into the cache and readiness returns 200.
Open: calendar override is not implemented; the `CALENDAR_FILE` path from R-CAL-03 is still only policy-based generation.

Note: loading the cache is deliberately tied to application-ready rather than startup, so an unreachable database leaves the instance up and reporting the problem instead of boot-looping.

---

## 2026-09-27 - feat: reference data module

`dev` · @Oxshadha

Pure domain (windows, vehicles, outlets, calendar policy, snapshot) plus the nine-rule validator, a CSV importer that hashes its source, a version writer and reader implementing snapshot-per-version, an in-memory cache with pointer swap, and the `import-reference` command.
Why: reference data is the shared vocabulary every other module reads, and it has no upstream dependency, so it is built first.
Verified: import of the supplied data published 120 outlets, 60 vehicles, 12 districts, 2 depots, 9 allowances, 910 calendar days, with exactly one current version. Re-import was a no-op by content hash. Fleet composition matches the booklet exactly: 12 reefer trucks, 40 dry trucks, 4 reefer vans, 4 ambient vans. All 12 mall outlets carry their mall window. 28 tests green, including 13 validator tests that each reject a deliberately broken fixture with no database.
Open: `ReferenceDataQuery` and the vehicle day status and calendar override handlers are not written yet; the import path is complete.

Two corrections while building. The `auditIsReachedThroughItsOwnComponent` boundary rule written in the previous stage was wrong: it forbade every module from naming `Database`, which is exactly what a repository must do. It now says what it meant, that `domain`, `web` and `contract` may not touch the seam. Separately, the working-directory bug fixed earlier in the migrator existed again in the importer, so both now share `DirectoryLocator`.

---

## 2026-09-27 - feat: platform baseline

`dev` · @Oxshadha

Typed validated configuration with a redacted startup report, structured logging, Micrometer metrics behind our own `Metrics` API, OpenTelemetry tracing, the command bus with idempotency receipts and audit, security headers, and migration `005_platform.sql` creating the `integration` schema (command receipts, partitioned audit log, outbox with worker state). Convention B8 and Part 0.3 added to FOUNDATION-PLAN.
Why: these are cross-cutting, so retrofitting any of them means touching every write path already written.
Verified: `mvn package` green, 15 tests including 9 boundary rules. Missing `DATABASE_URL` refuses to start with a named message; a database that is merely down starts fine with liveness UP and readiness DOWN. Unknown path returns 404 as problem+json, `/prometheus` serves 68 samples, all three security headers present, and the startup report shows `warehouseApiKey=absent` rather than a value. Migration 005 applied to an existing database.
Open: the command bus fails closed until the identity module supplies a `CommandAuthorizer`, so no command can run yet. That is intended.

Note: the boundary test caught `platform` depending on `identity.contract.CurrentActor`. Rather than weaken the rule, `CurrentActor` became `shared/domain/Actor`: naming the caller is kernel vocabulary, and platform may not depend on a business module.

---

## 2026-09-27 - feat: frontend skeleton

`dev` · @Oxshadha

Rebuilt `frontend/src/`: session gate and role router in `app-shell/`, API client with RFC 9457 parsing and correlation ids, command envelope carrying command id and expected version, and the tiered offline write queue over IndexedDB. Role folders hold placeholders. No screens.
Why: the shell is what the Figma design hangs in, and the write queue is the piece that is expensive to retrofit. Screens are what the design changes, so building them now would guarantee rework.
Verified: `tsc --noEmit` clean, production build compiles with a 56-asset offline shell, 6 boundary tests pass. The frontend boundary test now fails when it finds no files, mirroring ArchUnit, and that was confirmed by hiding `src/`.
Open: offline tiers are declared but only the driver tier is exercised once screens exist. `/api/session` and `/api/commands` are not implemented yet; they arrive with the IAM module.

---

## 2026-09-27 - feat: foundation schema for reference and identity

`dev` · @Oxshadha

Four migrations creating `ref` (14 tables) and `iam` (9 tables) with decisions D1 to D9 applied, plus a checksummed forward-only `Migrator` and the `migrate` command. `ops`, `ml` and `integration` land with their own modules.
Why: the prototype's nine-table schema was deleted; this is the baseline everything else is built on, so it is created clean rather than as corrections stacked on the old design.
Verified against PostgreSQL 16 from an empty database: 4 migrations apply, re-run is a no-op, editing an applied file is rejected as immutable. `waypoint_app` without adopting a module role gets "permission denied for schema ref"; after `SET ROLE waypoint_ref` it reads. `waypoint_ref` cannot reach `iam`; `waypoint_iam` reads `ref` but cannot write it. A fourth brand inserts with no migration (D3). Overlapping driver assignments on one vehicle are rejected by the exclusion constraint.
Open: reference import and the IAM module behaviour are next. Frontend skeleton not started.

---

## 2026-09-27 - docs: rule, assumption and parameter registers

`dev` · @Oxshadha

Added `RULES-AND-POLICIES.md` (104 rules with source and status, 6 source conflicts), `ASSUMPTIONS.md` (17 assumptions plus a 14-entry parameter register) and resolved decisions D1 to D9 in `FOUNDATION-PLAN.md`. Propagated to MODULES, SYSTEM-ARCHITECTURE, DATA-MODEL-REVIEW, EDGE-CASES (now 117 cases) and the schema README.
Why: the booklet, the supplied validator, the datasets and the team draft disagreed in six places, and nothing recorded which rules were ours versus mandated, or which values can change.
Verified: against `check_allocation.py` and the supplied data. Reefers carrying ambient confirmed by 4 of 9,734 reefer routes; chilled is Fresh-only across 34,742 orders; depot is a function of district with 0 exceptions in 120 outlets; 0 of 25,198 routes mix brand, district, vehicle or temperature; all 12 mall outlets have mall window identical to outlet window. Links checked, 0 broken.
Open: assumptions A-02, A-03, A-08 and A-09 are unconfirmed and each changes servable volume. Warehouse exposes no stock endpoint. Decision taken to rewrite the backend against this baseline rather than extend the existing one, and to defer frontend screens until the Figma design lands.

---

## 2026-09-27 - docs: plan the reference data and IAM foundation

`dev` · @Oxshadha

Added `docs/architecture/FOUNDATION-PLAN.md`: a seven-point stability contract, five baseline defects found in the target schema, and full specs for the Reference data and Identity modules (ownership, versioning model, import validation, contracts, edge cases, definition of done) plus the build sequence.
Why: these two modules are what every other module depends on, and the parts of the current target schema that would force change later needed fixing before anything is built on them.
Verified: not applicable, planning only. No code, no migrations, no application changes.
Open: seven decisions in section 3.4. The largest is the reference versioning model, recommended as snapshot-per-version.

---

## 2026-09-26 - schema: move into version control and add module roles

`dev` · @Oxshadha

Moved the schema out of Downloads into `docs/architecture/schema/`, split into 19 numbered forward-only migrations with `001_baseline.sql` holding the team's original untouched. Added a database role per module (018) and an inbound warehouse event inbox (019). Deleted the `.sql.bak`. Propagated the role model to SYSTEM-ARCHITECTURE, DATA-MODEL-REVIEW, AGENTS.md and seven new SEC edge cases, and added the warehouse integration contract to MODULES.md.
Why: a file defining the whole data model had no history and lived on one laptop, and schema separation is not enforced by anything until grants enforce it.
Verified: all 19 applied in order against PostgreSQL 16 from empty, 49 tables. `waypoint_app` without `SET LOCAL ROLE` is denied, `waypoint_ops` reads its schema and the kernel, is denied writing `iam.users` and deleting from `ops.orders`, and may insert to the outbox. Test databases and cluster roles dropped.
Open: reservation TTL, whether stock is per depot or per outlet, and whether availability is checked at confirm or at planning. These are business decisions, listed in MODULES.md.

---

## 2026-09-26 - schema: align industry schema with the architecture

`dev` · @Oxshadha

Added Part 2 to `waypoint_industry_schema.sql`: reference and policy versioning, `row_version` and immutability trigger on published plans, command receipts, order status transition table, trip vehicle assignment history, vehicle temporal exclusion, warehouse stock fields, catalogue provenance on `ref.products`, attachment scan and retention, return fuel plus a weekly rollup view, outbox worker state, notification delivery split, 16 missing foreign key indexes, RLS with FORCE and a fail-closed actor function.
Why: the schema was verified directly for the first time, which confirmed six findings and disproved two.
Verified: executed against PostgreSQL. Fresh run from an empty database exits 0, Part 2 re-run is idempotent, the published-plan trigger rejects an in-place edit, and the exclusion constraint rejects an overlapping driver assignment. Test databases dropped.
Open: the schema file still lives in Downloads, outside version control. Partitioning is documented as a migration recipe, not an ALTER, because converting a populated table needs a rebuild.

---

## 2026-09-26 - docs: add policy and rule change design

`dev` · @Oxshadha

Added SYSTEM-ARCHITECTURE.md section 6.8 (four-tier rule placement, effective dating, version stamping, shadow and canary rollout), versioned `PriorityPolicy` and `RuleSetVersion` in MODULES.md, finding 7 plus `ops.policy_versions` and `ops.rule_parameters` in DATA-MODEL-REVIEW.md, and a POL group of 10 edge cases.
Why: plans stamped the reference-data version and predictions stamped the model version, but nothing stamped the rules, so a deferral could not be reproduced once a threshold changed.
Verified: link check clean; findings renumbered 1 to 21 without corrupting the other numbered tables. Fixed two sections both numbered 3 in EDGE-CASES.md.
Open: whether deferral priority is authored by dispatchers or engineers decides if the decision table needs a UI.

---

## 2026-09-26 - docs: propagate catalogue rules across architecture docs

`dev` · @Oxshadha

Carried the external product catalogue rules into SYSTEM-ARCHITECTURE.md (external systems, anti-corruption boundary), MODULES.md (`ref.products` is a cached projection; capacity constraints read order-level weight and volume) and EDGE-CASES.md (six CAT cases plus STK-07). Recorded outlet coordinates and the driver-side temporal exclusion as deferred decisions.
Why: the rules only existed in the data model review, so the other documents still implied product lines could feed capacity maths.
Verified: link check across all markdown, 0 broken. Edge case register now 89 cases.
Open: nothing new.

---

## 2026-09-26 - docs: correct schema findings and add catalogue rules

`dev` · @Oxshadha

Reworded critical findings 1, 2, 3, 5 and 6 in DATA-MODEL-REVIEW.md after a second review against `waypoint_industry_schema.sql`: version columns and status CHECK constraints already exist, so the findings are about incomplete enforcement rather than absence. Added the RLS table-owner trap, `btree_gist`, `SKIP LOCKED`, and a section on the external product catalogue.
Why: the first wording overstated four findings and would have sent implementation after problems that are already half solved.
Verified: not applicable, documentation only.
Open: `waypoint_industry_schema.sql` is not in this repository, so the corrections rest on a second-hand reading. Commit it here. The catalogue carries no stock balances, so the `stock_held` flow has no data source behind it.

---

## 2026-09-26 - docs: rework architecture for enterprise scope

`dev` · @Oxshadha

Rewrote `SYSTEM-ARCHITECTURE.md` without deadline compromises and added `docs/architecture/`: MODULES.md (12 module specs with internal layers and connections), DATA-MODEL-REVIEW.md (team schema validated, 20 findings, corrected model, expand-contract migration) and EDGE-CASES.md (82 cases with enforcement, detection and test). Rewrote AGENTS.md around 10 architecture rules.
Why: timeline extended by months, so the design targets enterprise practice rather than a competition subset. Warehouse and stock is a separate system, modelled here as an external port behind an anti-corruption layer.
Verified: link check across all markdown, no broken links.
Open: six decisions in SYSTEM-ARCHITECTURE.md section 11, five in DATA-MODEL-REVIEW.md. Largest is migrating the nine-table JSONB schema to the target model.

---

## 2026-09-26 - docs: add system architecture

`refactor/module-structure` · @Oxshadha

Added `SYSTEM-ARCHITECTURE.md` at the root: six actors, thirteen modules, seven layers, aggregate to table mapping, authentication and authorization design, 45 edge cases and the workstream dependency order.
Why: consolidates the challenge booklet, the team requirements draft and the 40-table schema guide into one design, and separates what ships by October 4 from what the design supports later.
Verified: not applicable, documentation only.
Open: five decisions listed in section 12, including whether to migrate to the 40-table schema before the deadline. Four gaps found in the team schema: no inventory tables, no outlet coordinates, vehicle interchange unmodelled, return cost has no home.

---

## 2026-09-26 - docs: add local development guide and development log

`refactor/module-structure` · @Oxshadha

Added `development.md` (hybrid loop: PostgreSQL in Docker, app native) and this log, both under `docs/development-docs/`. Markdown is now ignored by default in `.gitignore` with an allow-list.
Why: agent sessions scatter scratch `.md`, and parallel work needs shared context.
Verified: 41 relative markdown links resolve, 0 broken.
Open: nothing.

---

## 2026-09-26 - refactor: organize backend by module and frontend by role

`refactor/module-structure` · @Oxshadha

Backend split into `shared/`, `platform/`, `referencedata/`, `identity/`, `planning/`; `platform/db/Database` extracted as the single PostgreSQL seam. Frontend moved to `src/app-shell/`, `src/roles/*`, `src/shared/*` behind path aliases. Added 7 ArchUnit rules and 5 frontend import rules.
Why: `DispatchService` had absorbed eight responsibilities at 1780 lines because packages were organized by layer.
Verified: `mvn package` 9 pass, `tsc` clean, `next build` clean, `npm test` 36+4 pass, e2e 11/12. Both boundary guards fail on a planted violation. The one e2e failure reproduces on untouched `HEAD`.
Open: `DispatchService` still 1742 lines; `ApiController` still one controller. Stages 3 and 4 in [code-structure.md](../code-structure.md).

---

## 2026-09-26 - docs: add architecture plan and code structure spec

`refactor/module-structure` · @Oxshadha

Added [enterprise-architecture-plan.md](enterprise-architecture-plan.md) and [code-structure.md](../code-structure.md).
Why: needed one agreed target before refactoring, and the Hackathon requires an architecture document.
Verified: not applicable, documentation only.
Open: two team decisions, whether to delete or isolate the duplicate Node service in `frontend/lib/`, and the missing Datathon data files in Phase C.
