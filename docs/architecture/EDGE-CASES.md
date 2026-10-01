# Edge case register

Every failure mode identified for Waypoint Dispatch, what the system must do, where that behaviour is enforced, how it is detected in production and how it is tested.

This is a working register, not prose. Add a row when a new case is found; never fix an edge case without adding its row and its test.

Read with [MODULES.md](MODULES.md) for where each enforcement point lives.

---

## 1. Handling patterns

Most of the cases below are instances of nine patterns. Learn the patterns and the register becomes predictable rather than a list to memorise.

| Pattern | When it applies | Rule |
| --- | --- | --- |
| **Idempotent replay** | Any retried or replayed write | Command id plus payload fingerprint. Same key and payload returns the original result; same key and different payload is rejected |
| **Version guard** | Any write against state that may have moved | `expected_version` mismatch fails the command. Never merge, never last-writer-wins |
| **Human-in-the-loop conflict** | Offline work that collided with server state | Hold it, show the current record, let a person decide. Automatic resolution destroys evidence |
| **Degrade visibly** | A dependency is down | Continue with reduced function and **say so on screen**. Silent degradation is worse than failure, because people trust the output |
| **Circuit breaker with defined fallback** | Any call leaving the process | Open after a failure threshold, half-open probe, and a written fallback for each port |
| **Retry with backoff and jitter** | Transient, idempotent failures | Exponential, plus or minus 50% jitter. Without jitter a regional reconnect arrives in lockstep and the recovery becomes the outage |
| **Compensating action** | Something already committed must be undone | A new attributed record that reverses it. Never an `UPDATE` that hides the original |
| **Escalation timer** | Something is waiting on a human | A deadline with a defined action when it expires. "Waiting forever" is not a state |
| **Deny by default** | Any authorization question | Unlisted or unmatched means `403` plus an audit entry, never an empty result that looks like no data |

---

## 2. Ordering and demand

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| ORD-01 | Order submitted after 16:00 | Accepted for the next operating run. Cutoff read from the **server clock in Asia/Colombo** | Ordering domain | Counter: orders accepted past cutoff | Unit, clock injected |
| ORD-02 | Order for a non-operating date | Rolled to the next `is_operating` date, shown before confirm | Ordering + Reference | Counter by rolled reason | Unit |
| ORD-03 | Chilled and ambient in one submission | Split into two orders; vehicle eligibility is decided per order | Ordering domain | Ratio of split submissions | Unit |
| ORD-04 | Double tap or lost acknowledgment | Same command id returns the original order, no duplicate | Application | Duplicate-suppressed counter | Integration |
| ORD-05 | Amend after allocation | Version conflict; requires dispatcher revalidation, never silent | Ordering + Planning | Conflict counter (`waypoint_version_conflict_total`) | Integration |
| ORD-06 | Amend after loading started | Rejected. The physical world has moved on; raise an issue instead | Ordering domain | Rejection counter | Integration |
| ORD-07 | Order volume or weight is zero or negative | Rejected at capture with a field-level problem detail | API validation | 4xx rate by field | Unit |
| ORD-08 | Outlet window shorter than its service allowance | Rejected at capture with the arithmetic shown, not discovered at 04:00 | Reference + Ordering | Alert on any occurrence | Unit |
| ORD-09 | Order for an outlet the actor does not manage | `403` plus audit | Authorization | Denied-attempt counter | Authorization test |
| ORD-11 | A Fresh outlet submits both a dry and a chilled order for the same day | Two separate orders. Never merged or de-duplicated, because vehicle eligibility differs (R-ORD-02) | Ordering domain | Merge-attempt counter | Unit |
| ORD-12 | Order placed on a holiday for a later operating day | Accepted; delivery date rolls to the next operating day and the store is shown it (R-ORD-08, conflict C-6) | Ordering + Reference | Rolled-date counter | Unit |
| ORD-10 | Store cancels an already-loaded order | Cancellation refused with the reason; the store raises an issue instead. There is no returns workflow (A-10) | Ordering domain | Refused-cancel counter | Integration |

