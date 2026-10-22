"""The scheduled retraining run (roadmap MF-05, issues #7 and #12), for cron on our own server.

Fetches every named depot's training export over the last finished weeks and
the reference tables the backend scores with, retrains, and leaves
``<out>/<date>/`` holding the reference, the export, the model, its manifest
entry and the report. The demand forecast is retrained beside it from the order
export, in ``<out>/<date>/demand/``. It never changes ``manifest.json`` or
``models/``. Exit 0 means at least one candidate worth handing over was built,
2 means none was, anything else is a failure.

The reference comes from the backend (R-ML-38), never from ``data/General
Data``: that calendar ends on 2026-06-28 and the backend generates the days
after it, so training on the CSVs would read different days from serving.
``--reference DIR`` keeps the CSVs for a dry run on the Datathon data.

With ``--store DIR`` a candidate worth handing over is copied into the model
store the service reads, and with ``--register`` also registered with the
backend: the late-risk one as the shadow of its kind, which scores every
published plan beside the active model; the demand one as registered. Neither
answers until an administrator activates it (R-ML-41).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path

from training import backend, fetch, retrain, retrain_demand
from training.planning import load_reference, reference_from_json

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REFERENCE = ROOT.parent / 'data' / 'General Data'
# The calendar runs past the last training day as a scoring request's does: a festival ramp reads forward.
CALENDAR_AHEAD_DAYS = 370
# A week is read from the calendar before the window opens, for paydays and ramps at its start.
CALENDAR_BEFORE_DAYS = 60


def window(today: dt.date, weeks: int) -> tuple[dt.date, dt.date]:
    """The last ``weeks`` finished weeks, Monday to Sunday, before ``today``'s week."""
    monday = today - dt.timedelta(days=today.weekday())
    return monday - dt.timedelta(weeks=weeks), monday - dt.timedelta(days=1)


def fetch_reference(url: str, depots: list[str], weeks: int, out: Path, cookie: str, today: dt.date,
                    opener=None) -> Path:
    """The backend's reference for these depots, from before the longest window to a year past today."""
    start, _ = window(today, weeks)
    first = start - dt.timedelta(days=CALENDAR_BEFORE_DAYS)
    last = today + dt.timedelta(days=CALENDAR_AHEAD_DAYS)
    kwargs = {} if opener is None else {'opener': opener}
    tables = fetch.reference(fetch.check_url(url), depots, first.isoformat(), last.isoformat(), cookie, **kwargs)
    build = out / today.isoformat()
    build.mkdir(parents=True, exist_ok=True)
    path = build / 'reference.json'
    path.write_text(json.dumps(tables), encoding='utf-8')
    return path


def _tables(reference) -> dict:
    """A reference given as fetched tables, a ``reference.json`` or a directory of the supplied CSVs."""
    if isinstance(reference, dict):
        return reference_from_json(reference)
    return load_reference(reference)


def run(url: str, depots: list[str], weeks: int, out: Path, cookie: str, today: dt.date,
        reference=None, opener=None) -> tuple[int, Path]:
    """The late-risk candidate. ``reference`` None fetches it from the backend."""
    start, end = window(today, weeks)
    build = out / today.isoformat()
    build.mkdir(parents=True, exist_ok=True)
    if reference is None:
        reference = fetch_reference(url, depots, weeks, out, cookie, today, opener)
    export = build / 'export.jsonl'
    base = fetch.check_url(url)
    kwargs = {} if opener is None else {'opener': opener}
    with open(export, 'w', encoding='utf-8') as f:
        for depot in depots:
            for item in fetch.pages(base, depot, start.isoformat(), end.isoformat(), cookie, **kwargs):
                f.write(json.dumps(item) + '\n')
    version = f'{today:%Y.%m.%d}'
    try:
        result, artifact = retrain.train(retrain.load_export(export, _tables(reference)))
    except ValueError as e:
        (build / 'report.json').write_text(json.dumps({'notTrained': str(e)}, indent=2) + '\n', encoding='utf-8')
        return 2, build
    retrain.write(result, artifact, 'waypoint-planning', version, build)
    return (0 if result.hand_over else 2), build


