"""Retrain the late-risk model on our own deliveries (roadmap MF-05, R-ML-18).

Step 2 of the production ladder: LightGBM on the planning-time features of
:mod:`training.planning` (the route's ten, and with the reference tables eight more), from the backend's ``/api/ml/training/routes`` export
(one JSON route per line). The last weeks are held out in date order, and the
candidate is compared with the window slack alone, the simplest honest rule. It
is better only when both its Brier score and its log loss are lower on at least
200 labelled stops (the same test as R-ML-16). The model is then refitted on
every labelled stop and written with a manifest entry for ``ml-server``.

Runs on our own machine: it reads a file and writes files, and sends nothing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

from training.leakage import HOLDOUT_WEEKS, date_holdout
from training.planning import columns, features, load_reference

MIN_LABELS = 200
PIPELINE = 'planning'
LATE = dict(n_estimators=400, learning_rate=0.03, num_leaves=15, min_child_samples=50, subsample=0.8,
            subsample_freq=1, colsample_bytree=0.9, reg_lambda=1.0, verbose=-1, n_jobs=4, random_state=0)
SERVICE = dict(LATE, objective='regression')


@dataclass(frozen=True)
class Figures:
    auc: float
    brier: float
    log_loss: float


@dataclass(frozen=True)
class Replay:
    """The candidate against what answered at the time, on the same held-out stops (MF-04, R-ML-16)."""
    answered_by: list[str]
    stops: int
    candidate: Figures
    incumbent: Figures
    verdict: str          # BETTER, WORSE, MIXED or NOT_ENOUGH


@dataclass(frozen=True)
class Result:
    rows: int
    labelled: int
    holdout_from: str
    holdout_to: str
    holdout_labels: int
    candidate: Figures
    slack_only: Figures
    service_mae_min: float | None
    verdict: str          # BETTER, NOT_BETTER or NOT_ENOUGH
    trained_from: str
    trained_to: str
    replay: Replay | None = None

    @property
    def hand_over(self) -> bool:
        """Beats the slack alone, and is not worse than what answered at the time where that is known."""
        return self.verdict == 'BETTER' and (self.replay is None or self.replay.verdict in ('BETTER', 'NOT_ENOUGH'))


def load_export(path: str | Path, g: dict | None = None) -> pd.DataFrame:
    """Each stop's features beside its label; a stop with no trustworthy arrival has ``late`` missing."""
    routes, labels = [], {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            item = json.loads(line)
            routes.append(item['route'])
            for lab in item['labels']:
                labels[lab['deliveryId']] = lab
    x = features(routes, g)
    x['late'] = [None if labels.get(d, {}).get('late') is None else float(labels[d]['late']) for d in x.deliveryId]
    x['service_min'] = [labels.get(d, {}).get('serviceMinutes') for d in x.deliveryId]
    x['answered_by'] = [labels.get(d, {}).get('answeredBy') for d in x.deliveryId]
    x['answered_prob'] = pd.to_numeric(pd.Series([labels.get(d, {}).get('answeredLateProb') for d in x.deliveryId],
                                                 dtype=object), errors='coerce').to_numpy()
    x['late'] = x.late.astype(float)
    x['service_min'] = pd.to_numeric(x.service_min, errors='coerce')
    return x


def _figures(y, p) -> Figures:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return Figures(auc=float(roc_auc_score(y, p)), brier=float(brier_score_loss(y, p)),
                   log_loss=float(log_loss(y, p, labels=[0, 1])))


def _late_model(x: pd.DataFrame, cols: list[str]):
    import lightgbm as lgb
    return lgb.LGBMClassifier(**LATE).fit(x[cols], x.late.astype(int))


def _service_model(x: pd.DataFrame, cols: list[str]):
    import lightgbm as lgb
    s = x[x.service_min.notna() & (x.service_min > 0)]
    if len(s) < MIN_LABELS:
        return None
    return lgb.LGBMRegressor(**SERVICE).fit(s[cols], np.log(s.service_min))


def _replay(te: pd.DataFrame, y: np.ndarray, predicted: np.ndarray) -> Replay | None:
    if 'answered_prob' not in te:
        return None
    known = te.answered_prob.notna().to_numpy()
    if not known.any():
        return None
    yk = y[known]
    cand, inc = _figures(yk, predicted[known]), _figures(yk, te.answered_prob.to_numpy()[known].astype(float))
    if known.sum() < MIN_LABELS or yk.sum() == 0 or yk.sum() == len(yk):
        verdict = 'NOT_ENOUGH'
    else:
        brier, loss = cand.brier < inc.brier, cand.log_loss < inc.log_loss
        verdict = 'BETTER' if brier and loss else 'WORSE' if not brier and not loss else 'MIXED'
    return Replay(answered_by=sorted(set(te.answered_by[known].dropna())), stops=int(known.sum()),
                  candidate=cand, incumbent=inc, verdict=verdict)


def train(frame: pd.DataFrame, weeks: int = HOLDOUT_WEEKS):
    """The holdout comparison, then the model refitted on every labelled stop: (Result, artifact)."""
    labelled = frame[frame.late.notna()].reset_index(drop=True)
    if labelled.empty:
        raise ValueError('no labelled stops to train on')
    hold = date_holdout(labelled.date, weeks)
    if hold.all() or not hold.any():
        raise ValueError(f'need labelled stops before and inside the last {weeks} weeks')
    tr, te = labelled[~hold], labelled[hold]
    cols = columns(labelled)
    y = te.late.astype(int).to_numpy()
    late_model = _late_model(tr, cols)
    predicted = late_model.predict_proba(te[cols])[:, 1]
    candidate = _figures(y, predicted)
    slack = LogisticRegression().fit(tr[['slack_close']], tr.late.astype(int))
    slack_only = _figures(y, slack.predict_proba(te[['slack_close']])[:, 1])
    svc = _service_model(tr, cols)
    s_te = te[te.service_min.notna() & (te.service_min > 0)]
    mae = float(np.mean(np.abs(np.exp(svc.predict(s_te[cols])) - s_te.service_min))) if svc is not None and len(s_te) else None
    if len(te) < MIN_LABELS or y.sum() == 0:
        verdict = 'NOT_ENOUGH'
    elif candidate.brier < slack_only.brier and candidate.log_loss < slack_only.log_loss:
        verdict = 'BETTER'
    else:
        verdict = 'NOT_BETTER'
    d = pd.to_datetime(labelled.date)
    dh = pd.to_datetime(te.date)
    replay = _replay(te, y, predicted)
    result = Result(rows=len(frame), labelled=len(labelled), holdout_from=str(dh.min().date()),
                    holdout_to=str(dh.max().date()), holdout_labels=len(te), candidate=candidate,
                    slack_only=slack_only, service_mae_min=mae, verdict=verdict,
                    trained_from=str(d.min().date()), trained_to=str(d.max().date()), replay=replay)
    known = labelled.service_min[labelled.service_min.notna() & (labelled.service_min > 0)]
    artifact = dict(pipeline=PIPELINE, features=cols, late=_late_model(labelled, cols),
                    service=_service_model(labelled, cols),
                    service_median=float(known.median()) if len(known) else 15.0)
    return result, artifact


def write(result: Result, artifact: dict, name: str, version: str, out: str | Path) -> dict:
    """The model file and its manifest entry, ready to copy into ``models/`` and ``manifest.json``."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    file = f'{name}-{version}.joblib'
    joblib.dump(artifact, out / file)
    digest = hashlib.sha256((out / file).read_bytes()).hexdigest()
    entry = dict(kind='delivery_risk', name=name, version=version, pipeline=PIPELINE,
                 trainedFrom=result.trained_from, trainedTo=result.trained_to,
                 metrics=dict(late_auc_last6w=round(result.candidate.auc, 4),
                              late_brier_last6w=round(result.candidate.brier, 4),
                              late_logloss_last6w=round(result.candidate.log_loss, 4),
                              slack_only_logloss_last6w=round(result.slack_only.log_loss, 4),
                              **({} if result.service_mae_min is None
                                 else dict(service_mae_min_last6w=round(result.service_mae_min, 4)))),
                 artifacts={file: digest})
    (out / 'manifest-entry.json').write_text(json.dumps(entry, indent=2) + '\n', encoding='utf-8')
    (out / 'report.json').write_text(json.dumps(asdict(result), indent=2) + '\n', encoding='utf-8')
    return entry


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Retrain the late-risk model on our own deliveries (MF-05).')
    ap.add_argument('--export', required=True, help='JSON lines from /api/ml/training/routes (training/fetch.py)')
    ap.add_argument('--name', default='waypoint-planning')
    ap.add_argument('--version', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--weeks', type=int, default=HOLDOUT_WEEKS)
    ap.add_argument('--reference', default=str(Path(__file__).resolve().parents[2] / 'data' / 'General Data'),
                    help='reference.json from fetch --kind reference, a directory of the supplied CSVs (dry runs),'
                         ' or "none" to train on the route alone')
    a = ap.parse_args(argv)
    g = None if a.reference == 'none' else load_reference(a.reference)
    result, artifact = train(load_export(a.export, g), a.weeks)
    write(result, artifact, a.name, a.version, a.out)
    print(json.dumps(asdict(result), indent=2))
    if not result.hand_over:
        why = result.verdict if result.verdict != 'BETTER' else f'{result.replay.verdict} than what answered then'
        print(f'Not handed over: {why}. What is in use stays.')
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
