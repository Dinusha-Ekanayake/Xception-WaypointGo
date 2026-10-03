# Live map implementation plan

Spec: [issue #161](https://github.com/kavindamihiran/Xception-WaypointGo/issues/161).
Goal: dispatcher fleet visibility, store delivery tracking and an exact-location driver map.
Execution: implement sequentially on `feat/161-live-map`, starting from `origin/dev` at `93fa8a4`.

## Current state and ownership

Reference imports publish immutable snapshots through `CsvReferenceImporter`, `ReferenceValidator`,
`ReferenceVersionWriter` and `ReferenceVersionReader`. Existing outlet coordinates are nullable and
unpopulated. Depot and district views do not yet exist in `ReferenceViews`; introduce them additively.
Execution owns run sheets but has no GPS stream. All three role screens currently omit maps.
The map must follow the data, with no fabricated outlet or vehicle positions.

- Reference domain owns geographic validation and fallback precision. Infrastructure parses CSV and
  persists snapshots; application retains the atomic publish transaction and authorization.
- Execution domain owns fix acceptance and offline status. Application owns commands, reads and
  retention. Execution projections must support store scope without querying Planning tables.
- Shared UI owns a client-only Leaflet renderer and no role data fetching. Each role owns its reads,
  selection, permission prompts and offline behavior. Next owns the same-origin tile proxy.

## Decisions

D1: tracked `geo_points.csv` with cited provenance, required depot and district coverage, optional
real outlet points. Resolve missing outlet points to their district centroid at publication. Never jitter.
D2: foreground phone geolocation while a run is open, using the driver's durable offline queue.
D3: Leaflet 1.9 without a React wrapper; server-side configured tiles through `/map-tiles/`, Sri Lanka
bounds, zoom 5-17, unchanged CSP, attribution and visible no-base-layer fallback.
D4: external navigation and Open map only for exact outlet coordinates.
D5: visible, online polling every 15 seconds; #147 can replace the trigger later.
D6: actual driven points, straight dashed planned legs; no road routing engine.
D7: full trails through service day plus 30 days, then one point per stop event.
**Product approved D7 on 2026-10-03 in the implementation conversation.**
Other outlets on a store map show stop numbers only. Store position access ends when its pending
stop ends, enforced in SQL and RLS. Dispatcher scope is depot; driver scope is assigned vehicle/date.

`RecordPositions` is an append-only create command: use the existing command-bus create convention,
not an invented shared vehicle row version. Before implementing it, verify that convention against
current create handlers, including offline replay. Retention conflicts with INSERT-only raw points:
use a separately privileged, audited retention path, preserve stop evidence, and test that the normal
Execution role cannot modify or delete raw points. Do not weaken normal role grants for the job.

## Delivery checkpoints

1. [x] Reference geo: pure validation, CSV parsing, sourced file and provenance, nullable migration,
   atomic publication, snapshot round-trip, additive contract views and frontend mirrors.
   First tests: missing district/depot, duplicate and unknown keys, bad ranges, wrong precision,
   absent provenance, exact outlet and identical district fallback; then publish/reimport integration.
2. [ ] Positions backend: domain fix rules; command and catalogue; SQL-scoped reads and RLS;
   idempotency, denied scope, concurrent batch and retention integration tests; metrics and rule rows.
3. [ ] Tiles and shared map: validate tile coordinates before fetching, bound cache and requests,
   client-only Leaflet lifecycle, accessible markers, clustering and failure notice; unit/browser tests.
4. [ ] Driver: foreground recorder and durable batching, permission denial, reload/replay, exact-only
   route map and navigation; driver browser suite.
5. [ ] Dispatcher: map/timeline preference, depot/status filters, selection drawer, stale positions
   and conflict trail; dispatcher browser suite.
6. [ ] Store: scoped live card, numbered other stops, approximate labels, stale state, desktop/mobile
   and both themes; store browser suite.
7. [ ] Closeout: screenshots against the issue's Figma nodes, WALKTHROUGH, current STATUS and full checks.

## Verification and risks

Run domain tests first and the module boundary test before cross-module imports. For data publication,
use an isolated PostgreSQL database and inspect skips; a skipped integration suite is not evidence.
Run `mvn verify`, frontend Node tests, typecheck and build, then the affected role browser suites once
UI exists. No deployment or issue closure is part of a local checkpoint.

Public coordinate provenance must describe the actual point (district centroid versus city centre).
Never substitute a city centre and call it a computed district centroid. Historical reference versions
remain valid with absent coordinates. A store detail override must preserve the imported location.
GPS capture must not retain fixes across sign-out, and low-quality fixes never advance last-seen time.
