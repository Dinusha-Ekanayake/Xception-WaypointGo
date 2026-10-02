# Design-to-implementation mapping

**Read this first.** The table under "The prototype's mapping" was written against the prototype, which was removed at tag `prototype-v0`. All four role applications were rebuilt from the Figma file after 2026-09-30, so several of its rows describe screens that no longer exist. The table directly below is the current one.

## The rebuilt screens and where they depart from the design

Each departure has the same cause: the design shows something no backend module provides yet. They are left out rather than faked, and each role's walkthrough lists them.

| Role | Built from the design | Left out, and why | Detail |
| --- | --- | --- | --- |
| Sign-in and shell | Sign-in, role routing, one address per role, sync status | The background map artwork and the theme toggle | [development log](development-docs/development-log.md), 2026-10-01 |
| Store manager | Home, place an order, orders, deliveries, receive and dispute | Notifications, driver and ETA details, call options, draft orders: the Notification module is not built | [development log](development-docs/development-log.md), 2026-10-01 |
| Dispatcher | Orders, Plan (generate, place by hand, take off, move a trip, publish, revise), Live, Vehicles, Overview shell | Snapshots and compare, regenerate with locked orders, late-risk percentages, contact store manager, global search and the map have no backend. Overview's live tiles, skipped outlets, fuel, the Issues inbox, interchange approval and Forecast are still to build | [walkthrough](issues/019-dispatcher-ui/WALKTHROUGH.md) |
| Loader | Dock board, item by item loading, the four exception types, release checklist, PIN switch on a shared device including offline, English, Sinhala and Tamil | Vehicle interchange and dispatcher handover: not built in the Loading module. Sinhala and Tamil are drafts | [walkthrough](issues/010-loading/WALKTHROUGH.md) |
| Driver | Home, Route, delivery report with proof, not delivered, report a problem, run complete, light and dark, a full day with no signal | Vehicle pick-up by QR, the inbox, fuel, call, voice notes, driving mode and the map have no backend. English only | [walkthrough](issues/021-driver-ui/WALKTHROUGH.md) |
| Admin and auditor | Nothing | Not built; the shell says so on screen | [STATUS](development-docs/STATUS.md) |

## The prototype's mapping

This mapping describes the current four-role implementation and its intended design rationale. It retains the role structure, typography and teal action color. The final submitted design file was not inspected during this documentation audit, so pixel-level fidelity and final Day 5 submission contents remain team verification tasks.

| Product decision | Working implementation | Reason for refinement |
| --- | --- | --- |
| One shared operation | Scoped four-role sessions, order history, server events | Preserve cross-role continuity. |
| Dispatcher allocation board | Real order/route data, draft stop times, two load limits | Remove placeholder results that could contradict the actual plan. |
| Deferral decision | Validated alternative assignments, follow-up, next open queue, original snapshot | Make an explanation lead to an operational next step. |
| Capacity | Availability and weekly fuel reservations | Remove fixed forecast/probability graphics from this Hackathon scope. |
| Loading | Every dated trip, reverse sequence, replacement recheck | Avoid hiding vehicles or mixing trips into one manifest. |
| Driver offline | Durable local acknowledgment, reload, same-account login, retry IDs | Recovery must work beyond the happy path. |
| Driver display | Saved Auto, Day and Night preferences across the route, proof dialogs, details and sync view | Support daylight and night work without changing the delivery workflow. |
| Readable type | Shared 17-18 px content and 15-16 px supporting text, stronger secondary contrast and wrapping mobile controls | Keep all four roles readable on phone and desktop; shared cards inherit the active appearance. |
| Unfinished delivery proof | Device drafts for receiver, count, note, photo and signature, scoped to account, order and record version | Restore interrupted work without submitting it or applying proof to a changed record. Draft removal follows successful delivery enqueueing. |
| Delivery exceptions | Dispatcher records redelivery, returned goods or closure; replacement orders retain a link to the original evidence | Resolve operational failures without rewriting delivery history. |
| Store receipt | Actual state, planned time, independent receipt or dispute | Do not show unplanned orders as already travelling. |
| Demo scenarios | Collapsed selector, explicitly labelled data origins | Keep judge exploration available without crowding the operator's main task. |

Record any departures from the actual submitted design here after the team reviews its final Day 5 file. Changes made before that submission can be incorporated into the design itself; do not describe them as post-deadline departures unless they actually are.