## 3. Stock, catalogue and the external warehouse

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| STK-01 | Insufficient stock at placement | Placement rejected with per-line available quantities; nothing is saved and the store adjusts and resubmits (D-F). Revised 2026-09-30: there is no `stock_held` state | Ordering + `StockPort` | Short-stock rejection counter | Integration, stubbed port |
| STK-02 | ~~Stock adjusted down~~ | **Withdrawn 2026-09-30 (D-F):** Waypoint never adjusts a placed order. A quantity change is the store's own amendment | n/a | n/a | n/a |
| STK-03 | Stock unresolved at cutoff | Auto-deferred with reason `stock_unresolved`, store notified | Scheduler | Alert if above a threshold | Integration, time-travel |
| STK-04 | Warehouse unreachable | Circuit opens. Orders enter `stock_unknown` and the dispatcher sees the degraded banner. **Never assume stock exists** | `StockPort` adapter | Circuit-open alert over 60 s | Chaos drill |
| STK-05 | Warehouse replies after Waypoint timed out | Late response reconciled by reservation reference, or compensated if the order already moved | Anti-corruption layer | Late-response counter | Integration |
| STK-06 | Warehouse reports a reservation Waypoint does not know | Logged, quarantined, never auto-applied | Anti-corruption layer | Alert on any occurrence | Integration |
| STK-07 | Warehouse returns no totals or temperature for a placed order | Placement is treated as `stock_unknown`, never as reserved with guessed totals; capacity is never computed from product lines. Revised 2026-09-30: stock balances do exist (A-08) | `StockPort` | Gauge: orders in `stock_unknown` | Integration |
| STK-08 | `POST /orders` succeeded, the Waypoint transaction then failed | A warehouse order exists holding stock that no Waypoint order points at. The reconciler finds it by query and cancels it, restoring stock (R-STK-09) | Warehouse adapter + reconciler | Orphaned-order gauge | Integration |
| STK-09 | `POST /orders` timed out, outcome unknown | **Never blind-retry**: the call is not idempotent and a retry double-decrements stock. Query by our reference first, then create only if absent (R-STK-11) | Warehouse adapter | Timeout-then-query counter | Integration |
| STK-10 | `PUT /orders/:id/status` rejected as an invalid transition | The two lifecycles have diverged. Surface it rather than forcing; the warehouse is authoritative for its own states (R-STK-10) | Warehouse adapter | Divergence counter | Integration |
| STK-11 | Warehouse order cancelled outside Waypoint | Stock is restored there, but the Waypoint order still expects delivery. Detected on reconcile and raised as an issue | Reconciler | Alert on any occurrence | Integration |
| STK-12 | Stock adjusted by `PATCH` between the check and dispatch | Availability is only true at the moment of the `POST`. A later shortfall is a loading shortfall, not a stock hold | Loading | Post-reservation shortfall rate | Integration |
| CAT-01 | Catalogue sync unavailable | Plan and deliver on order-level totals, which are authoritative anyway. Catalogue shown as stale with its version and age | `CataloguePort` | Catalogue age gauge | Chaos drill |
| CAT-02 | Product lines sum to a weight or volume different from the order total | **Order total wins.** Record the discrepancy; never adjust the order to match the lines | Ordering domain | Discrepancy histogram | Unit |
| CAT-03 | Catalogue version changes between draft and publication | Plan records the catalogue version it was built against. Capacity is unaffected because it never reads lines | Planning | Counter | Integration |
| CAT-04 | Order references a product absent from the current catalogue | Line renders as unknown; the order stays deliverable | Query layer | Unknown-product counter | Unit |
| CAT-05 | New order has no product lines | Normal, not an error. Lines exist only where the warehouse supplied them | Ordering domain | n/a | Unit |
| CAT-06 | Candidate product shown to a store manager | Labelled as inferred. Never presented as a confirmed SKU | Client | n/a | Browser test |

