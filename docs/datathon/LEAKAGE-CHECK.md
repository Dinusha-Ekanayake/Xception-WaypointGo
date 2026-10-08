# Task 1: leakage check of the late-risk model

How much of the reported late-risk score holds on days the model has never seen, and without information a plan does not have when it is made.

**AI tool disclosure.** This check (`ml-server/training/leakage.py` and its tests) was written with an AI coding assistant, and the team ran it on the competition data. The Task 1 labels, features and models it measures are the team's own work. If this result is used in the submission, the AI tool disclosure must say so.

## Why

The original validation grouped folds by route (`ml-server/datathon/src/task1_a.py`). Two things could make that score look better than it is:

1. **The split.** Folds grouped by route still put other routes of the same day on both sides, so a fold can learn that day's conditions.
2. **The inputs.** `disruption_index` in `road_conditions.csv` is the road condition that actually happened on the day. A plan made the evening before does not know it. The features derived from it (`tmult`, `exp_extra_travel`, `cum_exp_extra_travel`) carry the same information.

## Method

- **Labels.** The team's `construct_labels`: late means arrival after the window closed. Service is time from the later of arrival and window opening to leaving the outlet, so an early vehicle's wait is not counted as service.
- **Features.** The team's `features_a.build`, unchanged. The "without road conditions" run uses its own `no_road_conditions=True` option, which sets `disruption_index` to 100 (no disruption), so the derived features carry nothing either.
- **Model.** One LightGBM classifier with fixed parameters and seed, the same in every run. It measures the feature set and the split, not the final blend.
- **Three ways of scoring the same rows:**
  1. four folds grouped by route, as the original validation did;
  2. the last 6 weeks held out in date order, training strictly before them;
  3. the same holdout without road conditions.
- **A baseline with no model:** the window's slack (`slack_close`, window close minus planned arrival) alone.
- A gap of 0.02 or more in AUC, or a log loss 10% or more worse, is reported as a finding.

## Result

Full Task 1 training data, 91,894 stops from 2024-01-01 to 2026-02-14. Holdout: 2026-01-05 to 2026-02-14, 5,035 stops, 660 of them late.

| Validation | AUC | Log loss |
| --- | --- | --- |
| Folds grouped by route | 0.977 | 0.152 |
| Last 6 weeks, date order | 0.975 | 0.133 |
| Last 6 weeks, date order, **no road conditions** | **0.957** | **0.168** |
| Window slack alone, no model | 0.880 | |

## Findings

1. **The split is not the problem.** Folds by route score 0.002 of AUC more than unseen later weeks, within noise.
2. **The road conditions are.** Without them AUC falls by 0.018 and log loss is **26% worse** (0.133 to 0.168). Since `disruption_index` is only known after the day, 0.168 is the honest figure for a prediction made at planning time. The team's own manifest agrees: its fallback model, trained without road conditions, scores 0.1664 on the last 6 weeks.
3. **The model adds real value over the simplest rule.** 0.957 against 0.880 for the slack alone.

For the submission, the test inputs include road conditions for their dates, so using them for the Task 1 predictions is allowed by the booklet. The finding matters for the write-up and for deployment: the figure to quote for real use is the third row.

## Reproducing it

The competition data stays on the team's machine, as the terms require.

```bash
cd ml-server
python -m training.leakage --datathon "/path/to/Datasets/data" --out leakage-report.json
```

`ml-server/tests/test_leakage.py` checks on synthetic data that the method detects both problems when they exist and reports nothing when they do not.
