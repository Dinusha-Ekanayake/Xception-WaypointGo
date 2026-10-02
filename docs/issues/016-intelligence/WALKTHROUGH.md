# Issue #16: Intelligence module, walkthrough

This page covers what was built and how to run and check it. The decisions and their reasons are in [PLAN.md](PLAN.md). The other references are:
- rules R-ML-01 to 06 and R-RCP-06 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md);
- cases ML-01 to 08 in [EDGE-CASES](../../architecture/EDGE-CASES.md);
- A-36 to A-38, P-28 and P-29 in [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md);
- the module contract in [MODULES](../../architecture/MODULES.md) section 12.

The work is backend only. The only frontend change is the types-only mirror `frontend/src/shared/domain/intelligence.ts`.

## What was built

### The model service, `ml-server/`

A FastAPI service that serves the trained Datathon models and nothing else: it never trains and never reads the database. See its [README](../../../ml-server/README.md).

- **`datathon/src/`** is the Datathon package, vendored unchanged. The pickled models name their classes `src.<module>`, so the package keeps that name.
- **`models/`** holds the five model files, in Git LFS. **`manifest.json`** gives each model's version, training range, validation metrics and the SHA-256 of every file. `app/registry.py` refuses to start if any file differs.
- **`app/scoring.py`** turns a request into the two frames the Datathon code reads, `task1_test_inputs` and `route_legs_test`, plus the general tables:
  - It routes a date that has no road-conditions row to the no-road-conditions model.
  - It adds missing festival columns, so a calendar extended past the supplied one still forecasts.
- **`app/main.py`** serves `GET /health`, `POST /v1/delivery-risk` and `POST /v1/demand-forecast`.
- **Tests** cover four real planned routes. The API must equal the vendored `inference.predict_task1` on them, both the blend and the fallback, and the Task 2A submission must be reproduced exactly.

### Backend, `backend/src/main/java/com/waypoint/dispatch/intelligence/`

**contract**
- `TravelAndServiceEstimator`: `DemandForecast` gains `degraded`.
- `ModelViews`: the kinds are now `delivery_risk` and `demand_forecast`; adds metrics, activation and retirement fields.
- `PredictionQuery` and `PredictionViews`: plan scoring, stop predictions, supply probability and training rows.

**domain**
- `DeterministicEstimator`: the outlet's history median or its allowance (A-16), slack logistic lateness (A-37), and a weekday-mean forecast (A-38).
- `ModelGate` (R-ML-04).
- `SupplyPolicy` (R-RCP-06).
- `PlannedRoutes`: turns a trip into model legs.
- `CircuitBreaker`.

**application**
- `IntelligenceConsumers`: queue a scoring on `plan.published` and `plan.revised`.
- `PlanScoringJob`: every 30 s.
- `ForecastJob`: Mondays at 04:00.
- `ModelHandlers`: `ml:RegisterModel`, `ml:ActivateModel`, `ml:RetireModel`.
- `IntelligenceDataQuery`: reads, the estimator port, supply probability and the training export.
- `ReferencePayload`: rebuilds the CSV-shaped reference tables.

**infrastructure**
- `ModelServingAdapter`: JDK `HttpClient` with timeouts, circuit breaker and metrics.
- `JdbcIntelligenceRepository`.

**web**
- `IntelligenceController` under `/api/ml`:
  - `GET models`
  - `GET plans/{planId}/predictions`
  - `GET forecast?depot&brand&from=2026-W41&to=...`
  - `GET orders/{orderId}/supply-probability`
  - `GET training/deliveries?depot&from&to&after`

**Outside the module (additive)**
- **Planning:** `PlanQuery.plan(planId)`. `PlanView.plannedWithoutPredictor` is now read from `PredictionQuery.planScoring` through an `ObjectProvider`, so Planning still works with no Intelligence bean.
- **Execution:** `ExecutionQuery.actuals`, which keeps wait apart from service (EXE-18).
- **Ordering:** `OrderQuery.dailyVolumes`.
- **Reference:**
  - The importer loads the new `traffic_speed.csv` and `road_conditions.csv` (both in `data/General Data/`, recorded in `provenance.json`).
  - `ReferenceQuery` gains `calendarDays`, `trafficSpeed`, `roadConditions`, `depotCodes` and `brandCodes`.
  - `TravelView` gains `roadClass` and `freeFlowKmh`.
- **Config:** `platform/config/IntelligenceProperties` (`app.ml.*`, `ML_BASE_URL`).

**Migrations**
- `20261003T0100_ml_tables.sql`:
  - `ml.model_versions`, with one active model per kind;
  - `ml.plan_scorings`;
  - `ml.delivery_predictions` and `ml.demand_forecasts`, both write-once;
  - depot-scoped row-level security and no `DELETE`.
- `20261003T0101_iam_ml_actions_implemented.sql`: adds `ml:ExportTrainingData` and flips the `ml:*` actions to implemented.

