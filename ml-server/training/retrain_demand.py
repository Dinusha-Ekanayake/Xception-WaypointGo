"""Retrain the demand forecast on our own orders (issue #7, R-ML-28).

Fits the vendored ``task2a_demand.DemandModel`` on the order history the backend
exports (``/api/ml/training/orders``, one order per line, ``training/fetch.py
--kind orders``) and backtests it the way the team validated it: from rolling
Mondays, each fit only on the orders before it, forecasting the next 10 weeks.

The candidate is handed over only when its total and its chilled WAPE are both
lower than a naive forecast's on the same weeks (each depot and brand's mean of
the 8 weeks before the start), and, when ``--current`` names the model in use,
not higher than that model's. The naive forecast is a yardstick for this check,
not the product's fallback. The model is then refitted on every order and
written with a manifest entry for ``ml-server``.

Runs on our own machine: it reads files and writes files, and sends nothing.
Exit 0 with a candidate worth handing over, 2 without one.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'datathon'))
import task2a_demand  # noqa: E402 (vendored)

NAME = 'waypoint-demand'
# The model has an annual term and a ramp per festival: under a year it cannot tell season from trend.
MIN_HISTORY_WEEKS = 52
HORIZON_WEEKS = 10
STEP_WEEKS = 4
MAX_ORIGINS = 6
MIN_ORIGINS = 4
# Each backtest fit sees at least half a year before its start.
MIN_TRAIN_WEEKS = 26
NAIVE_WEEKS = 8
DEFAULT_REFERENCE = Path(__file__).resolve().parents[2] / 'data' / 'General Data'
KEYS = task2a_demand.KEYS


class NotTrained(ValueError):
    """Too little history, or history the reference cannot place: no candidate, and why."""


@dataclass(frozen=True)
class Wape:
    total: float
    chilled: float


@dataclass(frozen=True)
class Result:
    trained_from: str
    trained_to: str
    orders: int
    without_temperature: int
    without_volume: int
    origins: list
    weeks_scored: int
    candidate: Wape
    naive: Wape
    current: Wape | None
    verdict: str
    hand_over: bool


def load_export(path) -> tuple[pd.DataFrame, dict]:
    """The export as the model's order history; an order without a temperature cannot be put in a segment."""
    rows = [json.loads(line) for line in open(path, encoding='utf-8') if line.strip()]
    if not rows:
        raise NotTrained('the export holds no orders')
    df = pd.DataFrame(rows)
    if df.orderId.duplicated().any():
        raise NotTrained('the export holds an order twice')
    counts = dict(without_temperature=int(df.temperature.isna().sum()), without_volume=int(df.volumeM3.isna().sum()))
    df = df[df.temperature.notna()]
    history = pd.DataFrame(dict(
        delivery_id=df.orderId, order_date=df.placedDate, dispatch_status=df.status, outlet_id=df.outletId,
        brand=df.brand, depot=df.depot, temp_requirement=df.temperature,
        order_volume_m3=pd.to_numeric(df.volumeM3, errors='coerce'))).reset_index(drop=True)
    return history, counts


def load_reference(directory=DEFAULT_REFERENCE) -> tuple[pd.DataFrame, pd.DataFrame]:
    """The calendar and outlets the model schedules orders on, from the CSVs the backend imports."""
    d = Path(directory)
    return pd.read_csv(d / 'calendar.csv'), pd.read_csv(d / 'outlets.csv')


def _monday(day: str) -> dt.date:
    d = dt.date.fromisoformat(day)
    return d - dt.timedelta(days=d.weekday())


def origins(history: pd.DataFrame) -> list[str]:
    """Mondays to start a backtest from: the latest with 10 finished weeks after it, then every 4 weeks back."""
    first, last = dt.date.fromisoformat(history.order_date.min()), dt.date.fromisoformat(history.order_date.max())
    last_full = _monday(last.isoformat()) - dt.timedelta(weeks=0 if last.weekday() == 6 else 1)
    latest = last_full - dt.timedelta(weeks=HORIZON_WEEKS - 1)
    earliest = first + dt.timedelta(weeks=MIN_TRAIN_WEEKS)
    out = [latest - dt.timedelta(weeks=STEP_WEEKS * k) for k in range(MAX_ORIGINS)]
    return sorted(d.isoformat() for d in out if d >= earliest)


def _weeks(cal: pd.DataFrame) -> pd.DataFrame:
    """Each ISO week's Monday, from the calendar."""
    c = cal[['date', 'iso_year', 'iso_week']].copy()
    c['monday'] = c.date.map(lambda d: _monday(d).isoformat())
    return c.drop_duplicates(['iso_year', 'iso_week'])[['iso_year', 'iso_week', 'monday']]


def _naive(actual: pd.DataFrame, origin: str) -> pd.DataFrame:
    """Each depot and brand's mean week over the 8 weeks before the origin."""
    start = (dt.date.fromisoformat(origin) - dt.timedelta(weeks=NAIVE_WEEKS)).isoformat()
    past = actual[(actual.monday >= start) & (actual.monday < origin)]
    return past.groupby(['depot', 'brand'])[['v', 'ch']].sum().div(NAIVE_WEEKS).rename(
        columns={'v': 'naive_total', 'ch': 'naive_chilled'}).reset_index()


def _wape(actual: pd.Series, forecast: pd.Series) -> float:
    total = float(actual.sum())
    return float(np.abs(actual - forecast).sum() / total) if total > 0 else float('nan')


