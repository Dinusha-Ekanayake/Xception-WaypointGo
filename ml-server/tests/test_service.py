"""The service against the vendored Datathon code it wraps.

The fixture is four real planned routes from one test day. Every Task 1 test
compares the API's answer with `inference` run on the same rows with the
reference tables the model was trained with, so a mistake in turning a request
into the Datathon frames shows up as a difference, not as a plausible number.
"""
import json
import shutil
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

import inference
from app import registry, scoring
from app.main import LOADED, app

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / 'fixtures'
GENERAL = ROOT.parent / 'data' / 'General Data'
TABLES = ['outlets', 'vehicles', 'service_allowance', 'district_travel', 'traffic_speed', 'calendar', 'road_conditions']

client = TestClient(app)


def general():
    return {n: pd.read_csv(GENERAL / f'{n}.csv') for n in TABLES}


def reference(g):
    return {n: json.loads(df.to_json(orient='records')) for n, df in g.items()}


def request_body(orders, legs, g):
    o = orders.set_index(['route_id', 'seq_in_route'])
    routes = []
    for route_id, route in legs.sort_values(['route_id', 'seq']).groupby('route_id'):
        first = route.iloc[0]
        stops = []
        for leg in route.itertuples(index=False):
            order = o.loc[(route_id, leg.seq)]
            stops.append(dict(
                seq=int(leg.seq), deliveryId=order.delivery_id, outletId=leg.to_outlet, orderDate=order.order_date,
                deferred=order.dispatch_status == 'deferred', tempRequirement=order.temp_requirement,
                units=int(order.order_units), weightKg=float(order.order_weight_kg),
                volumeM3=float(order.order_volume_m3), fromPoint=leg.from_point, distanceKm=float(leg.distance_km),
                plannedDepartTime=leg.planned_depart_time, plannedTravelMin=float(leg.planned_travel_duration_min),
                plannedArrivalTime=leg.planned_arrival_time, windowOpenTime=order.window_open_time,
                windowCloseTime=order.window_close_time))
        routes.append(dict(routeId=route_id, date=first.date, depot=first.depot, vehicleId=first.vehicle_id,
                           vehicleType=first.vehicle_type, vehicleTemp=first.vehicle_temp, brand=first.brand,
                           district=first.district, stops=stops))
    return dict(routes=routes, reference=reference(g))


@pytest.fixture(scope='module')
def rows():
    return pd.read_csv(FIXTURES / 'orders.csv'), pd.read_csv(FIXTURES / 'route_legs.csv')


def vendored_models():
    return dict(task1=LOADED.task1, task2a=LOADED.task2a)


def vendored_task1(orders, legs, use_fallback=False):
    """inference.predict_task1, rounded as the API answers."""
    p = inference.predict_task1(orders, legs, vendored_models(), use_fallback=use_fallback).set_index('delivery_id')
    return p.pred_service_min.round(2), p.pred_late_prob.round(4)


def outlets():
    return reference({'outlets': general()['outlets']})['outlets']


def test_health_names_both_models():
    body = client.get('/health').json()
    assert body['status'] == 'ok'
    assert {m['kind'] for m in body['models']} == {'delivery_risk', 'demand_forecast'}
    assert all(m['version'] for m in body['models'])


def test_delivery_risk_is_exactly_the_vendored_blend(rows):
    orders, legs = rows
    g = general()
    res = client.post('/v1/delivery-risk', json=request_body(orders, legs, g))
    assert res.status_code == 200, res.text
    body = res.json()
    assert body['roadConditions'] == 'used'
    assert body['modelName'] == 'datathon-task1-parallel'
    svc, late = vendored_task1(orders, legs)
    got = pd.DataFrame(body['predictions']).set_index('deliveryId')
    assert len(got) == len(orders)
    assert np.allclose(got.loc[svc.index, 'serviceMin'], svc, atol=0, rtol=0)
    assert np.allclose(got.loc[late.index, 'lateProb'], late, atol=0, rtol=0)
    assert ((got.lateProb > 0) & (got.lateProb < 1)).all()


def test_a_date_without_road_conditions_uses_the_fallback_model(rows):
    orders, legs = rows
    g = general()
    g['road_conditions'] = g['road_conditions'][~g['road_conditions'].date.isin(legs.date.unique())]
    res = client.post('/v1/delivery-risk', json=request_body(orders, legs, g))
    assert res.status_code == 200, res.text
    body = res.json()
    assert body['roadConditions'] == 'fallback'
    assert {p['roadConditions'] for p in body['predictions']} == {'fallback'}
    svc, late = vendored_task1(orders, legs, use_fallback=True)
    got = pd.DataFrame(body['predictions']).set_index('deliveryId')
    assert np.allclose(got.loc[svc.index, 'serviceMin'], svc, atol=0, rtol=0)
    assert np.allclose(got.loc[late.index, 'lateProb'], late, atol=0, rtol=0)


def test_the_context_built_from_a_request_equals_the_trained_one():
    """The service scores with the tables the backend sends; on the supplied CSVs they are what the model was trained with."""
    trained = LOADED.task1.ctx
    ctx = scoring._context(scoring.general(reference(general())), trained)
    for name in ['outlets', 'cal', 'rc', 'ts', 'sa', 'dt', 'calf']:
        pd.testing.assert_frame_equal(getattr(ctx, name).reset_index(drop=True).astype(object),
                                      getattr(trained, name).reset_index(drop=True).astype(object),
                                      check_dtype=False, check_column_type=False, obj=name)
    assert ctx.speed == trained.speed
    assert ctx.vocab is trained.vocab


