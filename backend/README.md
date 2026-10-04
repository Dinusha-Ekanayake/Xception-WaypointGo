# Waypoint Dispatch backend

Spring Boot 3.4, Java 17, PostgreSQL 16. Package `com.waypoint.dispatch`, organised by business capability: `shared/` is the framework-free kernel, `platform/` holds config, the `Database` seam, web plumbing and telemetry, and each module has `contract/`, `domain/`, `application/`, `infrastructure/` and `web/`. Built: `identity`, `referencedata`, `ordering`, `planning`, `loading`, `execution`, `receipt`, `issues`, `warehouse`, `sync`. Contract only: `notification`, `intelligence`. Module contracts are in [MODULES.md](../docs/architecture/MODULES.md); what each still lacks is in [STATUS.md](../docs/development-docs/STATUS.md).

## Local startup

```sh
docker compose up -d db                 # from the repo root
export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
mvn spring-boot:run -Dspring-boot.run.arguments=migrate
mvn spring-boot:run -Dspring-boot.run.arguments=import-reference
ACCOUNT_EMAIL=admin@waypoint.local ACCOUNT_NAME=Admin ACCOUNT_PASSWORD=local-testing-only ACCOUNT_ROLE=admin \
  mvn spring-boot:run -Dspring-boot.run.arguments=account-create
mvn spring-boot:run                     # serve on :8080
```

Configuration and every environment variable: [README](../README.md#configuration) and `src/main/resources/application.properties`.

## HTTP surface

| Path | Purpose |
| --- | --- |
| `POST /api/session`, `GET /api/session`, `POST /api/session/end` | Sign in (opaque cookie), who am I, sign out |
| `POST /api/commands` | Every write: command id, kind, `expectedVersion`, payload. Idempotent per command id |
| `GET /api/accounts`, `/api/accounts/{id}`, `/api/accounts/driver-assignments` | Account reads, keyset paginated |
| `GET /api/policies`, `POST/PUT/DELETE /api/policies/...` | Policy administration |
| `GET /api/reference/version`, `/outlets`, `/vehicles`, `/calendar/{date}` | Reference data reads |
| `GET /api/devices`, `GET /api/session/crew`, `POST/DELETE /api/session/operator`, `POST /api/session/operator/offline` | Registered devices, and the loader's PIN switch on a shared dock device |
| `GET /api/orders`, `/{id}`, `/{id}/timeline`, `/day`, `/demand`, `/delivery-date` | Ordering reads |
| `GET /api/plans/published`, `/draft`, `/{id}`, `/deferrals`, `/fuel`, `/preview/assignments`, `/preview/placements`, `/preview/interchange` | Planning reads and previews |
| `GET /api/loading/trips`, `/trips/{id}/manifest`, `/shortfalls` | Loading reads |
| `GET /api/execution/run-sheets`, `/vehicles`, `/deliveries`, `/deliveries/{id}`, `/deliveries/{id}/proof`, `/attachments/{id}/content` | Execution reads and proof |
| `GET /api/receipts/pending`, `/{orderId}`, `/{orderId}/custody` | Receipt reads and the custody chain |
| `GET /api/issues`, `/by-subject`, `/{id}`, `/{id}/history` | Issue reads |
| `GET /api/warehouse/catalogue`, `/catalogue/status`, `/catalogue/{productId}` | The cached product catalogue and its age |
| `POST /api/sync`, `GET /api/sync` | Batch ingest of commands queued offline, and their outcomes |
| `GET /api/platform/events/dead` | Dead-lettered events, replayed with `platform:ReplayEvent` |
| `GET /health/liveness`, `/health/readiness`, `/prometheus` | Probes and metrics |

Every state change in every module is a command through `POST /api/commands`; the controllers above only read. The exact parameters are in each `<module>/web/*Controller.java`.

Lists return `{items, nextCursor}`; pass `nextCursor` back as `after`, with an optional `limit` (default 50, max 200). `nextCursor` is `null` on the last page. Every error is `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId` and `violations: [{rule, field?, message}]`; clients branch on `code`, never `title`. See SYSTEM-ARCHITECTURE section 7.

## Tests

```sh
mvn verify
```

Unit and architecture tests always run. Integration tests use `TEST_DATABASE_URL` if set, otherwise a throwaway PostgreSQL container if Docker is available, otherwise they are skipped with a reason. `architecture/ModuleBoundaryTest` enforces module boundaries: run it before adding any cross-module import.

## Where things are

Paths relative to `src/main/java/com/waypoint/dispatch/`:

- `platform/db/Database.java`: the only PostgreSQL seam. `SET LOCAL ROLE` and actor per transaction, serializable, bounded counted retries.
- `platform/db/Migrator.java`: atomic, checksummed migrations under an advisory lock.
- `platform/messaging/CommandBus.java`: authorize, idempotency receipt, handler, audit, in one transaction, timed.
- `platform/web/ApiExceptionHandler.java`: the problem-details contract.
- `platform/observability/Metrics.java`: every detection signal goes through here.
- `identity/`: sessions, sign-in, accounts, policy-as-data authorization.
- `referencedata/`: reference import, validation, versioned snapshots and the operating calendar.
- `platform/messaging/OutboxRelay.java`: delivers published events to every `EventSubscriber`, at least once, each in its own transaction.
- `platform/scheduling/ScheduledJobRunner.java`: runs each module's `ScheduledJob` on its cron under an advisory-lock lease.
- `<module>/application/*Handler.java`: one class per command. `<module>/application/*Consumers.java`: the events a module reacts to.
- `docs/issues/<NNN>-<module>/WALKTHROUGH.md`: each module explained flow by flow, with how to run and verify it.
