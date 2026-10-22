"""Builds the Datathon frames from a request and calls the vendored models.

The reference tables arrive with the request, shaped exactly like the supplied
CSVs (same table and column names), so the vendored feature code runs
unchanged. A route whose date has no road-condition row is scored by the copy
of the model trained without them, and is labelled so.
"""
import copy

import numpy as np
import pandas as pd

import mc_task1  # vendored
import task2a_demand  # vendored
from training import planning

TABLES = {
    'outlets': ['outlet_id', 'brand', 'district', 'depot', 'dock_type', 'parking_constraint', 'mall_window',
                'window_open_time', 'window_close_time'],
    'vehicles': ['vehicle_id', 'type', 'temp', 'weight_cap_kg', 'volume_cap_m3', 'depot'],
    'service_allowance': ['brand', 'dock_type', 'service_allowance_min'],
    'district_travel': ['district', 'depot', 'road_class', 'free_flow_kmh', 'depot_to_district_km',
                        'depot_to_district_freeflow_min', 'inter_stop_km', 'inter_stop_freeflow_min'],
    'traffic_speed': ['district', 'hour', 'monsoon', 'speed_index'],
    'calendar': ['date', 'dow', 'is_weekend', 'iso_year', 'iso_week', 'is_payday', 'festival', 'festival_ramp',
                 'is_holiday', 'monsoon', 'is_operating'],
    'road_conditions': ['district', 'date', 'disruption_index'],
}
INT_COLUMNS = {'hour', 'monsoon', 'dow', 'is_weekend', 'iso_year', 'iso_week', 'is_payday', 'is_holiday',
               'is_operating'}


class BadRequest(ValueError):
    """The request is well formed but cannot be scored, for example a stop at an unknown outlet."""


def general(reference: dict) -> dict:
    g = {}
    for name, columns in TABLES.items():
        rows = reference.get(name)
        if rows is None:
            raise BadRequest(f'reference.{name} is required')
        df = pd.DataFrame(rows, columns=None if rows else columns)
        missing = [c for c in columns if c not in df.columns]
        if missing:
            raise BadRequest(f'reference.{name} lacks columns {missing}')
        for c in df.columns:
            if c in INT_COLUMNS:
                df[c] = df[c].astype(int)
        if name == 'calendar':
            df['festival'] = df['festival'].replace({'': np.nan})
            df['festival_ramp'] = df['festival_ramp'].astype(float)
        g[name] = df
    return g


def frames(routes):
    """The two Datathon inputs: orders (task1_test_inputs) and route legs (route_legs_test)."""
    orders, legs = [], []
    for r in routes:
        for s in sorted(r.stops, key=lambda s: s.seq):
            orders.append(dict(
                delivery_id=s.deliveryId, order_date=s.orderDate, dispatch_date=r.date,
                dispatch_status='deferred' if s.deferred else 'attempted', outlet_id=s.outletId, brand=r.brand,
                district=r.district, depot=r.depot, temp_requirement=s.tempRequirement, order_units=s.units,
                order_weight_kg=s.weightKg, order_volume_m3=s.volumeM3, route_id=r.routeId, seq_in_route=s.seq,
                vehicle_id=r.vehicleId, vehicle_type=r.vehicleType, vehicle_temp=r.vehicleTemp,
                planned_arrival_time=s.plannedArrivalTime, window_open_time=s.windowOpenTime,
                window_close_time=s.windowCloseTime))
            legs.append(dict(
                leg_id=f'{r.routeId}-{s.seq}', date=r.date, route_id=r.routeId, depot=r.depot,
                vehicle_id=r.vehicleId, vehicle_type=r.vehicleType, vehicle_temp=r.vehicleTemp, brand=r.brand,
                district=r.district, seq=s.seq, from_point=s.fromPoint, to_outlet=s.outletId,
                distance_km=s.distanceKm, planned_depart_time=s.plannedDepartTime,
                planned_travel_duration_min=s.plannedTravelMin, planned_arrival_time=s.plannedArrivalTime))
    return pd.DataFrame(orders), pd.DataFrame(legs)


def _check_known(routes, g):
    outlets = set(g['outlets'].outlet_id)
    vehicles = set(g['vehicles'].vehicle_id)
    districts = set(g['district_travel'].district)
    dates = set(g['calendar'].date)
    for r in routes:
        if r.vehicleId not in vehicles:
            raise BadRequest(f'route {r.routeId}: vehicle {r.vehicleId} is not in reference.vehicles')
        if r.district not in districts:
            raise BadRequest(f'route {r.routeId}: district {r.district} is not in reference.district_travel')
        if r.date not in dates:
            raise BadRequest(f'route {r.routeId}: {r.date} is not in reference.calendar')
        for s in r.stops:
            if s.outletId not in outlets:
                raise BadRequest(f'route {r.routeId}: outlet {s.outletId} is not in reference.outlets')


def resolve(loaded, kind, label=None):
    """(info, model) a request is answered by: the one it names, or the image's (R-ML-37)."""
    found = loaded.resolve(kind, label)
    if found is None:
        raise BadRequest(f'model {label} is not loaded' if label else f'no {kind} model is loaded')
    return found


