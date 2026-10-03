# Live map: reference geo checkpoint

Issue #161 remains open. This checkpoint adds the geographic reference foundation; it does not
capture driver positions or render any of the three role maps.

## What is implemented

- `data/General Data/geo_points.csv`: two approximate depot locality points and 12 district centroids.
  Per-row source links and `data/provenance.json` identify sources, the fixed boundary revision,
  checksum, licence and equal-area centroid calculation. No outlet coordinates are fabricated.
- `referencedata/domain/GeoPoint.java` and `GeoReference.java`: pure R-REF-02 validation for ranges,
  stored precision, known unique keys, required depot/district coverage and provenance. An absent
  outlet row resolves to the exact same district point with `district` precision.
- `referencedata/infrastructure/GeoCsvReader.java`: strict six-column parsing. `CsvReferenceImporter`
  attaches validated points before publication and includes the file in the content hash.
- `migrations/20261003T1800_referencedata_geo.sql`: nullable coordinates and precision checks.
  `ReferenceVersionWriter` persists the locations; `ReferenceVersionReader` reloads them. Existing
  snapshots keep absent locations, and the store's detail overlay preserves imported coordinates.
- `referencedata/application/ImportReferenceDataHandler.java`: rejects invalid geo before writing,
  with a validation error and the existing rejected-import metric. Publication still uses the one
  existing transaction, so the version pointer, rows and content hash move together.
- `ReferenceViews` adds optional locations and depot/district views; `ReferenceQuery` exposes
  versioned internal depot/district lookups. Existing outlet REST reads carry the additive location.
  `frontend/src/shared/domain/referencedata.ts` mirrors the contracts. New map endpoints wait for
  the role implementation and its scope tests.

Java paths above are under `backend/src/main/java/com/waypoint/dispatch/`.

## Verify and run

Run `migrate` then `import-reference` explicitly as described in
[development.md](../../development-docs/development.md). Custom data directories must include
`geo_points.csv`; a previous dataset without it will now be rejected for new imports. Applications
can still read historical snapshots with no location.

From `backend/`, run `mvn test -Dtest=GeoReferenceTest,GeoImportTest,FoundationIntegrationTest`.
The foundation integration test requires isolated PostgreSQL, and checks publication, identical
re-import, rejected missing-district data, exact outlet publication and historical version stability.
`GeoReferenceTest` also protects locations from the store detail overlay. Never treat skipped
integration tests as successful database verification.

Rules and edge cases are in [R-REF-02](../../architecture/RULES-AND-POLICIES.md) and
[REF-04 to REF-06](../../architecture/EDGE-CASES.md); coordinate limitations are in
[A-11](../../architecture/ASSUMPTIONS.md). The [PLAN](PLAN.md) records all D1-D7 decisions,
including the product's 2026-10-03 approval of the 30-day full-trail retention window.

## Local evidence (2026-10-03)

`mvn verify`: 826 tests passed, zero skips, using a throwaway PostgreSQL 16.15 cluster under `/tmp`.
Docker was unavailable; no application database was used. The first full run encountered the existing
random-date assignment collision in `ReceiptHandoverIntegrationTest`; the isolated rerun and second
full suite passed without changing that fixture. The official allocation validator passed.
Frontend: 109 Node tests, typecheck and production build passed. No role screen changed, so role
browser suites and screenshots belong to the later UI checkpoints. This work is local, not deployed.

## Position policy checkpoint

`execution/domain/PositionFix.java` and `PositionPolicy.java` add tested pure rules for batches,
Sri Lanka bounds, five-minute clock skew, ordered offline trails, quality, exact-fix deduplication and
ten-minute offline status. Stationary heartbeats survive. These are domain building blocks only:
no GPS command, endpoint or capture is enabled. `platform/audit/AuditRedactor.java` now removes
whole points arrays and nested coordinates. The tests are `PositionPolicyTest` and `AuditRedactorTest`. Final `mvn verify` passed 833 tests
with no failures, errors or skips on the isolated PostgreSQL instance.

## Remaining on #161

Positions command, RLS and retention; durable driver capture; tile proxy and shared Leaflet map;
dispatcher, store and driver screens; Figma comparisons and role browser verification. Depot points
are locality approximations, and exact outlet coordinates still require real supplied data. The
existing screen comments about missing maps remain until the corresponding screens are built.