## 4. Planning and capacity

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| PLN-01 | Demand exceeds capacity | Defer by policy: prior skips, Fresh, chilled, earliest closing window. Every deferral records the **binding constraint** | Planning domain | Deferrals per run by reason | Property test |
| PLN-02 | Order exceeds every vehicle's capacity | `unservable`, surfaced for a split decision. Never deferred forever | Constraint registry | Alert on any occurrence | Fixture `OVERLOAD-001` |
| PLN-03 | Outlet skipped on consecutive runs | Skip count escalates priority; publication requires an explanation | Planning | Gauge: outlets skipped twice | Integration |
| PLN-04 | Vehicle enters workshop after publication | Only affected trips replan; untouched trips keep identity and loading state | Planning | Replan counter | Integration |
| PLN-05 | Weekly fuel quota exhausted | Allocation blocked, remaining litres shown. Reservations include return legs and other published plans that week | Constraint registry | Vehicles at quota gauge | Property test |
| PLN-06 | Two dispatchers edit one draft | Revision check rejects the stale edit with a diff | Application | Conflict counter (`waypoint_version_conflict_total`) | Integration, concurrent |
| PLN-07 | Order set changed since the draft was built | Publication blocked, regeneration required. Coverage must match the queue | Planning | Blocked-publication counter | Integration |
| PLN-08 | Mall window conflicts with the Fresh window | Infeasible, named explicitly. Not silently dropped | Constraint registry | Counter by constraint | Unit |
| PLN-09 | Chilled order to a van-only outlet above reefer-van capacity | `unservable`, named | Constraint registry | Alert | Fixture |
| PLN-10 | Longest-distance-first conflicts with a delivery window | **Windows win.** Distance ordering is a tie-break, never a constraint override | Planning domain | n/a | Property test |
| PLN-11 | Allocation engine exceeds its time budget | Returns the best feasible result so far, marked partial. Never an empty plan and never an unbounded wait | `AllocationEngine` | p95 duration, partial-result rate | Load test |
| PLN-12 | Engine returns an infeasible plan | `ValidatingEngine` rejects it before it leaves the module | Planning infrastructure | Alert on any occurrence | Property test |
| PLN-13 | Plan requested for a non-operating day | Refused | Reference | n/a | Unit |
| PLN-15 | Trip volume or weight sits within `1e-6` of the cap | Treated as fitting, matching the supplied validator's tolerance exactly (R-PLN-06). Never compared with bare floating point | Constraint registry | n/a | Property test |
| PLN-16 | An order requires `frozen` | Treated as reefer-requiring, identically to chilled (R-PLN-26). The supplied data carries only ambient and chilled | Constraint registry | Frozen-order counter | Unit |
| PLN-17 | Allocation output regresses against the official rules | CI runs the supplied `check_allocation.py` over generated output; a failure blocks the build | CI | Build gate | CI job |
| PLN-14 | Reference data changed between draft and publication | Publication uses the snapshot the draft was built against, or refuses and says why | Reference versioning | Counter | Integration |

## 5. Loading and the dock

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| LOD-01 | Missing or damaged goods | Departure blocked. Dispatcher records a replacement; loader rechecks **every** order in the trip | Loading | Shortfall counter | End to end |
| LOD-02 | Assigned truck unavailable at the dock | Interchange request. Substitute revalidated for capacity, temperature, access, depot, fuel and time **across the whole trip**. Written as history, never an `UPDATE` | Loading + Planning | Interchange counter | Integration |
| LOD-03 | No compatible substitute exists | Trip deferred as a unit; orders carry forward with identity | Planning | Alert | Integration |
| LOD-04 | Loader shift ends mid-session | Partial checks persist; another loader resumes; both recorded | Loading | Session handover counter | Integration |
| LOD-05 | Loading complete, no driver assigned | Trip holds in `ready_for_departure`; dispatcher notified. Escalation timer applies | Loading + Notification | Gauge: trips waiting for driver | Integration |
| LOD-06 | Mark-loaded submitted twice | Idempotent, no duplicate check rows | Application | Duplicate-suppressed counter | Integration |
| LOD-07 | Loader works from a stale manifest after a replan | The manifest carries the plan version; a stale submission is rejected with the new manifest returned | Loading | Stale-manifest counter | Integration |
| LOD-08 | Shared tablet used by two loaders in one session | Both attributed via device plus user identity | Identity + Audit | n/a | Integration |

