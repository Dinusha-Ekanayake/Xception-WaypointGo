# Waypoint model service

Serves the trained Datathon models to the backend (issue #16). It only predicts: it never trains, never reads the database, and keeps nothing between requests except the models it loaded at start. The backend reaches it through `ModelServingAdapter`, behind a circuit breaker. When the service is down, the backend answers with its deterministic estimator and says so on every result.

## What it serves

| Kind | Model | Endpoint |
| --- | --- | --- |
| `delivery_risk` | `datathon-task1-blend` 2026.1.1: service minutes and P(late) per stop, scored over whole planned routes. It blends two LightGBM/XGBoost/CatBoost pipelines (0.4/0.6 for lateness, 0.3/0.7 for service time) with a Monte-Carlo route simulator. A route whose date has no road-conditions row is scored by the shipped model trained without them. | `POST /v1/delivery-risk` |
| `demand_forecast` | `datathon-task2a` 2026.1.1: weekly total and chilled m³ per depot and brand | `POST /v1/demand-forecast` |

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

## Leakage check (roadmap MF-05)

`training/leakage.py` scores the late-risk feature set three ways on the same rows and reports them side by side (R-ML-17): route-grouped folds, as the original validation did; the last 6 weeks held out in date order; and the same holdout without the realised road conditions. It also gives the AUC of the window's slack alone. It reads only what it is given and sends nothing anywhere, so it runs on our own machine against the licensed data or our own training export, never in GitHub Actions.

```bash
python -m training.leakage --datathon "/path/to/Datasets/data" --out leakage-report.json
```

Findings are written in words when a gap is material: 0.02 of AUC for the split or the road index, or a log loss 10% worse without the road index. Run on the full Datathon training data (2026-10-08; holdout 2026-01-05 to 2026-02-14, 5,035 stops):

| Validation | AUC | Log loss |
| --- | --- | --- |
| Route-grouped folds | 0.977 | 0.152 |
| Last 6 weeks, date order | 0.975 | 0.133 |
| Last 6 weeks, no road conditions (the production figure) | 0.957 | 0.168 |
| Window slack alone | 0.880 | |

The split is not where the optimism is (0.002 of AUC). The road index is: without it log loss is 26% worse, and it does not exist when a plan is published. The figure to expect in production is the third row, and a model is only trusted on our own deliveries through the backtest (R-ML-16).

## Reproducibility

- Every random seed is fixed, so the same request always gives the same answer.
- The route simulator draws its random numbers over the whole request. So scoring one day on its own can differ slightly from scoring the same day inside a larger batch: up to 0.8 minutes of service time and 0.02 of P(late) in the measured case.
- The backend stores each prediction with the model that produced it.

## Handing over a new model

For the data-science team. A model reaches the product through this service only: the backend never loads a model file. The product does not need a model to run. With none active, or with this service down, every screen uses the backend's own estimate and says so (R-ML-04), so a new model can be wired in at any time without anything else waiting on it.

### What the team hands over

| Item | Where it goes | Notes |
| --- | --- | --- |
| The saved model files (`.joblib`, `.json`, `.cbm`, ...) | `ml-server/models/` | Stored in Git LFS (`git lfs track` already covers `models/*`). Trained with Python 3.11 and the versions in `requirements.txt`, or that file is updated in the same change |
| The Python package that builds the features and predicts | `ml-server/datathon/src/` | Replaces the current package. Pickles refer to their classes as `src.<module>`, so the folder stays `datathon/src` |
| `inference.py` inside that package | `ml-server/datathon/src/inference.py` | The contract below. It is the notebook's final cell as functions |
| Name, version, training range, holdout metrics, SHA-256 of every file | `ml-server/manifest.json` | One entry per kind. A new version every time: a retired version can never be activated again |
| The submission files for the test inputs | `ml-server/tests/fixtures/` | The proof: the service must reproduce them exactly |

### The contract in `inference.py`

The functions take the same tables, with the same column names, as the CSVs in `data/` (the backend sends `outlets`, `vehicles`, `service_allowance`, `district_travel`, `traffic_speed`, `calendar` and `road_conditions` with every request, as `g`):

```python
def load_models(models_dir) -> dict: ...
# orders: rows like task1_test_inputs.csv; legs: their route legs (planned times only)
def predict_task1(orders, legs, models, g, use_fallback=False) -> DataFrame:
    # columns delivery_id, pred_service_min, pred_late_prob (0 to 1), one row per order
def predict_task2a(rows, models, g) -> DataFrame:
    # rows: depot, brand, iso_year, iso_week; adds pred_total_volume_m3, pred_chilled_volume_m3 (0 unless Fresh)
```

`use_fallback=True` scores routes on dates with no road-conditions row (after 2026-06-28); a model that does not use road conditions can ignore it. The output columns are the submission templates' own, so a model that fills `submission_task1.csv` and `submission_task2a.csv` already meets them.

### Wiring it in

1. Put the files and the package in place, and fill `manifest.json` with the new version and each file's SHA-256 (`shasum -a 256 models/*`).
2. If the files or the steps differ from the current ones (two Task 1 pipelines blended 0.4/0.6 and 0.3/0.7, and one Task 2A model), change `app/registry.py` (`load`) and `app/scoring.py` (`_blend`, `delivery_risk`, `demand_forecast`) to call the new `inference` functions with the models already in memory. This is the only code that knows the team's pipeline.
3. Replace `tests/fixtures/task2a_expected.csv` with the team's Task 2A submission rows for `task2a_weeks.csv`, and check `tests/test_service.py` against the new Task 1 output. Run `pytest`: the API must equal `inference.predict_task1` and `predict_task2a` exactly, repeat the same answer for the same request, and refuse an altered file.
4. Start the service and read `GET /health`: it names the loaded models and versions.
5. As an administrator, register the version (`ml:RegisterModel` with the manifest's name, version and kind; see `docs/issues/016-intelligence/WALKTHROUGH.md`), then activate it on the admin Models screen. The backend uses a model only when the served name and version equal the active entry.
6. Publish a plan: within 30 seconds `GET /api/ml/plans/{planId}/predictions` shows the new label on every stop, and the dispatcher's late risk no longer reads "estimated". The forecast follows at its next run, or at once with `forecast-run`.

### What happens when it fails

| Failure | What the product does |
| --- | --- |
| The service is down, slow (past the timeout) or erroring | The circuit breaker opens; the plan is scored in the backend by its own estimate, marked degraded with the reason, and the screens say "estimated". That estimate is not a bare rule: it blends each outlet's history with the allowance and is recalibrated nightly on its own outcomes, with an offset per outlet (R-ML-14). Scoring retries with backoff, and a plan is scored again when the model returns |
| A model file differs from the manifest | The service refuses to start, so no prediction is ever recorded against the wrong version |
| The served version is not the active one | The backend does not use it and falls back, as above |
| Competition-data models in production | Blocked by `ML_ALLOW_DATATHON=false`: they serve preview and judge only, as the data licence requires |

Every prediction is stored with the model that made it, and its outcome is kept beside it (R-ML-12), so the new version's Brier score, calibration and service-minute error appear in `ml.model_metrics_daily` and on the alert rules from the first day (R-ML-13).
