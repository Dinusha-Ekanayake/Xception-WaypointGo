"""Agent-A (accuracy-first) Task 1 feature frame: planned-time information only (ported from work/agent_a/t1_features.py)."""
import numpy as np
import pandas as pd
from .data import hhmm_to_min

CAT = ['brand', 'dock_type', 'parking_constraint', 'district', 'depot', 'vehicle_type', 'vehicle_temp',
       'temp_requirement', 'outlet_id', 'vehicle_id', 'road_class']
NUM = ['order_units', 'order_weight_kg', 'order_volume_m3', 'vol_per_unit', 'seq', 'n_stops', 'seq_frac', 'is_last',
       'distance_km', 'planned_travel_duration_min', 'planned_depart_time_m', 'planned_arrival_time_m',
       'window_open_time_m', 'window_close_time_m', 'win_len', 'slack_close', 'slack_open', 'route_start_m',
       'cum_plan_travel', 'cum_allow_before', 'cum_dist', 'route_vol', 'route_wt', 'route_units', 'load_frac_vol',
       'load_frac_wt', 'elapsed_plan', 'service_allowance_min', 'depot_to_district_freeflow_min',
       'inter_stop_freeflow_min', 'weight_cap_kg', 'volume_cap_m3', 'disruption_index', 'speed_arr', 'speed_dep',
       'tmult', 'exp_extra_travel', 'cum_exp_extra_travel', 'monsoon', 'dow', 'is_payday', 'festival_ramp',
       'is_holiday', 'month', 'deferred']


def build(legs_parts, order_parts, g, no_road_conditions=False):
    """legs_parts: list of (split, legs_df); order_parts: list of deliveries dfs (not_run rows are dropped)."""
    ts, rc, sa, dt, veh, out, cal = (g['traffic_speed'], g['road_conditions'], g['service_allowance'], g['district_travel'],
                                     g['vehicles'], g['outlets'], g['calendar'])
    legs = pd.concat([l.assign(split=s) for s, l in legs_parts], ignore_index=True)
    ords = pd.concat([o[o.route_id.notna()] for o in order_parts], ignore_index=True)
    ords['seq_in_route'] = ords.seq_in_route.astype(int)
    ocols = ['delivery_id', 'order_date', 'dispatch_status', 'route_id', 'seq_in_route', 'temp_requirement',
             'order_units', 'order_weight_kg', 'order_volume_m3', 'window_open_time', 'window_close_time']
    df = legs.merge(ords[ocols], left_on=['route_id', 'seq'], right_on=['route_id', 'seq_in_route'], how='inner')
    assert len(df) == len(legs)
    df = df.merge(out[['outlet_id', 'dock_type', 'parking_constraint']], left_on='to_outlet', right_on='outlet_id')
    df = df.merge(sa, on=['brand', 'dock_type'])
    df = df.merge(dt[['district', 'road_class', 'depot_to_district_freeflow_min', 'inter_stop_freeflow_min']], on='district')
    df = df.merge(veh[['vehicle_id', 'weight_cap_kg', 'volume_cap_m3']], on='vehicle_id')
    df = df.merge(rc, on=['district', 'date'], how='left')
    if no_road_conditions:
        df['disruption_index'] = 100.0
    df = df.merge(cal[['date', 'is_payday', 'festival_ramp', 'is_holiday', 'is_weekend', 'iso_week']], on='date', how='left')
    for col in ['planned_depart_time', 'planned_arrival_time', 'window_open_time', 'window_close_time',
                'actual_depart_time', 'arrival_time', 'leave_outlet_time']:
        df[col + '_m'] = hhmm_to_min(df[col]) if col in df else np.nan
    df['arr_hr'] = (df.planned_arrival_time_m // 60).astype(int)
    df['dep_hr'] = (df.planned_depart_time_m // 60).astype(int)
    df = df.merge(ts.rename(columns={'hour': 'arr_hr', 'speed_index': 'speed_arr'}), on=['district', 'arr_hr', 'monsoon'], how='left')
    df = df.merge(ts.rename(columns={'hour': 'dep_hr', 'speed_index': 'speed_dep'}), on=['district', 'dep_hr', 'monsoon'], how='left')
    df = df.sort_values(['route_id', 'seq']).reset_index(drop=True)
    g_ = df.groupby('route_id')
    df['n_stops'] = g_.seq.transform('size')
    df['seq_frac'] = df.seq / df.n_stops.clip(lower=1)
    df['is_last'] = (df.seq == df.n_stops - 1).astype(int)
    df['route_start_m'] = g_.planned_depart_time_m.transform('first')
    df['cum_plan_travel'] = g_.planned_travel_duration_min.cumsum()
    df['cum_allow_before'] = g_.service_allowance_min.cumsum() - df.service_allowance_min
    df['cum_dist'] = g_.distance_km.cumsum()
    df['route_vol'] = g_.order_volume_m3.transform('sum')
    df['route_wt'] = g_.order_weight_kg.transform('sum')
    df['route_units'] = g_.order_units.transform('sum')
    df['load_frac_vol'] = df.route_vol / df.volume_cap_m3
    df['load_frac_wt'] = df.route_wt / df.weight_cap_kg
    df['elapsed_plan'] = df.planned_arrival_time_m - df.route_start_m
    df['slack_close'] = df.window_close_time_m - df.planned_arrival_time_m
    df['slack_open'] = df.planned_arrival_time_m - df.window_open_time_m
    df['win_len'] = df.window_close_time_m - df.window_open_time_m
    df['tmult'] = 100.0 / df.speed_dep * 100.0 / df.disruption_index
    df['exp_extra_travel'] = df.planned_travel_duration_min * (df.tmult - 1)
    df['cum_exp_extra_travel'] = df.groupby('route_id').exp_extra_travel.cumsum()
    df['min_disr_route'] = df.disruption_index
    df['vol_per_unit'] = df.order_volume_m3 / df.order_units.clip(lower=1)
    df['month'] = pd.to_datetime(df.date).dt.month
    df['deferred'] = (df.dispatch_status == 'deferred').astype(int)
    has = df.leave_outlet_time_m.notna()
    df['service_min'] = np.where(has, df.leave_outlet_time_m - np.maximum(df.arrival_time_m, df.window_open_time_m), np.nan)
    df['late'] = np.where(has, (df.arrival_time_m > df.window_close_time_m).astype(float), np.nan)
    return df


def set_categories(df, cats):
    for c in CAT:
        df[c] = pd.Categorical(df[c].astype(object), categories=cats[c])
    return df


def categories(df):
    return {c: sorted(df[c].dropna().astype(str).unique()) for c in CAT}
