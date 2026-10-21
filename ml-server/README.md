# Waypoint model service

Serves the trained Datathon models to the backend (issue #16). It only predicts: it never trains, never reads the database, and keeps nothing between requests except the models it loaded at start. The backend reaches it through `ModelServingAdapter`, behind a circuit breaker. When the service is down, the backend answers with its deterministic estimator and says so on every result.

## What it serves

| Kind | Model | Endpoint |
| --- | --- | --- |
| `delivery_risk` | `datathon-task1-parallel` 2026.10.1: service minutes and P(late) per stop, scored over whole planned routes. A Monte-Carlo route simulator (500 draws per route, delays carried from stop to stop) and LightGBM run side by side; P(late) is blended 50/50 in log-odds, service time 55/45. A route whose date has no road-conditions row is scored by the copy trained without them, in the same file. | `POST /v1/delivery-risk` |
| `demand_forecast` | `datathon-task2a` 2026.10.1: weekly total and chilled m³ per depot and brand, as every expected order of each outlet times its expected size | `POST /v1/demand-forecast` |

### Model comparison — Datathon Task 1

| Model | Service MAE ↓ | Late log-loss ↓ | Accuracy at 0.5 ↑ | False alarms ↓ | Final training time ↓ | Inference per order ↓ | Number of Monte Carlo runs | ML libraries | Model size ↓ |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| Statistical baseline | 5.235 min | 0.302 | 85.811% | 368 | 10.14 ms | Not supplied | — | pandas | negligible |
| Single LightGBM | 3.748 min | 0.133 | 94.491% | 115 | 18.05 s | Not supplied | — | LightGBM | not recorded |
| Two-pipeline ensemble | **3.664 min** | **0.125** | **94.626%** | **89** | **16.02 min** | **1.987 s** | — | LightGBM + XGBoost + CatBoost | **33.442 MB** |
| **Selected parallel hybrid** | 3.718 min | 0.131 | 94.491% | 95 | **1.49 min** | **48 ms** | **250** | **LightGBM only** | **9.747 MB** |

`GET /health` names the loaded models and their versions. The backend uses a model only when this name and version match the active entry in its registry.

`manifest.json` lists each model's version, its training range, its validation metrics and the SHA-256 of every file in `models/`. **The service refuses to start if any file differs.**

## Where it came from

- `datathon/mc_task1.py`, `parallel_model.py` and `task2a_demand.py` are the final Datathon submission's code, vendored unchanged. The pickled models refer to their classes as these top-level modules, so the files keep their names and `datathon/` goes on the import path.
- `datathon/inference.py` is ours: the submission notebook's final cell as functions. The tests compare the API with it.
- `models/` holds the two saved models, stored in Git LFS. Run `git lfs pull` after cloning.
- Validation figures are in `manifest.json`: the six weeks held out at the end of the training data for Task 1, and the forecasts started from September to December 2025 for Task 2A.
- The Task 1 model was saved with scikit-learn 1.6.1, so loading it warns `InconsistentVersionWarning`; under the versions in `requirements.txt` it gives the submitted predictions.

## Requests

The backend sends each scoring request together with the reference tables the features need: `outlets`, `vehicles`, `service_allowance`, `district_travel`, `traffic_speed`, `calendar` and `road_conditions`. They use the same table and column names as the CSVs in `data/General Data/`, so the vendored feature code runs as written. The Task 1 model carries the tables it was trained with; the service builds its context from the tables sent instead, keeping only the trained category vocabulary, so the backend stays the one source of reference data and days past the training data score.

A forecast request sends the `calendar` from 2024-01-01 and the `outlets` of every depot: the model schedules each outlet's orders, so without them it answers 422.

## Run and test

```bash
cd ml-server
python -m venv .venv && . .venv/bin/activate      # Python 3.12, where the submissions reproduce
pip install -r requirements-dev.txt
pytest                                            # compares the API with the vendored inference code
uvicorn app.main:app --port 8000
```

The Task 1 tests score four real routes and require the API to equal `inference.predict_task1` on the same rows, primary and fallback, and the context built from a request to equal the one the model was trained with. The forecast test requires the Datathon Task 2A submission to be reproduced exactly.

## Leakage check (roadmap MF-05)

`training/leakage.py` scores the late-risk feature set three ways on the same rows and reports them side by side (R-ML-17): route-grouped folds, as the original validation did; the last 6 weeks held out in date order; and the same holdout without the realised road conditions. It also gives the AUC of the window's slack alone. It reads only what it is given and sends nothing anywhere, so it runs on our own machine against the licensed data or our own training export, never in GitHub Actions.

```bash
python -m training.leakage --datathon "/path/to/Datasets/data" --out leakage-report.json
```

Findings are written in words when a gap is material: 0.02 of AUC for the split or the road index, or a log loss 10% worse without the road index. It builds its frames with the served feature code (`parallel_model.prepare`, the 47 static features). The figures below were measured on 2026-10-08 with the earlier pipeline's features and have not been rerun for the final model; run it again on the licensed data before quoting them for this model. Full Datathon training data, holdout 2026-01-05 to 2026-02-14, 5,035 stops:

| Validation | AUC | Log loss |
| --- | --- | --- |
| Route-grouped folds | 0.977 | 0.152 |
| Last 6 weeks, date order | 0.975 | 0.133 |
| Last 6 weeks, no road conditions (the production figure) | 0.957 | 0.168 |
| Window slack alone | 0.880 | |

The split is not where the optimism is (0.002 of AUC). The road index is: without it log loss is 26% worse, and it does not exist when a plan is published. The figure to expect in production is the third row, and a model is only trusted on our own deliveries through the backtest (R-ML-16).

## Retraining on our own deliveries (roadmap MF-05)

Step 2 of the production ladder: LightGBM on eighteen planning-time features (`training/planning.py`). Ten come from the route: the window's slack either side, planned arrival, the stop's place and the route's length, planned travel so far, route start, distance, volume, van or truck. Eight come from the reference tables, which arrive with every scoring request and are read from `data/General Data` when training: the outlet's unloading allowance and the allowances before it on the route, the traffic speed at the planned hour, monsoon, weekday, payday, festival ramp and holiday. The features come from the route exactly as the backend sent it to be scored, which the backend keeps per plan (`ml.scoring_inputs`, R-ML-18), so training and serving share one definition and nothing learned after publication goes in. Everything below runs on our own machine; nothing is sent anywhere.

```bash
# 1. The export, as an administrator (ml:ExportTrainingData). Plain HTTP only to localhost.
WAYPOINT_SESSION='SESSION=...' python -m training.fetch --url http://localhost:8080 --depot Kandy \
    --from 2026-08-01 --to 2026-10-04 --out export.jsonl
# 2. Train, hold out the last 6 weeks, compare with the window slack alone and with what answered then.
python -m training.retrain --export export.jsonl --version 2026.10.1 --out build/
```

`retrain` writes the model file, `manifest-entry.json` (with `"pipeline": "planning"`, the holdout metrics and the file's SHA-256) and `report.json`. It exits 2, and the model is not handed over, unless it beats the slack alone on both Brier score and log loss with 200 or more labelled stops held out, and is not worse than what answered at the time on the same stops (the replay: each label carries `answeredBy` and `answeredLateProb`; better or worse means both scores, as on the Models screen, R-ML-16).

**On a schedule.** `training.run` does both steps for every depot over the last 26 finished weeks and leaves `<out>/<date>/` with the export, model, manifest entry and report. It never touches `manifest.json` or `models/`: a model is handed over by a reviewed change. On our own server, as the user that runs the stack (the session cookie is an administrator's, kept in a file only that user reads):

```bash
# crontab: Sundays 02:30 depot time
30 2 * * 0  cd /srv/waypoint/ml-server && WAYPOINT_SESSION="$(cat ~/.waypoint-session)" \
  python -m training.run --url http://localhost:8080 --depot Peliyagoda --depot Kandy --out /srv/waypoint/retrain >> /var/log/waypoint-retrain.log 2>&1
```

Exit 0 means a candidate worth handing over was built, 2 means none was. The export holds operational data, so the build folder stays on that server. To hand it over: copy the file into `models/`, put the entry in `manifest.json` in place of the `delivery_risk` one, run `pytest`, then register and activate it as in "Wiring it in". The service loads a planning model instead of the Datathon model and answers with `roadConditions: fallback`, since it never uses them. After a week in use, the admin Models screen shows its backtest beside the built-in estimate (R-ML-16).

Dry run on the Datathon data, in the export's shape (`python -m training.datathon_export --datathon <dir> --out dt.jsonl`; 25,198 routes; holdout 2026-01-05 to 2026-02-14, 5,035 stops):

| Model | AUC | Brier | Log loss |
| --- | --- | --- | --- |
| Eighteen planning features (route and reference) | 0.954 | 0.054 | 0.176 |
| Ten route features only (`--reference none`) | 0.941 | 0.070 | 0.227 |
| Window slack alone | 0.880 | 0.083 | 0.285 |
| Earlier Datathon features without road conditions (for reference, from the leakage check) | 0.957 | | 0.168 |

Eighteen plain features come within 0.008 of log loss of the earlier Datathon pipeline without road conditions, with nothing learned after the plan. The reference tables a run trains on are the ones in `data/`; if production imports a newer reference version, retrain after it.

## Model file safety

Loading a pickle runs the classes and functions it names, so no model file is loaded until it is shown to name only what `model-globals.txt` allows (R-ML-28):

```bash
python -m app.pickle_scan models/            # exit 0 clean, 1 refused, with the file and the pair
python -m app.pickle_scan --list models/     # every pair each file names, for review
```

`app/pickle_scan.py` reads the opcodes without loading anything. It undoes joblib's compression (zlib, gzip, bz2, xz), steps over each numpy array's raw bytes using the shape and dtype recorded before them, walks an object array's nested pickle, and tracks the stack so a `STACK_GLOBAL` is named exactly. A file it cannot read to the end, or that names any pair not on the list, is refused. It runs in three places: the CI job before the model tests ("Scan model files"), the image build (so every deploy refuses the file and keeps the running service), and the service itself before `joblib.load` (`registry.UnsafeModel`).

The list holds exact pairs, never a whole module, and grows only in a reviewed change after reading what the new pair does when called with the file's arguments. This is on purpose:

- The public scanners are blocklists, and bypasses through helpers inside trusted libraries are found regularly.
- They cannot read these files. Measured 2026-10-21: picklescan 1.0.5 and modelscan 0.8.8 cannot parse the zlib-compressed Task 1 model at all, and on the decompressed stream both stop at byte 5,378 of 23 MB, where the first array begins. On a joblib file with an `os.system` payload after an array, picklescan reports no infected file; this scan names `os.system`.

What it does not cover: an allowlisted class can still misbehave on hostile state when it is rebuilt. The list keeps that set to the libraries the models are built from, and the hash in `manifest.json` ties each served file to the reviewed change that brought it in.

## Retraining the demand forecast on our own orders (issue #7)

The served demand model (`datathon-task2a`) was fitted on the competition data, so production does not use it. `training.retrain_demand` refits the same `DemandModel` on Waypoint's own orders, and a model it hands over (`waypoint-demand`) may serve in production. As with the late-risk retrain, everything runs on our own machine and sends nothing.

```bash
# 1. The order export, as an administrator (ml:ExportTrainingData), per depot.
WAYPOINT_SESSION='SESSION=...' python -m training.fetch --kind orders --url http://localhost:8080 --depot Kandy \
    --from 2024-10-07 --to 2026-10-04 --out kandy.jsonl
# 2. Fit, backtest, and write the model if it earns it (every depot's export in one file: one model for all).
cat kandy.jsonl peliyagoda.jsonl > orders.jsonl
python -m training.retrain_demand --export orders.jsonl --version 2026.10.21 --out build/ \
    --current models/task2a_model.joblib
```

The export counts each order once by the day it was placed, as the forecast's actuals count it (R-ML-29). An order without a temperature is left out and counted in the report; one without a volume still counts towards the order schedule but not the order-size regressions.

`retrain_demand` writes the model file, `manifest-entry.json` and `report.json`. It exits 2 without a model, with the reason in the report, when:

- there are fewer than 52 weeks of orders (the model has an annual term and a ramp per festival);
- an order day is not in the calendar (import a newer reference first);
- there are fewer than 4 backtest starts.

It also exits 2, writing the model but not handing it over, when the backtest says so (R-ML-30):

- Starts: the latest Monday with 10 finished weeks after it, then every 4 weeks back, at most 6, each with 26 weeks or more of orders before it. Each start's model is fitted only on the orders before it and forecasts 10 weeks.
- The candidate must have lower total and chilled WAPE than a naive forecast on the same weeks: each depot and brand's mean of the 8 weeks before the start, a yardstick for this check rather than the product's fallback.
- With `--current`, it must also not be worse than the model in use on those weeks.

**On a schedule.** `training.run` builds a demand candidate beside the late-risk one, in `<out>/<date>/demand/`, over the last `--demand-weeks` finished weeks (default 104). It compares the candidate with the demand model `manifest.json` serves. The run exits 0 when either candidate is worth handing over. Handing it over is the same reviewed change as for the late-risk model: copy the file into `models/`, replace the `demand_forecast` entry in `manifest.json` with `manifest-entry.json`, run `pytest`, then register and activate `waypoint-demand` on the admin Models screen.

On a synthetic history of 78 weeks whose orders follow the calendar (`tests/demand_orders.py`), the candidate scores 5.9% total and 3.3% chilled WAPE against the naive forecast's 21% and 20%, over 6 starts. A dry run on the full Datathon order history, which reproduces the team's backtest, has not been run: the history is licensed and is not on this machine.

## Reproducibility

- Every random seed is fixed, so the same request always gives the same answer.
- The route simulator draws its random numbers over the whole request. So scoring one day on its own can differ slightly from scoring the same day inside a larger batch: up to 0.8 minutes of service time and 0.02 of P(late) in the measured case.
- The backend stores each prediction with the model that produced it.

## Handing over a new model

For the data-science team. A model reaches the product through this service only: the backend never loads a model file. The product does not need a model to run. With none active, or with this service down, every screen uses the backend's own estimate and says so (R-ML-04), so a new model can be wired in at any time without anything else waiting on it.

### What the team hands over

| Item | Where it goes | Notes |
| --- | --- | --- |
| The saved model files (`.joblib`, `.json`, `.cbm`, ...) | `ml-server/models/` | Stored in Git LFS (`git lfs track` already covers `models/*`). Loaded with Python 3.12 and the versions in `requirements.txt`, or that file is updated in the same change |
| The Python modules that build the features and predict | `ml-server/datathon/` | Replace the current modules. Keep the module names the pickles refer to (today `mc_task1`, `parallel_model`, `task2a_demand`) |
| `inference.py` | `ml-server/datathon/inference.py` | The contract below: the notebook's final cell as functions. Written by us when the team hands over only the notebook |
| Name, version, training range, holdout metrics, SHA-256 of every file | `ml-server/manifest.json` | One entry per kind. A new version every time: a retired version can never be activated again |
| The submission files for the test inputs | `ml-server/tests/fixtures/` | The proof: the service must reproduce them exactly |

### The contract in `inference.py`

The functions take the same tables, with the same column names, as the CSVs in `data/`:

```python
def load_models(models_dir) -> dict: ...
# orders: rows like task1_test_inputs.csv; legs: their route legs (planned times only)
def predict_task1(orders, legs, models, use_fallback=False) -> DataFrame:
    # columns delivery_id, pred_service_min, pred_late_prob (0 to 1), one row per order
def predict_task2a(rows, models, calendar, outlets) -> DataFrame:
    # rows: depot, brand, iso_year, iso_week; adds pred_total_volume_m3, pred_chilled_volume_m3 (0 unless Fresh)
```

`predict_task1` scores with the reference tables the model was trained with; the service passes the tables the backend sends instead (`app/scoring.py` `_context`). `use_fallback=True` scores routes on dates with no road-conditions row (after 2026-06-28); a model that does not use road conditions can ignore it. The output columns are the submission templates' own, so a model that fills `submission_task1.csv` and `submission_task2a.csv` already meets them.

### Wiring it in

1. Put the files and the package in place, and fill `manifest.json` with the new version and each file's SHA-256 (`shasum -a 256 models/*`). Run `python -m app.pickle_scan models/`. If it names a pair that is not on the list, read what it is before adding it to `model-globals.txt` in the same change; a reviewer checks that line.
2. If the files or the steps differ from the current ones (one Task 1 `ParallelModel` holding its no-road copy, and one Task 2A `DemandModel`), change `app/registry.py` (`load`) and `app/scoring.py` (`_context`, `delivery_risk`, `demand_forecast`) to call the new models already in memory. This is the only code that knows the team's pipeline.
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