## 6. Execution, the road and offline

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| EXE-01 | Offline for an entire run | All outcomes queue locally. UI acknowledges only after the **local** write is durable | Client + Sync | Queue-age p95 | Browser test, offline |
| EXE-02 | Reconnect with a queue | Replay in submission order, each idempotent. Items leave the queue only on server confirmation | Sync | Time-to-drain p95 | Browser test |
| EXE-03 | Server state changed while offline | Conflict held for review against the current record. **Never auto-merge** | Sync | Conflict counter | Browser test |
| EXE-04 | Mass reconnect after a regional outage | Backoff with jitter plus ingest bulkhead. Recovery must not become the outage | Client + Platform | Ingest queue depth | Load test |
| EXE-05 | Arrive before the window opens | Vehicle waits; service time starts at window open | Execution domain | Wait-time histogram | Unit |
| EXE-06 | Arrive after the window closes | Delivery still recorded, lateness flagged with a reason (R-EXE-05) | Execution domain | Late-arrival rate | Unit |
| EXE-16 | Arrive later than planned but still inside the window | **Not late.** Lateness is measured against `window_close_time`, never against planned arrival (R-EXE-14) | Execution domain | n/a | Unit |
| EXE-17 | Arrive on plan but the window has already closed | **Late**, even though the plan was met. The plan was wrong, and that is the signal | Execution domain | Plan-vs-window divergence | Unit |
| EXE-18 | Arrive before the window and wait | Waiting time recorded separately from service time, so a long wait does not inflate the service-time history the model learns from (R-EXE-13) | Execution domain | Wait-time histogram | Unit |
| EXE-19 | Mall outlet where the mall window and the outlet window differ | The effective window is the **intersection** of the two. If the intersection is empty the stop is unservable and is surfaced, never silently attempted (R-PLN-29) | Constraint registry | Empty-intersection alert | Unit |
| EXE-07 | Outlet closed or refuses goods | `failed` outcome plus an issue. Dispatcher chooses redelivery, return or closure. Redelivery links a new order and preserves the original evidence | Execution + Issues | Failure rate by reason | End to end |
| EXE-08 | Vehicle breaks down mid-route | Vehicle set `fault`, issue raised, remaining stops released for replanning, goods disposition recorded | Execution + Planning | Breakdown counter | Integration |
| EXE-09 | Camera denied or photo too large | Delivery may complete with a recorded reason, flagged lower-evidence. A device limit must never block the work | Client + Execution | Low-evidence rate | Browser test |
| EXE-10 | Object storage unavailable at capture | Proof queued locally with the outcome; the driver is told it is not yet uploaded | `ProofStore` + Sync | Pending-upload gauge | Chaos drill |
| EXE-11 | Device lost or browser data cleared before sync | Unsynced work is lost. The durable-save acknowledgment is the stated contract | Client | Unrecoverable-loss reports | Documented, not testable |
| EXE-12 | Device clock is wrong | Server timestamps decide; client time stored for forensics only | Application | Skew histogram | Unit |
| EXE-13 | Driver posts for a vehicle assigned to someone else | `403` plus audit. Driver scope is **vehicle plus date** | Authorization | Denied counter | Authorization test |
| EXE-14 | Duplicate delivery submitted from two devices | First wins by version; second becomes a conflict for review | Version guard | Conflict counter (`waypoint_version_conflict_total`) | Integration |
| EXE-15 | Stop recorded for an order not on this trip | Rejected | Execution domain | Alert | Unit |

## 7. Receipt and dispute

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| RCP-01 | Store confirms partial receipt | Recorded as `partial` with quantities; raises a dispute issue | Receipt + Issues | Partial rate | Integration |
| RCP-02 | Store never confirms | Auto-closes after a configured window as `unconfirmed`. Never silently "delivered" | Scheduler | Auto-close rate | Integration, time-travel |
| RCP-03 | Store disputes after proof exists | Dispute recorded alongside the proof. Evidence is never deleted or overwritten | Receipt | Dispute rate | Integration |
| RCP-06 | Loader passed every check, driver recorded delivered, store reports items missing | Shortage investigation opened, linked to the loading check, the proof of delivery and the receipt. **No record is amended to make the three agree**, and it is not auto-resolved for either party (R-RCP-07) | Receipt + Issues | Shortage rate by depot and by route | End to end |
| RCP-07 | Store reports missing goods and the proof photo shows a complete load | Both stand as evidence. The investigation records the contradiction; the system does not adjudicate it | Issues | Contradiction counter | Integration |
| RCP-08 | Shortage reported after the auto-close window | Still accepted and investigated; auto-close is a state, not a deadline for the truth | Receipt | Late-shortage counter | Integration |
| RCP-04 | Receipt confirmed for a delivery that never happened | Rejected: confirmation requires a delivery record | Receipt domain | Alert | Unit |
| RCP-05 | Proof requested by a different outlet's manager | `403` plus audit | Authorization | Denied counter | Authorization test |

## 8. Fleet and vehicles

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| FLT-01 | Driver reports a fault before loading | Vehicle marked unavailable; affected trips replanned before the dock is blocked | Reference + Planning | Fault counter | Integration |
| FLT-02 | Vehicle returns from workshop mid-day | Available for the next planning run only, not retroactively | Reference | n/a | Unit |
| FLT-03 | Two drivers assigned to one vehicle on one day | Impossible: temporal exclusion constraint at the database | Database constraint | Constraint violation alert | Migration test |
| FLT-04 | Vehicle assigned to a trip outside its home depot | Rejected by the `HomeDepot` constraint | Constraint registry | Counter | Unit |
| FLT-05 | Fuel usage reported above the weekly quota | Recorded as an overrun with an alert; not silently clamped | Fuel ledger | Overrun alert | Integration |

