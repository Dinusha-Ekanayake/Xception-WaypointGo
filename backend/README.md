# Waypoint Dispatch backend

Spring Boot 3.4, Java 17, PostgreSQL 16. Package `com.waypoint.dispatch`, organised by business capability: `shared/` is the framework-free kernel, `platform/` holds config, the `Database` seam, web plumbing and telemetry, and each module (`identity`, `referencedata`, `ordering`, `planning`, ...) has `contract/`, `domain/`, `application/`, `infrastructure/` and `web/`. Module contracts are in [MODULES.md](../docs/architecture/MODULES.md).

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
| `GET /health/liveness`, `/health/readiness`, `/prometheus` | Probes and metrics |

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
