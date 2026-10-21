# Retrain the demand forecast on our own orders

Issue [#7](https://github.com/jv-ransika/yi-yi/issues/7) in `jv-ransika/yi-yi`, following [#5](../005-final-models/PLAN.md). Reviewed against `feat/final-datathon-models` at `9ddb8de` on 2026-10-21; it builds on #5 and merges after it.

## Goal

The weekly demand forecast keeps itself current on Waypoint's own orders: a weekly job refits the model, backtests it the way the team validated it, and hands over a candidate only when it beats a naive forecast. A model trained on our own orders is allowed in production, where the Datathon model is not.

## Current state

- The served model is `datathon-task2a@2026.10.1`, a `task2a_demand.DemandModel`. It is trained on the competition data, so `ModelGate.competitionModel` keeps it out of production, which forecasts with the deterministic 8-week weekday mean (`ForecastJob.deterministic`).
- `DemandModel.fit(orders, calendar, outlets)` refits in seconds. It needs one row per order: placement date, outlet, brand, depot, temperature and volume. `task2a_demand.backtest(history, calendar, outlets, origins, horizon_weeks)` scores rolling 10-week forecasts from given Mondays. Both are vendored and unchanged.
- The backend keeps every order (`ordering.orders`: `placed_at`, `outlet_id`, `temperature`, `volume_m3`, `status`, `redelivery_of`). The ordering contract only publishes daily totals (`OrderQuery.dailyVolumes`), not single orders.
- `ml-server/training/` retrains only the late-risk model (`fetch`, `retrain`, `run`, MF-05). `app/registry.py` loads the demand model by a fixed file name.
- The repository holds a 560-row sample of the Datathon orders; the full data stays on the team's machines.

## Layer ownership

| Piece | Where | Notes |
| --- | --- | --- |
| `OrderQuery.orderHistory(depot, from, to, cursor, limit)` | `ordering/contract` + `application`, index in `migrations/` | One row per order, keyset-paged on `(placed date, order_id)`. Read under the caller's actor, so row-level security limits it to their depots (rule 7) |
| `OrderHistoryView` | `ordering/contract` | `orderId, placedDate, outletId, brandCode, depotCode, temperature, volumeM3, status`. No personal data |
| `IntelligenceDataQuery.trainingOrders` and `GET /api/ml/training/orders` | `intelligence/application`, `web` | The same action and resource as the route export: `ml:ExportTrainingData` on `wpt:ml:training:<depot>`. No new catalogue row |
| `training/fetch.py --kind orders` | `ml-server/training` | The same pager, the other endpoint |
| `training/retrain_demand.py` | `ml-server/training` | Fit, backtest, gate, write the model and its manifest entry |
| `training/run.py` | `ml-server/training` | One demand candidate per week beside the late-risk ones |
| `app/registry.py` | `ml-server/app` | Load the demand model by its manifest artifact name |
| Frontend | none | No screen reads it: like the other training exports and the module-to-module order views (`DailyVolumeView`), it has no mirror in `frontend/src/shared/domain/` |

No module reads another's tables: intelligence reaches orders only through `OrderQuery`.

## Decisions

1. **What counts as demand.** Every order once, by its placement date in depot time, exactly the orders `OrderQuery.dailyVolumes` counts: cancelled orders are left out, deferred orders and redeliveries stay. `dailyVolumes` gives the actuals every forecast is judged against (`ml.forecast_accuracy`, R-ML-13), so training on any other count would grade the model against a different quantity (rule 5). A redelivery is also volume the depot carries that week, which is what the forecast plans for. Temperature and volume are the warehouse's answer and can be missing: an order without a temperature cannot be put in a segment and is left out (counted in the export's report), and one without a volume is kept with none, which the model uses for the order schedule but not for the size regressions, as it did for the Datathon's never-run orders.
2. **One model for all depots.** `DemandModel` fits each segment across every outlet with an effect per outlet, as the team trained it. The export is fetched per depot and concatenated.
3. **The reference.** Calendar and outlets come from the same tables the late-risk retrain reads (`data/General Data`, `planning.reference_tables`). After a newer reference import, retrain (the same rule as the README gives for MF-05).
4. **Enough history first.** The model has an annual term and a ramp per festival, so it is refused with under 52 weeks of orders. The tool exits 2 and says so. A pilot depot needs a year before its first candidate; until then production keeps the deterministic forecast, which is the honest answer.
5. **The gate.** Rolling origins: every fourth Monday of the last 26 weeks, each fitted only on orders before it and forecasting 10 weeks, as the team validated. The candidate passes when its total and chilled WAPE are both lower than the naive forecast's on the same weeks, over at least 6 origins. The naive forecast is the mean of the 8 weeks before the origin per depot and brand, a yardstick here rather than a second product rule. When `--current <model file>` is given, the candidate must also not be worse than the current model on the same weeks. Exit 0 with a candidate, 2 without, as `retrain`.
6. **Name and version.** `waypoint-demand`, versioned by the run date (`2026.MM.DD`). It has no `datathon-` prefix, so `ModelGate` lets it serve in production. The manifest entry carries the training range, the backtest WAPEs and the SHA-256.
7. **Handover stays a reviewed change.** `run` writes `<out>/<date>/demand/` and never touches `manifest.json` or `models/`, like the late-risk retrain.
8. **Not in scope.** Recovering never-run orders from gaps in order numbers is a Datathon artifact; our own orders are all recorded. Retraining the Task 1 parallel model needs per-leg actual times in the export and is a separate issue.

## Pull requests

1. **Export:** the ordering query and its index, the intelligence endpoint, the integration and denied-scope test, and `fetch --kind orders`.
2. **Retrain:** `retrain_demand.py`, `run.py`, the registry change, tests, and the README section next to "Retraining on our own deliveries".

## Tests

| Level | Covers |
| --- | --- |
| Integration (Java) | The export pages by keyset, counts each order once, leaves out cancelled orders, keeps redeliveries, and is dated by placement in depot time |
| Authorization (Java) | A depot outside the person's scope is 403 and audited, never an empty page |
| `ml-server` pytest | On a synthetic year of orders: under 52 weeks exits 2; a model that beats the naive forecast is written with a manifest entry whose hash matches; a worse one exits 2; `--current` blocks a regression; the registry serves a `waypoint-demand` entry |
| Dry run (team machine) | The Datathon order history in the export's shape reproduces the team's backtest at their origins (total WAPE about 3.2%, chilled about 2.0%) within rounding. It checks the tool, not a production model |

## Verification

The Java tests, `pytest`, and the dry run above. Then one `training.run` against a local backend with a seeded year of orders, ending in a manifest entry, a `pytest` pass with it in place, and activation on the admin Models screen.
