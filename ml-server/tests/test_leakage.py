"""Roadmap MF-05, R-ML-17: the leakage check finds a realised feature and an optimistic split."""
import numpy as np
import pandas as pd
import pytest

from training.leakage import check, date_holdout


def frames(seed=0, routes=600, stops=5, road_signal=2.5):
    """Routes over 20 weeks. Lateness rises with tight slack, and each day's (district) disruption,
    which the realised road index reveals exactly and nothing at planning time does."""
    rng = np.random.default_rng(seed)
    rows = []
    days = pd.date_range('2026-01-05', periods=140, freq='D')
    disruption = {(d, k): rng.normal() for d in days for k in range(4)}
    for r in range(routes):
        day = days[rng.integers(len(days))]
        district = int(rng.integers(4))
        dis = disruption[(day, district)]
        for s in range(stops):
            slack = rng.uniform(-10, 120)
            z = -slack / 30 + 1.0 + road_signal * dis + 0.3 * s
            rows.append(dict(date=day, day=(day - days[0]).days, route_id=f'R{r}', seq=s, district=district, slack_close=slack,
                             disruption_index=100 - 20 * dis, late=float(rng.random() < 1 / (1 + np.exp(-z)))))
    with_road = pd.DataFrame(rows)
    no_road = with_road.assign(disruption_index=100.0)
    return with_road, no_road, ['slack_close', 'seq', 'district', 'disruption_index']


def test_the_holdout_is_the_last_weeks_and_training_is_strictly_before():
    dates = pd.Series(pd.to_datetime(['2026-01-01', '2026-02-01', '2026-03-01', '2026-03-20']))
    hold = date_holdout(dates, weeks=6)
    assert list(hold) == [False, False, True, True]
    assert dates[~hold].max() < dates[hold].min()


def test_a_realised_feature_and_a_same_day_split_are_both_reported():
    with_road, no_road, features = frames()
    r = check(with_road, no_road, features)
    assert r.road_dependence > 0.05, r
    assert any('realised road conditions add' in f for f in r.findings)
    assert any('log loss is' in f for f in r.findings)
    assert r.date_holdout_no_road.auc < r.date_holdout.auc
    assert 0.5 < r.slack_close_auc < r.date_holdout.auc


def test_route_folds_that_can_learn_the_day_are_reported_as_optimistic():
    # No road index at all, but the day itself is a feature: route folds hold other routes of the
    # same day and learn its disruption; later weeks are days the model has never seen.
    with_road, no_road, _ = frames(seed=2, routes=2500)
    r = check(no_road, no_road, ['slack_close', 'seq', 'district', 'day'])
    assert r.split_optimism > 0.02, r
    assert any('route folds overstate' in f for f in r.findings)


def test_without_a_realised_feature_nothing_is_flagged_about_it():
    with_road, no_road, features = frames(seed=1, road_signal=0.0)
    r = check(with_road, no_road, features)
    assert abs(r.road_dependence) < 0.02, r
    assert not any('road conditions' in f for f in r.findings)


def test_both_frames_must_hold_the_same_rows():
    with_road, no_road, features = frames(routes=50)
    with pytest.raises(ValueError):
        check(with_road, no_road.iloc[:-1], features)
