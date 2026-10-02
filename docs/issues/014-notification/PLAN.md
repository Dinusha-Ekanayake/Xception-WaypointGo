# Issue #14: Notification module (`notification` schema), plan

Written before code, per AGENTS.md "Issue Documents". What was actually built goes in `WALKTHROUGH.md`.

## Progress (handoff)

Branch `14-notification`, from `dev` at `adc8369`. Backend only: the inbox component, push opt-in and service worker handlers are placed by each role UI (comment on #14: no shared notification UI). The one frontend change is the types-only contract mirror.

| Step | Status | Notes |
| --- | --- | --- |
| 0 Plan | done | this file |
| 1 Schema and catalogue | done | `20261002T2000_notification_tables.sql`, `20261002T2001_iam_notification_actions_implemented.sql` |
| 2 Domain | done | routing policy, templates, delivery state machine, web push crypto |
| 3 Consumers, inbox, commands, reads, SSE | done | |
| 4 Push delivery job and gateway | done | |
| 5 Tests | written | domain and crypto run green; database integration tests need a reachable test database |
| 6 Docs closeout | done | [WALKTHROUGH.md](WALKTHROUGH.md), registers, STATUS, log |

## Where `dev` stood

- **Already exists:**
  - `notification/contract` (`NotificationCommands`, `NotificationQuery`, `NotificationViews`) and its TypeScript mirror `frontend/src/shared/domain/notification.ts`;
  - the `notification` schema and the `waypoint_notification` role (`20260930T1200`), with insert on `integration.consumed_events` (`20261001T0100`);
  - catalogue rows `notification:Read`, `MarkRead`, `Subscribe`, `Unsubscribe`, all `implemented = false`;
  - `notification:*` with `Resource: *` in the dispatcher, loader, driver and store manager policies (`20260930T1201`);
  - `IdentityQuery.recipientsFor(role, scopeType, scopeId)`, written for this module.
- **Not built:** every table, every consumer, push of any kind.
- **Events:** 15 of the 17 events in the MODULES section 9 matrix are published today. `vehicle.status_changed` is not published by Reference, and `issue.resolved` has no publisher.

## Which layer owns each dependency

| Dependency | Owner | Resolution |
| --- | --- | --- |
| Who holds a role at a depot, an outlet or a vehicle | Identity | `IdentityQuery.recipientsFor`. This module never reads `iam` tables |
| A vehicle's driver on a future service date | Identity | Additive overload `recipientsFor(role, scopeType, scopeId, LocalDate on)` |
| Outlet of an order | Ordering | `OrderQuery.order(orderId)` |
| Depot of an outlet or a vehicle | Reference | `ReferenceQuery.outlet`, `ReferenceQuery.vehicle` |
| Event delivery | Platform (#6) | `EventSubscriber` beans; idempotent by `(consumer, event_id)`, and by `UNIQUE (event_id, recipient_user_id)` here |
| Retry timing | Platform scheduler | `ScheduledJob` `notification.push` |
| A browser's push service | Outside the process | Port `PushGateway`, adapter `WebPushGateway` |

## Decisions

1. **The routing matrix is data.** `notification.routing_rules`, versioned by `rule_version`, with one current version in `notification.routing_versions`. Seeded by migration and read by the pure `NotificationPolicy`. The module role may only read it. There is no admin edit API in this issue. It lives here, not as a Planning policy kind.
2. **Retention: kept forever.** Operational records are never deleted, and module roles hold no DELETE. The inbox is keyset paged, so old rows cost a reader nothing. Recorded in ASSUMPTIONS.
3. **Driver push is trip-level only:** `plan.published`, `plan.revised`, `trip.released`, all of which happen before departure. Anything else reaching a driver is inbox only. No quiet hours.
4. **`notification:MarkAllRead(upTo)` is added**, and `MarkRead` requires ids. Both are set-once (`read_at = coalesce(read_at, now)`): read state cannot go back to unread, so there is no lost update and no `expectedVersion`. A replayed command is answered from the bus receipt. `upTo` keeps a notification that arrived after the click unread.
5. **Web push is written here, behind a port**, from RFC 8291 (aes128gcm) and RFC 8292 (VAPID, ES256) on JDK crypto and `java.net.http`. The common Java library pulls in `bcprov-jdk15on`, which clashes with the `jdk18on` already on the classpath. Tested against the RFC 8291 appendix vector.
6. **The person who caused an event is not notified of it** (the envelope's actor is excluded).
7. **No VAPID keys means push is off, visibly:** nothing is queued, `GET /api/notifications/push-config` says why, and the gauge `waypoint.notification.push_enabled` reads 0. One key without the other refuses to start.
8. **Live badge over SSE** (`GET /api/notifications/stream`): the unread count on connect, on each new notification or read, and on a 30 s re-check, so a signal raised on another instance still arrives. The client's polling fallback and "live updates paused" state belong to the role UIs.
9. **Another person's inbox cannot be named.** Reads take no user id. `GET /api/notifications?user=<id>` for someone else is `403` plus an audit row, so the refusal is recorded rather than silently answered with the caller's own inbox.

## Routing matrix, version 1

| Event | Recipients | Push |
| --- | --- | --- |
| `order.deferred` | store manager of the outlet | yes |
| `order.auto_deferred` | store manager of the outlet | yes |
| `order.unservable` | store manager of the outlet; dispatchers of its depot | yes |
| `warehouse.order_status_changed` (`insufficient`, `expired` only) | store manager of the order's outlet | yes |
| `plan.published`, `plan.revised` | loaders of the depot; driver of each trip's vehicle on the service date | yes |
| `trip.released` | driver of the vehicle on the service date; dispatchers when there is none (LOD-05) | yes |
| `loading.shortfall` | dispatchers of the depot | yes |
| `delivery.started`, `delivery.completed` | store manager of the outlet | no |
| `delivery.failed` | dispatchers of the depot; store manager of the outlet | yes |
| `eta.changed` | store manager of the outlet; dispatchers of its depot | yes |
| `issue.raised` | dispatchers of the depot; store manager of the outlet when named | yes |
| `issue.escalated` | dispatchers of the depot | yes |
| `vehicle.fault_reported`, `road.disruption_reported`, `receipt.disputed` | dispatchers of the depot | yes |
| `vehicle.status_changed` | dispatchers of the vehicle's depot | no |

Not routed, because the person acted or nobody needs to: `order.placed`, `order.amended`, `order.cancelled`, `loading.started`, `receipt.confirmed`, `receipt.auto_closed`, `issue.resolved`, `reference.version_published`, `calendar.overridden`.

## Tables

- `notification.routing_versions`, `notification.routing_rules`: read only to the module.
- `notification.notifications`: one per recipient per event, `UNIQUE (event_id, recipient_user_id)`.
- `notification.deliveries`: one per channel attempt target, `in_app` written `delivered`, `push` from `pending` through `sent`, `failed` or `dead`.
- `notification.push_subscriptions`: one per browser endpoint, `active`, `unsubscribed` or `expired`, never deleted.
- Push attempts, backoff and TTL are typed configuration (`app.push.*`, P-27), not a table: they are deployment settings like the relay's.

Row-level security: a person sees their own rows; the process sees all.

## PR breakdown

One pull request into `dev`, one commit per step above.

## Known gaps, owned elsewhere

- `vehicle.status_changed` is not published (Reference).
- `trip.released` carries no dock, so the dock half of R-EXE-08 waits on Loading.
- `order.deferred` carries no next planned date.
- No admin API to publish a routing version.
- The inbox component, push opt-in and service worker handlers (#18 to #21).
