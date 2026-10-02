"""Agent-B (mechanism-first) leg-level feature frame for Task 1.

Every feature is known at planning time: planned times, order size, outlet/vehicle attributes, calendar, the published
traffic_speed table and the date-specific road_conditions table. Labels are attached for rows with actual times.
"""
import numpy as np
import pandas as pd
from .data import hhmm_to_min


def build(parts, g, no_road_conditions=False):
    """parts: list of (part_name, deliveries_df, legs_df). g: dict of general tables (data.load_general()).
    no_road_conditions=True replaces disruption_index by 100 (clear roads) -> fallback model features."""
    o, sa, cal, ts, rc, dt = g['outlets'], g['service_allowance'], g['calendar'], g['traffic_speed'], g['road_conditions'], g['district_travel']
    out = []
    for part, d, l in parts:
        d = d[d.route_id.notna()]
        x = l.merge(d[['route_id', 'seq_in_route', 'delivery_id', 'order_date', 'dispatch_status', 'temp_requirement',
                       'order_units', 'order_weight_kg', 'order_volume_m3']],
                    left_on=['route_id', 'seq'], right_on=['route_id', 'seq_in_route'], how='left')
        assert x.delivery_id.notna().all(), 'every leg must match exactly one order'
        x['part'] = part
        out.append(x)
    x = pd.concat(out, ignore_index=True)
    x = x.merge(o[['outlet_id', 'dock_type', 'parking_constraint', 'mall_window', 'window_open_time', 'window_close_time']],
                left_on='to_outlet', right_on='outlet_id', how='left')
    x = x.merge(sa, on=['brand', 'dock_type'], how='left')
    x = x.merge(cal[['date', 'is_payday', 'festival_ramp', 'is_holiday', 'is_weekend', 'iso_week']], on='date', how='left')
    x = x.merge(rc, on=['district', 'date'], how='left')
    x = x.merge(dt[['district', 'free_flow_kmh', 'road_class', 'inter_stop_freeflow_min', 'depot_to_district_freeflow_min']],
                on='district', how='left')
    if no_road_conditions:
        x['disruption_index'] = 100.0
    for c in ['planned_depart_time', 'planned_arrival_time', 'window_open_time', 'window_close_time']:
        x[c[:-5] + '_m'] = hhmm_to_min(x[c])
    for c in ['actual_depart_time', 'arrival_time', 'leave_outlet_time']:
        if c in x:
            x[c + '_m'] = hhmm_to_min(x[c])
    x = x.sort_values(['route_id', 'seq']).reset_index(drop=True)
    x['wo'] = x.window_open_m; x['wc'] = x.window_close_m; x['parr'] = x.planned_arrival_m; x['pdep'] = x.planned_depart_m
    x['pdep_hr'] = (x.pdep // 60).astype(int)
    T = ts.set_index(['district', 'hour', 'monsoon']).speed_index
    x['spd_pl'] = T.reindex(pd.MultiIndex.from_arrays([x.district, x.pdep_hr, x.monsoon])).values
    # mechanism: expected travel = planned x 100/speed x 100/disruption (log-coefficients measured -0.996 / -1.004)
    x['exp_travel'] = x.planned_travel_duration_min * 100 / x.spd_pl * 100 / x.disruption_index
    x['n_stops'] = x.groupby('route_id').seq.transform('size')
    x['slack_close'] = x.wc - x.parr
    x['slack_open'] = x.wo - x.parr
    x['cum_allow'] = x.groupby('route_id').service_allowance_min.cumsum() - x.service_allowance_min
    x['cum_travel_excess'] = x.groupby('route_id').exp_travel.cumsum() - x.groupby('route_id').planned_travel_duration_min.cumsum()
    x['pwait'] = np.maximum(0, x.wo - x.parr)                 # the planner ignores early-arrival waits
    x['cum_pwait'] = x.groupby('route_id').pwait.cumsum() - x.pwait
    x['start_pdep'] = x.groupby('route_id').pdep.transform('first')
    x['days_deferred'] = (pd.to_datetime(x.date) - pd.to_datetime(x.order_date)).dt.days
    x['lu'] = np.log(x.order_units); x['lv'] = np.log(x.order_volume_m3); x['lw'] = np.log(x.order_weight_kg)
    x['chilled'] = (x.temp_requirement == 'chilled').astype(int)
    x['van'] = (x.vehicle_type == 'van').astype(int)
    if 'leave_outlet_time_m' in x:
        x['svc'] = x.leave_outlet_time_m - np.maximum(x.arrival_time_m, x.wo)   # label (see data.py)
        x['late'] = (x.arrival_time_m > x.wc).astype(float)
        x.loc[x.leave_outlet_time_m.isna(), ['svc', 'late']] = np.nan
    x['cum_units'] = x.groupby('route_id').order_units.cumsum() - x.order_units
    x['trend'] = (pd.to_datetime(x.date) - pd.Timestamp('2024-01-01')).dt.days / 365.0   # parametric model only, never trees
    c2 = cal.copy(); c2['fest_next'] = c2.festival.bfill()
    x = x.merge(c2[['date', 'fest_next']], on='date', how='left')
    for f in festivals(cal):
        x['fr_' + f] = x.festival_ramp * (x.fest_next == f)          # festival-specific ramps
    return x


def festivals(cal):
    return sorted(cal.festival.dropna().unique())