## 9. Identity, access and security

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| SEC-01 | Session expires with pending offline work | Re-authenticate without clearing the queue. Sign-out blocked while work is pending | Identity + Client | Blocked sign-out counter | Browser test |
| SEC-02 | Role changed or account disabled mid-session | All sessions revoked, account audit written, pending queue preserved for review | Identity | Revocation counter | Integration |
| SEC-03 | Permission revoked during an in-flight command | Re-checked inside the transaction; the command fails | Application | Race-loss counter (`waypoint_race_lost_total`) | Concurrent test |
| SEC-04 | Scope violation attempt | `403` plus audit. Never an empty list | Authorization | Denied-attempt rate, alert on spikes | Authorization test |
| SEC-05 | Credential stuffing | Per-identity lockout shared across instances | Identity | Lockout rate | Integration |
| SEC-06 | Pooled connection reused across users | Impossible: actor set with `SET LOCAL` inside the transaction | Data layer | Alert if actor unset (`waypoint_db_no_actor_total` outside `waypoint_iam`/`waypoint_ref`) | Integration |
| SEC-07 | Application role holds `BYPASSRLS` | Deployment fails the check | CI policy check | Build gate | Migration test |
| SEC-09 | A policy names an action that does not exist | Rejected at authoring time against the catalogue (R-IAM-03). Accepting it would deny silently forever | Identity application | Rejected-policy counter (`waypoint_policy_rejected_total`) | Unit |
| SEC-10 | An actor has no policy attached at all | Default deny with "no policy allows", not an empty screen | Policy evaluator | Actors with no policy gauge (`waypoint_iam_actors_without_policy`) | Unit |
| SEC-11 | One policy allows an action and another denies it | Deny wins, and the denial names the statement responsible (R-IAM-02, R-IAM-08) | Policy evaluator | n/a | Unit |
| SEC-12 | A policy changes while a session is live | The whole policy cache is cleared, so the next command re-evaluates. No sign-out required | Policy cache | Cache clear counter (`waypoint_policy_cache_cleared_total`) | Integration |
| SEC-13 | A transaction runs with no `app.actor_id` set | Every scope predicate is false and row-level security returns zero rows. Fails closed | Database policies | Alert on unset actor (`waypoint_db_no_actor_total`) | Migration test |
| SEC-08 | Oversized or malformed request body | Rejected at the edge before it reaches the application | Edge | 4xx rate (`waypoint_problem_total{code="PAYLOAD_TOO_LARGE"}`) | Integration |
| SEC-15 | A transaction forgets `SET LOCAL ROLE` | Permission denied. `waypoint_app` is `NOINHERIT` and holds nothing until it assumes a module role | Database grants | Permission-denied counter | Migration test |
| SEC-16 | A module queries another module's schema directly | Permission denied at the database, even though the code passed review and the boundary test | Database grants | Alert on any occurrence (`waypoint_db_permission_denied_total`) | Migration test |
| SEC-17 | A migration re-runs where a role already exists with wrong attributes | `ALTER ROLE` asserts them. Roles are cluster-wide, so `CREATE ROLE` alone silently keeps an inheriting role and defeats the separation | Migration | Role attribute check in CI | Migration test |
| SEC-18 | Inbound warehouse webhook with an invalid or missing signature | Stored unverified and `quarantined`, never processed. A `CHECK` constraint makes processing an unverified row impossible | Inbound inbox | Quarantine rate, alert | Integration |
| SEC-19 | Inbound webhook replayed | Rejected by the unique `(source_system, source_event_id)`. Replay detection is exact, not heuristic | Database constraint | Duplicate-suppressed counter | Integration |
| SEC-14 | Inbound event of an unknown type | Quarantined for review, never silently ignored | Inbound inbox | Alert on any occurrence | Integration |

