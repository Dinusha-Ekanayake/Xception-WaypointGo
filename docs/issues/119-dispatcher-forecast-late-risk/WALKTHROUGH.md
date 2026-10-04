# Dispatcher: Forecast and late-risk screens: walkthrough

Issue [#119](https://github.com/kavindamihiran/Xception-WaypointGo/issues/119), branch `feat/119-forecast-late-risk`. The [plan](PLAN.md) has the decisions. The teammates' Plan, Forecast and Live designs are kept as built; this adds to them.

## What is built

| Layer | Files |
| --- | --- |
| Pure logic | `frontend/src/roles/dispatcher/data/planViews.ts`: `stopRisks`, `tripRisks`, `riskTone`, `riskLabel`, `scoredByEstimate`, `LATE_WATCH_PERCENT`. `data/forecast.ts`: `forecastError` |
| Reads | `data/useForecast.ts`: `useModels` (`GET /api/ml/models`). The plan's `GET /api/ml/plans/{id}/predictions` was already read by `usePredictions` |
| Plan | `screens/PlanBoard.tsx`: a trip cell gets `Late risk N%` from 20% (amber) and 35% (red); the card notes an estimate. `screens/PlanTrip.tsx`: every stop row gets `Late N%` beside the load share |
| Forecast | `screens/Forecast.tsx`: the "Forecast error" chip under the run strip. `ForecastChart.tsx`: the screen reader table sits in a hidden wrapper, so it no longer widens a phone |
| Counts | Live, Orders and Vehicles filters read `Needs you (9)`, `At risk (4)`, `All (12)` |
| Phones | `FilterTabs` (shared) and Live's day and view group wrap; the dispatcher's phone header wraps; the Plan's four cards sit two per row below desktop |

No backend change. Figma frames matched (file `TX2bkkANaIMI3UbpnjlPer`, read only): View plan `189:18510`, Forecast `189:11993`.

## Flows

**Late risk.**
1. A plan is published.
2. Intelligence scores it within about 30 s. Its predictions give each stop a `lateProbability`, and say whether the model or the deterministic estimate answered.
3. The board tags a trip by its worst stop.
4. The open trip tags each stop.
5. A tiny chance reads `<1%`, never 0%.
6. When the estimate answered, every tag adds `· estimate` and the card says "estimated".
7. A draft is never scored, and says so.

**Forecast error.** The chip takes the active `demand_forecast` model's validation `total_wape` and `chilled_wape`: "Forecast error · ±4% total · ±4% chilled". A fallback run reads "Recent averages · forecast error not measured". The registry has no per-brand error, so none is shown. The model's own name stays off the screen (86e6d57).

## Run and verify

- **Unit:** `npm test` (`dispatcher-plan-views.test.ts`, `dispatcher-forecast.test.ts`).
- **Browser:** `npx playwright test -c playwright.dispatcher.config.ts`, 42 pass, including `plan-risk.spec.ts` and the three `forecast.spec.ts` tests that failed on `dev`.
- **On the VPS preview database:**
  - **Forecast:** the chip reads the real registry, ±4% total and ±4% chilled.
  - **Plan:** the Peliyagoda plan for Mon 5 Oct was published through the plan commands. It shows `Late <1% · estimate` on its stops.
  - **Widths:** every dispatcher screen has no horizontal overflow at 1440, 1024, 768, 390 and 360 px.

## Known gaps

- On the preview, the `delivery_risk` model is registered active but the model service answers 500, so plans are scored by the estimate (#16).
- Late risk on Live is a teammate's (#190) and unchanged.
