# Serve the final Datathon models: walkthrough

Issue [#5](https://github.com/jv-ransika/yi-yi/issues/5) in `jv-ransika/yi-yi`. The plan is in [PLAN.md](PLAN.md).

## What was built

### The model service, `ml-server/`

- **`datathon/mc_task1.py`, `parallel_model.py`, `task2a_demand.py`**: the final submission's code, vendored unchanged. The pickles name their classes as these top-level modules, so `datathon/` is on the import path (`app/registry.py`, `pytest.ini`). The earlier `datathon/src/` package is removed.
- **`datathon/inference.py`**: ours, the submission notebook's final cell as functions (`load_models`, `predict_task1`, `predict_task2a`). The tests compare the API with it.
- **`models/`**: `task1_parallel_model.joblib` (a `ParallelModel`: primary, plus the copy trained without road conditions) and `task2a_model.joblib` (a `DemandModel`). The five earlier files are removed.
- **`manifest.json`**: `datathon-task1-parallel@2026.10.1` and `datathon-task2a@2026.10.1`, with their training ranges, holdout figures and SHA-256.
- **`app/registry.py`**: `Loaded` holds `task1`, `task2a` and the retrained `planning` model (unchanged).
- **`app/scoring.py`**:
  - `_context` rebuilds the model's `mc_task1.Context` from the tables in the request, with the steps of `Context.__init__`, and keeps the trained category vocabulary.
  - `_with_context` gives each request a shallow copy of the model and its simulators that reads that context; the loaded model is never changed.
  - `delivery_risk` scores routes with road conditions by the primary and the rest with `use_fallback=True`, labelled `used` and `fallback` as before.
  - `demand_forecast` checks the weeks and the outlet columns, then calls `task2a_demand.predict_rows`.
- **`app/schemas.py`**: the forecast request requires `outlets`.
- **`app/pickle_scan.py`, `model-globals.txt`**: a static scan of every model file before anything loads it. Each file may name only the (module, name) pairs on the list; joblib's compression and raw array bytes are handled, which the public scanners cannot do (R-ML-28, EDGE-CASES ML-44). It runs in the CI job, in the image build and in `registry.verify` (`UnsafeModel`).
- **`requirements.txt`, `Dockerfile`**: XGBoost and CatBoost dropped; the image is Python 3.12 and runs the scan when it is built. The CI model job also moves to Python 3.12.
- **`training/datathon_export.py`** reads the Datathon CSVs itself; **`training/leakage.py`** builds its frames with the served feature code.

### Backend

- `intelligence/application/ReferencePayload.outlets(depots, versionId)`: every outlet of the depots, in the outlet table's shape.
- `ForecastJob` sends them with the forecast request. With no reference version loaded it does not call the model, keeps its deterministic forecast and records why.

## Flows

**Plan scoring.** Unchanged from the backend's side: `PlanScoringJob` posts the routes and the reference tables to `POST /v1/delivery-risk`. The service builds the context from those tables, scores each group of routes, and answers each stop's service minutes and P(late) with `roadConditions`.

**Forecast.** `ForecastJob` posts the calendar from 2024-01-01, every depot's outlets and the weeks to `POST /v1/demand-forecast`. The model schedules each outlet's expected orders on the calendar's operating days and multiplies them by the expected order size. Without outlets the service answers 422 and the backend degrades (EDGE-CASES ML-38).

## Running and checking it locally

```bash
cd ml-server
python -m venv .venv && . .venv/bin/activate      # Python 3.12
pip install -r requirements-dev.txt
pytest
uvicorn app.main:app --port 8000                 # GET /health names both 2026.10.1 models
```

Then register and activate both models as in [016's walkthrough](../016-intelligence/WALKTHROUGH.md), publish a plan, and run `forecast-run`.

Measured while building (2026-10-21):
- `pytest`: 44 passed, 16 of them the model scan's: a payload after a numpy array, inside an object array, under every joblib compression, and through `builtins.getattr` is refused; a file the hashes accept but the scan refuses stops the service. The served models and a model `training.retrain` writes pass.
- On the four fixture routes, the API is within 0.17 min and 0.0104 of P(late) of the team's submitted predictions. The simulator draws its random numbers over the whole request, and the submission scored the whole test set at once.
- A request of 40 routes and 170 stops scores in 0.9 s, far inside the backend's 180 s timeout.

## Decisions and where they are recorded

- The reference comes from the request and the vocabulary from the model, and the loaded model is never changed: [PLAN.md](PLAN.md) decisions 1 and 2.
- The forecast requires outlets: EDGE-CASES ML-38.
- The fallback's cost on the last six weeks: EDGE-CASES ML-03.

## Known gaps

- The leakage check now builds the final model's features, but the figures in `ml-server/README.md` and [LEAKAGE-CHECK.md](../../datathon/LEAKAGE-CHECK.md) are still the earlier pipeline's. They need a rerun on the licensed data.
- The Task 1 test inputs are not in `data/`, so the full Task 1 submission was not reproduced here; the team's notebook reports it within 0.005 min and 0.0000.
- The new versions still have to be registered and activated on preview, as the earlier ones did (STATUS, Intelligence).
