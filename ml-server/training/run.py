"""The scheduled retraining run (roadmap MF-05, issue #7), for cron on our own server.

Fetches every named depot's training export over the last finished weeks,
retrains with the reference tables, and leaves ``<out>/<date>/`` holding the
export, the model, its manifest entry and the report. The demand forecast is
retrained beside it from the order export, in ``<out>/<date>/demand/``. It never changes
``manifest.json`` or ``models/``: handing a model over stays a reviewed change
(README, "Retraining on our own deliveries"). Exit 0 means at least one
candidate worth handing over was built, 2 means none was, anything else is a
failure.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from dataclasses import asdict
from pathlib import Path

from training import fetch, retrain, retrain_demand
from training.planning import reference_tables

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REFERENCE = ROOT.parent / 'data' / 'General Data'


def window(today: dt.date, weeks: int) -> tuple[dt.date, dt.date]:
    """The last ``weeks`` finished weeks, Monday to Sunday, before ``today``'s week."""
    monday = today - dt.timedelta(days=today.weekday())
    return monday - dt.timedelta(weeks=weeks), monday - dt.timedelta(days=1)


def run(url: str, depots: list[str], weeks: int, out: Path, cookie: str, today: dt.date,
        reference=DEFAULT_REFERENCE, opener=None) -> tuple[int, Path]:
    start, end = window(today, weeks)
    build = out / today.isoformat()
    build.mkdir(parents=True, exist_ok=True)
    export = build / 'export.jsonl'
    base = fetch.check_url(url)
    kwargs = {} if opener is None else {'opener': opener}
    with open(export, 'w', encoding='utf-8') as f:
        for depot in depots:
            for item in fetch.pages(base, depot, start.isoformat(), end.isoformat(), cookie, **kwargs):
                f.write(json.dumps(item) + '\n')
    version = f'{today:%Y.%m.%d}'
    try:
        result, artifact = retrain.train(retrain.load_export(export, reference_tables(reference)))
    except ValueError as e:
        (build / 'report.json').write_text(json.dumps({'notTrained': str(e)}, indent=2) + '\n', encoding='utf-8')
        return 2, build
    retrain.write(result, artifact, 'waypoint-planning', version, build)
    return (0 if result.hand_over else 2), build


def run_demand(url: str, depots: list[str], weeks: int, out: Path, cookie: str, today: dt.date,
               reference=DEFAULT_REFERENCE, opener=None, manifest=ROOT / 'manifest.json',
               models=ROOT / 'models') -> tuple[int, Path]:
    """The demand forecast over every named depot's orders: one model for all of them, as it was trained."""
    start, end = window(today, weeks)
    build = out / today.isoformat() / 'demand'
    build.mkdir(parents=True, exist_ok=True)
    export = build / 'orders.jsonl'
    base = fetch.check_url(url)
    kwargs = {} if opener is None else {'opener': opener}
    with open(export, 'w', encoding='utf-8') as f:
        for depot in depots:
            for item in fetch.pages(base, depot, start.isoformat(), end.isoformat(), cookie, kind='orders', **kwargs):
                f.write(json.dumps(item) + '\n')
    cal, outlets = retrain_demand.load_reference(reference)
    try:
        history, counts = retrain_demand.load_export(export)
        result, model = retrain_demand.train(history, counts, cal, outlets,
                                             retrain_demand.current_model(manifest, models))
    except retrain_demand.NotTrained as e:
        (build / 'report.json').write_text(json.dumps({'notTrained': str(e)}, indent=2) + '\n', encoding='utf-8')
        return 2, build
    retrain_demand.write(result, model, f'{today:%Y.%m.%d}', build)
    return (0 if result.hand_over else 2), build


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Scheduled retraining (MF-05).')
    ap.add_argument('--url', required=True)
    ap.add_argument('--depot', action='append', required=True, help='repeat for each depot')
    ap.add_argument('--weeks', type=int, default=26, help='finished weeks of deliveries to train on')
    ap.add_argument('--out', required=True)
    ap.add_argument('--reference', default=str(DEFAULT_REFERENCE))
    ap.add_argument('--demand-weeks', type=int, default=104,
                    help='finished weeks of orders for the demand forecast (it needs 52 or more)')
    a = ap.parse_args(argv)
    cookie = os.environ.get('WAYPOINT_SESSION')
    if not cookie:
        raise SystemExit('set WAYPOINT_SESSION to an administrator session cookie')
    today = dt.date.today()
    code, build = run(a.url, a.depot, a.weeks, Path(a.out), cookie, today, a.reference)
    report = json.loads((build / 'report.json').read_text(encoding='utf-8'))
    print(json.dumps({'build': str(build), 'handOver': code == 0,
                      **({k: report[k] for k in ('verdict', 'replay', 'holdout_labels') if k in report}
                         or report)}, indent=2, default=str))
    demand_code, demand_build = run_demand(a.url, a.depot, a.demand_weeks, Path(a.out), cookie, today, a.reference)
    demand = json.loads((demand_build / 'report.json').read_text(encoding='utf-8'))
    print(json.dumps({'build': str(demand_build), 'handOver': demand_code == 0,
                      **({k: demand[k] for k in ('verdict', 'candidate', 'naive', 'current') if k in demand}
                         or demand)}, indent=2, default=str))
    return 0 if 0 in (code, demand_code) else 2


if __name__ == '__main__':
    raise SystemExit(main())
