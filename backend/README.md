# Waypoint Dispatch backend

Spring Boot 3 and PostgreSQL own the REST API, authentication, planning rules, command handling and staff administration. The Next.js catch-all route in `frontend/app/api/[...path]/route.ts` proxies browser requests here. The older Node implementation remains under `frontend/lib/` for scripts and regression tests; it is not the active web API.

## Local startup

Use Java 17+, Maven and an existing PostgreSQL 16+ database. Export `DATABASE_URL` in the backend terminal. Spring does not automatically read root `.env` or `frontend/.env.local`.

For a fresh competition demo, also export `DEMO_MODE=1` and a private `SEED_PASSWORD` of at least 12 characters, then run from the repository root:

```sh
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="migrate"
mvn spring-boot:run -Dspring-boot.run.arguments="seed"
mvn spring-boot:run
```

The service listens on port 8080. Builds and normal startup never migrate or seed. Production mode rejects demo seeding; provision staff with the commands in [deployment.md](../docs/deployment.md#staff-accounts).

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | Required connection for runtime, Java migrations, seeding and account commands |
| `DATA_DIR` | Reference CSV directory, default `../data` |
| `MIGRATIONS_DIR` | Shared SQL directory, default `../migrations` |
| `PUBLIC_DIR` | Seed proof fixtures, default `../frontend/public` |
| `DEMO_MODE` / `DEMO_NOW` | Demo clock, default `0` / `2026-02-13T15:30:00+05:30` |
| `CALENDAR_FILE` | Optional production calendar override CSV, relative to `DATA_DIR` or absolute |
| `SEED_PASSWORD` | Required for demo seeding, at least 12 characters; does not rotate existing passwords |
| `COOKIE_SECURE` | Set `1` behind HTTPS |

Relative path defaults assume commands run from `backend/`. When launching the packaged JAR elsewhere, set the path variables explicitly. Java does not automatically select `DATABASE_URL_UNPOOLED`; set `DATABASE_URL` to the intended connection for each operation. The direct-URL variable is used by backup scripts and the legacy Node migration command.

## REST contract

| Method and path | Access and behavior |
| --- | --- |
| `GET /api/health` | Database connectivity check; does not validate the full workflow |
| `GET /api/state` | Session required; role-scoped snapshot, currently without pagination |
| `GET /api/assignments?day=...&order_id=...` | Dispatcher assignment preview |
| `GET /api/proof?order_id=...&image_id=...` | Session and order access required; one image as a data URL |
| `POST /api/login` | JSON `{ "email": "...", "password": "..." }`; sets an HttpOnly session cookie |
| `POST /api/logout` | Session required; send JSON `{}` |
| `POST /api/command` | Session required; JSON `{ "id": "...", "kind": "...", ... }` |

POST endpoints require a JSON object and enforce a 3.5 MB body limit. Cookies use SameSite Strict, a one-day lifetime and configurable Secure. Login attempts are limited per account in PostgreSQL. Commands validate roles, assignments and expected versions or draft revisions. Replaying the same user/command ID with the same fingerprint returns the accepted result; changed payloads under that ID are rejected.

The service uses serializable transactions with bounded retries, PBKDF2 password hashes and hashed session tokens. Shared SQL and legacy regression tests support migration continuity, but do not establish complete behavioral parity between Java and Node.

## Verification and navigation

```sh
# From backend/: Java unit tests only
mvn test
# From frontend/: includes Maven package/tests and Spring HTTP integration tests
npm run test:spring
```

The second command requires an already-created dedicated `TEST_DATABASE_URL` database. Browser tests also launch this backend against disposable schemas. See [verification.md](../docs/verification.md) for observed results and blockers.

- `domain/Planning.java`: route feasibility and allocation.
- `domain/ReferenceLoader.java`: reference CSVs and production calendar policy.
- `service/DispatchService.java`: commands, persistence, state and demo scenarios.
- `service/AccountAdmin.java`: trusted-host account lifecycle.
- `api/ApiController.java`: REST routes, request guards and cookies.
- `db/Migrator.java`: atomic migrations with checksum and advisory-lock protection.

These Java paths are relative to `src/main/java/com/waypoint/dispatch/`. Maven output under `target/` is generated and ignored.