**Deployment**
- `compose.yaml` gains an `ml` service with no published port. The backend points at it but does not depend on it.
- `deploy/vps/deploy.sh` runs `git lfs pull` and stops if `git-lfs` is missing.
- CI has a `ml-server` job: LFS checkout, Python 3.11, `pytest`.

## Flows, end to end

**Scoring a published plan**
1. `plan:Publish` commits `plan.published`.
2. `ml.on-plan-published` inserts a pending `ml.plan_scorings` row, once per plan.
3. `PlanScoringJob` claims it with `FOR UPDATE SKIP LOCKED` and leases it.
4. In one read as the process it gathers:
   - the plan;
   - each stop's order size;
   - each trip's vehicle and travel profile;
   - eight weeks of the depot's actuals for the history medians.

   It then computes the deterministic answer for every stop.
5. `ModelGate` checks whether serving is configured, whether a `delivery_risk` model is active, and whether `/health` reports that exact `name@version`.
6. If the gate allows it, the job calls the model service with no transaction open (R-ML-01), sending the routes and the reference tables.
7. In a transaction of its own it writes one prediction per stop: the model's, or the deterministic one marked degraded. It writes the scoring as `scored` or `degraded`, with the reason and the road-conditions mode.
8. A degraded plan is tried again while a model is active (P-28). On a read, the model's predictions win.
9. `GET /api/plans/{id}` now reports `plannedWithoutPredictor = false` once a model has scored the plan.

**The weekly forecast**
1. `ForecastJob` asks the model for the next 10 ISO weeks for every depot and brand, sending the calendar from 2024-01-01.
2. If the model cannot answer, it computes the weekday mean of the last eight weeks from `OrderQuery.dailyVolumes`.
3. It stores one run, labelled. A read takes the newest per week.

**The registry**
- `ml:RegisterModel` records a model.
- `ml:ActivateModel` makes it the one active model of its kind (R-ML-03). It needs `expectedVersion`.
- `ml:RetireModel` is final and needs a reason.
- Policy limits all three to the administrator; the auditor is denied them.

## Running and checking it locally

1. `git lfs pull`. Then start the model service as in [development.md](../../development-docs/development.md#the-model-service), or run `docker compose up --build`, which starts it as `ml`.
2. Run `migrate` and `import-reference`. The import now loads traffic speed and road conditions.
3. As an administrator, register and activate both models. The names and versions are in `ml-server/manifest.json`:

   ```json
   {"kind":"ml:RegisterModel","payload":{"name":"datathon-task1-blend","version":"2026.1","kind":"delivery_risk"}, ...}
   {"kind":"ml:ActivateModel","expectedVersion":1,"payload":{"name":"datathon-task1-blend","version":"2026.1"}, ...}
   ```

4. Publish a plan. Within 30 s, `GET /api/ml/plans/{planId}/predictions` shows every stop with `datathon-task1-blend@2026.1`, and the plan reads `plannedWithoutPredictor: false`. Dates after 2026-06-28 show `roadConditions: fallback`.
5. Stop the `ml` container and publish again. The predictions are `deterministic` and degraded, with the reason, and the plan reads `plannedWithoutPredictor: true`.
6. The metrics at `/prometheus` are `waypoint_ml_call`, `waypoint_ml_fallback`, `waypoint_ml_circuit_open`, `waypoint_ml_scoring_pending`, `waypoint_ml_scoring`, `waypoint_ml_forecast` and `waypoint_ml_estimate`.

## Tests

| Test | Covers | Database |
| --- | --- | --- |
| `ml-server/tests/test_service.py` (11) | API equals the vendored blend and fallback, deterministic repeats, refusals, Task 2A reproduced, calendar past the supplied one, altered model file | no |
| `intelligence/domain/IntelligenceDomainTest` (17) | Estimator, gate, supply policy, routes | no |
| `intelligence/application/IntelligenceIntegrationTest` | A real published plan scored by the model, with the service down, rescored when it is back, with a version mismatch; one scoring per plan; another depot's predictions not found; forecasts by model and fallback; registry rules; 403 for a dispatcher activating; training export; supply probability; reference import | yes |

`CommandPathIntegrationTest`, `ModuleBoundaryTest` and `EventCatalogueTest` pick up the module unchanged.

## Known gaps

| Gap | Owner |
| --- | --- |
| Screens: dispatcher Forecast and late risk, store supply probability, admin model registry | #19, #18, #22 |
| `git-lfs` must be installed on the VPS once before the first deploy that carries `ml-server` | operations |
| Road conditions end on 2026-06-28; later dates use the fallback model until a road-condition feed exists | data |
| No retraining pipeline; the training export is its input | a later issue |
| Supply probability is deterministic (planned versus deferral rate); a learned model would replace it | a later issue |
| Execution's ETA still uses `EtaPolicy`; the port has no travel-time method | #12 |
