# Issue #21: Driver UI (phone PWA, full offline): walkthrough

What was built. The plan and its nine decisions are in [PLAN.md](PLAN.md); the backend is [issue #12](../012-execution/WALKTHROUGH.md). Cases are EXE-01 to EXE-03, EXE-09, EXE-22, EXE-26, EXE-27 and SEC-01 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md).

## Layers

Under `frontend/src/`:

- `shared/offline/store.ts`: the device database is version 2, with `snapshots` (reads kept for a day with no signal) and `uploads` (binary files on their way) beside the command `outbox`.
- `shared/offline/snapshots.ts`, `uploads.ts`: `keep`/`kept`, and `saveUpload`/`drainUploads`/`discardUpload`. An upload is addressed by an id minted on the device and sent by `PUT`, so sending twice is one file. A refusal is held, never dropped or resent.
- `shared/offline/queue.ts`: `pendingEntries`, so a screen can draw the writes still waiting.
- `shared/ui/Sheet.tsx`, `theme.css`: a bottom sheet on theme tokens; `--color-go-card/action/on-action/soft/on-soft` and the `.go-dark` overrides, so the dark theme is the same markup.
- `app-shell/session.ts`, `AppShell.tsx`: the last session the server confirmed is remembered (who, roles, scope; no credential) and used only while the server cannot be asked, for roles that keep their working set on the device. A `401` or a sign-out forgets it. The shell asks again when the connection returns.
- `roles/driver/data/run.ts`: pure. `project` applies waiting writes to the server's run sheet by the server's own transitions and version arithmetic; `missing` says what a record still needs; lateness and waiting as the phone reads them.
- `roles/driver/data/gateway.ts`, `useRun.ts`, `image.ts`: the only code that talks to Execution. Reads the day, keeps it, sends or queues writes, shrinks photos.
- `roles/driver/useDriver.ts`: what each screen does, as commands. `index.tsx` only chooses which screen is drawn.
- `roles/driver/screens/`: `Home`, `Route`, `DeliveryReport` with `ProofCapture` and `SignaturePad`, `StopDetail`, `RunComplete`, `Sheets` (report a problem, saved, sign out), `RefusedUploads`. `TopBar.tsx`, `ui.tsx`.

No backend change.

## Flows

- **The day.** `GET /api/execution/vehicles` and `/run-sheets` for today at the depots, then each vehicle and outlet from Reference. The answer is kept on the phone; when the server cannot be reached the kept copy is shown with a banner saying so.
- **A write.** With a connection and nothing waiting it is sent to `/api/commands`, the run is read back, and the next screen opens on what the server holds. A rule refusal is shown at once and nothing is queued. Otherwise (no signal, an outage, an expired session, or anything already waiting) it is stored in IndexedDB and joins the queue; the screen says "saved on this phone" only after the store confirms. The shell's sync engine sends the queue through `/api/sync` in order.
- **A stop.** Start run sends `delivery:Start`; I've arrived sends `delivery:RecordArrival`; Confirm sends `delivery:Record` and then `delivery:CaptureProof`, each naming the version the one before it produces. The signature (PNG) and the photo (JPEG, at most 1600 px) are stored as uploads under ids the proof names and travel on their own.
- **Not delivered.** From Report a problem, or "Could not deliver" on the report: a reason from the server's list and what happened to the goods. A stop never reached records no proof. A mall outlet reached after its window offers only this outcome (EXE-20).
- **Reports.** Running late and road blocked send `delivery:ReportFault` kind `road`; vehicle problem sends kind `vehicle`. The vehicle's day status sends `delivery:ReportVehicleStatus`.
- **A refusal after the fact.** A queued write the server refuses is held in the shell's review list with the server's reason; the stop is drawn from the server's copy without it. A proof file the server refuses is listed on Home until the driver removes it. Both keep sign-out blocked until dealt with.
- **Session ended.** The queue is kept, the screen says the driver has been signed out, and signing in again sends it.

## Run and verify

```
cd frontend
npm test                # 12 tests of the projection and the record rules in driver-run.test.ts
npm run typecheck
npm run build
npx playwright test -c playwright.driver.config.ts   # 10 browser tests at 393x852, mocked API
```

The browser tests cover: a stop worked with no signal, a reload with no signal, and the queue sent once in order with its signature; a partial delivery with a signal and no proof captured; proof required; not delivered from Report a problem; a road report; a rule refusal shown and not queued; a refused proof file; sign-out blocked with work waiting; a session that ended; a stop replanned while offline.

By hand, against a running backend: sign in as a driver assigned a vehicle whose trip the loader has released, and work the stops with the browser's network set to offline.

## Decisions

All nine are in [PLAN.md](PLAN.md). Added while building:

- **A finished stop can be opened**, and proof still owed can be added there, because the server takes proof after the outcome (R-EXE-01).
- **Stops can be opened out of order** from Home. The run sheet's order is the plan; the road sometimes disagrees.
- **A direct write is read back before the next screen opens**, so the delivery report never opens on a stop the phone still believes is on the way.
- **The design's slide-to-send is a choice and a button**, which a keyboard and a screen reader can use.

## Store-led handover (2026-10-05)

The stop flow the team planned with the Figma screens, on real data. The plan and its decisions are in [PLAN.md](PLAN.md#amendment-store-led-handover-2026-10-04); the rules are R-RCP-10, R-EXE-26 and R-NOT-17 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md), the cases RCP-19 to RCP-23 in [EDGE-CASES](../../architecture/EDGE-CASES.md).

**The flow.** The driver slides I've arrived, then Hand over (`delivery:Record`, the order as loaded). That opens the store's receipt and tells the store manager (routing version 8). The driver's phone shows "Waiting for store confirmation" and asks `GET /api/receipts/{orderId}/answer` every 10 s; when the store answers, the Figma delivery report shows what the store counted against what was sent, and the driver accepts with the store's PIN (`receipt:VerifyHandover`). At any point the driver can Continue to next stop with a reason (`delivery:LeaveWithoutStoreAnswer`); "I disagree" also raises an issue for the dispatcher. The dispatcher sees a stop left before the store answered under Needs you, with the reason.

**By layer.**

- Receipt: `migrations/20261005T1500_receipt_driver_answer.sql` (read-only row policies through the handover, action `receipt:ReadAnswer` for drivers), `ReceiptDataQuery.answer`, `ReceiptController` `GET /{orderId}/answer`, `ReceiptViews.ReceiptAnswerView`.
- Execution: `migrations/20261005T1501_execution_store_answer_waivers.sql`, `domain/StoreAnswerWaiver`, `LeaveWithoutStoreAnswerHandler`, `storeAnswerWaived` on `RunSheetStopView`.
- Notification: `migrations/20261005T1502_notification_routing_v8.sql`.
- Driver: `data/storeAnswer.ts` (the poll), `data/handover.ts` (the phase and the reasons), `screens/StopHandover.tsx` (container, PIN, reason sheet), `screens/DeliveryReportWaiting.tsx` (the Figma views, now driven by the phase), `useDriver` `handOver`, `moveOn`, `toNextStop`. The delivery form is now reached only for a stop that did not happen.
- Dispatcher: `data/live.ts` and `data/liveDesk.ts`: "left before the store answered" replaces "proof owed", since a handover the store checked is its own evidence.

**Run it locally.** With the backend on the local database, sign in as the driver on a seeded, released day, hand over at a stop, then as the store manager answer it from Receive; the driver's screen shows the answer within 10 s. Tests: `ReceiptHandoverIntegrationTest`, `ExecutionIntegrationTest`, `StoreAnswerWaiverTest`, `tests/driver-handover.test.ts`, `tests/dispatcher-live-desk.test.ts`.

**Not built.** The store answering before the driver records anything (it would change R-RCP-04 and R-RCP-09; a team decision). A push to the driver when the store answers: the phone polls while it waits.

## Known gaps

- Left out because nothing backs them (plan decision 9): vehicle pick-up by QR, the notification inbox (#14), fuel, call, voice notes, driving mode, map.
- The vehicle status shown is the one reported in this session; Execution has no read for the last reported status.
- Sinhala and Tamil: the loader screens are translated, these are English only.
- Browser tests run against a mocked API. The same flows against the real backend are covered on the server side by `ExecutionIntegrationTest`; a live browser run needs a seeded released trip, which the fresh-install seed provides once it exists.
- The browser tests are not in CI, like the loader's.
