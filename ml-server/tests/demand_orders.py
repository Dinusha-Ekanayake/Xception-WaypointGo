"""A synthetic order history in the shape of /api/ml/training/orders, for the demand retrain's tests.

The supplied outlets order as the demand model expects: every Fresh outlet daily
(ambient) and on two fixed weekdays (chilled), every Style outlet on one
weekday, Tech outlets now and then. Sizes carry a season, a weekday pattern and
a payday lift, so a model that reads the calendar beats a recent average.
"""
import datetime as dt
import json

import numpy as np
import pandas as pd

from training.retrain_demand import DEFAULT_REFERENCE


def orders(start='2024-01-01', end='2025-06-29', seed=0, brands=('Fresh', 'Style', 'Tech')):
    rng = np.random.default_rng(seed)
    cal = pd.read_csv(DEFAULT_REFERENCE / 'calendar.csv')
    outlets = pd.read_csv(DEFAULT_REFERENCE / 'outlets.csv')
    days = cal[(cal.date >= start) & (cal.date <= end) & (cal.is_operating == 1)]
    base = {o: rng.uniform(0.6, 2.4) for o in outlets.outlet_id}
    rows, n = [], 0
    for day in days.itertuples(index=False):
        d = dt.date.fromisoformat(day.date)
        season = 1 + 0.35 * np.sin(2 * np.pi * d.timetuple().tm_yday / 365.25)
        lift = season * (1.25 if day.is_payday else 1.0) * (1 + 0.08 * day.dow)
        for o in outlets.itertuples(index=False):
            k = int(o.outlet_id[3:])
            if o.brand not in brands:
                continue
            temps = []
            if o.brand == 'Fresh':
                temps = ['ambient'] + (['chilled'] if day.dow in (k % 6, (k + 3) % 6) else [])
            elif o.brand == 'Style' and day.dow == k % 6:
                temps = ['ambient']
            elif o.brand == 'Tech' and rng.random() < 0.3:
                temps = ['ambient']
            for t in temps:
                n += 1
                volume = base[o.outlet_id] * lift * (0.7 if t == 'chilled' else 1.0) * rng.lognormal(0, 0.08)
                rows.append(dict(orderId=f'00000000-0000-7000-8000-{n:012d}', placedDate=day.date,
                                 outletId=o.outlet_id, brand=o.brand, depot=o.depot, temperature=t,
                                 volumeM3=round(float(volume), 4), status='delivered'))
    return rows


def write(path, rows):
    with open(path, 'w', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r) + '\n')
    return path
