# Issue #14: Notification module, walkthrough

What was built, and how to run and check it. The decisions and their reasons are in [PLAN.md](PLAN.md). Rules are in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md) (R-NOT-01 to 09), cases in [EDGE-CASES](../../architecture/EDGE-CASES.md) section 7b (NOT-01 to 09), and the module contract in [MODULES](../../architecture/MODULES.md) section 9.

Backend only. Each role UI places its own inbox, badge and push opt-in (comment on #14). The only frontend change is the contract mirror `frontend/src/shared/domain/notification.ts`.

## What was built, layer by layer

All under `backend/src/main/java/com/waypoint/dispatch/notification/`.

**contract**
- `NotificationCommands`: adds `MARK_ALL_READ` and `MarkAllRead(upTo)`. `MarkRead` now needs at least one id.
- `NotificationViews`: adds `UnreadCountView` and `PushConfigView`.
- `NotificationQuery` is unchanged.

**domain** (no Spring, no clock)
- `NotificationPolicy.route(event, table, recipients)`: the routing table applied to one event. It fills the templates, excludes the actor (R-NOT-07), limits a driver's push to trip-level events (R-NOT-08), applies a fallback rule only when its role reached nobody (LOD-05), and reports a scope nobody holds instead of throwing (NOT-02).
- `RoutingRule`, `RoutingTable`, `ScopeKind`, `RoutedEvent` (the event's facts and every outlet, depot or vehicle it concerns).
- `Template`: `{name}` filling. A missing fact renders as `-` and is reported.
- `Delivery`: channels, statuses, `RetryPolicy` (capped, jittered backoff), `after(result, ...)`, the outcome of one push attempt, and `classify(httpStatus)`.

**application**
- `NotificationConsumers`: 18 `EventSubscriber`s, one per routed event, named `notification.on-<event>`. Each only builds a `RoutedEvent`, looking up a missing scope through a contract (`OrderQuery`, `ReferenceQuery`).
- `Notifier`: inside the consumer's transaction, writes each notification, its `in_app` delivery (delivered on write) and a `pending` push per active subscription. It signals the live badge after commit. A redelivery writes nothing: `UNIQUE (event_id, recipient_user_id, target_key)`.
- `RoutingTables`: the current routing version, with its rules reread only when the version changes.
- `PushDeliveryJob` (`notification.push`, every 15 s): claims due pushes `FOR UPDATE SKIP LOCKED` under a lease, sends with no transaction open (R-NOT-05), and records each outcome in its own transaction.
- `NotificationHandlers`: `MarkRead`, `MarkAllRead`, `SubscribePush`, `UnsubscribePush`.
- `NotificationDataQuery`: implements `NotificationQuery`. An inbox is keyset paged on `(created_at, notification_id)`; another person's is `403` plus an audit row.
- `InboxSignals`: the listeners of the live badge, sent on a thread of their own.
- `PushGateway`: the port to a push service.

**infrastructure**
- `JdbcNotificationRepository`.
- `WebPushCrypto`: RFC 8291 `aes128gcm` encryption and an RFC 8292 VAPID ES256 token, on the JDK. Checked against the RFC 8291 appendix vector.
- `WebPushGateway`: RFC 8030 delivery with `java.net.http`. With no keys it is off, and `config()` says why.

**web**
- `NotificationController`:
  - `GET /api/notifications?after=&limit=`
  - `GET /api/notifications/unread-count`
  - `GET /api/notifications/stream` (`text/event-stream`, events named `unread`)
  - `GET /api/notifications/push-config`
- Every endpoint requires `notification:Read`. Commands go through `POST /api/commands`.

**Outside the module**
- `identity/contract/IdentityQuery.recipientsFor(role, scopeType, scopeId, LocalDate on)`: additive. It takes a vehicle's driver on the service date, not today.
- `platform/config/PushProperties` (`app.push.*`), `application.properties`, `.env.example`, `compose.yaml`, `compose.prod.yaml`: `PUSH_VAPID_PUBLIC_KEY`, `PUSH_VAPID_PRIVATE_KEY`, `PUSH_SUBJECT`, `PUSH_MAX_ATTEMPTS`.

**Migrations**
- `20261002T2000_notification_tables.sql`: the five tables, routing version 1, grants with no `DELETE` and read-only routing, and row-level security that limits a person to their own rows.
- `20261002T2001_iam_notification_actions_implemented.sql`: adds `notification:MarkAllRead` and flips the five notification actions to implemented. The role policies already grant `notification:*`.

## Flows, end to end

**An event becomes notifications.**
1. A module publishes, for example `order.deferred`, in its own transaction.
2. The relay delivers it to `notification.on-order-deferred`, as `waypoint_notification` for the system actor.
3. The consumer names the outlet and the facts.
4. `Notifier` applies routing version 1: the outlet's store manager, through `IdentityQuery`.
5. It writes the notification and its `in_app` delivery, plus a `pending` push for each of that person's active devices when push is on.
6. After commit, any badge that person has open is sent the new count.

**A push is sent.**
1. `PushDeliveryJob` claims due rows and leases them.
2. For each row it encrypts the payload to the device's keys, signs a VAPID token for the push service's origin, and posts with `TTL` and `Urgency`. A decision-now event such as a shortfall or a failed delivery is sent `high`.
3. The outcome:
   - 201 means `sent`, and push latency is recorded.
   - 404 or 410 means the delivery is `dead` and the subscription `expired` (NOT-01).
   - 429, 5xx or no answer means `failed`, with backoff.
   - Reaching the last attempt means `dead`, with the last error kept (NOT-04).

**A person reads their inbox.**
1. `GET /api/notifications` runs as that person under row-level security.
2. `notification:MarkRead` takes ids and `notification:MarkAllRead` takes `upTo`.
3. Both are set-once (R-NOT-06), and each moves the badge after commit.

**A browser subscribes.**
1. `notification:Subscribe` checks the key shapes.
2. An endpoint held by another person is retired first, as the process, because one browser pushes to one person (NOT-06).
3. Subscribing an endpoint the person already holds refreshes its keys.
4. `Unsubscribe` sets `unsubscribed`; nothing is deleted.

## Running and checking it locally

1. `scripts/dev.sh setup` (or `migrate`) applies both migrations.
2. With no VAPID keys, `GET /api/notifications/push-config` answers `{"enabled":false,...,"reason":"push is not configured on this server"}`. To try push, generate keys as in [development.md](../../development-docs/development.md#web-push-for-notifications).
3. Sign in as a store manager and cause a deferral, or any event in the matrix. Then:

   ```bash
   curl -b cookies.txt http://localhost:8080/api/notifications
   curl -b cookies.txt http://localhost:8080/api/notifications/unread-count
   curl -N -b cookies.txt http://localhost:8080/api/notifications/stream   # held open; an "unread" event per change
   ```

4. Delivery state is in `notification.deliveries`, and the metrics are `waypoint_notification_*` at `/prometheus`:
   - `created`
   - `unrouted`
   - `delivery{channel,status}`
   - `dead`
   - `push_latency`
   - `push_enabled`
   - `live_listeners`
   - `template_missing`

## Tests

| Test | What it covers | Database |
| --- | --- | --- |
| `notification/domain/NotificationPolicyTest` | Every routing behaviour: scope per role, actor excluded, unrouted, dated driver lookup, driver push limit, LOD-05 fallback, fact conditions, dedupe, missing facts | no |
| `notification/domain/DeliveryTest` | Attempt outcomes, backoff, HTTP classification, templates | no |
| `notification/infrastructure/WebPushEncryptionTest` | The RFC 8291 vector, a browser-side decrypt, the VAPID token, a mismatched key pair refused | no |
| `notification/application/NotificationConsumersIntegrationTest` | Consumers through the inbox, replay, the actor, an unmanaged outlet, depot lookup, warehouse status filter, tomorrow's driver, LOD-05, push sent, retry to dead, gone to expired, push off | yes |
| `notification/NotificationCommandIntegrationTest` | Paging, unread count, 403 plus audit for another inbox, row-level security, no `DELETE` and read-only routing, set-once marking, `MarkAllRead` cutoff, foreign id 404, device handover, subscribe and unsubscribe, push off 503, malformed keys, the live stream | yes |

`CommandPathIntegrationTest` checks that the catalogue rows say implemented, and `ModuleBoundaryTest` and `EventCatalogueTest` pass unchanged.

## Known gaps

| Gap | Owner |
| --- | --- |
| Inbox, badge, "live updates paused" state, push opt-in and service worker `push` and `notificationclick` handlers | Role UIs #18, #19, #21 |
| No admin API to publish a new routing version; a change is a migration today | a later admin issue (#22) |
| `trip.released` carries no dock, so R-EXE-08's dock half is not in the message | Loading (#10) |
| `order.deferred` carries no next planned date | Planning |
| `vehicle.status_changed` is not published; its consumer is ready and tested by direct delivery | Reference |
| The badge signal is in-process; with several instances a change made on another one arrives within the 25 s refresh | accepted (NOT-09) |
