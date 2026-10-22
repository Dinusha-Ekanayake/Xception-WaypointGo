"""Planning-time features of a scored route (roadmap MF-05, R-ML-18).

Built from the route exactly as the backend sends it to be scored, so training
and serving share one definition and a model can never learn from a fact the
plan did not have when it was published. Ten numbers per stop from the route;
with the reference tables (sent with every scoring request, and in
``data/General Data`` for training) eight more that are also known at
planning: the outlet's unloading allowance and the allowances before it, the
traffic speed at the planned arrival, and the calendar. No road conditions, no
actual times, no outlet or vehicle identity.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

FEATURES = ['slack_close', 'slack_open', 'planned_arrival_m', 'seq', 'n_stops', 'cum_travel_min',
            'route_start_m', 'distance_km', 'volume_m3', 'is_truck']
REFERENCE = ['allowance_min', 'allowance_before_min', 'speed_at_arrival', 'monsoon', 'dow', 'is_payday',
             'festival_ramp', 'is_holiday']


def minutes(hhmm: str) -> float:
    h, m = hhmm.split(':')
    return int(h) * 60 + int(m)


def features(routes: list[dict], g: dict | None = None) -> pd.DataFrame:
    """One row per stop: ``deliveryId``, ``date`` and :data:`FEATURES`, in route and stop order;
    with the reference tables ``g`` (as ``app.scoring.general`` gives them) also :data:`REFERENCE`."""
    rows = []
    for r in routes:
        stops = sorted(r['stops'], key=lambda s: s['seq'])
        if not stops:
            continue
        start = minutes(stops[0]['plannedDepartTime'])
        travel = 0.0
        for s in stops:
            travel += float(s['plannedTravelMin'])
            arrival = minutes(s['plannedArrivalTime'])
            rows.append(dict(
                deliveryId=s['deliveryId'], date=r['date'],
                slack_close=minutes(s['windowCloseTime']) - arrival,
                slack_open=arrival - minutes(s['windowOpenTime']),
                planned_arrival_m=arrival, seq=s['seq'], n_stops=len(stops), cum_travel_min=travel,
                route_start_m=start, distance_km=float(s['distanceKm']), volume_m3=float(s['volumeM3']),
                is_truck=1 if r['vehicleType'] == 'truck' else 0,
                _route=r['routeId'], _outlet=s['outletId'], _brand=r['brand'], _district=r['district']))
    x = pd.DataFrame(rows, columns=['deliveryId', 'date'] + FEATURES + ['_route', '_outlet', '_brand', '_district'])
    if g is not None:
        x = _reference(x, g)
        return x[['deliveryId', 'date'] + FEATURES + REFERENCE]
    return x[['deliveryId', 'date'] + FEATURES]


def _reference(x: pd.DataFrame, g: dict) -> pd.DataFrame:
    out = g['outlets'][['outlet_id', 'dock_type']].rename(columns={'outlet_id': '_outlet'})
    allow = g['service_allowance'].rename(columns={'brand': '_brand', 'service_allowance_min': 'allowance_min'})
    cal = g['calendar'][['date', 'monsoon', 'dow', 'is_payday', 'festival_ramp', 'is_holiday']].copy()
    cal['date'] = cal.date.astype(str)
    x = x.merge(out, on='_outlet', how='left').merge(allow, on=['_brand', 'dock_type'], how='left')
    x = x.merge(cal, on='date', how='left')
    x['allowance_min'] = x.allowance_min.astype(float)
    x['allowance_before_min'] = x.groupby('_route', sort=False).allowance_min.cumsum() - x.allowance_min
    speed = g['traffic_speed'].rename(columns={'district': '_district', 'speed_index': 'speed_at_arrival'})
    x['hour'] = (x.planned_arrival_m // 60).astype(int) % 24
    x['monsoon'] = x.monsoon.fillna(0).astype(int)
    x = x.merge(speed, on=['_district', 'hour', 'monsoon'], how='left')
    for c in REFERENCE:
        x[c] = x[c].astype(float)
    return x


TABLES = ['outlets', 'service_allowance', 'traffic_speed', 'calendar']


def reference_tables(directory) -> dict:
    """The reference tables from the CSVs the backend imports (``data/General Data``), for dry runs on the
    Datathon data. A run on our own deliveries reads :func:`reference_from_json` instead: the supplied calendar
    ends on 2026-06-28 and the backend generates the days after it."""
    from pathlib import Path
    return {n: pd.read_csv(Path(directory) / f'{n}.csv') for n in TABLES}


def load_reference(path) -> dict:
    """A ``reference.json`` the backend exported, or a directory of the supplied CSVs."""
    import json
    from pathlib import Path
    p = Path(path)
    if p.suffix == '.json':
        return reference_from_json(json.loads(p.read_text(encoding='utf-8')))
    return reference_tables(p)


def reference_from_json(tables: dict) -> dict:
    """The same tables from the backend's ``/api/ml/training/reference`` (R-ML-36), as the CSV reader gives them."""
    missing = [n for n in TABLES if n not in tables]
    if missing:
        raise ValueError(f'the reference lacks {missing}')
    out = {n: pd.DataFrame(tables[n]) for n in TABLES}
    cal = out['calendar']
    if len(cal):
        cal['festival'] = cal['festival'].where(cal['festival'].notna(), np.nan)
        cal['festival_ramp'] = cal['festival_ramp'].astype(float)
    return out


def columns(frame: pd.DataFrame) -> list[str]:
    """The features a frame holds: the route's own, and the reference ones when they were built."""
    return FEATURES + (REFERENCE if all(c in frame for c in REFERENCE) else [])
