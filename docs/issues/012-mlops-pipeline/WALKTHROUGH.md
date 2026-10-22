# Walkthrough: the models as a pipeline

Issue [#12](https://github.com/jv-ransika/yi-yi/issues/12), built on `feat/mlops-pipeline` from the [plan](PLAN.md). The rules are R-ML-36 to R-ML-40 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md), the cases ML-57 to ML-61 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md).

## What was built

A weekly run retrains on the system's own data and reference, puts a candidate that passed its gates into a model store the service picks up without a redeploy, registers it, and makes it the shadow: it scores every published plan beside the active model and never answers. A nightly review compares the two on the same labelled stops. An administrator promotes the shadow; the model it replaces becomes the shadow, and the system puts it back if it turns out better.

```
training.run --store --register          (cron, our own server)
   │  reference.json ◄── GET /api/ml/training/reference
   │  retrain / retrain_demand: gates as before
   ▼
ml-store/<name>@<version>/               (read only in the ml container)
   │  checked: SHA-256, pickle allowlist; else listed as rejected
   ▼
ml:RegisterModel ─► ml:ShadowModel       (late risk; demand is registered only)
   │
PlanScoringJob: active answers ──► ml.delivery_predictions   (screens, outcomes)
                shadow scores  ──► ml.shadow_predictions     (nobody reads)
   │
ShadowReviewJob 04:10: shadow vs active on labelled stops ─► ml.shadow_reviews
   │
ml:ActivateModel (administrator) ─► the replaced model becomes the shadow
   │
review: replaced model BETTER within 28 days ─► the system activates it again
```

## Layer by layer

**ml-server**
- `app/store.py`: the store reader. Entries are `<name>@<version>/entry.json` plus files, each checked with `registry.check_entry` before `joblib.load`; a rejected entry keeps its reason until its files change; a name starting with `.` is a copy in progress. Rescans at most every 30 s, or 5 s when a request names a model not loaded.
- `app/registry.py`: `check_entry` (one entry, the image's or the store's), `load_model` (refuses an object that is not what its pipeline serves), `Loaded.resolve(kind, label)` and `Loaded.available()`. `ML_STORE_DIR` turns the store on.
- `app/scoring.py`, `app/schemas.py`, `app/main.py`: an optional `model` on both requests, validated as a label; an unknown one is 422. `/health` adds `available` and `rejected`.
- `training/fetch.py` `reference()`, `training/planning.py` `reference_from_json` and `load_reference`, `training/retrain_demand.py` `load_reference_json`: the backend's reference in place of the CSVs.
- `training/backend.py`: `publish` (into the store, checked first, written under a hidden name and renamed), `register` (`ml:RegisterModel`, then `ml:ShadowModel` for late risk; a rerun finds it registered and carries on).
- `training/run.py`: one reference fetch per run, `--store`, `--register`.

**Backend, `intelligence`**
- `contract/ModelViews`: `ModelStatus.SHADOW`, `ShadowRole` (`CANDIDATE`, `REPLACED`), `ShadowReviewView`, `ml:ShadowModel`; `ModelVersionView` carries `shadowRole` and `shadowedAt`.
- `domain/ModelRegistryPolicy`: the legal moves and what each does to the kind's other models. `domain/ShadowReview`: the verdict, the window and the rollback condition. `domain/ModelGate`: the active model must be among the labels the service holds.
- `application/RegistryMoves`: applies a move in the caller's transaction (every changed model leaves active or shadow first, because both are unique indexes checked per statement), version-guarded on the model named, with an `ml.model_events` row per change. `ModelHandlers.Activate` and `.Shadow` use it.
- `application/PlanScoringJob`: one `/health` call feeds the gate and the shadow; the request names the active model to a service that lists `available`; `shadow(...)` scores the shadow after the plan's own scoring is written. `ForecastJob` names the active demand model the same way.
- `application/ShadowReviewJob`: the nightly review and the rollback, with an audit row as the process.
- `application/ReferencePayload.forTraining`, `IntelligenceDataQuery.trainingReference` and `.shadowReviews`; `web/IntelligenceController`: `GET /api/ml/training/reference`, `GET /api/ml/models/shadow-reviews`.
- `infrastructure/JdbcIntelligenceRepository`: the moves, events, shadow predictions, pairs and reviews. `infrastructure/ModelServingAdapter.availableModels`.

**Migrations**: `20261023T1200_ml_model_pipeline.sql` (the `shadow` status and its role, one shadow per kind, `ml.shadow_predictions`, `ml.shadow_reviews`, `ml.model_events`, grants and row-level security); `20261023T1201_iam_ml_shadow_model.sql` (the catalogue row, and the auditor's policy naming it in its deny).

**Frontend**: `shared/domain/intelligence.ts` mirrors the status, role and review; the admin Models screen labels a shadow "Being compared" and offers "Use this model" on it.

**Deployment**: `compose.yaml` mounts `ML_STORE_PATH` (default `./ml-store`, git-ignored) read only at `/srv/store`.

## Flows

**Retrain and hand to the pipeline.** `training.run` fetches the reference for every depot from 60 days before its longest window to a year ahead, exports and retrains each kind as before, and for a candidate that exits 0 copies it into the store and, with `--register`, registers it and (late risk) makes it the shadow. Nothing activates it.

**Score a plan with a shadow.** `PlanScoringJob.score` reads the plan, asks `/health` once, gates the active model, scores with it and writes the plan's predictions. Then, when the kind has a shadow the service holds, it sends the same routes naming the shadow and writes `ml.shadow_predictions`, once per plan. Any failure there is counted and changes nothing.

**Review, promote, roll back.** At 04:10 `ShadowReviewJob` pairs the shadow's predictions with the outcomes the active model answered (`ml.delivery_outcomes`), judges them, and keeps the review. An administrator activates the shadow; `ModelRegistryPolicy.activate` makes the old model the `replaced` shadow. If a later review finds that one `BETTER` within 28 days, `ModelRegistryPolicy.rollBack` puts it back.

## Run and verify locally

```bash
cd ml-server && pytest                       # store, pipeline, reference, retrain
cd backend && mvn -Dtest='ModelRegistryPolicyTest,ShadowReviewTest,IntelligenceDomainTest,ModuleBoundaryTest' test
cd backend && mvn verify                     # with Docker or TEST_DATABASE_URL: IntelligenceIntegrationTest
cd frontend && npm run typecheck && npm test
```

By hand: start the stack with `ML_STORE_PATH` set, run `training.run --store ... --register` against seeded deliveries, read `/health` (the candidate under `available`), publish a plan and check `ml.shadow_predictions`, then run `ShadowReviewJob` and read `/api/ml/models/shadow-reviews`.

## Decisions and where they are recorded

The ten decisions are in the [plan](PLAN.md#decisions): the reference from the backend, the store beside the image, the backend naming the model, shadow predictions kept apart, the review, promotion by a person, the champion as challenger, rollback as the only automatic step, demand stored but not shadowed, and no notification. The rules are R-ML-36 to R-ML-40.

## Known gaps

- The Java integration tests (`IntelligenceIntegrationTest`, seven new among them) were written and compile but have not run: there was no PostgreSQL on the machine that built this. Run `mvn verify` with a database before merging.
- The demand model is stored and registered but has no shadow forecasts: a review needs finished weeks of actuals.
- Nothing notifies the administrator of a review or a rollback: Notification has no administrator recipient. Both are counted for the alert rules and readable on the endpoint.
- Loaded store models are never evicted from memory; a retired entry is removed from the store by hand.
- The Models screen has no panel for the reviews.
