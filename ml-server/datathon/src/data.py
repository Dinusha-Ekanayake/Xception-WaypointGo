"""Raw-data loading, clock-time parsing and Task 1 label construction.

Label definitions (verified empirically, see docs/data_preprocessing.md):
  service_min = leave_outlet_time - max(arrival_time, window_open_time)   # early vehicles wait for the window
  late        = arrival_time > window_close_time                          # strict; service jumps +25% exactly here
Join key: deliveries.(route_id, seq_in_route) <-> route_legs.(route_id, seq) (1:1; not_run orders have no leg).
"""
import numpy as np
import pandas as pd
from . import config as C


def hhmm_to_min(s: pd.Series) -> pd.Series:
    """'HH:MM' -> minutes after midnight (float, NaN for blanks). No midnight wrap exists in the data (02:00-23:33)."""
    s = s.astype('string')
    h = pd.to_numeric(s.str.slice(0, 2), errors='coerce')
    m = pd.to_numeric(s.str.slice(3, 5), errors='coerce')
    return (h * 60 + m).astype(float)


def load_general():
    g = {}
    for n in ['calendar', 'district_travel', 'outlets', 'road_conditions', 'service_allowance', 'traffic_speed', 'vehicles']:
        g[n] = pd.read_csv(C.GENERAL / f'{n}.csv')
    return g


def load_task1():
    """Training and test order/leg tables."""
    return dict(dtr=pd.read_csv(C.TRAIN / 'deliveries_train.csv'), ltr=pd.read_csv(C.TRAIN / 'route_legs_train.csv'),
                dte=pd.read_csv(C.TEST / 'task1_test_inputs.csv'), lte=pd.read_csv(C.TEST / 'route_legs_test.csv'))


def construct_labels(legs: pd.DataFrame) -> pd.DataFrame:
    """Adds service_min and late to a leg-level frame that has actual times (training legs)."""
    arr = hhmm_to_min(legs.arrival_time)
    leave = hhmm_to_min(legs.leave_outlet_time)
    wo = hhmm_to_min(legs.window_open_time)
    wc = hhmm_to_min(legs.window_close_time)
    out = legs.copy()
    out['service_min'] = leave - np.maximum(arr, wo)
    out['late'] = (arr > wc).astype(float)
    return out


def label_audit(d: pd.DataFrame, l: pd.DataFrame, outlets: pd.DataFrame) -> dict:
    """Integrity checks behind the label definitions (used by the notebook)."""
    m = d[d.route_id.notna()].merge(l, left_on=['route_id', 'seq_in_route'], right_on=['route_id', 'seq'], how='left',
                                    suffixes=('', '_leg'))
    res = dict(unmatched_dispatched=int(m.leg_id.isna().sum()), legs=len(l), matched_legs=int(m.leg_id.nunique()),
               outlet_match=float((m.outlet_id == m.to_outlet).mean()),
               planned_arrival_match=float((m.planned_arrival_time == m.planned_arrival_time_leg).mean()))
    mo = m.merge(outlets[['outlet_id', 'window_open_time', 'window_close_time']], on='outlet_id', suffixes=('', '_o'))
    res['window_equals_outlets_csv'] = float(((mo.window_open_time == mo.window_open_time_o) &
                                              (mo.window_close_time == mo.window_close_time_o)).mean())
    ls = l.sort_values(['route_id', 'seq'])
    nxt = ls.groupby('route_id').actual_depart_time.shift(-1)
    res['next_depart_equals_leave'] = float((nxt == ls.leave_outlet_time)[nxt.notna()].mean())
    t = pd.concat([hhmm_to_min(l[c]) for c in ['actual_depart_time', 'arrival_time', 'leave_outlet_time']])
    res['min_clock_min'], res['max_clock_min'] = float(t.min()), float(t.max())
    return res
