# Roadmap: from handover to an intelligent delivery system

This roadmap says what to build next on Waypoint Dispatch, in what order, why, and how we know it worked. It covers the foundation checks and fixes, the field features, the integration of the Datathon models and the LLM and Jev features, and it closes with the business case.

The roadmap was written after a full audit of the repository at handover (October 2026). It was then reviewed in three rounds:

1. Two rounds by a software architect and an operations head.
2. One round on the reply-suggestion feature.
3. A final ensemble vote by a business owner, an AI engineer and a system architect.

Where the product owner's own decision differed from a vote, the owner's decision stands and the vote's safeguards were added.

## How to use these documents

| Question | Where |
| --- | --- |
| What did we find at handover? | [00-BASELINE-AUDIT.md](00-BASELINE-AUDIT.md) |
| What are the normal flows and how do we measure them? | [01-FOUNDATION-FLOWS.md](01-FOUNDATION-FLOWS.md) |
| What must be fixed first? | [02-FOUNDATION-FIXES.md](02-FOUNDATION-FIXES.md) |
| Do we need RabbitMQ, Redis, partitioning, a bucket? | [03-INFRA-DECISIONS.md](03-INFRA-DECISIONS.md) |
| Which field features, and how do they work? | [04-FIELD-FEATURES.md](04-FIELD-FEATURES.md) |
| How do the Datathon models become real decisions? | [05-DATATHON-MODELS.md](05-DATATHON-MODELS.md) |
| Where do Jev and an LLM fit, and where do they not? | [06-INTELLIGENCE.md](06-INTELLIGENCE.md) |
| Which business rules must Operations, Finance and Legal own? | [07-POLICIES.md](07-POLICIES.md) |
| Is it worth it, what does it cost, how could it earn revenue? | [08-BUSINESS-CASE.md](08-BUSINESS-CASE.md) |

How these documents relate to the rest of the repo:

- The roadmap **proposes**. The canonical documents ([SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md), [RULES-AND-POLICIES.md](../architecture/RULES-AND-POLICIES.md), [MODULES.md](../architecture/MODULES.md), [EDGE-CASES.md](../architecture/EDGE-CASES.md)) change only in the pull request that builds the item, as [AGENTS.md](../../AGENTS.md) requires.
- Each item gets a GitHub issue. Its `docs/issues/NNN-slug/PLAN.md` links back to the item here rather than copying it.
- **State lives in the GitHub Project, not in this file.** A shared state table goes stale the way STATUS.md did, and parallel pull requests conflict on it. The tracker below holds only what does not change: ID, phase, dependencies and the issue link.
- [STATUS.md](../development-docs/STATUS.md) stays the "where things stand" page and links here for "what comes next".

### Every item records the same things

- ID and goal.
- Depends on.
- KPI it moves, with a target.
- Owning module and layer, with paths.
- Reused components.
- Rules, edge cases, IAM catalogue rows and a policy version per role.
- Visible degrade behaviour for any external dependency (architecture rule 9).
- Tests: domain, integration, one denied-scope case and one browser spec.
- Done criteria.

## Programme

### Timeline (4 engineers)

