# Role PWA walkthrough

Issue [#201](https://github.com/ranathungaWK/Xception-WaypointGo/issues/201); plan in [PLAN.md](PLAN.md).
The driver, loader and store manager each install as their own app from their role address, fit
phones and tablets either way up, and open, read and record work with no network. It is a PWA from the
same Next.js frontend: no app store, no native code.

## What was built, by PR

1. **Installable apps (#203).**
   - `app/manifest.ts` reads the Host and returns `manifestFor(host)` from
     `src/app-shell/appManifest.ts`, built on `hostRole.ts`. The result is Waypoint Driver, Loader or Store, each
     with its own name, colour and icons (`public/icons/app/`, drawn by `scripts/build-app-icons.mjs`).
   - `app/apple-touch-icon.png/route.ts` does the same for iOS. `/` stays static.
   - `app/layout.tsx` sets `viewportFit: cover`, the apple web app meta and a theme colour for light and dark.
   - Fixed bottom bars pad with `env(safe-area-inset-bottom)`.
   - `@shared/pwa` holds install (`watchInstall`, `useInstall`, `installKind`) and persistent storage
     (`keepStorage`). `InstallApp` sits beside the assistant button in every role's settings.
2. **Offline reads for the loader and store (#204).**
   - `readThrough` in `src/shared/offline/keptReads.ts` keeps each read per account and answers from the
     device only in an outage.
   - The loader and store gateways use it.
   - `session.ts` lets the resilient tier carry on with the remembered session, as the driver does.
   - The top bars say "Offline · showing HH:MM" or "Server unreachable · showing HH:MM".
3. **Driver layouts (#205).**
   - The phone mock-up is gone. The run fills a phone. A tablet gets a 600 px column, and a landscape
     tablet gets the trip map beside the run (`useMedia` in `@shared/ui`).
   - On a phone held sideways (`short:` in `theme.css`), each screen scrolls as one page.
   - `SignaturePad` re-fits on rotation.
   - `tilesFor` and `keepTiles` (`@shared/ui/map`) fetch the run's map tiles while online.
4. **Store layouts (#206).**
   - Compact tab bar under `short:`.
   - "+ New order" kept clear of the floating sync pill from `lg`.
5. **Loader layouts (this PR).**
   - Compact completion ring under `short:`.
   - The existing tablet, desk and terminal layouts were checked on every size and needed no change.

## Flows end to end

- **Install.**
  1. The browser fetches `/manifest.webmanifest` and gets the app for the address.
  2. Chrome fires `beforeinstallprompt`. `watchInstall` holds it until Settings, Install app, calls
     `prompt()`. Safari shows the Add to Home Screen steps instead.
  3. Once installed, `display-mode: standalone` hides the row.
- **Offline reload (loader, store).**
  1. Each read is kept under `loader:...` or `store:...` in the account's IndexedDB.
  2. With no network, the service worker answers the navigation with the precached `/`.
  3. `currentSession()` returns the remembered session as `unverified`.
  4. Each `readThrough` answers from the device and marks the time, and the top bar shows it.
  5. Writes queue as before. The first live answer clears the marks.
- **Map with no signal (driver).** While online, the driver fetches the run's tiles. The worker keeps
  them cache first, up to 1500 entries with the oldest dropped first, across builds. In a valley the
  map draws from them.
- **What the worker never keeps:** `/api`, `/oauth`, `/.well-known` and `/mcp`. API reads offline are
  always the per-account snapshots, never a shared cache.

## Run and verify locally

From `frontend/`:

1. `npm run build`, then `npm start`.
2. Open `http://driver.waypoint.localhost:3000/` (or `loader.` or `store.`). Chromium resolves `*.localhost`
   to this machine. DevTools, Application, Manifest shows the role's app and "installable".
3. Sign in, open a trip or the orders, set DevTools, Network, Offline, and reload. The screen comes back
   from the device and the top bar says from when.

Tests:

- `npm test`
- `npx playwright test tests/e2e/install.spec.ts`
- The role suites, `-c playwright.{driver,loader,store}.config.ts`. Each runs `devices.spec.ts` on the seven
  sizes in `tests/devices.ts` plus the role's own size.

## Decisions and where they are recorded

- **One app per role address**, with the manifest chosen by Host: PLAN.md D1, D2.
- **No orientation lock:** D3.
- **The resilient tier carries on offline:** D6 and [ASSUMPTIONS.md](../../architecture/ASSUMPTIONS.md) A-43.
- **Edge cases:** [EDGE-CASES.md](../../architecture/EDGE-CASES.md) EXE-31 (offline reload), EXE-32 (only an outage
  falls back, kept per day) and EXE-33 (storage eviction).
- **Log:** [kavindamihiran.md](../../development-docs/log/kavindamihiran.md), 2026-10-04 entries.

## Known gaps

- The Figma frames 07-16 were not readable through the API (only the cover and contents page). The
  layouts follow the existing built screens and the device matrix, not a frame-by-frame comparison.
- Persistent storage is requested, but a browser cannot be made to evict in a test, so EXE-33 is
  checked by hand on a device.
- The role browser suites are still not in CI (as before); run them by hand.
- `e2e/shell.spec.ts`, "the app shell loads under its Content-Security-Policy", times out on
  `networkidle`. It does the same on `dev` without these changes.
