"""Leakage check (roadmap MF-05, R-ML-17).

A model's reported figures can flatter it in two ways this check measures:

* the split: folds grouped by route still let the same day's conditions sit on
  both sides, so a fold score is better than what the model will see on days
  it has never met. The honest figure holds out the last weeks in date order.
* the inputs: a feature known only after the day (``disruption_index``, the
  realised road conditions) lifts the score but does not exist when a plan is
  published. The honest figure is scored without it.

The report puts the four numbers side by side, plus how far the window's slack
alone gets, so a model that is mostly slack is visible. Nothing here reads a
clock or a network; the runner decides what data goes in.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict, dataclass

import numpy as np
import pandas as pd
from sklearn.metrics import log_loss, roc_auc_score
from sklearn.model_selection import GroupKFold

HOLDOUT_WEEKS = 6
# A gap above this many points of AUC is reported as a finding, not noise.
MATERIAL_AUC_GAP = 0.02
# AUC ranks and hides calibration; a log loss this much worse, relatively, is a finding too.
MATERIAL_LOSS_RISE = 0.10
LGB_PARAMS = dict(n_estimators=300, learning_rate=0.05, num_leaves=31, min_child_samples=40, subsample=0.8,
                  subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, verbose=-1, n_jobs=4, random_state=0)


@dataclass(frozen=True)
class Score:
    auc: float
    log_loss: float
    n: int
    positives: int


@dataclass(frozen=True)
class Report:
    holdout_from: str
    holdout_to: str
    train_rows: int
    route_folds: Score          # what the original validation reported
    date_holdout: Score         # last weeks in date order, road conditions included
    date_holdout_no_road: Score  # the production figure: last weeks, no realised conditions
    slack_close_auc: float      # the window's slack alone, no model
    split_optimism: float       # route folds minus date holdout, in AUC
    road_dependence: float      # with minus without road conditions, in AUC
    road_loss_rise: float       # how much worse log loss is without road conditions, relatively
    findings: list[str]


def date_holdout(dates: pd.Series, weeks: int = HOLDOUT_WEEKS) -> np.ndarray:
    """True for rows in the last ``weeks`` weeks of the data, by calendar date; training is strictly before."""
    d = pd.to_datetime(dates)
    cut = d.max() - pd.Timedelta(weeks=weeks)
    return (d > cut).to_numpy()


def score(y: np.ndarray, p: np.ndarray) -> Score:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return Score(auc=float(roc_auc_score(y, p)), log_loss=float(log_loss(y, p, labels=[0, 1])), n=int(len(y)),
                 positives=int(np.sum(y)))


def _fit_predict(train: pd.DataFrame, test: pd.DataFrame, features: list[str], label: str) -> np.ndarray:
    import lightgbm as lgb
    m = lgb.LGBMClassifier(**LGB_PARAMS).fit(train[features], train[label].astype(int))
    return m.predict_proba(test[features])[:, 1]


def route_folds(frame: pd.DataFrame, features: list[str], label: str, group: str, folds: int = 4) -> Score:
    """The original validation: out-of-fold predictions with folds grouped by route."""
    oof = np.zeros(len(frame))
    for a, b in GroupKFold(folds).split(frame, groups=frame[group]):
        oof[b] = _fit_predict(frame.iloc[a], frame.iloc[b], features, label)
    return score(frame[label].to_numpy().astype(int), oof)


def check(with_road: pd.DataFrame, no_road: pd.DataFrame, features: list[str], *, label: str = 'late',
          date: str = 'date', group: str = 'route_id', slack: str = 'slack_close',
          weeks: int = HOLDOUT_WEEKS) -> Report:
    """``with_road`` and ``no_road`` are the same rows, built with and without the realised conditions."""
    if len(with_road) != len(no_road):
        raise ValueError('both frames must hold the same rows')
    with_road = with_road[with_road[label].notna()].reset_index(drop=True)
    no_road = no_road[no_road[label].notna()].reset_index(drop=True)
    hold = date_holdout(with_road[date], weeks)
    if hold.all() or not hold.any():
        raise ValueError(f'need data before and inside the last {weeks} weeks')
    y = with_road.loc[hold, label].to_numpy().astype(int)
    folds = route_folds(with_road, features, label, group)
    dated = score(y, _fit_predict(with_road[~hold], with_road[hold], features, label))
    honest = score(y, _fit_predict(no_road[~hold], no_road[hold], features, label))
    # Less slack means more likely late, so the slack itself is negated to rank.
    slack_auc = float(roc_auc_score(y, -with_road.loc[hold, slack].to_numpy()))
    optimism = folds.auc - dated.auc
    road = dated.auc - honest.auc
    loss_rise = honest.log_loss / dated.log_loss - 1
    findings = []
    if optimism > MATERIAL_AUC_GAP:
        findings.append(f'route folds overstate AUC by {optimism:.3f} against unseen later weeks')
    if road > MATERIAL_AUC_GAP:
        findings.append(f'realised road conditions add {road:.3f} AUC that a plan cannot know when published')
    if loss_rise > MATERIAL_LOSS_RISE:
        findings.append(f'without realised road conditions log loss is {loss_rise:.0%} worse '
                        f'({dated.log_loss:.3f} to {honest.log_loss:.3f}): the second is the production figure')
    if honest.auc - slack_auc < MATERIAL_AUC_GAP:
        findings.append(f'the model adds little over the window slack alone ({slack_auc:.3f})')
    d = pd.to_datetime(with_road.loc[hold, date])
    return Report(holdout_from=str(d.min().date()), holdout_to=str(d.max().date()), train_rows=int((~hold).sum()),
                  route_folds=folds, date_holdout=dated, date_holdout_no_road=honest, slack_close_auc=slack_auc,
                  split_optimism=optimism, road_dependence=road, road_loss_rise=loss_rise,
                  findings=findings)


def datathon_frames(data_dir: str):
    """The Task 1 training legs built twice, with and without road conditions, by the Datathon feature code."""
    os.environ['DATA_DIR'] = data_dir
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'datathon'))
    from src import data, features_a
    g = data.load_general()
    t = data.load_task1()
    frames = []
    for no_road in (False, True):
        f = features_a.build([('train', t['ltr'])], [t['dtr']], g, no_road_conditions=no_road)
        cats = features_a.categories(f)
        frames.append(features_a.set_categories(f, cats))
    return frames[0], frames[1], features_a.NUM + features_a.CAT


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Leakage check for the late-risk model (MF-05).')
    ap.add_argument('--datathon', required=True, help='directory holding the Datathon "General Data" and "Training Data"')
    ap.add_argument('--weeks', type=int, default=HOLDOUT_WEEKS)
    ap.add_argument('--out', help='write the report as JSON here as well')
    a = ap.parse_args(argv)
    with_road, no_road, features = datathon_frames(a.datathon)
    report = check(with_road, no_road, features, weeks=a.weeks)
    text = json.dumps(asdict(report), indent=2)
    print(text)
    if a.out:
        with open(a.out, 'w') as f:
            f.write(text + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