## 10. Platform, data and time

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| PLT-01 | Serialization failure or deadlock | Bounded retry that **re-runs validation**, never a blind replay | Platform | Retry rate (`waypoint_db_retry_total`), exhaustion alert (`waypoint_db_retry_exhausted_total`) | Concurrent test |
| PLT-02 | Outbox relay crashes after commit | Events redelivered at least once; consumers idempotent | Notification | Relay lag gauge | Chaos drill |
| PLT-03 | Poison event | Dead-lettered with attempt history after N attempts. Never blocks the queue, never disappears | Outbox relay | Dead-letter alert | Integration |
| PLT-04 | Two app instances run the same scheduled job | Advisory-lock lease means exactly one runs | Platform | Duplicate-run alert | Integration |
| PLT-05 | Database failover | Connections drain and reconnect; in-flight transactions fail cleanly and are retried by the client | Platform | Error-budget burn | Chaos drill |
| PLT-06 | Read replica lag visible in the dispatcher view | Lag displayed when above threshold, or the read is routed to the primary | Query layer | Lag gauge | Load test |
| PLT-07 | Supplied calendar runs out | Extension policy at startup, Monday to Saturday, with supplied dates and `CALENDAR_FILE` overrides taking precedence | Reference | Alert before exhaustion (`waypoint_reference_calendar_days_remaining`) | Unit |
| PLT-08 | Migration adds `NOT NULL` to a large table | Expand and contract: nullable column, batched backfill, then the constraint. Never a full-table lock in a deploy | Migration policy | Lock-wait alert | Migration test |
| PLT-09 | Partition for the current month missing | Created ahead by the scheduler; alert if the next partition is absent | Platform | Alert | Integration |
| PLT-10 | Proof artifact retention expires | Detached and purged on schedule, with the audit record retained | Retention job | Purge counter | Integration |
| PLT-11 | Clock changes on the server | All decisions use `timestamptz`; no wall-clock arithmetic across a change | Domain | n/a | Unit |

---

## 11. Policy and rule change

| ID | Trigger | Required behaviour | Enforced in | Detection | Test |
| --- | --- | --- | --- | --- | --- |
| POL-01 | A threshold changes (Fresh budget 270 to 300) | New effective-dated `rule_parameters` row supersedes the old one. No deploy, no mutation of the previous value | Rule parameters | Parameter change audit | Integration |
| POL-02 | A rule changes between draft generation and publication | Publication uses the rule set version the draft was built against, or refuses and says which version changed | Planning | Counter | Integration |
| POL-03 | A historical decision is replayed after the rules changed | Replays against the `policy_version_id` and `reference_version_id` stamped on the plan, never against current rules | Planning + audit | Replay mismatch alert | Integration |
| POL-04 | Two policy versions claim the same effective instant | Impossible: exclusion constraint on `(policy_kind, effective range)` | Database constraint | Constraint violation alert | Migration test |
| POL-05 | A policy is published with an effective date in the past | Rejected. Retroactive rules rewrite decisions already communicated | Application | Alert on any occurrence | Unit |
| POL-06 | A new priority policy is in shadow mode | Evaluated and logged, changes nothing. Divergence from the active policy is reported per run | Planning | Shadow divergence rate | Integration |
| POL-07 | Shadow policy diverges beyond a threshold | Promotion blocked until reviewed. Divergence is a decision for a human, not a deployment gate to override | Rollout process | Alert | Process |
| POL-08 | A canary policy is active on one depot only | Plans record which version applied. Two depots may legitimately differ that day | Planning | Version distribution gauge | Integration |
| POL-09 | Authorization scope revoked while a policy decision is cached | No decision caching across a transaction. The PDP is consulted inside the transaction | Application | Race-loss counter (`waypoint_race_lost_total`) | Concurrent test |
| POL-10 | A rule parameter is missing for the date being planned | Refuse to plan. Never fall back to a compiled-in default, which would silently reintroduce the old value | Rule parameters | Alert on any occurrence | Unit |

---

## 12. How this register is tested

| Layer | Covers | Cost |
| --- | --- | --- |
| Domain unit tests | Every constraint and state transition, no database, clock injected | milliseconds |
| Property tests | Allocation invariants: no published plan ever violates a constraint, for generated demand | seconds |
| Integration tests | Command bus, transactions, version guards, authorization denials, on an ephemeral database | seconds |
| Concurrency tests | PLN-06, SEC-03, PLT-01, EXE-14 with real parallel commands | seconds |
| Contract tests | Module contracts and the client API contract | seconds |
| Browser tests | Offline cases EXE-01 to EXE-04, EXE-09, SEC-01 | minutes |
| Chaos drills | STK-04, EXE-10, PLT-02, PLT-05 by deliberately breaking a dependency in staging | scheduled |
| Load tests | PLN-11, EXE-04, PLT-06 against production-shaped data | scheduled |

Two rules keep this honest:

1. **A case without a test is a case that is not handled.** The register and the suite are reviewed together.
2. **Every detection column must exist as a real metric or alert.** An edge case handled in code but invisible in production is one you will learn about from a user.