def backtest(history, cal, outlets, starts, current=None) -> pd.DataFrame:
    """One row per origin, depot, brand and week: what was ordered and what each forecast said."""
    weeks = _weeks(cal)
    actual = task2a_demand.weekly_actuals(history, cal).merge(weeks, on=['iso_year', 'iso_week'])
    rows = []
    for origin in starts:
        end = (dt.date.fromisoformat(origin) + dt.timedelta(weeks=HORIZON_WEEKS, days=-1)).isoformat()
        model = task2a_demand.DemandModel().fit(history[history.order_date < origin], cal, outlets)
        f = model.weekly(origin, end, cal, outlets).rename(columns={'total': 'cand_total', 'chilled': 'cand_chilled'})
        a = actual[(actual.monday >= origin) & (actual.monday <= end)]
        r = a.merge(f, on=KEYS, how='left').merge(_naive(actual, origin), on=['depot', 'brand'], how='left')
        if current is not None:
            c = current.weekly(origin, end, cal, outlets).rename(columns={'total': 'cur_total', 'chilled': 'cur_chilled'})
            r = r.merge(c, on=KEYS, how='left')
        rows.append(r.assign(origin=origin).fillna({k: 0.0 for k in r.columns if k.startswith(('cand_', 'naive_', 'cur_'))}))
    return pd.concat(rows, ignore_index=True)


def scores(bt: pd.DataFrame, prefix: str) -> Wape:
    return Wape(total=round(_wape(bt.v, bt[f'{prefix}_total']), 4), chilled=round(_wape(bt.ch, bt[f'{prefix}_chilled']), 4))


def gate(candidate: Wape, naive: Wape, current: Wape | None) -> tuple[str, bool]:
    """BETTER only when both WAPEs beat the naive forecast and neither is worse than the model in use."""
    if not (candidate.total < naive.total and candidate.chilled < naive.chilled):
        return 'NOT_BETTER_THAN_NAIVE', False
    if current is not None and (candidate.total > current.total or candidate.chilled > current.chilled):
        return 'WORSE_THAN_CURRENT', False
    return 'BETTER', True


def train(history, counts, cal, outlets, current=None):
    """(Result, the model refitted on every order), or NotTrained naming what is missing."""
    first, last = history.order_date.min(), history.order_date.max()
    span = (dt.date.fromisoformat(last) - dt.date.fromisoformat(first)).days / 7
    if span < MIN_HISTORY_WEEKS:
        raise NotTrained(f'{span:.0f} weeks of orders; the model needs {MIN_HISTORY_WEEKS} (an annual term and a ramp per festival)')
    unplaced = sorted(set(history.order_date) - set(cal.date))
    if unplaced:
        raise NotTrained(f'{len(unplaced)} order days are not in the calendar, first {unplaced[0]}: import a newer reference')
    starts = origins(history)
    if len(starts) < MIN_ORIGINS:
        raise NotTrained(f'{len(starts)} backtest starts; at least {MIN_ORIGINS} are needed')
    bt = backtest(history, cal, outlets, starts, current)
    candidate, naive = scores(bt, 'cand'), scores(bt, 'naive')
    in_use = scores(bt, 'cur') if current is not None else None
    verdict, hand_over = gate(candidate, naive, in_use)
    model = task2a_demand.DemandModel().fit(history, cal, outlets)
    result = Result(trained_from=first, trained_to=last, orders=int(len(history)), origins=starts,
                    weeks_scored=int(len(bt)), candidate=candidate, naive=naive, current=in_use, verdict=verdict,
                    hand_over=hand_over, **counts)
    return result, model


def write(result: Result, model, version: str, out) -> dict:
    """The model file and its manifest entry, ready to copy into ``models/`` and ``manifest.json``."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    file = f'{NAME}-{version}.joblib'
    joblib.dump(model, out / file, compress=3)
    digest = hashlib.sha256((out / file).read_bytes()).hexdigest()
    entry = dict(kind='demand_forecast', name=NAME, version=version, trainedFrom=result.trained_from,
                 trainedTo=result.trained_to,
                 metrics=dict(total_wape=result.candidate.total, chilled_wape=result.candidate.chilled,
                              naive_total_wape=result.naive.total, naive_chilled_wape=result.naive.chilled,
                              backtest_origins=len(result.origins)),
                 artifacts={file: digest})
    (out / 'manifest-entry.json').write_text(json.dumps(entry, indent=2) + '\n', encoding='utf-8')
    (out / 'report.json').write_text(json.dumps(asdict(result), indent=2) + '\n', encoding='utf-8')
    return entry


def current_model(manifest_path, models_dir):
    """The demand model the service would load now, as the replay's comparison; None when the manifest has none."""
    manifest = json.loads(Path(manifest_path).read_text(encoding='utf-8'))
    entry = next((m for m in manifest['models'] if m['kind'] == 'demand_forecast'), None)
    if entry is None:
        return None
    return joblib.load(Path(models_dir) / next(iter(entry['artifacts'])))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Retrain the demand forecast on our own orders (#7).')
    ap.add_argument('--export', required=True, help='JSON lines from /api/ml/training/orders (fetch --kind orders)')
    ap.add_argument('--version', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--reference', default=str(DEFAULT_REFERENCE))
    ap.add_argument('--current', help='the model file in use, which the candidate must not do worse than')
    a = ap.parse_args(argv)
    cal, outlets = load_reference(a.reference)
    current = joblib.load(a.current) if a.current else None
    try:
        history, counts = load_export(a.export)
        result, model = train(history, counts, cal, outlets, current)
    except NotTrained as e:
        Path(a.out).mkdir(parents=True, exist_ok=True)
        (Path(a.out) / 'report.json').write_text(json.dumps({'notTrained': str(e)}, indent=2) + '\n', encoding='utf-8')
        print(f'Not trained: {e}. What is in use stays.')
        return 2
    write(result, model, a.version, a.out)
    print(json.dumps(asdict(result), indent=2))
    if not result.hand_over:
        print(f'Not handed over: {result.verdict}. What is in use stays.')
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