| Week | Work |
| --- | --- |
| 1 | Phase 0: hygiene and safety (H-00 to H-05) |
| 2-3 | Phase 1: verify and baseline (B-01 to B-04); B-05 KPI baseline starts in parallel |
| 3-5 | Phase 1.5: quick wins; T2-01 early capacity plan live by 23 Oct |
| 5-9 | Phase 2: foundation fixes |
| 6 | Kandy pilot starts in shadow; MF-02 begins collecting actuals |
| 8-11 | Phase 2b: media and storage (off the pilot's critical path) |
| 10-17 | Phase 3: field features |
| 3-10 | D-00: Datathon package, its own ML workstream (not on the critical path) |
| 14-15 | MF-04 backtest on 8 weeks of actuals |
| about 19 | Phase 4 trust gates evaluated |
| 17-22 | Phase 5, only after the pilot go/no-go and signed data processing agreements |

The critical path is collecting real delivery outcomes, not writing code. The first model-driven feature on by default lands about 22 weeks out.

### People, budget and decision owners

- **Headcount:**
  - 3 to 4 engineers;
  - half an ML engineer;
  - one operations champion per depot;
  - a part-time native-speaker reviewer for Sinhala and Tamil.
- **Budget:**
  - Build: about USD 25-36k.
  - Running: about USD 58 a month, against a ceiling of USD 100 a month.
  - Detail is in [08-BUSINESS-CASE.md](08-BUSINESS-CASE.md). Each phase lead returns an LKR figure before the phase starts.

| Owner | Decides |
| --- | --- |
| Business owner | Funding at each phase gate |
| Head of Distribution | Pilot go/no-go, message template wording |
| Finance | Write-off thresholds (P-DG-1), the Datathon data licence, fuel allocation |
| Engineering lead | Technical exit gates |

### Programme success (Kandy pilot against the B-05 baseline)

- Fresh deliveries before 08:00 up 5 points or more.
- Calls to the dispatcher per trip down 30%.
- Damaged-goods write-off value down 20%.
- 90% or more of stops recorded in the app.
- Zero cross-store data leaks.
- Zero lost delivery records.

### Risks

| Risk | Answer |
| --- | --- |
| Datathon-trained models reach production | Config guard `ML_ALLOW_DATATHON=false`; production retrains on own actuals only (MF-05) |
| Drivers in Kandy do not adopt the app | Operations champion, induction card, paper fallback runbook, adoption KPI |
| Jev is a new vendor on an alpha endpoint | Behind `DecisionPort`, pinned version, rules fallback, Phase 5 only |
| PDPA No. 9 of 2022 in force 1 Jan 2027 | Data processing agreements and code-side masking before any cloud AI or speech-to-text (P-PD-1) |
| The unmerged branch conflicts with new work | H-00 reconciles it in week 1, before anything else |
| Documentation sprawl | These documents were capped at 2 days; issue plans link here instead of copying |

## Environment matrix

| Environment | Object store | DecisionPort | Datathon models | AI features |
| --- | --- | --- | --- | --- |
| Judge and CI (`docker compose up`) | `database` | stub | active | off |
| Preview | R2 preview bucket | live, OpenRouter key capped at USD 5 | active | on |
| Production | R2 | live after DPA | off (`ML_ALLOW_DATATHON=false`) | off until the DPA is signed |

Secrets live in one GitHub Environment per deployment. Gitleaks runs in CI. A rotation runbook sits beside [deployment.md](../deployment.md).

## Phase graph

```
P0 (week 1) -> P1 (B-04; B-05 in parallel) -> P1.5 (T2-01 early, fuel log)
  -> P2 --+-> P3 field features (on database storage first) ---+
          +-> P2b media (off critical path) -> migrate           +-> Kandy pilot (from week 6)
          +-> P4 models (MF-02 from week 6, gates about week 19)-+      -> go/no-go + DPA -> P5
D-00 Datathon package: own ML workstream, weeks 3-10
```

## Exit gates

| Phase | Gate |
| --- | --- |
| P0 | Branch reconciled, README restored, secrets rotated, restore drill passed with RPO 24 h or less and RTO 4 h or less |
| P1 | Technical baseline (B-04) and business KPI baseline (B-05) recorded |
| P2 | Driver screen at 60 requests a minute or fewer; Hikari pending p99 = 0; outbox lag p95 under 2 s; retention backlog 0; `promtool test rules` passes |
| P2b | Restore drill on the bucket; 100% of objects match sha256; the judge walk still works on `database` |
| P3 | Each item has its rule, edge case and denied-scope test merged |
| P4 | Trust gates met ([05-DATATHON-MODELS.md](05-DATATHON-MODELS.md#trust-gates)) |
| Pilot | Programme success criteria met |

## Pilot and rollout

**Pilot.**
- Kandy hub, all brands, starting week 6: 4 weeks in shadow, then 4 weeks live.
- Kandy has the worst coverage, so it proves offline.
- Phase 3 and 4 items join the pilot as each passes its gate.
- The go/no-go comes before any rollout at Peliyagoda.

**Rollout:**
- an operations champion per depot;
- a 30-minute session on the dock tablet;
- a driver induction card;
- a paper run-sheet fallback runbook;
- a support line for the first 4 weeks;
- adoption measured as the share of stops recorded in the app.

## Tracker

State is in the GitHub Project. This table never records progress.

| ID | Phase | Item | Depends on | Document |
| --- | --- | --- | --- | --- |
| H-00 | P0 | Reconcile the unmerged branch, cut `dev` | none | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| H-01 | P0 | Restore the README | H-00 | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| H-02 | P0 | Gitleaks, rotate secrets, fixed proof secret | none | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| H-03 | P0 | Secrets in GitHub Environments | H-02 | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| H-04 | P0 | Refresh STATUS | H-00 | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| H-05 | P0 | Off-server backup and restore drill | none | [02](02-FOUNDATION-FIXES.md#phase-0-hygiene-and-safety) |
| B-01 | P1 | Backend test baseline | H-00 | [01](01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline) |
| B-02 | P1 | Frontend and browser baseline | H-00 | [01](01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline) |
| B-03 | P1 | Fresh-install judge walk | B-01, B-02 | [01](01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline) |
| B-04 | P1 | Alerts first, then technical baseline | H-00 | [01](01-FOUNDATION-FLOWS.md#phase-1-verify-and-baseline) |
| B-05 | P1 | Business KPI baseline (parallel) | none | [01](01-FOUNDATION-FLOWS.md#b-05-business-kpi-baseline) |
| F-01 | P1.5 | Jittered backoff, honour Retry-After | B-02 | [02](02-FOUNDATION-FIXES.md#phase-15-quick-wins) |
| F-02 | P1.5 | Request timeouts | B-02 | [02](02-FOUNDATION-FIXES.md#phase-15-quick-wins) |
| F-03 | P1.5 | Separate queue lane for positions | B-02 | [02](02-FOUNDATION-FIXES.md#phase-15-quick-wins) |
| F-09a | P1.5 | Worker executor with bulkheads | B-04 | [02](02-FOUNDATION-FIXES.md#phase-15-quick-wins) |
| M-03 | P1.5 | WebP static assets | none | [02](02-FOUNDATION-FIXES.md#phase-15-quick-wins) |
| MF-01 | P1.5 | Admin Models screen, environment guard | F-09a | [05](05-DATATHON-MODELS.md#foundation) |
| T1-01 | P1.5 | Publish risk section (dispatcher, flag) | MF-01 | [05](05-DATATHON-MODELS.md#task-1-service-time-and-lateness) |
| T2-01 | P1.5, P4 | Capacity plan (early, then full) | B-04 | [05](05-DATATHON-MODELS.md#task-2a-demand-forecast) |
| FF-06a | P1.5 | Fuel log | B-02 | [04](04-FIELD-FEATURES.md#ff-06-fuel-log-and-fuel-pass-qr) |
| F-04 | P2 | Snapshots, eviction, sign-out, quota | F-01 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-05 | P2 | Service worker per role, update prompt | F-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-06 | P2 | Driver redo, prefetch tomorrow | F-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-07 | P2 | ETag, aggregated driver read, one SSE stream | B-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-08 | P2 | Position cadence and light audit | F-03 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-09b | P2 | Pool split, only on evidence | B-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-10 | P2 | Reference generation counter | B-01 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-11 | P2 | Planning claim token | B-01 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-12 | P2 | Retention loops and partial indexes | B-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| F-15 | P2 | Container memory limits | B-04 | [02](02-FOUNDATION-FIXES.md#phase-2-foundation-fixes) |
| M-01 | P2b | Object store ports over S3 | F-09a | [02](02-FOUNDATION-FIXES.md#phase-2b-media-and-storage) |
| M-02 | P2b | Shared photo encoder, blur check | none | [02](02-FOUNDATION-FIXES.md#phase-2b-media-and-storage) |
| FF-00 | P3 | Templates and driver screens in si and ta | none | [04](04-FIELD-FEATURES.md#ff-00-language) |
| FF-01 | P3 | Shared report sheet | M-02 | [04](04-FIELD-FEATURES.md#ff-01-shared-report-sheet) |
| FF-02 | P3 | Loader damaged-goods photos | FF-01 | [04](04-FIELD-FEATURES.md#ff-02-loader-damaged-goods-photos) |
| FF-03 | P3 | Operations inquiry with images | FF-02 | [04](04-FIELD-FEATURES.md#ff-03-operations-inquiry) |
| FF-04 | P3 | Live trip channel | F-07 | [04](04-FIELD-FEATURES.md#ff-04-live-trip-channel) |
| FF-08 | P3 | Dispatcher reply suggestions | FF-04, FF-00 | [04](04-FIELD-FEATURES.md#ff-08-dispatcher-reply-suggestions) |
| FF-05 | P3 spike, build later | Voice transcription | F-09a, P-PD-1 | [04](04-FIELD-FEATURES.md#ff-05-voice-transcription) |
| FF-06b | P3 | Fuel Pass QR on the driver phone | FF-06a, step 0 | [04](04-FIELD-FEATURES.md#ff-06-fuel-log-and-fuel-pass-qr) |
| FF-07 | after pilot | Profile photos | M-01, P-PD-1 | [04](04-FIELD-FEATURES.md#ff-07-profile-photos) |
| D-00 | ML stream | Datathon package | none | [05](05-DATATHON-MODELS.md#d-00-datathon-package) |
| MF-02 to MF-08 | P4 | Outcomes, metrics, backtest, retrain, intervals, freshness, reasons | MF-01 | [05](05-DATATHON-MODELS.md#foundation) |
| T1-02 to T1-04 | P4 | Store notice, ETA, handling time | MF-02 | [05](05-DATATHON-MODELS.md#task-1-service-time-and-lateness) |
| T2-02, T2-03, T2B-01 | P4 | Workshop clash, accuracy, deferral text | MF-02 | [05](05-DATATHON-MODELS.md#task-2a-demand-forecast) |
| AI-0 | P5 | Model gateway (ADR-005) | pilot, DPA | [06](06-INTELLIGENCE.md#ai-0-the-gateway) |
| AI-J1, AI-J5, AI-1, AI-2 | P5 | Triage, reply ranking, plan explainer, infeasibility | AI-0 | [06](06-INTELLIGENCE.md#items) |
