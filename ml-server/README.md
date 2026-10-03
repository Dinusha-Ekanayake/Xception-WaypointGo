# Waypoint model service

Serves the trained Datathon models to the backend (issue #16). It only predicts: it never trains, never reads the database, and keeps nothing between requests except the models it loaded at start. The backend reaches it through `ModelServingAdapter`, behind a circuit breaker. When the service is down, the backend answers with its deterministic estimator and says so on every result.

## What it serves

| Kind | Model | Endpoint |
| --- | --- | --- |
| `delivery_risk` | `datathon-task1-blend` 2026.1: service minutes and P(late) per stop, scored over whole planned routes. It blends two LightGBM/XGBoost/CatBoost pipelines (0.4/0.6 for lateness, 0.3/0.7 for service time) with a Monte-Carlo route simulator. A route whose date has no road-conditions row is scored by the shipped model trained without them. | `POST /v1/delivery-risk` |
| `demand_forecast` | `datathon-task2a` 2026.1: weekly total and chilled m³ per depot and brand | `POST /v1/demand-forecast` |

`GET /health` names the loaded models and their versions. The backend uses a model only when this name and version match the active entry in its registry.

`manifest.json` lists each model's version, its training range, its validation metrics and the SHA-256 of every file in `models/`. **The service refuses to start if any file differs.**

## Where it came from

- `datathon/src/` is the Datathon package, vendored unchanged. The pickled models refer to their classes as `src.<module>`, so the package keeps its name.
- `models/` holds the saved models, stored in Git LFS. Run `git lfs pull` after cloning.
- Validation figures are in `manifest.json`.

## Requests

The backend sends each request together with the reference tables the features need: `outlets`, `vehicles`, `service_allowance`, `district_travel`, `traffic_speed`, `calendar` and `road_conditions`. They use the same table and column names as the CSVs in `data/General Data/`, so the vendored feature code runs as written.

## Run and test

```bash
cd ml-server
python -m venv .venv && . .venv/bin/activate      # Python 3.11, as trained
pip install -r requirements-dev.txt
pytest                                            # compares the API with the vendored inference code
uvicorn app.main:app --port 8000
```

The Task 1 tests score four real routes and require the API to equal the vendored `inference.predict_task1` on the same rows. The forecast test requires the Datathon Task 2A submission to be reproduced exactly.

## Reproducibility

- Every random seed is fixed, so the same request always gives the same answer.
- The route simulator draws its random numbers over the whole request. So scoring one day on its own can differ slightly from scoring the same day inside a larger batch: up to 0.8 minutes of service time and 0.02 of P(late) in the measured case.
- The backend stores each prediction with the model that produced it.
