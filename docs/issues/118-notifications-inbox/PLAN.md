# Notifications inbox and last sync in each role UI: plan

Issue [#118](https://github.com/kavindamihiran/Xception-WaypointGo/issues/118), branch `feat/118-notifications-inbox`, targeting `dev`.

## Current state

The Notification backend (#14) is built: inbox, unread count, an SSE stream of the count, push config. No role showed it:
- dispatcher: a disabled bell, and an Overview card marked Pending;
- store: a Home card marked Pending;
- loader: the bell left out on purpose.

"Synced HH:MM" was plain text in every role. Two gaps also blocked the issue's priorities:
- the store was told an arrival time only when it *changed*, never the expected arrival;
- the loader was never told of a release.

The stream was also cut every 25 s by the Next proxy, and buffered by nginx.

## Decisions (2026-10-03)

- **Loader:** hears plans published or revised (routed already), plus other loaders' releases. New rule R-NOT-10; the releaser is the actor and is excluded (R-NOT-07).
- **Store manager:** `trip.released` tells each outlet on the trip its stop number and expected arrival. New rule R-NOT-11, booklet p6.
- **Driver:** out of scope here (Isuru, #21). The shared hook is there for him.
- **No shared notification UI component**, decided on #14. A shared *data* hook, `useInbox`, and each role draws its own.
- **The dispatcher's "Reply"** (Figma) is left out: there is no messaging between roles to reply through.

## Layers

| Layer | Owner | Change |
| --- | --- | --- |
| Migration | Notification | Routing version 2 = version 1 plus the two `trip.released` rows. `FORCE ROW LEVEL SECURITY` is lifted around the writes |
| Application | Notification | `OnTripReleased` adds one outlet target per stop with `stopNumber` and `plannedArrival` |
| Web | Notification | The stream sends `X-Accel-Buffering: no` |
| Proxy, deploy | frontend, nginx | The stream escapes the 25 s proxy limit; nginx gives it an unbuffered location with a 120 s read timeout |
| Shared | frontend | `shared/notifications/inbox.ts` (pure) and `useInbox.ts` (live count, poll fallback, list, read state, offline copy) |
| Roles | dispatcher, loader, store | Bell, inbox and "Synced" as a button, each against its Figma frames |

## PR breakdown

One pull request, in commits: backend rules; transport; shared hook; loader; dispatcher; store; docs.
