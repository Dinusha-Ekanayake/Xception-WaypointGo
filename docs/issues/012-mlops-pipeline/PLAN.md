# Run the models as a pipeline: retrain, shadow, promote, roll back

Issue [#12](https://github.com/jv-ransika/yi-yi/issues/12) in `jv-ransika/yi-yi`, following [#5](../005-final-models/PLAN.md) and [#7](../007-demand-retraining/PLAN.md). Reviewed against `dev` at `c7692fc` on 2026-10-22.

## Goal

A model is kept current without anyone editing the repository: a weekly run retrains on the system's own data, a candidate that passes its gates is put in a model store and scored beside the active model on every published plan, an administrator promotes it once it has proven itself on real outcomes, and the system puts the previous model back if the new one turns out worse. A person stays on the one step that changes what answers (promotion); the system may only ever undo that step.

## Current state

- `training.run` retrains both kinds weekly on our own server and writes `<out>/<date>/`. Handing a candidate over is a commit to `ml-server/models/` and `manifest.json` and a rebuilt image (README, "Handing over a new model").
- The retraining reads its reference tables from `data/General Data/*.csv`. That calendar ends on 2026-06-28; the backend generates later days by policy (`OperatingCalendarPolicy`) and sends them with every scoring request. So `retrain_demand` refuses every order placed after 2026-06-28 ("order days are not in the calendar"), and `retrain` joins the calendar with a left join and trains on empty `dow`, `is_payday`, `is_holiday`, `festival_ramp` and `monsoon` for those days, while serving sends them filled: the training/serving skew `planning.py` exists to prevent (R-ML-18).
- The model service loads exactly one model per kind, from the image, and `/health` reports that one. `ModelGate.decide` uses a model only when that one label equals the registry's active label (R-ML-04).
- The registry (`ml.model_versions`) has `registered`, `active`, `retired`; `ml:ActivateModel` returns the previous active model to `registered`.
- Outcomes and daily metrics exist (`OutcomeJob`, `ModelMetricsJob`, `ModelQualityJob` with prediction drift, R-ML-12, R-ML-13). `OutcomeJob` reads the newest row of `ml.delivery_predictions` per order, so a second model's predictions cannot be stored there without changing what every metric measures.
- Notification routes by outlet, depot and vehicle only (`ScopeKind`); there is no administrator recipient.

## Layer ownership

| Piece | Where | Notes |
| --- | --- | --- |
| `GET /api/ml/training/reference?depot=&from=&to=` | `intelligence/web`, `IntelligenceDataQuery`, `ReferencePayload.forTraining` | The outlets, service allowances, traffic speed and calendar exactly as a scoring request builds them. `ml:ExportTrainingData` on `wpt:ml:training:<depot>` for every depot named, so scope is checked as for the other exports; no new action |
| `training/fetch.py` `reference()` | `ml-server/training` | One call; written beside the export as `reference.json` |
| `planning.reference_from_json`, `retrain_demand.load_reference_json` | `ml-server/training` | The same DataFrames the CSV readers return; the CSV path stays for dry runs on the Datathon data |
| Model store | `ml-server/app/store.py`, `registry.py` | `ML_STORE_DIR/<name>@<version>/entry.json` plus its files. Each entry is checked like the image's models (SHA-256 against its own entry, pickle allowlist) before it is loaded; a failed entry is listed as rejected and never loaded. Rescanned at most every 30 s, on `/health` and when a request names a model not loaded |
| Model selection | `app/schemas.py`, `scoring.py`, `main.py` | Optional `model: "name@version"` on both requests; absent means the image's model, as today. `/health` adds `available` (every loaded model, with its source) and `rejected` |
| `training/backend.py`, `run.py --store --register` | `ml-server/training` | A candidate that passed its gates is copied into the store, registered (`ml:RegisterModel`) and, for late risk, made the shadow (`ml:ShadowModel`), through the command endpoint with the administrator session the run already uses |
| `ModelStatus.SHADOW`, `ml:ShadowModel` | `intelligence/contract`, `ModelHandlers`, migration | At most one shadow per kind. `shadow_role` is `candidate` (set by `ml:ShadowModel`) or `replaced` (the model a promotion replaced) |
| `ModelRegistryPolicy` | `intelligence/domain` | Pure: which moves are legal and what each does to the other models of the kind |
| `ShadowScoring` | `intelligence/application`, called from `PlanScoringJob` | After the plan's own scoring is written, the shadow scores the same request with no transaction open; stored in `ml.shadow_predictions`, which no screen or metric reads |
| `ShadowReview` (pure), `ShadowReviewJob` (nightly) | `intelligence/domain`, `application`, `ml.shadow_reviews` | The shadow against the active model on the same labelled stops |
| Rollback | `ShadowReviewJob`, `ml.model_events` | A `replaced` shadow that beats the model that replaced it is activated again by the system, with the reason |
| `GET /api/ml/models/shadow-reviews` | `intelligence/web` | `ml:Read` on `wpt:ml:model:*`, like `/models/backtest` |
| `ModelServingAdapter.availableModels` | `intelligence/infrastructure` | Kind to every label the service has loaded; `model` is sent only to a service that lists `available`, so an older service keeps working |
| Frontend | `frontend/src/shared/domain/intelligence.ts` | `SHADOW` in the model status and the review view. No screen in this issue |

No module reads another's tables. The ml-server stays stateless towards the database and never writes the store (mounted read only).

## Decisions

1. **Reference from the system, not the CSVs.** Training must read the days serving reads. The backend already builds them for scoring (`ReferencePayload`), so the export reuses it rather than a second definition (rule 5). `run` fetches the calendar from 2024-01-01 (where the forecast's trend and the training data start) to a year past today, as scoring sends it, capped at 2,000 days per request.
2. **Store beside the image, not instead of it.** The image keeps its manifest models as the default answer, so a service with an empty or unreadable store behaves exactly as today. The store is a directory mounted read only (`ML_STORE_DIR`); only the retraining run writes it. Each entry carries its own manifest entry, the same shape `retrain` already writes, and is refused unless every file matches its SHA-256 and passes `pickle_scan`. A rejected entry is reported on `/health`, never silently skipped (rule 9).
3. **The backend names the model.** With more than one model per kind loaded, "the served label equals the active label" becomes "the active label is among the loaded labels", and every request names the label it means. The guarantee of R-ML-04 is unchanged: a prediction is stored against the model that produced it.
4. **Shadow predictions are kept apart.** `ml.shadow_predictions`, unique per plan, order and label. Putting them in `ml.delivery_predictions` would change what `OutcomeJob`, the metrics, the backtest and every screen read. A shadow call never changes the served scoring and its failure is counted, not retried.
5. **The review.** Nightly at 04:10, after outcomes and metrics. Window: the last 14 days of service dates, from the day the model became shadow. Compared on stops that have a label, a shadow prediction, and a prediction by the active model (or the deterministic one when no model is active). Verdict as R-ML-16 and `retrain`'s replay: `BETTER` when both Brier score and log loss are lower on at least 200 stops, `WORSE` when both are higher, `MIXED` otherwise, `NOT_ENOUGH` under 200 stops or with only one class. One row per shadow model and day; a rerun the same day replaces that day's figures.
6. **Promotion stays a person's.** Activation is `ml:ActivateModel` by an administrator, as today, from the Models screen or the command endpoint, with the review to read. Not automatic: the README's "a model reaches production by a reviewed change" becomes "a model answers only after a person activates it", and the review is the evidence.
7. **The champion stays as the challenger.** Activating a model makes the one it replaces the shadow (`shadow_role = replaced`), and a shadow already there returns to `registered`. So the old model keeps being measured on the new model's plans, with no extra machinery.
8. **Rollback is the one automatic step, and only backwards.** When the shadow is `replaced`, the active model was activated in the last 28 days, and the review says the shadow is `BETTER`, the system activates the shadow again and returns the newer model to `registered`. It is written in the job's own transaction as the process, version-guarded on both rows, with an `ml.model_events` row and an audit row naming the review and both scores (rule 8). It never activates a model that was never active, and never acts on `MIXED` or `NOT_ENOUGH`.
9. **Demand is stored and registered, not shadowed.** A demand candidate goes into the store and is registered so an administrator can activate it with its backtest report; `ForecastJob` sends the active label like `PlanScoringJob`. Shadow forecasts need finished weeks of actuals to judge and are left to a follow-up.
10. **No notification.** Notification has no administrator recipient. A promotion-ready review and a rollback are counted (`waypoint.ml.shadow_verdict`, `waypoint.ml.model_rolled_back`) for the alert rules, and visible on `/api/ml/models/shadow-reviews` and in the registry.
11. **Not in scope:** evicting old models from the store's memory (an entry is removed from the store directory by hand when retired), a Models-screen panel for reviews, and retraining the Task 1 parallel model.

## Pull requests

One branch, `feat/mlops-pipeline`, in four commits that each build and test on their own:

1. **Reference from the system:** the endpoint, `fetch.reference`, the JSON readers, `run` using them; tests.
2. **Model store and selection:** `app/store.py`, registry and scoring changes, `/health`, `run --store --register`, compose volume; tests.
3. **Shadow:** migrations (status, roles, `shadow_predictions`, `shadow_reviews`, `model_events`, catalogue row), `ModelRegistryPolicy`, `ml:ShadowModel`, the changed activation, `ShadowScoring`, `ShadowReviewJob`, the read endpoint, `availableModels` in both jobs; tests.
4. **Rollback and documents:** the rollback, rules R-ML-36 to R-ML-40, edge cases ML-57 to ML-61, MODULES, STATUS, the README, the walkthrough and the log.

## Tests

| Level | Covers |
| --- | --- |
| Domain (Java) | `ModelRegistryPolicy`: every move from every status; `ShadowReview`: each verdict, the 200-stop floor, one class only; the rollback condition; `ModelGate` with several loaded labels |
| Integration (Java) | The reference export equals what a scoring request sends for the same day; `ml:ShadowModel` and the activation that makes the old model the shadow; shadow predictions stored apart and absent from `/plans/{id}/predictions`; a review and a rollback with their event and audit rows |
| Authorization (Java) | The reference export for a depot outside scope is 403 and audited |
| `ml-server` pytest | A store entry loads and answers when named; a tampered or unsafe entry is rejected and listed; an absent `model` answers with the image's model; an unknown model is 422; `run --store --register` copies, registers and shadows with a fake backend; the JSON reference gives the same features as the CSVs on the same days, and calendar days past 2026-06-28 are filled |

## Verification

`mvn verify` (the integration tests need PostgreSQL), `pytest` in `ml-server`, `npm run typecheck` and `npm test` for the mirror. Then on a local stack: one `training.run --store --register` against seeded deliveries, `/health` listing the stored model, a published plan scored by both, `ShadowReviewJob` run by hand.
