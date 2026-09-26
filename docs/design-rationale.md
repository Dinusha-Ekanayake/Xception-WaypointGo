# Designathon: one delivery, every handoff accounted for

## Problem framing

Waypoint's problem is not simply finding a truck. Four people hold different fragments of the same delivery: the store's request, the dispatcher's allocation, the loader's check, and the driver's evidence. Capacity shortages and unreliable connectivity make those fragments disagree. Our design makes each handoff explicit, explains who must act next, and preserves decisions when the day goes wrong.

The product promise is: **Know what can run, explain what must wait, and never confuse saved work with confirmed work.** Success means a judge can follow one order through four roles, identify why an alternative fails, recover a delivery recorded offline, and distinguish driver evidence from store acceptance. We have not conducted real user interviews; the following personas are design assumptions grounded in the competition brief.

## Personas and working conditions

| Role | Context and goal | Design consequence |
| --- | --- | --- |
| Dispatcher | Office desktop, stable connection, competing brands and scarce refrigerated/van capacity. Needs defensible decisions under time pressure. | Order queue, validated alternatives, explicit repeat-deferral follow-up, published decision history. |
| Loader | Shared dock device, interruptions, physical load checking. Needs the correct trip and loading order. | Trip/date selector, reverse stop sequence, large per-order checks, explicit departure blocker and replacement recheck. |
| Driver | Personal phone, unreliable coverage; interacts only while safely stopped. Needs the next action and durable proof. | Focused next-stop card, large controls, local-save acknowledgment, account-preserving reauthentication and sync review. |
| Store manager | Counter desktop or phone, staff planning and receiving disputes. Needs acknowledgment, truthful arrival information and a way to challenge a discrepancy. | Server-confirmed order date, planned ETA only after allocation, next-run queue explanation, independent receipt/dispute actions. |

## Screen flows and rationale

1. **Role sign-in.** Clearly named roles select the appropriate seeded identity. Roles share one service, but operational screens and records are scoped. Recovery signs back into the same account so unsent work is not orphaned.
2. **Store orders.** Place ambient and chilled orders separately. Pending synchronization is distinct from confirmation. The confirmed run comes from the 16:00 cutoff and operating calendar. Existing orders are not hidden after the first three cards.
3. **Dispatcher demand queue.** Date, depot and search answer which demand is under review. Loads, receiving windows and prior skips establish the constraints before allocation. No invented sample records fill an empty state.
4. **Allocation and deferral board.** A side-by-side layout puts unmet demand next to feasible trips. Weight and volume are both visible. Published plans are locked; the board explains repeat skips before it permits publication. A publication review shows assigned and deferred counts across all depots, even when the board is filtered, and confirms the exact draft revision.
5. **Assignment review.** For an order that does not fit, show fully validated alternatives with arrival, incremental fuel/distance and remaining load capacity. Rejected options explain the failed constraint. Applying an alternative is deliberate; a stale draft cannot overwrite another dispatcher's decision.
6. **Fleet availability.** Show actual vehicles, workshop exclusions and weekly reserved fuel. A capacity total alone cannot promise feasibility, so the screen explains the additional access/window constraints.
7. **Loading manifest.** Select a particular dated trip, then load in reverse sequence. A shortfall prevents departure. Replacement requires a fresh full-load check rather than silently shrinking the requested order.
8. **Driver run.** Show the next unfinished stop and one appropriate action. Delivery records distinguish full, partial and failed outcomes. Proof is captured while stopped. Completed/disputed stops do not become the next stop again.
9. **Device sync and recovery.** Show pending, rejected and confirmed records separately. Keep command IDs and evidence across reload and session expiry. A conflict requires review; it is not silently overwritten.
10. **Store receipt and dispute.** Arrival/proof is evidence supplied by the driver. Store confirmation is a separate event. A reported issue is not treated as a clean receipt.
11. **Dispatcher progress.** Show synchronized delivery outcomes and exceptions for the selected day. A quiet screen says no exceptions. We do not infer GPS, network status or a live ETA from the absence of an update. Exception review records redelivery, returned goods or closure with an explanation; linked replacement orders preserve the original evidence.

## Fully developed degradation journey: proof recorded without signal

A driver reaches an outlet in a coverage gap. Losing that record could cause a dispute or duplicate delivery, so the interface keeps the downloaded run available and acknowledges a record only after a local transaction finishes. The next action reflects queued work, including after reload. On reconnect, the same command IDs are replayed. If authentication expired, sign-in opens without discarding work. If the shared record changed, the queue marks the action Needs review and retains the evidence until an explicit decision. Only after server acceptance does the store see the proof and gain the separate receipt action.

Prototype states to demonstrate: downloaded run -> offline banner -> depart/arrive saved -> proof saved -> offline reload -> reconnect with expired session -> same-account sign-in -> synchronized proof -> store confirmation. Also show a stale-record conflict remaining visible in the sync queue. Use a phone-sized driver frame.

## Central tradeoff

We chose **assisted, explainable allocation and human-reviewed exceptions**. Greedy allocation is fast and reproducible; validated alternatives improve a local decision without pretending to find a mathematically optimal fleet plan. A next-run date is eligibility, not a promise of arrival. We prioritized reliable handoffs over live tracking, external messaging and prediction features.

## Visual system

Keep Instrument Sans for operational text and IBM Plex Mono for IDs/times. Teal #0A6B63 identifies primary decisions, white #FFFFFF holds records, canvas #F6F6F6 separates work areas, ink #13161B carries primary text, amber #7A4A00 marks attention and red #E11900 marks failure. Status words accompany color. Driver controls support Auto, Day and Night appearance modes across the workspace and dialogs. Auto follows the system preference; the selected mode is saved on the device. Shared text sizes, secondary contrast and wrapping controls support readability across all four roles. Keyboard focus is visible; motion is not required to understand any state.

## Preparing the design submission

Transfer these sections into distinct pages of the team's design file: framing, personas, connected flows, screen rationales, degradation journey, high-fidelity screens/prototype, tradeoff and AI disclosure. Capture the current working screens as references and preserve the final Day 5 export. The live app is a working prototype; this document is supporting content, not a claim that a Figma file has been created or submitted.
