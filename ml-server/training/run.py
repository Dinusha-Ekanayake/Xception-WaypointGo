"""The scheduled retraining run (roadmap MF-05), for cron on our own server.

Fetches every named depot's training export over the last finished weeks,
retrains with the reference tables, and leaves ``<out>/<date>/`` holding the
export, the model, its manifest entry and the report. It never changes
``manifest.json`` or ``models/``: handing a model over stays a reviewed change
(README, "Retraining on our own deliveries"). Exit 0 means a candidate worth
handing over was built, 2 means none was, anything else is a failure.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from dataclasses import asdict
from pathlib import Path

from training import fetch, retrain
from training.planning import reference_tables

DEFAULT_REFERENCE = Path(__file__).resolve().parents[2] / 'data' / 'General Data'


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


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Scheduled retraining (MF-05).')
    ap.add_argument('--url', required=True)
    ap.add_argument('--depot', action='append', required=True, help='repeat for each depot')
    ap.add_argument('--weeks', type=int, default=26, help='finished weeks of deliveries to train on')
    ap.add_argument('--out', required=True)
    ap.add_argument('--reference', default=str(DEFAULT_REFERENCE))
    a = ap.parse_args(argv)
    cookie = os.environ.get('WAYPOINT_SESSION')
    if not cookie:
        raise SystemExit('set WAYPOINT_SESSION to an administrator session cookie')
    code, build = run(a.url, a.depot, a.weeks, Path(a.out), cookie, dt.date.today(), a.reference)
    report = json.loads((build / 'report.json').read_text(encoding='utf-8'))
    print(json.dumps({'build': str(build), 'handOver': code == 0,
                      **({k: report[k] for k in ('verdict', 'replay', 'holdout_labels') if k in report}
                         or report)}, indent=2, default=str))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