def run_demand(url: str, depots: list[str], weeks: int, out: Path, cookie: str, today: dt.date,
               reference=None, opener=None, manifest=ROOT / 'manifest.json',
               models=ROOT / 'models') -> tuple[int, Path]:
    """The demand forecast over every named depot's orders: one model for all of them, as it was trained."""
    start, end = window(today, weeks)
    build = out / today.isoformat() / 'demand'
    build.mkdir(parents=True, exist_ok=True)
    if reference is None:
        reference = fetch_reference(url, depots, weeks, out, cookie, today, opener)
    export = build / 'orders.jsonl'
    base = fetch.check_url(url)
    kwargs = {} if opener is None else {'opener': opener}
    with open(export, 'w', encoding='utf-8') as f:
        for depot in depots:
            for item in fetch.pages(base, depot, start.isoformat(), end.isoformat(), cookie, kind='orders', **kwargs):
                f.write(json.dumps(item) + '\n')
    tables = _tables(reference)
    cal, outlets = tables['calendar'], tables['outlets']
    try:
        history, counts = retrain_demand.load_export(export)
        result, model = retrain_demand.train(history, counts, cal, outlets,
                                             retrain_demand.current_model(manifest, models))
    except retrain_demand.NotTrained as e:
        (build / 'report.json').write_text(json.dumps({'notTrained': str(e)}, indent=2) + '\n', encoding='utf-8')
        return 2, build
    retrain_demand.write(result, model, f'{today:%Y.%m.%d}', build)
    return (0 if result.hand_over else 2), build


def handover(build: Path, a, cookie: str, shadow: bool, opener=None) -> dict:
    """A passing candidate into the store, and into the registry with ``--register`` (issue #12)."""
    if not a.store:
        return {}
    kwargs = {} if opener is None else {'opener': opener}
    return backend.hand_to_pipeline(build, a.store, fetch.check_url(a.url) if a.register else None, cookie,
                                    shadow, **kwargs)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Scheduled retraining (MF-05, #12).')
    ap.add_argument('--url', required=True)
    ap.add_argument('--depot', action='append', required=True, help='repeat for each depot')
    ap.add_argument('--weeks', type=int, default=26, help='finished weeks of deliveries to train on')
    ap.add_argument('--out', required=True)
    ap.add_argument('--reference', default=None,
                    help='a directory of the supplied CSVs, for a dry run; by default the backend\'s reference')
    ap.add_argument('--demand-weeks', type=int, default=104,
                    help='finished weeks of orders for the demand forecast (it needs 52 or more)')
    ap.add_argument('--store', default=os.environ.get('ML_STORE_PATH'),
                    help='the model store the service reads (ML_STORE_PATH); a passing candidate is copied in')
    ap.add_argument('--register', action='store_true',
                    help='also register a stored candidate, the late-risk one as the shadow of its kind')
    a = ap.parse_args(argv)
    if a.register and not a.store:
        raise SystemExit('--register needs --store: the service could not load a model the registry names')
    cookie = os.environ.get('WAYPOINT_SESSION')
    if not cookie:
        raise SystemExit('set WAYPOINT_SESSION to an administrator session cookie')
    today = dt.date.today()
    out = Path(a.out)
    reference = a.reference or fetch_reference(a.url, a.depot, max(a.weeks, a.demand_weeks), out, cookie, today)
    code, build = run(a.url, a.depot, a.weeks, out, cookie, today, reference)
    report = json.loads((build / 'report.json').read_text(encoding='utf-8'))
    handed = handover(build, a, cookie, shadow=True) if code == 0 else {}
    print(json.dumps({'build': str(build), 'handOver': code == 0, **handed,
                      **({k: report[k] for k in ('verdict', 'replay', 'holdout_labels') if k in report}
                         or report)}, indent=2, default=str))
    demand_code, demand_build = run_demand(a.url, a.depot, a.demand_weeks, out, cookie, today, reference)
    demand = json.loads((demand_build / 'report.json').read_text(encoding='utf-8'))
    handed = handover(demand_build, a, cookie, shadow=False) if demand_code == 0 else {}
    print(json.dumps({'build': str(demand_build), 'handOver': demand_code == 0, **handed,
                      **({k: demand[k] for k in ('verdict', 'candidate', 'naive', 'current') if k in demand}
                         or demand)}, indent=2, default=str))
    return 0 if 0 in (code, demand_code) else 2


if __name__ == '__main__':
    raise SystemExit(main())