def delivery_risk(loaded, routes, reference, model=None):
    """[(deliveryId, serviceMin, lateProb, roadConditions)] in request order, by ``model`` or the image's."""
    info, served = resolve(loaded, 'delivery_risk', model)
    g = general(reference)
    _check_known(routes, g)
    if info.pipeline == 'planning':
        return _planning(served, routes, g)
    cal = g['calendar'].set_index('date')
    model = _with_context(served, _context(g, served.ctx))
    covered = set(zip(g['road_conditions'].district, g['road_conditions'].date))
    out = {}
    for fallback in (False, True):
        group = [r for r in routes if ((r.district, r.date) in covered) != fallback]
        if not group:
            continue
        orders, legs = frames(group)
        legs['monsoon'] = legs.date.map(cal.monsoon).astype(int)
        for row in model.predict(orders, legs, use_fallback=fallback).itertuples(index=False):
            out[row.delivery_id] = (round(float(row.pred_service_min), 2), round(float(row.pred_late_prob), 4),
                                    'fallback' if fallback else 'used')
    result = []
    for r in routes:
        for s in sorted(r.stops, key=lambda s: s.seq):
            svc, late, rc = out[s.deliveryId]
            result.append((s.deliveryId, svc, late, rc))
    return result


def _planning(model, routes, g):
    """A model retrained on our own deliveries: the shared planning-time features, no road conditions (R-ML-18).

    Its service model needs 200 labelled stops; until then each stop's planned
    allowance is not known here, so the service answer is the model's median
    of what it learned, never zero.
    """
    uses_reference = any(c in planning.REFERENCE for c in model['features'])
    x = planning.features([r.model_dump() for r in routes], g if uses_reference else None)
    late = np.clip(model['late'].predict_proba(x[model['features']])[:, 1], 5e-4, 1 - 5e-4)
    if model.get('service') is not None:
        svc = np.exp(model['service'].predict(x[model['features']]))
    else:
        svc = np.full(len(x), float(model.get('service_median', 15.0)))
    return [(d, round(float(s), 2), round(float(p), 4), 'fallback') for d, s, p in zip(x.deliveryId, svc, late)]


def _context(g, trained):
    """mc_task1.Context built from the request's tables instead of the training CSVs.

    The same steps as Context.__init__, so a day past the training data scores
    with the reference the backend sent. The vocabulary is the trained model's:
    the category codes must be the ones it learned.
    """
    ctx = object.__new__(mc_task1.Context)
    ctx.outlets, ctx.cal, ctx.rc = g['outlets'], g['calendar'], g['road_conditions']
    ctx.ts, ctx.sa, ctx.dt = g['traffic_speed'], g['service_allowance'], g['district_travel']
    cal = ctx.cal.copy()
    cal['d'] = pd.to_datetime(cal.date)
    pay = cal.loc[cal.is_payday == 1, 'd'].values
    idx = np.searchsorted(pay, cal.d.values, side='right') - 1
    lag = (cal.d.values - pay[np.clip(idx, 0, None)]).astype('timedelta64[D]').astype(int)
    cal['pay_lag'] = np.where(idx >= 0, np.minimum(lag, 10), 10)
    nxt = np.searchsorted(pay, cal.d.values, side='left')
    lead = (pay[np.clip(nxt, 0, len(pay) - 1)] - cal.d.values).astype('timedelta64[D]').astype(int)
    cal['pay_lead'] = np.where(nxt < len(pay), np.minimum(lead, 10), 10)
    ctx.calf = cal[['date', 'is_weekend', 'is_payday', 'pay_lag', 'pay_lead', 'festival_ramp', 'is_holiday']]
    ctx.speed = {(r.district, r.monsoon, r.hour): r.speed_index for r in ctx.ts.itertuples()}
    ctx.vocab = trained.vocab
    return ctx


def _with_context(model, ctx):
    """A copy of the loaded ParallelModel that reads ctx. The loaded model is never changed,
    so concurrent requests cannot see each other's tables."""
    m = copy.copy(model)
    m.ctx = ctx
    m.parts = {}
    for name, part in model.parts.items():
        sim = copy.copy(part['sim'])
        sim.ctx = ctx
        m.parts[name] = {**part, 'sim': sim}
    return m


FORECAST_OUTLETS = ['outlet_id', 'brand', 'depot']


def demand_forecast(loaded, calendar_rows, outlet_rows, weeks, model=None):
    """task2a_demand.predict_rows for any calendar, past the supplied one too, by ``model`` or the image's.

    The model schedules each outlet's expected orders, so the outlets of every
    depot forecast come with the request.
    """
    _info, served = resolve(loaded, 'demand_forecast', model)
    cal = general({**{k: [] for k in TABLES}, 'calendar': calendar_rows})['calendar']
    outlets = pd.DataFrame(outlet_rows)
    missing = [c for c in FORECAST_OUTLETS if c not in outlets.columns]
    if missing:
        raise BadRequest(f'outlets lack columns {missing}')
    rows = pd.DataFrame([w.model_dump() for w in weeks]).rename(
        columns={'isoYear': 'iso_year', 'isoWeek': 'iso_week'})
    known = set(zip(cal.iso_year, cal.iso_week))
    for row in rows.itertuples(index=False):
        if (row.iso_year, row.iso_week) not in known:
            raise BadRequest(f'week {int(row.iso_year)}-W{int(row.iso_week)} is not in the calendar sent')
    out = task2a_demand.predict_rows(served, rows, cal, outlets)
    return [(row.depot, row.brand, int(row.iso_year), int(row.iso_week), float(row.pred_total_volume_m3),
             float(row.pred_chilled_volume_m3)) for row in out.itertuples(index=False)]
