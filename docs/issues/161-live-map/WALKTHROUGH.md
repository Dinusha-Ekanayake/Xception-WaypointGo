# Live map: walkthrough

Issue #161: a live map for the dispatcher, the store manager and the driver. Decisions D1 to D7 are in
the [PLAN](PLAN.md). Java paths are under `backend/src/main/java/com/waypoint/dispatch/`; frontend
paths under `frontend/`.

## What is built, layer by layer

**Reference geography** (merged in #163). `data/General Data/geo_points.csv` carries two approximate
depot locality points and 12 district centroids, each sourced; `data/provenance.json` records sources,
boundary revision, checksum, licence and the centroid method. `referencedata/domain/GeoPoint` and
`GeoReference` validate (R-REF-02); an outlet with no row gets its district centroid with `district`
precision at publish time. No outlet point is invented. `GET /api/reference/depots/{depotCode}`
serves a depot's location to the dispatcher map under depot scope.

**Positions, database.** `migrations/20261003T2000_execution_vehicle_positions.sql`:
`execution.vehicle_positions`, append only (the execution role has `SELECT, INSERT`), forced
row-level security, `UNIQUE (vehicle_id, recorded_at)`, coordinate and sensor checks, and the
`delivery:RecordPositions` catalogue row (the driver policy already allows `delivery:*`). Two
`SECURITY DEFINER` functions do retention: `execution.thin_vehicle_positions` (execution role only)
and `sync.redact_position_payloads` (sync role only).

**Positions, domain.** `execution/domain/PositionFix` and `PositionPolicy` (R-EXE-18, R-EXE-19).

**Positions, application.**
- `execution/application/RecordPositionsHandler`: parses, applies `PositionPolicy`, checks each
  point's service date with `app.actor_drives`, ties points to the trip only when the trip is this
  vehicle's on that date, inserts. No event. Metrics: `positions_accepted`, `positions_duplicate`,
  `positions_rejected{reason}`, `positions_lag`.
- `execution/application/PositionsQuery`: last good fix per vehicle for a depot or an outlet, with
  `offline` from `PositionPolicy.isOffline`, and a trip's trail on a keyset cursor. Out-of-scope
  depot, outlet or trip is an audited `403`.
- `execution/application/PositionRetentionJob` (02:45) and `sync/application/PositionPayloadRetentionJob`
  (02:50): R-EXE-21.
- `execution/infrastructure/JdbcPositions`, `execution/web/PositionsController`.
- `platform/audit/AuditRedactor` strips points and coordinates from audit snapshots (#163).

**Tiles and the shared map.**
- `src/app-shell/mapTiles.ts` behind `app/map-tiles/[z]/[x]/[y]/route.ts`: fetches from
  `MAP_TILE_URL` server side, Sri Lanka at zoom 5-17 only, a week's cache header. The CSP is unchanged.
  `nginx/templates/waypoint.conf.template` caches `/map-tiles/`; compose passes `MAP_TILE_URL`.
- `src/shared/ui/map/`: `geo.ts` (pure: tile bounds, distance, the recorder's keep rule, grid
  clusterer, eight headings), `MapCanvas.tsx` (the only Leaflet file; our own markers, zoom,
  attribution and "Base map unavailable"), `index.tsx` (client-only `LiveMap` via `next/dynamic`
  with `ssr: false`, `MapLegend`), `types.ts`. Colour `--color-go-offline` added to `theme.css`.

**Driver.** `src/roles/driver/data/position.ts` (`usePositionRecorder`: asks once, records while a
stop is still to do, keeps a fix every 30 s or 150 m, flushes every 60 s or 20 points through the
offline queue, flushes on stop) and `data/points.ts` (pure shaping). `screens/RouteMap.tsx`: own
position, trail, dashed leg, store pin, Navigate hand-off. `screens/Route.tsx` shows Open map only
for an exact store location. Declining shows "Location off · the dispatcher sees your stops only"
with Turn on.

**Dispatcher.** `data/live.ts` `mapStatus`; `data/useDay.ts` `usePositions` (15 s) and `useDepots`;
`screens/LiveMap.tsx`: depot and status filters, clustering, the selected vehicle's panel (next stop,
window, ETA, stops, "Last seen", trail point count) and its trail drawn; vehicles with no fix listed
as "No live location · stops only". `screens/Live.tsx` has the Map / Timeline toggle, remembered per
browser.

**Store manager.** `screens/LiveMapCard.tsx` on the Track screen: this vehicle, its trail, the dashed
leg to this store, "Live" or "Last seen", and "Approximate · <district>" on a district location.
Other stores on the trip are not drawn.

## Flows end to end

1. **Recording.** Driver starts the run and shares location; `watchPosition` feeds the recorder;
   a batch becomes a `delivery:RecordPositions` command in the IndexedDB queue; the queue drains to
   `POST /api/sync`; Sync replays it through the command bus; the handler inserts under
   `waypoint_execution` with RLS checking `app.actor_drives`.
2. **Reading.** Dispatcher Live polls `/api/execution/positions?depot=` every 15 s; the store card
   polls `?outlet=`. RLS returns only rows in scope; the store's rows vanish once its stops are done.
3. **Retention.** Nightly, the execution job thins trips older than 30 days past their service date
   to the fixes nearest each arrival and completion; the sync job removes points from stored copies.

## Run and verify locally

`migrate`, then `import-reference` (see [development.md](../../development-docs/development.md)).
Set `MAP_TILE_URL` in `frontend/.env.local` to see a base map; without it the maps say so.

- `mvn verify` from `backend/` with `TEST_DATABASE_URL` set: all tests pass, none skipped.
- Position tests: `mvn test -Dtest='ExecutionIntegrationTest,PositionPolicyTest'`.
- `npm test`, `npm run typecheck`, `npm run build` from `frontend/`.
- Browser suites, by hand: dispatcher (`live-map.spec.ts`), driver (`location.spec.ts`, including
  offline points surviving a reload), store (`track-map.spec.ts`).

## Decisions and where they are recorded

R-EXE-18 to R-EXE-21 and R-REF-02 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md);
EXE-LOC-01 to EXE-LOC-10 and REF-04 to REF-06 in [EDGE-CASES](../../architecture/EDGE-CASES.md);
A-11, A-42 and P-31 in [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md); module contracts in
[MODULES](../../architecture/MODULES.md).

