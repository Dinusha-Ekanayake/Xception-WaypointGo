# Messaging, a thread per trip: walkthrough

Issue #136 (epic #150), branch `feat/136-messaging`. The [plan](PLAN.md) has the decisions; the model is ADR-004 in [SYSTEM-ARCHITECTURE.md](../../../SYSTEM-ARCHITECTURE.md).

## What is built

| Layer | Files |
| --- | --- |
| Migrations | `migrations/20261004T0100_messaging_threads.sql` (schema, role, row-level security), `20261004T0101_iam_messaging_actions.sql` (`message:Read`, `message:Post`), `20261004T1700_notification_routing_v4.sql` |
| Contract | `messaging/contract/MessagingCommands.java`, `MessagingEvents.java` (`message.posted`), `MessagingViews.java` |
| Domain | `messaging/domain/MessagePolicy.java`: who writes to whom, reports, the posting window, voice limits, excerpts, the report a raised issue becomes |
| Application | `PostMessageHandler` (the command), `MessagingConsumers` (threads from `plan.published` and `plan.revised`, reports from `issue.raised`), `MessagingQuery` (reads), `VoiceNotes`, `VoiceRetentionJob` (P-33) |
| Infrastructure | `messaging/infrastructure/JdbcThreadRepository.java` |
| Web | `messaging/web/ThreadController.java` under `/api/threads` |
| Other modules | `issues/contract/IssueQuery.issue`; `notification/application/NotificationConsumers.OnMessagePosted`; `NotificationPolicy.DRIVER_PUSH_EVENTS`; `platform/db/ModuleRole.MESSAGING`; `platform/config/MessagingProperties`; `identity/web/SessionActorResolver` and `sync/application/SubmitBatchHandler` (a command naming `actingUserId` acts as the PIN operator) |
| Frontend, shared | `shared/domain/messaging.ts` (the mirror); `shared/messaging/thread.ts` (labels, mentions, order, `Translate`), `useThread.ts` (reads, kept on the device for offline roles, messages waiting to send), `senders.ts` (`sendNow`, `queuedSender`), `recorder.ts` (the microphone); `shared/offline` (`waitsFor`: a write waits for the uploads it names), `scripts/sw-drain.mjs`; `shared/ui/TripThread.tsx`, `ThreadMessages.tsx`, `ThreadComposer.tsx`; `shared/notifications/inbox.ts` (the "Message" kind, a report reads urgent) |
| Dispatcher | `roles/dispatcher/inbox.tsx` (the open thread), `ThreadSheet.tsx`, `NotificationsPanel.tsx` (Reply), `data/threads.ts`, `data/useDay.ts` `useReports`, `screens/LiveTimeline.tsx` (report signs), `LiveTripUpdate.tsx`, `LiveNeeds.tsx` and `LivePanel.tsx` (Notify store, voice) |
| Driver | `roles/driver/screens/Messages.tsx`, `data/messages.ts` (the durable sender), `useDriver.ts`, `index.tsx` (Messages with a count of new ones) |
| Loader | `roles/loader/screens/TripMessages.tsx` (written as the PIN operator), `LoadSheet.tsx` (Messages), `data/strings.ts` (the thread in Sinhala and Tamil) |
| Store manager | `roles/store/screens/TripMessages.tsx`, `index.tsx` (a thread notification opens it), `screens/deliveries/Deliveries.tsx` (Message on today's row) |

## Flows

**A thread opens.** Planning publishes `plan.published`. `MessagingConsumers.OnPlanPublished` opens one thread per planned trip, keeping the depot, vehicle, date and outlets on it, so row-level security needs no other module. `plan.revised` widens a thread to new outlets and never narrows it (MSG-07). The thread exists before the vehicle leaves, so a loader's shortfall has somewhere to go (MSG-06).

**Someone writes.**
1. The phone sends `message:Post` through the command bus with a `clientMessageId`.
2. `PostMessageHandler`:
   - finds the thread;
   - checks the writer belongs to it, else returns 403 and audits (R-MSG-01);
   - asks `MessagePolicy.check` whom they may write to (R-MSG-02) and whether the thread still takes posts (R-MSG-04);
   - inserts the message and publishes `message.posted` in the same transaction.
3. A resend under a new command id is the same message (MSG-04).
4. `NotificationConsumers.OnMessagePosted` names the depot, the vehicle and the outlets reached. Routing version 4 tells the dispatcher of every message, and the loaders, the driver and the stores only of theirs (R-NOT-14). The author is never told (R-NOT-07).

**A report arrives from an event.** Issues raises an issue from a shortfall, a fault, a road disruption, a failed delivery or a receipt dispute.
- `OnIssueRaised` finds the trip through the issue's subjects: the trip, then the delivery, the order, then the vehicle's thread that day.
- It posts one report for the dispatcher alone, keyed by the event id (R-MSG-03, R-MSG-05, MSG-05).
- It publishes nothing, because the issue already notified.

**Voice.**
1. The phone records up to two minutes (`recorder.ts`).
2. It uploads the audio to `PUT /api/threads/{id}/voice/{voiceNoteId}`.
3. It then posts the message that carries the voice note.
4. The audio is served only to who may read the message (R-MSG-06, MSG-09).
5. Offline, the audio is kept with `saveUpload` and the message with `enqueue(..., waitsFor)`; the page's queue and the service worker send neither the message nor anything after it until the audio is up.
6. The audio is kept 400 days (P-33); `VoiceRetentionJob` then clears it nightly, and the message stays (MSG-11).
7. The site lets its own pages use the microphone (`Permissions-Policy` in `deploy/vps/nginx/snippets/site.conf`) and play a recording before it is sent (`media-src 'self' blob:` in `frontend/next.config.mjs` and `nginx/templates/waypoint.conf.template`).

**The dispatcher.**
- Live's timeline reads `/api/threads/reports` for each depot. Each report is the pulsing red sign at its time, on the run whose trip it is. Clicking the sign opens `ThreadSheet` scrolled to that report.
- The bell lists every message. Reply opens its thread.
- The trip page's "Send an update" posts to the driver, the store expected next, or everyone, starting from the expected arrival.
- "Notify store" on a Needs you card or the map panel opens the thread written to that store with the new expected arrival. "Voice" opens it with the recorder.
- Calls stay disabled.

**The driver.**
- Messages, with a count of new ones, opens the trip's thread full screen. The thread is kept on the phone, so it opens with no signal.
- Messages and reports, typed or spoken, go through `queuedSender`: sent now, or kept on the phone and shown as waiting. When the signal returns the audio is uploaded first and the message waits for it (MSG-10).

**The loader and the store.**
- The loader's load sheet opens the thread in a sheet. The store opens it from a message notification or from a delivery's Message.
- Both write to the dispatcher alone, may make it a report, and keep what they write with no signal, as the driver does.
- On a shared loader device a message names the loader who entered their PIN, and the server writes it as them (MSG-12). The thread reads in the loader's language.

## Run and verify locally

1. `scripts/dev.sh` (applies the three migrations).
2. Publish a plan as a dispatcher.
3. As the loader, open the trip's load sheet, then Messages, and report a loading shortfall.
4. As the dispatcher, on Live and then Timeline, the run shows the red sign. Open it and write `@driver ...`, then to everyone.
5. As the driver, see the count on Messages, reply, and report by voice.
6. As the store manager, the notification opens the thread with only what is for the store.

Tests:
- `mvn test -Dtest='MessagePolicyTest,MessagingIntegrationTest,ModuleBoundaryTest,EventCatalogueTest'`. The integration test needs PostgreSQL; check the report says it ran, not skipped.
- From `frontend/`: `npm test`, which includes `messaging-thread.test.ts` and `notifications-inbox.test.ts`.
- The browser specs:
  - `trip-messages.spec.ts` (dispatcher);
  - `messages.spec.ts` in the driver, loader and store suites;
  - `e2e-driver/voice.spec.ts` records real audio with Chromium's fake microphone, online and offline.
- On a phone: record a voice note, play it back, send it; then again in flight mode, and turn it off.

## Decisions and where they are recorded

- The model: ADR-004.
- The module's contract: MODULES.md section 13.
- Rules: R-MSG-01 to R-MSG-06 and R-NOT-14 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md).
- Edge cases: MSG-01 to MSG-10 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md).
- The log entries of 2026-10-04 in [the log](../../development-docs/log/tharushaudana.md).

## Known gaps

- Threads for issues, orders and deliveries: the rest of #136.
- What each outlet was told is not recorded per stop on the trip page.
- Calls have no backend.
