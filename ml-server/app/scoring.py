"""Builds the Datathon frames from a request and calls the vendored models.

The reference tables arrive with the request, shaped exactly like the supplied
CSVs (same table and column names), so the vendored feature code runs
unchanged. A route whose date has no road-condition row is scored by the
fallback model, which was trained without them, and is labelled so.
"""
import numpy as np
import pandas as pd

from src import features_a, features_b, task1_a, task1_b, task2a  # noqa: F401 (vendored)

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


def delivery_risk(loaded, routes, reference):
    """[(deliveryId, serviceMin, lateProb, roadConditions)] in request order."""
    g = general(reference)
    _check_known(routes, g)
    cal = g['calendar'].set_index('date')
    covered = set(zip(g['road_conditions'].district, g['road_conditions'].date))
    with_rc = [r for r in routes if (r.district, r.date) in covered]
    without_rc = [r for r in routes if (r.district, r.date) not in covered]
    out = {}
    if with_rc:
        orders, legs = frames(with_rc)
        legs['monsoon'] = legs.date.map(cal.monsoon).astype(int)
        legs['dow'] = legs.date.map(cal.dow).astype(int)
        for row in _blend(loaded, orders, legs, g).itertuples(index=False):
            out[row.delivery_id] = (float(row.pred_service_min), float(row.pred_late_prob), 'used')
    if without_rc:
        orders, legs = frames(without_rc)
        legs['monsoon'] = legs.date.map(cal.monsoon).astype(int)
        legs['dow'] = legs.date.map(cal.dow).astype(int)
        xb = features_b.build([('serve', orders, legs)], g, no_road_conditions=True)
        pb = task1_b.predict(loaded.task1_fallback, xb)
        for d, svc, late in zip(xb.delivery_id, pb.svc, pb.late):
            out[d] = (round(float(svc), 2), round(float(np.clip(late, 5e-4, 1 - 5e-4)), 4), 'fallback')
    result = []
    for r in routes:
        for s in sorted(r.stops, key=lambda s: s.seq):
            svc, late, rc = out[s.deliveryId]
            result.append((s.deliveryId, svc, late, rc))
    return result


def _blend(loaded, orders, legs, g):
    """inference.predict_task1 with the models already in memory."""
    xb = features_b.build([('serve', orders, legs)], g)
    pb = xb[['delivery_id']].join(task1_b.predict(loaded.task1_b, xb))
    xa = features_a.build([('serve', legs)], [orders], g)
    xa = features_a.set_categories(xa, loaded.task1_a['cats'])
    pa = task1_a.predict(loaded.task1_a, xa, g['traffic_speed'])
    m = orders[['delivery_id']].merge(pa[['delivery_id', 'svc', 'late']], on='delivery_id').merge(
        pb[['delivery_id', 'svc', 'late']], on='delivery_id', suffixes=('_A', '_B'))
    wl, ws = loaded.blend['w_A_late'], loaded.blend['w_A_svc']
    a_s, a_l, b_s, b_l = m.svc_A.round(2), m.late_A.round(4), m.svc_B.round(2), m.late_B.round(4)
    m['pred_service_min'] = (ws * a_s + (1 - ws) * b_s).round(2)
    m['pred_late_prob'] = np.clip(wl * a_l + (1 - wl) * b_l, 5e-4, 1 - 5e-4).round(4)
    return m[['delivery_id', 'pred_service_min', 'pred_late_prob']]


def demand_forecast(loaded, calendar_rows, weeks):
    """inference.predict_task2a for any calendar, past the supplied one too.

    A day generated past the supplied calendar has no festival, so a festival
    the model knows may have no column; it is added as 0 rather than failing.
    """
    cal = general({**{k: [] for k in TABLES}, 'calendar': calendar_rows})['calendar']
    c, wk, _ = task2a.calendar_features(cal)
    for f in loaded.task2a['afest']['fests']:
        if 'fr_' + f not in c:
            c['fr_' + f] = 0.0
    rows = pd.DataFrame([w.model_dump() for w in weeks]).rename(
        columns={'isoYear': 'iso_year', 'isoWeek': 'iso_week'})
    r = rows.merge(wk[['iso_year', 'iso_week', 'widx']], on=['iso_year', 'iso_week'], how='left')
    if r.widx.isna().any():
        bad = r[r.widx.isna()].iloc[0]
        raise BadRequest(f'week {int(bad.iso_year)}-W{int(bad.iso_week)} is not in the calendar sent')
    f = task2a.forecast(loaded.task2a, c, sorted(r.widx.astype(int).unique()))
    out = r.merge(f[['widx', 'depot', 'brand', 'total', 'chilled']], on=['widx', 'depot', 'brand'], how='left')
    out['total'] = out.total.fillna(0.0).round(2)
    out['chilled'] = np.where(out.brand == 'Fresh', out.chilled.fillna(0.0), 0.0).round(2)
    return [(row.depot, row.brand, int(row.iso_year), int(row.iso_week), float(row.total), float(row.chilled))
            for row in out.itertuples(index=False)]
