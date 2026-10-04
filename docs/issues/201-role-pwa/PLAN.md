# Role PWA implementation plan

Spec: [issue #201](https://github.com/ranathungaWK/Xception-WaypointGo/issues/201).
Goal: the driver, loader and store manager each install as their own app (a PWA, not a native app) from
their role address, fit phones and tablets in both orientations, and open, read and record work with
no network.

## Why

The booklet asks for a responsive web application, judges the driver and loader on phone-sized
screens, and gives degradation, offline operation and recovery 10% of the weight. Coverage drops
across hill country and the Kandy corridor. The driver uses a personal phone, the loader a shared dock
tablet or terminal, the store manager a phone or desktop. Figma frames 07-16 cover loader tablet,
phone and portrait, driver mobile with offline, store mobile and the degradation screens.

## Current state

- `public/manifest.json` has no icons, `id` or scope, so no browser offers to install; no favicon or
  apple-touch-icon exists.
- The driver draws a fixed 393x852 phone frame from `sm:` up; the store has phone and `lg` desktop
  layouts only; the loader is responsive (07/08/09/10) but has no landscape phone handling.
- Only the driver keeps read snapshots. The loader and store queue writes but lose their screens on an
  offline reload, and `currentSession()` lets only the full tier continue unverified.
- `scripts/build-sw.mjs` precaches the shell and `.next/static`; icons, fonts, `/assets` and
  `/map-tiles` are never cached.

## Ownership

- App shell owns which manifest and touch icon a host gets (`hostRole.ts` already maps hosts to roles).
- `app/manifest.ts` and `app/apple-touch-icon.png/route.ts` are thin Next handlers over that.
- The service worker owns static and tile caching. API reads are never cached by it: kept snapshots in
  `@shared/offline` stay the only offline read path, so scope and session rules are untouched.
- `@shared/pwa` owns install and persistent storage; `@shared/ui` the install button beside the
  assistant connection in each role's settings.
- Each role owns its layouts and its kept reads.

## Decisions

D1: one PWA per role address (`driver.`, `loader.`, `store.` and their `-preview` names). They are
separate origins already, so each has its own worker, queue, device id and install. Chosen over path
routes, which would need new routes, a fallback per route and a shared queue.
D2: the manifest is chosen by Host at request time (`app/manifest.ts` reads `headers()`); the page
itself stays static. The shared host keeps a generic manifest.
D3: no `orientation` lock. Tablets mount in landscape, and every role lays out in both.
D4: icons are generated once from SVG by a committed script and the PNGs are committed.
D5: queuing roles ask for persistent storage after sign-in so the browser does not evict the queue.
D6: the resilient tier (loader, store manager) may continue an unverified session offline, like the
driver. Queued writes are re-authorized by `/api/sync`, so this widens nothing (PR 2).

## Device matrix

Phone 375x667, 412x915, 430x932 and landscape 852x393; tablet portrait 768x1024 and 834x1194;
tablet landscape 1024x768 and 1180x820. Bands: base, a `short:` variant for landscape phones
(height at most 500 px), `md:` and `lg:`.

## PR breakdown

1. PWA foundation: per-role manifest and icons, apple web app metadata, safe areas, worker runtime
   caching for icons, fonts, assets and map tiles, install button, persistent storage.
2. Loader and store offline reads and reload: kept snapshots, resilient-tier session, shared offline
   banner, edge case rows with tests.
3. Driver responsive: no phone frame, phone landscape, tablet portrait and landscape, canvas resize on
   rotation, map tile prefetch for the run.
4. Store manager tablet layouts and landscape phone.
5. Loader landscape phone and matrix audit.

Each PR adds device-matrix projects and an `offline.spec.ts` to the affected role suite, updates
STATUS.md and the development log.
