# Design-to-implementation mapping

The existing README identified a Waypoint Figma overview/style guide, rather than full application frames. This implementation retains the role structure, typography and teal action color. The current session did not inspect or modify the actual Figma file, so pixel-level fidelity and final Day 5 submission contents must be verified by the team.

| Product decision | Working implementation | Reason for refinement |
| --- | --- | --- |
| One shared operation | Scoped four-role sessions, order history, server events | Preserve cross-role continuity. |
| Dispatcher allocation board | Real order/route data, draft stop times, two load limits | Remove placeholder results that could contradict the actual plan. |
| Deferral decision | Validated alternative assignments, follow-up, next open queue, original snapshot | Make an explanation lead to an operational next step. |
| Capacity | Availability and weekly fuel reservations | Remove fixed forecast/probability graphics from this Hackathon scope. |
| Loading | Every dated trip, reverse sequence, replacement recheck | Avoid hiding vehicles or mixing trips into one manifest. |
| Driver offline | Durable local acknowledgment, reload, same-account login, retry IDs | Recovery must work beyond the happy path. |
| Driver display | Saved Auto, Day and Night preferences across the route, proof dialogs, details and sync view | Support daylight and night work without changing the delivery workflow. |
| Readable type | Shared 17–18 px content and 15–16 px supporting text, stronger secondary contrast and wrapping mobile controls | Keep all four roles readable on phone and desktop; shared cards inherit the active appearance. |
| Unfinished delivery proof | Device drafts for receiver, count, note, photo and signature, scoped to account, order and record version | Restore interrupted work without submitting it or applying proof to a changed record. Draft removal follows successful delivery enqueueing. |
| Store receipt | Actual state, planned time, independent receipt or dispute | Do not show unplanned orders as already travelling. |
| Demo scenarios | Collapsed selector, explicitly labelled data origins | Keep judge exploration available without crowding the operator's main task. |

Record any departures from the actual submitted design here after the team reviews its final Day 5 file. Changes made before that submission can be incorporated into the design itself; do not describe them as post-deadline departures unless they actually are.
