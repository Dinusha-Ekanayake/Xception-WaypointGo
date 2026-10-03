# Issue #21: Driver UI (phone PWA, full offline): plan

Written before code, per AGENTS.md "Issue Documents". What was built is in [WALKTHROUGH.md](WALKTHROUGH.md). The backend is issue #12 ([walkthrough](../012-execution/WALKTHROUGH.md)).

## Where the branch stood

- `frontend/src/roles/driver/index.tsx` was a placeholder.
- The design is Figma page "12 · Driver · Mobile (393)": Home, Route (next stop), Delivery report, confirmed sheet, Run complete, No run planned, Report a problem, Sign out, each in light and dark.
- `src/shared/offline` queues commands in IndexedDB and drains them through `/api/sync`. It holds commands only: no cached reads, no binary uploads.
- `AppShell` asks the server who is signed in on every load. Offline that answer is "unreachable", so a reload with no signal locked the driver out of work already on the phone.

## Which layer owns each dependency

| Concern | Owner | Why there |
| --- | --- | --- |
| What a stop looks like with writes still on the device; which outcome a stop may take; lateness and waiting as the driver sees them | `roles/driver/data/run.ts` | Pure functions over the run sheet and the queue, tested with no browser |
| Reading, caching and writing the run | `roles/driver/data/useRun.ts`, `gateway.ts` | The only code that talks to Execution |
| Cached reads and queued uploads on the device | `shared/offline` | Every full-tier screen needs them; they sit beside the command queue |
| Carrying on with no signal | `app-shell` | The shell owns the session gate |
| Sheet, theme tokens | `shared/ui` | Shared by roles; roles never import each other |

## Decisions

1. **Every write is saved on the device first, then sent.** With nothing waiting and a connection, the command is sent at once and a rule refusal is shown immediately. Otherwise it joins the queue behind what is already there, so nothing overtakes. "Saved" is said only after IndexedDB confirms (EXE-01).
2. **The run sheet is kept on the device** each time it is read, and shown from there when the server cannot be reached. The screen says when it is showing a kept copy and how old it is.
3. **A stop is shown as the device believes it is**: the server's copy with the device's waiting commands applied, by the same transitions the server uses. Each waiting command carries the version the previous one will produce, because the server moves the version on by exactly one per command.
4. **The shell keeps a full-tier role working when the server cannot be asked.** The last session the server confirmed is remembered on the device (who, roles, scope: no credential), and used only while the server is unreachable. A `401` is still signed out, and signing out forgets it. The queue is kept either way (SEC-01).
5. **Photos are shrunk on the phone** to at most 1600 px and JPEG quality 0.8 before they are stored (open decision 1). A photo that still exceeds the server's 3 MB is refused on the phone with the fallback offered (EXE-09).
6. **Proof artifacts travel on their own** from an `uploads` store, before and independently of the queue; the server accepts either order. Each stop shows whether its proof has reached the server.
7. **The driver sees a refused write and decides** (open decision 2): the shell's review list, already built in #15, with discard and retry. Nothing is resent or dropped automatically.
8. **No map and no navigation link** (open decision 3): outlets have no coordinates (A-11).
9. **Where the design asks for something no backend provides, it is left out, not faked** (D-D): vehicle pick-up by QR (assignment is an administrator's command), the notification inbox (#14), fuel QR, call buttons (no phone numbers in the data), voice notes, driving mode. The design's store-manager PIN on the driver's phone is replaced by the proof the backend records: who received it, a signature or a photo. The store's own confirmation stays a separate act in the store's screens (R-RCP-07).

## Work breakdown

1. `shared/offline`: snapshots and uploads beside the queue; `shared/ui`: sheet and dark tokens.
2. `app-shell`: remembered session for full-tier roles.
3. `roles/driver/data`: projection and rules with unit tests, gateway, hook.
4. Screens: Home, Route, Delivery report with proof, Report a problem, Run complete, No run.
5. Playwright at phone width: a day offline with reload, a failed delivery, sign-out blocked with writes waiting, session expiry keeps the queue.
6. Walkthrough, log.