def test_scoring_leaves_the_loaded_model_unchanged(rows):
    orders, legs = rows
    trained = LOADED.task1.ctx
    sims = {name: part['sim'].ctx for name, part in LOADED.task1.parts.items()}
    assert client.post('/v1/delivery-risk', json=request_body(orders, legs, general())).status_code == 200
    assert LOADED.task1.ctx is trained
    assert all(part['sim'].ctx is sims[name] for name, part in LOADED.task1.parts.items())


def test_the_same_request_gives_the_same_answer(rows):
    orders, legs = rows
    body = request_body(orders, legs, general())
    first = client.post('/v1/delivery-risk', json=body).json()['predictions']
    second = client.post('/v1/delivery-risk', json=body).json()['predictions']
    assert first == second


def test_an_outlet_the_reference_does_not_know_is_refused(rows):
    orders, legs = rows
    body = request_body(orders, legs, general())
    body['routes'][0]['stops'][0]['outletId'] = 'OUT999'
    res = client.post('/v1/delivery-risk', json=body)
    assert res.status_code == 422
    assert 'OUT999' in res.text


def test_a_malformed_time_is_refused(rows):
    orders, legs = rows
    body = request_body(orders, legs, general())
    body['routes'][0]['stops'][0]['plannedArrivalTime'] = '25:00'
    assert client.post('/v1/delivery-risk', json=body).status_code == 422


def test_a_missing_reference_table_is_refused(rows):
    orders, legs = rows
    body = request_body(orders, legs, general())
    del body['reference']['traffic_speed']
    res = client.post('/v1/delivery-risk', json=body)
    assert res.status_code == 422
    assert 'traffic_speed' in res.text


def test_the_forecast_reproduces_the_datathon_submission():
    weeks = pd.read_csv(FIXTURES / 'task2a_weeks.csv')
    expected = pd.read_csv(FIXTURES / 'task2a_expected.csv')
    body = dict(calendar=reference({'calendar': general()['calendar']})['calendar'], outlets=outlets(),
                weeks=[dict(depot=w.depot, brand=w.brand, isoYear=int(w.iso_year), isoWeek=int(w.iso_week))
                       for w in weeks.itertuples(index=False)])
    res = client.post('/v1/demand-forecast', json=body)
    assert res.status_code == 200, res.text
    got = pd.DataFrame(res.json()['forecasts'])
    merged = weeks.assign(totalM3=got.totalM3.values, chilledM3=got.chilledM3.values).merge(expected, on='row_id')
    assert np.allclose(merged.totalM3, merged.pred_total_volume_m3, atol=0, rtol=0)
    assert np.allclose(merged.chilledM3, merged.pred_chilled_volume_m3, atol=0, rtol=0)


def test_the_forecast_runs_past_the_supplied_calendar():
    """Days generated by the extension policy (R-CAL-03) carry no festival and still forecast."""
    cal = general()['calendar']
    last = date.fromisoformat(cal.date.max())
    generated = []
    for i in range(1, 60):
        d = last + timedelta(days=i)
        iso = d.isocalendar()
        generated.append(dict(date=d.isoformat(), dow=d.weekday(), dow_name=d.strftime('%a'),
                              is_weekend=int(d.weekday() >= 5), iso_year=iso.year, iso_week=iso.week, is_payday=0,
                              festival=None, festival_ramp=0.0, is_holiday=0, monsoon=0,
                              is_operating=int(d.weekday() != 6)))
    calendar = reference({'calendar': cal})['calendar'] + generated
    target = (last + timedelta(days=30)).isocalendar()
    res = client.post('/v1/demand-forecast', json=dict(
        calendar=calendar, outlets=outlets(), weeks=[dict(depot='Kandy', brand='Fresh', isoYear=target.year, isoWeek=target.week)]))
    assert res.status_code == 200, res.text
    f = res.json()['forecasts'][0]
    assert f['totalM3'] > 0
    assert 0 <= f['chilledM3'] <= f['totalM3']


def test_a_week_outside_the_calendar_is_refused():
    res = client.post('/v1/demand-forecast', json=dict(
        calendar=reference({'calendar': general()['calendar']})['calendar'], outlets=outlets(),
        weeks=[dict(depot='Kandy', brand='Fresh', isoYear=2099, isoWeek=1)]))
    assert res.status_code == 422


def test_a_forecast_without_outlets_is_refused():
    """The model schedules each outlet's orders; without them every week would read as no demand."""
    body = dict(calendar=reference({'calendar': general()['calendar']})['calendar'],
                weeks=[dict(depot='Kandy', brand='Fresh', isoYear=2026, isoWeek=14)])
    assert client.post('/v1/demand-forecast', json=body).status_code == 422
    body['outlets'] = [dict(outlet_id='OUT001', brand='Fresh')]
    res = client.post('/v1/demand-forecast', json=body)
    assert res.status_code == 422
    assert 'depot' in res.text


def test_an_altered_model_file_refuses_to_load(tmp_path):
    models = tmp_path / 'models'
    shutil.copytree(registry.MODELS_DIR, models)
    with open(models / 'task2a_model.joblib', 'ab') as f:
        f.write(b' ')
    with pytest.raises(registry.ManifestMismatch, match='task2a_model.joblib'):
        registry.verify(registry.MANIFEST, models)
