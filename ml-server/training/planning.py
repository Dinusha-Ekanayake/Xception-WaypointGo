"""Planning-time features of a scored route (roadmap MF-05, R-ML-18).

Built from the route exactly as the backend sends it to be scored, so training
and serving share one definition and a model can never learn from a fact the
plan did not have when it was published. Ten numbers per stop; no road
conditions, no actual times, no outlet or vehicle identity.
"""
from __future__ import annotations

import pandas as pd

FEATURES = ['slack_close', 'slack_open', 'planned_arrival_m', 'seq', 'n_stops', 'cum_travel_min',
            'route_start_m', 'distance_km', 'volume_m3', 'is_truck']


def minutes(hhmm: str) -> float:
    h, m = hhmm.split(':')
    return int(h) * 60 + int(m)


def features(routes: list[dict]) -> pd.DataFrame:
    """One row per stop: ``deliveryId``, ``date`` and :data:`FEATURES`, in route and stop order."""
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
                is_truck=1 if r['vehicleType'] == 'truck' else 0))
    return pd.DataFrame(rows, columns=['deliveryId', 'date'] + FEATURES)