## Known gaps

- Screenshots against the Figma frames were not taken; the layout follows the issue's visual spec.
- No road-snapped route (D6) and no turn by turn inside the app (D4), by design.
- No age check on a position the server has not flagged offline: an old fix still reads "Live".
- Map markers keep fixed light colours (white labels, dark ink), so the driver's dark theme shows light markers.
- The road conditions chip (Figma 05) and the 05c "Notify store" confirmation are not on the map:
  no source is wired for either. The 05e conflict modal does not exist yet; the selected vehicle's
  trail and point count stand in for "Review on map".
- ~~Push updates replace polling when #147 lands.~~ Done by the live tracking addendum below.
- Exact outlet coordinates need real data from Waypoint; until then Navigate is never offered.

## Addendum 2026-10-05: live tracking (R-EXE-22, R-EXE-23)

What changed, end to end: phone → queue → sync → handler → signals → stream → map.

1. **Phone** (`frontend/src/roles/driver/data/position.ts`, `recording.ts`, `useRecording.ts`). The
   watch keeps the newest fix; a five-second tick keeps it when it is new (`takeSample` in
   `shared/ui/map/geo.ts`) and asks the phone outright when the watch has gone quiet. Points are queued
   and sent every five seconds. `recordingTrip` decides when: from the first started stop (Start run)
   to within 200 m of the depot after the last stop, or four hours after it; a trip back at the depot
   is remembered on the phone so a reload does not restart it. The screen is kept awake while
   recording. No driver screen changed.
2. **Server** (`execution/application/RecordPositionsHandler.java`, `PositionSignals.java`). After the
   batch commits, the depot and day are signalled; within a second each watcher is re-read as itself
   through `PositionsQuery` (scope and RLS as on the GET) and sent a `positions` event. Every 20 s
   every watcher is re-sent regardless. `GET /api/execution/positions/stream` checks scope before it
   opens (`403` and audit otherwise). The Next proxy and both nginx configurations pass it unbuffered.
3. **Heading** (`execution/domain/PositionPolicy.travelHeading`). The latest view reads the last eight
   good fixes per vehicle (`JdbcPositions`, window function) and faces the truck the way it moved;
   jitter under 15 m keeps the previous direction.
4. **Map** (`shared/live/usePositionStream.ts`, `useTripTrail.ts`, `shared/ui/map/MapCanvas.tsx`).
   Positions arrive on the stream, polled every minute as a net, every 15 s after 45 s of silence with
   "Live updates paused" on the map. Markers are kept by id and glide to each new fix. A chosen
   vehicle's run path is read once and then only `since` its newest point (an additive parameter of the
   trail endpoint), reaches the truck between reads, starts at a marked trip start, and is fitted into
   view. Trucks are the Figma top-down symbols in the status colour, turned by the exact heading; the
   depot is a small GO pill with its name beside it, grey outside the depot filter.

Verify: `PositionPolicyTest`, `ExecutionIntegrationTest` (heading, stream, scope), `tests/live-map.test.ts`,
`tests/driver-recording.test.ts`, `e2e-dispatcher/live-map.spec.ts` (symbols, path on select, paused).
Cases EXE-LOC-11 to EXE-LOC-14 in [EDGE-CASES](../../architecture/EDGE-CASES.md).
