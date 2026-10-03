# Notifications inbox and last sync in each role UI: walkthrough

Issue [#118](https://github.com/kavindamihiran/Xception-WaypointGo/issues/118), branch `feat/118-notifications-inbox`. The [plan](PLAN.md) has the decisions.

## What is built

| Layer | Files |
| --- | --- |
| Migration | `migrations/20261003T1500_notification_routing_v2.sql` |
| Notification | `NotificationConsumers.OnTripReleased` (outlet targets per stop), `NotificationController` (no-buffering header) |
| Transport | `frontend/app/api/[...path]/route.ts` (`STREAMS`), `deploy/vps/nginx/snippets/site.conf`, `nginx/templates/waypoint.conf.template` |
| Shared hook | `frontend/src/shared/notifications/inbox.ts`, `useInbox.ts` |
| Dispatcher | `roles/dispatcher/inbox.tsx` (one inbox for every screen), `NotificationsPanel.tsx`, `PageHeader.tsx`, `screens/Overview.tsx`; `shared/ui` `ConnectionStatus` can be a button |
| Loader | `roles/loader/TopBar.tsx`, `screens/Notifications.tsx`, `index.tsx`, `screens/LoadSheet.tsx` (reload on sync), `data/strings.ts` |
| Store manager | `roles/store/TopBar.tsx`, `screens/Notifications.tsx`, `screens/Home.tsx`, `index.tsx` |

**Figma frames matched** (file `WSYlQF5chNlrln207gOEWr`, read only; each compared side by side at its size):
- **Dispatcher:**
  - panel `189:23606`;
  - Overview card `189:10739`;
  - header with "Synced" `13:17692`.
- **Loader:**
  - bell in the phone name pill `11:78720`;
  - tablet bar `11:57925`;
  - the loader has no list frame, so the store drawer's rows are used.
- **Store manager:**
  - drawer `11:117503`;
  - phone sheet `11:125924`;
  - Home with the card `11:112937`.
  - The bell is plain on desktop (the card shows the count) and has a dot on phones.

## Flows

**A trip is released.** `trip.released` reaches `OnTripReleased`, which routes it to:
- the driver of the vehicle;
- the dispatcher, only if no driver was reached (LOD-05);
- the depot's loaders, except the one who released it (R-NOT-10);
- each stop's outlet with "You're stop {n} of {m}. Expected {HH:mm}." (R-NOT-11).

**The live badge.**
- `useInbox` opens `EventSource('/api/notifications/stream')` and asks `/unread-count` once.
- The server sends the count on connect, on change and every 25 s; the browser reconnects a dropped stream by itself.
- After 40 s of silence the inbox reads "Live updates paused" and polls `/unread-count` every 30 s until the stream speaks again (NOT-09, NOT-10).
- The list reloads when the count moves.

**Reading.**
- Opening a row sends `notification:MarkRead` for it and shows its subject where the role has a screen:
  - store: the order, the deliveries or the issues;
  - dispatcher: issues, orders, live or vehicles.
- "Mark all as read" sends `notification:MarkAllRead` with `upTo` = when the list was opened, so later arrivals stay unread (NOT-08).
- Offline, the last list is shown with when it was saved, and reading waits for the connection.

**Last sync.**
- Loader: "Synced 02:23" sends what waits and reads the board and an open load sheet again.
- Store: "Synced 02:23 AM" sends what waits and reads everything again.
- Dispatcher (online only): "Synced 4:12 PM" reads the current screen again.

## Run and verify locally

- **Backend:** `mvn verify`; `NotificationConsumersIntegrationTest` covers releases to loaders and outlets.
- **Frontend:** `npm test` (`notifications-inbox.test.ts`).
- **Browser suites:**
  - loader: `npx playwright test -c playwright.loader.config.ts notifications.spec.ts`;
  - dispatcher: `-c playwright.dispatcher.config.ts notifications.spec.ts`;
  - store: `-c playwright.store.config.ts notifications.spec.ts`.

## Decisions recorded

- [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md): R-NOT-10 and R-NOT-11.
- [EDGE-CASES](../../architecture/EDGE-CASES.md): NOT-09 (client half) and NOT-10.
- [MODULES](../../architecture/MODULES.md) section 9: the matrix.

## Known gaps

- **Push:** opt-in and the service worker `push` / `notificationclick` handlers are not built; the inbox works without them.
- **Driver:** the feed is #21's.
- **Dispatcher Reply:** waits on messaging between roles, which does not exist.
- **Language:** the messages are composed on the server in English; the loader translates the frame around them.
