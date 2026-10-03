# Dispatcher: Forecast and late-risk screens: plan

Issue [#119](https://github.com/kavindamihiran/Xception-WaypointGo/issues/119). Figma file `TX2bkkANaIMI3UbpnjlPer`, page "05 · Dispatcher · Desktop", read only: Forecast `189:11993`, Plan 2 View plan `189:18510`.

## Current state

Most of the issue is already on `dev`, built by teammates:

| Asked for | Built | Where |
| --- | --- | --- |
| Forecast: weekly demand against fleet and refrigerated capacity | Yes: KPI cards, stacked weekly bars against the fleet, chilled against refrigerated strip, what the busiest days need, suggested actions, last and next run | #181, `screens/Forecast.tsx`, `ForecastChart.tsx`, `ForecastSide.tsx`, `ForecastRuns.tsx` |
| Fallback when no model | Yes: a fallback forecast run is a named warning; a plan made without the predictor says so | `Forecast.tsx`, `Plan.tsx` (`plannedWithoutPredictor`) |
| Late risk on the plan | Only the count: the View plan card "Late risk · N high · over 35% chance" on a published plan | `PlanBoard.tsx`, `data/planViews.ts` (`lateRisk`) |
| Late risk per trip and per stop | **Not built** | |

The backend needs no change: `GET /api/ml/plans/{planId}/predictions` already gives every stop's `lateProbability`, `modelLabel` and `degraded`. `GET /api/ml/models` gives each model's validation `metrics`.

Measured against Figma at 1440x900:
- **Forecast (`189:11993`)**:
  - matches in layout and content;
  - missing the "Forecast error" chip;
  - the brand filter sits in the header rather than under the title (a teammate's layout; kept);
  - three `forecast.spec.ts` browser tests fail on `dev` since 86e6d57 removed the model label text they expect.
- **View plan (`189:18510`)**: the late-risk card matches. Per-trip and per-stop risk is absent.

## Decisions

1. **The percentage beside each stop in the trip timeline stays the load share.** The team decided this in FIGMA-GAP V10, and other people's decisions on Plan take priority. Late risk is a separate tag on the stop row: `Late 41%` when scored, amber from 20%, red from 35% (`LATE_RISK_PERCENT`).
2. **Trips get a late-risk tag on the board**, in the same style as the existing `Tight` and `Added` tags: `Late risk 41%` when the trip's worst stop is at or above 35%. Below that, no tag; the board stays quiet.
3. **Only a published plan is scored**, as now. A draft says "Late risk is scored once the plan is published", never 0%.
4. **Degraded predictions are labelled.** When the predictor was off, predictions are the deterministic estimate. Tags then read `Late 41% · estimate` and the card says "Estimated: the time predictor was not running" (degrade visibly, rule 9).
5. **The Forecast error chip shows what the model actually measured.** The active `demand_forecast` model has `total_wape` and `chilled_wape` (about 4.2% and 4.4%), not per brand. The chip reads "Forecast error · ±4% total · ±4% chilled" with the model's name in its tooltip. On a fallback run it reads "Recent averages · error not measured". Figma's per-brand figures are not invented.
6. **The three failing forecast tests are rewritten** to the wording the screen uses now. No product text goes back.
7. **Live is out of scope.** The issue names the Plan and Forecast views, and Live's at-risk view is a teammate's (#190).

## Work

**Frontend** (`frontend/src/roles/dispatcher/`), additive, inside the teammates' components:

- `data/planViews.ts`, pure:
  - `stopRisk(predictions)` maps each order to its percent and degraded flag;
  - `tripRisk(plan, predictions)` gives each trip's worst stop;
  - `riskTone(percent)`.
- `screens/PlanBoard.tsx`: the trip cell gets the `Late risk N%` tag; the card's note handles the degraded case.
- `screens/PlanTrip.tsx`: each stop row gets the `Late N%` tag next to the load share.
- `data/useForecast.ts` and `screens/Forecast.tsx`: read `/api/ml/models` once, take the active `demand_forecast` model's metrics and draw the error chip, or its fallback wording.
- `tests/e2e-dispatcher/forecast.spec.ts`: the three failing tests follow the current wording.

**Tests**
- Unit, in `dispatcher-plan-views.test.ts`: stop and trip risk, the thresholds, a degraded scoring, and a draft with no predictions. In `dispatcher-forecast.test.ts`: the error chip text from metrics and for a fallback run.
- Browser, in the dispatcher suite:
  - a published plan shows the trip tag and the stop tags;
  - a draft shows the "scored once published" note;
  - a degraded scoring says it is an estimate;
  - the Forecast shows the error chip.

**Docs**: this plan, then `WALKTHROUGH.md`, the STATUS dispatcher row, and a log entry.

## PR

One PR into `dev`: `feat/119-forecast-late-risk`.

## Verification

- `npm run typecheck`, `npm test`, `npm run build`, and the dispatcher browser suite.
- Screenshots at 1440x900 beside `189:18510` and `189:11993`.
- On a running stack with the models active:
  - publish a plan;
  - wait for its scoring (about 30 s);
  - see the tags;
  - stop the `ml` container and publish again, and see the estimate labels.
