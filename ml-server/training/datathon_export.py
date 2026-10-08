"""The Datathon training data in the export's shape (roadmap MF-05), for a dry run.

Each Datathon route becomes one line as ``/api/ml/training/routes`` returns it:
the route as the backend would send it to be scored, and each stop's label
(late when it arrived after the window closed; service from the later of
arrival and window open). Only planned fields go into the route. The data is
licensed for the competition, so the output stays on this machine.
"""
from __future__ import annotations

import argparse
import json
import os
import sys


def routes(data_dir: str):
    os.environ['DATA_DIR'] = data_dir
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'datathon'))
    from src import data
    t = data.load_task1()
    d = t['dtr'][t['dtr'].route_id.notna()].copy()
    d['seq_in_route'] = d.seq_in_route.astype(int)
    m = t['ltr'].merge(d, left_on=['route_id', 'seq'], right_on=['route_id', 'seq_in_route'], suffixes=('', '_o'))
    m = data.construct_labels(m)
    for route_id, g in m.sort_values(['route_id', 'seq']).groupby('route_id', sort=False):
        first = g.iloc[0]
        stops, labels = [], []
        for s in g.itertuples(index=False):
            stops.append(dict(seq=int(s.seq), deliveryId=s.delivery_id, outletId=s.to_outlet, orderDate=s.order_date,
                              deferred=s.dispatch_status == 'deferred', tempRequirement=s.temp_requirement,
                              units=int(s.order_units), weightKg=float(s.order_weight_kg),
                              volumeM3=float(s.order_volume_m3), fromPoint=s.from_point,
                              distanceKm=float(s.distance_km), plannedDepartTime=s.planned_depart_time,
                              plannedTravelMin=float(s.planned_travel_duration_min),
                              plannedArrivalTime=s.planned_arrival_time, windowOpenTime=s.window_open_time,
                              windowCloseTime=s.window_close_time))
            has = s.late == s.late  # NaN when the stop has no actual times
            labels.append(dict(deliveryId=s.delivery_id, late=bool(s.late) if has else None,
                               labelSource='device' if has else 'none',
                               serviceMinutes=float(s.service_min) if has else None))
        yield dict(routeId=route_id, serviceDate=first.date, route=dict(
            routeId=route_id, date=first.date, depot=first.depot, vehicleId=first.vehicle_id,
            vehicleType=first.vehicle_type, vehicleTemp=first.vehicle_temp, brand=first.brand,
            district=first.district, stops=stops), labels=labels)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Write the Datathon training data as a training export (MF-05).')
    ap.add_argument('--datathon', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    n = 0
    with open(a.out, 'w', encoding='utf-8') as f:
        for item in routes(a.datathon):
            f.write(json.dumps(item) + '\n')
            n += 1
    print(f'{n} routes written to {a.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
