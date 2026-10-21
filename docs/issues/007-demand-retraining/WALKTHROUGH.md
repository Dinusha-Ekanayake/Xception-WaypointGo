# Retrain the demand forecast on our own orders: walkthrough

Issue [#7](https://github.com/jv-ransika/yi-yi/issues/7) in `jv-ransika/yi-yi`. The plan is in [PLAN.md](PLAN.md). Two pull requests: the order export (#9) and the retrain tool.

## What was built

### Backend: the order export (#9)

- `ordering/contract/OrderQuery.orderHistory` and `OrderViews.OrderHistoryView`: a depot's orders one by one, by placement date in depot time, keyset-paged on `(placed_at, order_id)`. Implemented in `ordering/application/OrderDataQuery`, read as the ambient actor so row-level security limits it to their depots. Index `ix_orders_depot_placed` in `migrations/20261021T1500_ordering_orders_depot_placed.sql`.
- `intelligence/application/IntelligenceDataQuery.trainingOrders` and `GET /api/ml/training/orders` in `intelligence/web/IntelligenceController`, behind `ml:ExportTrainingData` on `wpt:ml:training:<depot>`. A depot outside scope is refused and audited (`requireDepot`, now taking the action and resource).
- `PredictionViews.TrainingOrderView`: the export's row.

### Model service: the retrain

- `ml-server/training/retrain_demand.py`:
  - `load_export` turns the export into the model's history, leaving out and counting orders without a temperature.
  - `train` checks the history (52 weeks, every day in the calendar, 4 or more starts), backtests with `backtest`, applies `gate`, and refits on every order.
  - `write` gives the model file, `manifest-entry.json` and `report.json`.
  - `current_model` reads the demand model the manifest serves.
- `ml-server/training/run.py`: `run_demand` fetches every depot's orders (`fetch --kind orders`), retrains, compares with the served model and writes `<out>/<date>/demand/`. `main` runs it beside the late-risk retrain.
- `ml-server/app/registry.py`: the demand model is loaded by the artifact its manifest entry names, so a `waypoint-demand` entry replaces the Datathon one without code changes.
- `ml-server/training/fetch.py`: `--kind orders`.

## Flows

**Weekly run (cron on our own server).** `training.run` exports the last 26 weeks of routes and the last 104 weeks of orders for each depot. It retrains both models and leaves each candidate in the build folder with its report. Nothing changes in `manifest.json` or `models/`.

**Handover (reviewed change).**
1. Copy `waypoint-demand-<version>.joblib` into `ml-server/models/`.
2. Replace the `demand_forecast` entry in `manifest.json` with `manifest-entry.json`.
3. Run `pytest`.
4. Register and activate `waypoint-demand@<version>` on the admin Models screen.

The next `ForecastJob` run, or `forecast-run`, then forecasts with it. It has no `datathon-` prefix, so `ModelGate` allows it in production.

## Running and checking it locally

```bash
cd ml-server
pytest tests/test_retrain_demand.py tests/test_retrain.py
python -m training.retrain_demand --export orders.jsonl --version 2026.10.21 --out build/ --current models/task2a_model.joblib
```

The tests train on a synthetic 78-week history that follows the calendar (`tests/demand_orders.py`). The candidate scores 5.9% total and 3.3% chilled WAPE against the naive forecast's 21% and 20%, over 6 starts. They also cover:
- under a year, and an order day outside the calendar, are refused;
- an order without a temperature is left out and counted;
- the gate (chilled counts as much as total; the model in use blocks a regression);
- the service serving a retrained model through the manifest;
- the scheduled run fetching the order export per depot.

The export's backend test is `IntelligenceIntegrationTest.theOrderHistoryExportCountsEachOrderOnceByPlacementAndRefusesAnotherDepot`.

## Decisions and where they are recorded

- What counts as demand: the orders `dailyVolumes` counts, redeliveries included (R-ML-28, EDGE-CASES ML-43; PLAN decision 1, changed from the first draft).
- The backtest and the gate (R-ML-29, EDGE-CASES ML-44; PLAN decision 5, starts corrected when built).
- One model for all depots, a year of history first, `waypoint-demand` by date: PLAN decisions 2, 4 and 6.

## Known gaps

- The dry run on the full Datathon order history, which should reproduce the team's backtest at their starts, needs the licensed data and has not been run.
- No pilot depot has a year of orders yet, so no candidate can be built in production; the forecast there stays deterministic until one is.
- Retraining the Task 1 parallel model needs per-leg actual times in the export: a separate issue.
