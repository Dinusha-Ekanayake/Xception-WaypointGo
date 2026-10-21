# Serve the final Datathon models

Issue [#5](https://github.com/jv-ransika/yi-yi/issues/5) (in `jv-ransika/yi-yi`; the other `005-` folder belongs to the earlier repository). Reviewed against `main` at `9690902` on 2026-10-21.

## Goal

The model service serves the team's final Datathon models in place of the earlier ones, with the same endpoints and the same fallbacks, so the backend and the screens keep working unchanged apart from one added field in the forecast request.

## Current state

- `ml-server/` serves `delivery_risk` (`datathon-task1-blend@2026.1.1`: pipelines A and B blended 0.4/0.6 and 0.3/0.7, plus a B model without road conditions) and `demand_forecast` (`datathon-task2a@2026.1.1`). Five files in `models/`, the vendored package in `datathon/src/`.
- The final submission has two files. Task 1 is `task1_parallel_model.joblib`: a `parallel_model.ParallelModel` (a Monte-Carlo simulator with 500 draws per route, plus LightGBM, blended in log-odds) with its no-road fallback in the same file. It was retrained on all training data to 2026-02-14; holdout service MAE 3.72 min, late log loss 0.131, AUC 0.976, fallback 3.73 and 0.166. Task 2A is `task2a_model.joblib`: a `task2a_demand.DemandModel`, a bottom-up order regression fitted on orders 2024-01-01 to 2026-03-28.
- The pickles name their classes as the top-level modules `parallel_model`, `mc_task1` and `task2a_demand`, not `src.*`. The Task 1 model also carries an `mc_task1.Context` holding the training reference tables.
- Task 2A needs the outlet table when it predicts. The backend's `ForecastJob` sends only the calendar and the weeks.

## Layer ownership

| Piece | Where | Notes |
| --- | --- | --- |
| Vendored model code | `ml-server/datathon/mc_task1.py`, `parallel_model.py`, `task2a_demand.py` | Copied unchanged from the submission. `datathon/src/` is removed |
| Inference contract | `ml-server/datathon/inference.py` | Ours: the notebook's final cell as functions. The tests compare the API with it |
| Loading | `ml-server/app/registry.py` | Two files for the Datathon pipeline; the planning pipeline (MF-05) unchanged |
| Request to frames | `ml-server/app/scoring.py` | Builds a request-local `Context` from the tables sent, keeping the trained category vocabulary |
| Forecast outlets | `intelligence/application/ReferencePayload`, `ForecastJob` | Through `ReferenceQuery.outletsOfDepot`; no other module changes |

## Decisions

1. **The reference tables come from the request, not the pickle.** The model's embedded `Context` holds the training CSVs, which end at the competition's last day. The service rebuilds the `Context` from what the backend sends, so the backend stays the one source of the reference data and new days score. The category vocabulary is copied from the trained `Context`, because the codes must be the training ones. A test proves the rebuild equals the trained `Context` on `data/General Data`.
2. **The loaded model is never mutated.** Each request uses a shallow copy that carries its own `Context`, so concurrent requests cannot see each other's tables.
3. **The no-road split stays in the service.** A route whose district and date have no road-conditions row is scored with `use_fallback=True` and labelled `fallback`, as today.
4. **New versions.** `datathon-task1-parallel@2026.10.1` and `datathon-task2a@2026.10.1`. Both keep the `datathon-` prefix, so `ModelGate` still keeps them out of production. A retired version is never reused.
5. **`outlets` is required in the forecast request.** Without it the service answers 422, the backend degrades to its deterministic forecast and says so (rule 9). The two are deployed together.
6. **Python 3.12.** That is the environment where the team reproduced both submissions from the saved files. XGBoost and CatBoost are no longer imported, so they are dropped.
7. **The training tools follow the served features.** `training/leakage.py` builds its frames with the new feature code; `training/datathon_export.py` reads the CSVs itself. The leakage figures in the README were measured on the earlier pipeline until the check is rerun on the licensed data.

## Pull requests

One pull request into `dev`: the vendored package and models, the service, the backend's forecast request, tests and documents.

## Tests

| Level | Covers |
| --- | --- |
| Service (pytest) | The API equals `inference.predict_task1` exactly, primary and fallback; the rebuilt `Context` equals the trained one; the forecast reproduces the Task 2A submission exactly; a forecast without outlets is 422; the same request gives the same answer; an altered model file refuses to load |
| Integration (Java) | The forecast request carries the outlets of every depot |
