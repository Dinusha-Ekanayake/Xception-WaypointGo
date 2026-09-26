# Waypoint Dispatch backend (Spring Boot)

Port of the Next.js API route (`app/api/[...path]/route.ts`) plus the server
layer (`lib/service.ts`, `lib/domain.ts`, `lib/database.ts`,
`lib/scenarios.ts`, `lib/migrate.ts`) to Spring Boot 3 + PostgreSQL.

The Next.js app keeps the UI only; `/api/*` is proxied to this service
(see `frontend/app/api/[...path]/route.ts`).

## Run

```bash
cd backend
# DATABASE_URL=postgresql://waypoint:local-testing-only@localhost:5432/waypoint
mvn spring-boot:run
```

Service listens on `:8080`. Environment mirrors `.env.example`:

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | App + seed connection (pooled Neon URL also works) |
| `DATA_DIR` | Reference CSVs (default `../data`) |
| `MIGRATIONS_DIR` | SQL migrations (default `../migrations`) |
| `DEMO_MODE` / `DEMO_NOW` | Demo clock (default `0` / `2026-02-13T15:30:00+05:30`) |
| `SEED_PASSWORD` | Required by `seed`, at least 12 chars |
| `COOKIE_SECURE` | Set `1` behind HTTPS |

## Operational commands (never run on boot or on requests)

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="migrate"
DEMO_MODE=1 SEED_PASSWORD=YOUR_PRIVATE_PASSWORD mvn spring-boot:run -Dspring-boot.run.arguments="seed"
```

## API

Same contract as the Next.js backend:

- `GET /api/health`
- `GET /api/state` (session cookie)
- `GET /api/assignments?day=...&order_id=...` (dispatcher)
- `GET /api/proof?order_id=...&image_id=...`
- `POST /api/login {email, password}` (sets `session` cookie)
- `POST /api/logout`
- `POST /api/command {id, kind, ...}` (idempotent per user + command id)

Transactions run at `SERIALIZABLE` with the same retry behavior; login rate
limiting, PBKDF2 password hashes, session tokens and planning rules are
byte-for-byte compatible with the previous implementation.

Production deployment and calendar overrides: see [deployment](../docs/deployment.md). Browser and HTTP integration tests now launch this backend against disposable PostgreSQL schemas.
