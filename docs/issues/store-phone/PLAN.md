# Store phone on the trip, no Call button

Owner: @Oxshadha. Item 9 of @jv-ransika's plan of 2026-10-21 (store phone, demo stops, truck breakdown, resend missing, permission editor). The folder takes its issue number once the issue is opened.

## Current state

- `GET /api/outlets/{id}/contacts` (`iam:ReadStoreContact`, R-IAM-41) gives one outlet's active store managers with the phone each keeps on their profile, only for an outlet in a depot the dispatcher holds; any other outlet is 404.
- `StoreContactCard` (the deferred order's panel) shows them with a `tel:` Call link.
- The Live trip page lists the stops with no store contact.

## Decision (owner, 2026-10-21)

The dispatcher sees the store manager's name and phone as plain text. No Call button anywhere.

## Who owns what

| Piece | Layer |
| --- | --- |
| Batch read `GET /api/outlets/contacts?ids=` | `identity/web/StoreContactController`, `identity/application/StoreContactQuery` |
| Depot scope, per outlet, in SQL (`app.actor_has_depot`) | `StoreContactQuery` |
| The trip's stores in one request | `roles/dispatcher/data/storeContacts.ts` |
| Name and phone per stop | `roles/dispatcher/screens/LiveTrip.tsx` |

## The read

`GET /api/outlets/contacts?ids=OUT001,OUT002` answers `{ "OUT001": [{name, phone}], ... }`, each outlet with its managers (an empty list when none). At most 30 outlets, duplicates once. Every outlet is checked like the single read: the action against `wpt:iam:outlet:<id>`, and the outlet's depot in SQL. One outlet outside the caller's depots, or one that does not exist, makes the whole answer 404, so a list never tells which outlet was refused. Never logged (phones are personal data).

## Tests

Integration: a dispatcher reads two outlets of their depot in one request; adding an outlet of another depot is 404; a store manager is 403; 31 outlets is 422. Browser: the trip page shows each stop's manager and phone as text, and no Call link anywhere (`e2e-dispatcher`).
