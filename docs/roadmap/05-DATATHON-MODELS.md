# 05. Datathon models in real decisions: Phase 4 and D-00

The Datathon produced three things:

- a service-time and lateness model (Task 1);
- a weekly demand forecast (Task 2A);
- a peak-day allocation (Task 2B), which is the planning engine itself.

This document wires them into decisions people take, and measures whether they help. A chart nobody acts on is not integration.

**Principle.** Every model output has:

- a decision point with a named owner;
- a measured outcome read from actuals.

It is advice: R-ML-05 stays, and predictions never change an allocation.

**Cost.** CPU only, the existing `ml-server`, scoring as jobs, Postgres for every store, no paid machine-learning platform.

## What exists and is reused

- **Model service.** `ml-server/` serves `delivery_risk` (Task 1 blend) and `demand_forecast` (Task 2A). It refuses to start if a model file differs from `manifest.json`.
- **Backend `intelligence/`.**
  - `ModelServingAdapter` behind `CircuitBreaker`, gated by `ModelGate` (R-ML-04), with `DeterministicEstimator` as the fallback.
  - `PlanScoringJob`, `ForecastJob`, `OutlookWatchJob`, `AttentionWatchJob`.
  - `FleetCapacity` and `DateOutlookPolicy`.
- **Endpoints under `/api/ml`.** `models`, `plans/{id}/predictions`, `forecast`, `forecast/overview`, `outlook`, `orders/{id}/supply-probability`, `training/deliveries`, `attention`.
- **Tables.** `ml.model_versions`, `ml.plan_scorings`, `ml.delivery_predictions`, `ml.demand_forecasts`.
- **Screens.** Dispatcher late-risk tags (#119), the Forecast screen, the store's 28-day date strip.
- **Commands.** `ml:ActivateModel` already exists.

## What the audit found

- **Training data.** The models were trained on the synthetic competition data, licensed for the competition only. The repository holds a 560-row sample.
- **The 0.979 AUC is likely optimistic.**
  - Validation groups by route (`ml-server/datathon/src/task1_a.py:29`), so routes from the same date sit on both sides.
  - `disruption_index` is the realised road condition.
  - The fallback model (log loss 0.2385 against 0.1516) is the honest production figure after road conditions end on 2026-06-28.
- **Outputs.** The models return service minutes and P(late), not an arrival time.
- **Gaps.** Nothing is active on any deployment, and nothing compares predictions with actuals.

## D-00 Datathon package

Its own ML workstream, weeks 3-10. Not on the product's critical path.

| Part | What it contains |
| --- | --- |
| Notebook | One notebook covering the work |
| Label construction | Write-up: late means arrival after the window closes; an early vehicle waits for the window to open, and that wait is not service time |
| Model files | The trained models |
| Task 1 and Task 2A | Submission files |
| Task 2B | Allocation produced by the engine, passed by the official validator (`tools/check_allocation`), with the one-page deferral policy |
| Leakage check | MF-05 method; report honest out-of-time metrics |
| Video and AI tool disclosure | One video and one disclosure |

Jev may appear only as a proposed deployment, never inside the scored pipeline.

## Foundation

### MF-01 Model registry screen and environment guard (Phase 1.5)

**Screen.** Admin **Models** screen over the existing `GET /api/ml/models` and `ml:ActivateModel`. It lists versions and metrics, and lets the admin activate and retire.

**Where Datathon models may be active:**

| Environment | Datathon models |
| --- | --- |
| Preview | Active |
| Judge build | Active |
| Production | Off. Config guard `ML_ALLOW_DATATHON=false` |

**Production until a model is trained on its own data.** Production runs `DeterministicEstimator`, labelled "estimate: learning". It switches only when both are true:

- a model trained on Waypoint's own actuals passes the MF-04 backtest;
- Finance confirms the licence position.

**Depends on.** F-09a: scoring runs on the worker executor.

### MF-02 Outcome store

**First slice built 2026-10-07** (R-ML-12, ML-21): `ml.delivery_outcomes` keeps each stop's outcome beside the newest prediction for its order, from Execution's actuals every 10 minutes; the late label only from an on-time arrival; label coverage per depot as a gauge. Not yet: positions with a geofence to fill missing arrivals, and weekly volumes from Ordering.

**What it stores.** `ml.prediction_outcomes` joins every stored prediction to what happened.

**Where the actuals come from:**

- `delivery.completed` and `delivery.failed`, read through the Execution `actuals` contract;
- weekly volumes per depot and brand, read through the Ordering `dailyVolumes` contract.

**The late label:**

- It comes from `delivery:RecordArrival` (`deviceArrivedAt`).
- A missing arrival means **no label**, never "not late". Rushed drivers skip the tap, so missing labels lean late.
- Gaps are filled from F-08 positions with a geofence around the outlet.
- Label coverage is reported.

**Start date.** Collection starts with the Kandy pilot in week 6.

### MF-03 Daily metrics

**First slice built 2026-10-07** (R-ML-13, ML-22): `ModelMetricsJob` writes Brier, log loss, calibration slope and intercept, service-minute MAE and the labelled count per depot, day and model, and keeps them as gauges. Not yet: WAPE of the weekly forecast, PSI drift, and the Prometheus alert rules.

**Job output.** A daily job writes `ml.model_metrics_daily`:

| Metric | Measures |
| --- | --- |
| Brier score and log loss | Lateness |
| Calibration slope and intercept | Lateness |
| Mean absolute error | Service minutes |
| WAPE | Weekly forecast |
| PSI drift | Main features, excluding `disruption_index` after 2026-06-28 |

Equal-bin calibration error is too noisy at this volume and is not used.

**Exposure.** Metrics go to Prometheus, with an alert when calibration or error moves past a threshold.

### MF-04 Backtest gate

**Method.** A rolling-origin backtest on the last 8 weeks of MF-02 outcomes:

- uses only features known when the plan was published;
- scores the predictions that were actually stored at the time, not fresh ones.

**Rule.** No model is promoted without passing it.

**Not done.** Live shadow models are not used. At about 200 stops a day there is too little data for a live comparison to mean anything.

### MF-05 Leakage check and retraining

**Leakage check:**

- Hold out the last 6 weeks in date order.
- Report AUC and log loss with and without `disruption_index`.
- Report the AUC of `slack_close` alone.

**Production model ladder.** Trained on Waypoint's own actuals only. Go to the next step only if the previous one fails MF-04:

1. Refit the `DeterministicEstimator` constants by logistic regression.
2. LightGBM on 10 or fewer features known at planning time.
3. The Datathon blend and simulator.

**Retraining:**

- Runs on our own runner, not GitHub Actions, so production data never leaves our infrastructure.
- Produces artifacts and a manifest with metrics, through a pull request.
- `ml-server` tests and the backtest gate run before activation.
- Model versions are pinned.

### MF-06 Prediction intervals

**Source.** Intervals come from out-of-time residuals per bucket:

- at least 30 per bucket;
- falls back to the parent bucket.

**Forecast residuals** use pooled log-ratios across the six depot and brand series.

**Not used.** No extra library.

**Storage.** Interval columns are added to `ml.delivery_predictions` and `ml.demand_forecasts` (expand only).

### MF-07 Feature freshness

The screen states "Road conditions known until DATE". Later dates use the fallback model, which already exists.

### MF-08 Reasons people can read

**What is shown.** Each flag shows its top three reasons, built by decomposition:

- slack against the window;
- start delay;
- position in the run;
- the outlet's history.

SHAP is not used: a SHAP value on one part of a blend plus a simulator does not explain the whole.

**Wording.** Reasons map to plain labels in `frontend/src/shared/wording`.

## Task 1: service time and lateness

### T1-01 Publish risk section (Phase 1.5)

**What the dispatcher sees.** Before Publish, trips and stops at or above the risk threshold are listed. The dispatcher reviews, swaps or keeps each.

**Recording.** The choice is recorded with a reason (`plan:RecordRiskReview`).

**Rollout.**

- Dispatcher only, behind a flag.
- Labelled "estimate: learning" until the trust gates pass.

**KPI.** Fresh on time.

### T1-02 Proactive notice to stores

**What it does.** When a published stop's P(late) is at or above the threshold, the store manager is told once: "Likely after your window, expected about 06:40".

**Limit.** At most two notices per store per week.

**Gate.**

- Off for stores until the trust gates pass, with precision lower bound 80% or more for this use.
- In learning mode the notice goes to the dispatcher only, who may forward it.

**Routing.** Needs a new notification routing version.

**KPI.** "Where is my order" calls.

### T1-03 Live arrival times

**First slice built 2026-10-07** (R-EXE-29, EXE-41): the plan's minutes at each stop now travel with the plan and the released trip; Execution propagates from the last actual arrival or departure at each arrival, each recorded delivery and every 2 minutes, stores the time on the delivery record and tells the store through `eta.changed`. Not yet: expected service minutes from history (T1-04) in place of the plan's and `ml.eta_predictions` with its endpoint. The attention watch and the store already read the stored time from the run sheet.

**How it is computed.** In code, by propagation:

> last actual departure + planned travel + expected service minutes for each remaining stop.

The model gives service minutes and P(late), not times.

**When.** The `delivery.completed` consumer enqueues a job. A worker computes the new times and stores them in `ml.eta_predictions`.

**Endpoint.** `GET /api/ml/trips/{tripId}/eta`.

**Used by:**

- the attention watch: window at risk on the expected time, not the planned one;
- the store's Track screen;
- closing the gap noted in #12.

### T1-04 Expected handling time

| Who | Sees |
| --- | --- |
| Driver | "Expect about 18 min unload" on each stop |
| Store manager | "Keep receiving staff for about 18 min" |

The brief's store manager needs exactly this to schedule staff.

## Task 2A: demand forecast

### T2-01 Capacity plan

**Early version (Phase 1.5, live by 23 October)**

**Why the date.** December hiring and reefer decisions need 4 to 6 weeks' notice, so they must be made by 30 October.

**What it shows.** Weeks ahead per depot and brand:

- the forecast band;
- the deterministic `FleetCapacity` figure from orders already booked;
- conversion into the reefer, dry-truck and van trips needed, against vehicles available (`ref.vehicle_day_status`).

**Data needs.** In production `DeterministicEstimator.weekForecast` needs 8 weeks of orders. Import the depot's spreadsheet history, or show "Insufficient history".

**Recording.** Decisions such as moving workshop days, hiring, or pre-advising stores are recorded with a reason in `ml.capacity_decisions` (`ml:RecordCapacityDecision`).

**Full version (Phase 4).** Adds the MF-06 intervals and the T2-03 accuracy.

**KPI.** Reefer capacity used against available in peak weeks; Fresh on time in peak.

### T2-02 Workshop clash warning

Setting a vehicle "in workshop" on a forecast shortfall day shows a warning. Advice only.

### T2-03 Forecast accuracy

**What it shows.** The last 8 weeks, forecast against actual.

**Baseline.** Compared with the 8-week same-weekday mean. "Same week last year" applies only once a year of history exists.

### T2-04 Interval on the store date strip

LATER.

## Task 2B: peak-day allocation

### T2B-01 Deferral policy in Decide

The written deferral policy is shown in the dispatcher's Decide screen as the reasons the engine applies. It is kept in step with `PriorityPolicy`, and the validator stays in CI.

**Backlog:**

- a scenario replay sandbox, which shares the what-if runs with AI-1;
- planning against p80 service times in shadow (chance-constrained planning).

## Trust gates

No model-driven feature is on by default until all of these pass. A late flag means P(late) at or above a threshold τ, frozen from the MF-04 backtest.

**On 4 weeks of labelled live stops:**

- The lower bound of the 95% Wilson interval on precision is 60% or more, and 80% or more for store notices.
- The same lower bound on recall is 70% or more. At about 10 late stops a day, plain percentages swing too much.
- Brier score and log loss beat `DeterministicEstimator` on the same stops.
- Calibration slope is between 0.8 and 1.2.
- Label coverage is 90% or more.

**Arrival times.** Within plus or minus 15 minutes for 80% of stops.

**Forecast.** Within plus or minus 10% per depot and brand for 6 of 8 weeks, and better than the 8-week same-weekday mean.

**On screen.** Every flag shows three reasons and a one-tap override with a reason (`ml:OverrideFlag`).

## Database, endpoints and screens

**Migrations.** All expand-only, named `YYYYMMDDTHHMM_ml_*`.

| Kind | Items |
| --- | --- |
| Tables | `ml.prediction_outcomes`, `ml.model_metrics_daily`, `ml.eta_predictions`, `ml.capacity_decisions` |
| Columns | Interval columns on the prediction and forecast tables |
| Routing | New notification routing version for T1-02 |
| Catalogue rows | `ml:RecordCapacityDecision`, `ml:OverrideFlag`, `plan:RecordRiskReview` (each with a policy version per role) |

**Endpoints.** `GET /api/ml/models/{id}/performance`, `/trips/{id}/eta`, `/capacity`, `/forecast/accuracy`.

**Screens:**

| Role | Screens |
| --- | --- |
| Admin | Models |
| Dispatcher | Capacity, Publish risk, live times on the timeline |
| Store manager | Expected arrival and handling time |
| Driver | Expected unload time |

**Tests:**

- domain tests for thresholds and capacity conversion;
- integration through the command bus with one denied case;
- `ml-server` tests for intervals;
- one browser spec per screen;
- edge cases from ML-13 onward.

## Sources

- [Chance-constrained routing with learned service times](https://arxiv.org/pdf/2601.07514)
- [Contextual stochastic VRP with time windows](https://arxiv.org/pdf/2402.06968)
- [Proactive delay notices: parcelLab Trending Late](https://docs.parcellab.com/docs/engage/trending-late)
- [Conformalised quantile regression](https://mapie.readthedocs.io/en/stable/generated/mapie.regression.ConformalizedQuantileRegressor.html)
- [Demand forecast to fleet capacity](https://www.ontruck.com/en/blog/calculate-optimal-fleet-capacity-ai)
- [Shadow, canary and drift practice](https://techinterview.org/system-design-ml-platform-mlops/)
