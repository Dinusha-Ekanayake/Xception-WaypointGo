# Issue #16: Intelligence module (`ml` schema) and model serving, plan

Written before code, per AGENTS.md "Issue Documents". What was actually built goes in `WALKTHROUGH.md`.

## Progress (handoff)

Branch `16-intelligence`, from `dev` at `6588cdd`. Backend only; the dispatcher Forecast and late-risk screens, the store's supply probability and the admin model registry screen are placed by the role UIs. The only frontend change is the types-only contract mirror.

| Step | Status | Notes |
| --- | --- | --- |
| 0 Plan | done | this file |
| 1 Python model service `ml-server/` | | vendored Datathon code, models in Git LFS, FastAPI, pytest |
| 2 Reference: traffic speed and road conditions | | CSVs, importer, `ReferenceQuery` |
| 3 Schema and catalogue | | `ml.*` tables, `ml:*` actions implemented |
| 4 Domain | | deterministic estimator, model gate, supply policy, route requests |
| 5 Application, adapter, web | | scoring and forecast jobs, registry commands, reads, Planning's flag |
| 6 Deployment and CI | | compose service, VPS LFS, CI job |
| 7 Docs closeout | | walkthrough, registers, STATUS, log |

## Where `dev` stood

- **Already exists:**
  - `intelligence/contract` (`TravelAndServiceEstimator`, `ModelViews`) and its mirror `frontend/src/shared/domain/intelligence.ts`;
  - the `ml` schema and the `waypoint_ml` role (`20260930T1200`), and `ModelRole.ML`;
  - catalogue rows `ml:Read`, `ml:RegisterModel`, `ml:ActivateModel`, `ml:RetireModel`, all unimplemented. The administrator holds `ml:*`, dispatchers and auditors `ml:Read`, and auditors are denied the three writes.
- **Nothing uses it yet.** Planning hard-codes `plannedWithoutPredictor = true` (`PlanRecords.java`), and the dispatcher shows "The time predictor is not running".
- **Missing model inputs.** `ref.traffic_speed` and `ref.road_conditions` exist but are empty: no CSV feeds them.
- **The trained models** are in `tharushaudana/Temp`, `datathon/`:

  | Model | What it scores | Validation |
  | --- | --- | --- |
  | Task 1 | service minutes and P(late) per stop, from whole planned routes; blend of two LightGBM/XGBoost/CatBoost pipelines with a Monte-Carlo route simulator | log-loss 0.152 / 0.129, AUC 0.98, service MAE 3.6 min against 6.5 for the allowance |
  | Task 1 fallback | the same, without road conditions | log-loss 0.239 / 0.166 |
  | Task 2A | weekly total and chilled m³ per depot × brand | WAPE 4.15% / 4.39% |

## Which layer owns each dependency

| Dependency | Owner | Resolution |
| --- | --- | --- |
| Planned routes of a published plan | Planning | `PlanQuery.draft(planId)` (trips, stops, windows, planned arrivals) and the `plan.published` / `plan.revised` events |
| Order size, temperature, order date | Ordering | `OrderQuery.order` |
| History for the deterministic forecast | Ordering | new additive `OrderQuery.weeklyVolumes` |
| Outlets, vehicles, travel profiles, calendar, traffic, road conditions | Reference | `ReferenceQuery`, extended with traffic speed, road conditions and calendar days |
| Actuals for the training export and history medians | Execution | new additive `ExecutionQuery.actuals`, with wait kept apart from service (EXE-18) |
| Model inference | Outside the process | the Python service, through the `ModelServingAdapter` port behind a circuit breaker |
| The predictor flag on a plan | Planning reads, Intelligence answers | `PredictionQuery.planScoring(planId)` at read time |

## Decisions

1. **Serving runtime: a Python service** (`ml-server/`, FastAPI) vendoring the Datathon `src/` code and serving the saved models. It never retrains. ONNX in the JVM is not viable: features come from pandas and a Monte-Carlo route simulator, and the forecast is statsmodels. This is ADR-001's extraction trigger ("model serving needs a Python runtime") firing.
2. **Model files live in Git LFS** (`ml-server/models/*.joblib`). A `manifest.json` names each model's version and the SHA-256 of every artifact; the service refuses to start on a mismatch.
3. **Published plans are scored; allocation is unchanged.** Plan building keeps the booklet allowances, because plans are validated against them (R-PLN-08). On `plan.published` or `plan.revised` every stop gets service minutes and P(late), stored with the model version, as advisory late risk.
4. **Backend only.** Screens are the role UIs' work.
5. **Traffic speed and road conditions are reference data** (D9): imported into `ref` from `data/`, and sent with each scoring request. A service date with no road conditions is scored by the shipped fallback model, and the response says so.
6. **Model kinds are `delivery_risk`** (Task 1: service minutes and P(late) from one artifact) **and `demand_forecast`** (Task 2A). This replaces the contract's unused `service_time`, `lateness` and `demand`.
7. **A model is used only when the registry's active version is the version the server reports.** A mismatch, the server down or the circuit open gives the deterministic answer with `degraded` and a reason.
8. **No HTTP inside a transaction.** The plan consumers record a scoring request; a job calls the server with no transaction open and stores the result in a transaction of its own.
9. **`plannedWithoutPredictor` is answered by Intelligence at read time**: true unless the plan's predictions came from an active model. Planning stops hard-coding it.
10. **Model registry authoring is commands only** (`ml:RegisterModel`, `ml:ActivateModel`, `ml:RetireModel`, administrator). The admin screen comes later (#22).
11. **Forecasts are precomputed** weekly (Mondays 04:00, next 10 weeks) and stored, so a read never waits on the server.
12. **Supply probability (R-RCP-06) is a deterministic query** with its basis named: an order in a published plan is 1 minus the outlet's recent failure rate; an unplanned one is 1 minus the recent deferral rate for its depot, brand and temperature.

## PR breakdown

One pull request into `dev`, one commit per step above.

## Known gaps, owned elsewhere

- Screens: dispatcher Forecast and late risk (#19), store supply probability (#18), admin registry (#22).
- No retraining pipeline; the training export is its input.
- Road conditions in the supplied data end on 2026-06-28, so later dates use the fallback model until a road-condition feed exists.
- Execution's ETA still uses `EtaPolicy`; the port has no travel-time method yet.
