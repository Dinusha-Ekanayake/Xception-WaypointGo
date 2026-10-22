"""Roadmap MF-05, R-ML-18: retraining on our own deliveries, and serving what it produced."""
import json
import shutil

import numpy as np
import pandas as pd
import pytest

from app import registry, scoring
from app.schemas import Route
from tests.test_service import FIXTURES, ROOT, general, request_body
from tests import reference_export
from training import fetch, planning, retrain


def stop(seq, arrive, close, travel=20.0, depart='04:00'):
    return dict(seq=seq, deliveryId=f'D{seq}', outletId='OUT001', orderDate='2026-03-01', deferred=False,
                tempRequirement='ambient', units=5, weightKg=50.0, volumeM3=0.4, fromPoint='DEPOT' if seq == 0 else 'X',
                distanceKm=8.0, plannedDepartTime=depart, plannedTravelMin=travel, plannedArrivalTime=arrive,
                windowOpenTime='04:00', windowCloseTime=close)


def test_planning_features_come_from_the_route_as_scored():
    route = dict(routeId='R1', date='2026-03-02', depot='Kandy', vehicleId='V1', vehicleType='truck',
                 vehicleTemp='ambient', brand='Fresh', district='Kandy',
                 stops=[stop(1, '05:10', '06:00', travel=15.0), stop(0, '04:30', '07:00', travel=30.0)])
    x = planning.features([route])
    assert list(x.deliveryId) == ['D0', 'D1'], 'in stop order'
    assert list(x.slack_close) == [150, 50]
    assert list(x.cum_travel_min) == [30.0, 45.0]
    assert list(x.n_stops) == [2, 2] and list(x.is_truck) == [1, 1]
    assert x.route_start_m.iloc[0] == 240
    assert list(x.columns[2:]) == planning.FEATURES


def test_reference_features_are_what_the_plan_knew_about_the_outlet_the_hour_and_the_day():
    g = planning.reference_tables(ROOT.parent / 'data' / 'General Data')
    route = dict(routeId='R1', date='2026-03-02', depot='Peliyagoda', vehicleId='V1', vehicleType='van',
                 vehicleTemp='ambient', brand='Fresh', district='Colombo',
                 stops=[stop(0, '05:10', '07:30'), stop(1, '05:40', '07:30')])
    x = planning.features([route], g)
    assert list(x.columns[2:]) == planning.FEATURES + planning.REFERENCE
    allowance = g['service_allowance'].set_index(['brand', 'dock_type']).service_allowance_min[('Fresh', 'street')]
    assert list(x.allowance_min) == [allowance, allowance], 'OUT001 is a Fresh street outlet'
    assert list(x.allowance_before_min) == [0, allowance]
    ts = g['traffic_speed']
    day = g['calendar'].set_index('date').loc['2026-03-02']
    assert x.speed_at_arrival.iloc[0] == ts[(ts.district == 'Colombo') & (ts.hour == 5) & (ts.monsoon == day.monsoon)].speed_index.iloc[0]
    assert x.dow.iloc[0] == day.dow
    assert list(planning.features([route]).columns[2:]) == planning.FEATURES, 'without the tables, the route alone'


def export(path, routes=900, seed=0, labelled=True):
    """Synthetic export lines over 20 weeks: lateness rises as the slack shrinks and further down the route."""
    rng = np.random.default_rng(seed)
    days = pd.date_range('2026-01-05', periods=140, freq='D')
    with open(path, 'w', encoding='utf-8') as f:
        for r in range(routes):
            day = str(days[rng.integers(len(days))].date())
            stops, labels = [], []
            t = 240
            for s in range(5):
                t += int(rng.integers(10, 40))
                close = t + int(rng.integers(-20, 120))
                st = stop(s, f'{t // 60:02d}:{t % 60:02d}', f'{min(close, 1439) // 60:02d}:{min(close, 1439) % 60:02d}')
                st['deliveryId'] = f'R{r}-{s}'
                stops.append(st)
                late = rng.random() < 1 / (1 + np.exp((close - t) / 15 - 3 + 1.2 * (2 - s) ** 2 / 2))
                labels.append(dict(deliveryId=st['deliveryId'], late=bool(late) if labelled else None,
                                   labelSource='device' if labelled else 'none',
                                   serviceMinutes=float(rng.uniform(8, 25))))
            f.write(json.dumps(dict(routeId=f'R{r}', serviceDate=day, labels=labels, route=dict(
                routeId=f'R{r}', date=day, depot='Kandy', vehicleId='V1', vehicleType='van', vehicleTemp='ambient',
                brand='Fresh', district='Kandy', stops=stops))) + '\n')


def test_a_model_that_beats_the_slack_alone_is_written_with_its_manifest_entry(tmp_path):
    export(tmp_path / 'export.jsonl')
    result, artifact = retrain.train(retrain.load_export(tmp_path / 'export.jsonl'))
    assert result.verdict == 'BETTER', result
    assert result.candidate.log_loss < result.slack_only.log_loss
    entry = retrain.write(result, artifact, 'waypoint-planning', '0.1.0', tmp_path / 'out')
    assert entry['pipeline'] == 'planning' and entry['kind'] == 'delivery_risk'
    file, digest = next(iter(entry['artifacts'].items()))
    assert registry._sha256(tmp_path / 'out' / file) == digest
    assert json.loads((tmp_path / 'out' / 'report.json').read_text())['verdict'] == 'BETTER'


def with_incumbent(src, dst, prob):
    """The same export with what answered at the time on every label."""
    with open(src) as f, open(dst, 'w') as out:
        for line in f:
            item = json.loads(line)
            for lab in item['labels']:
                lab['answeredBy'], lab['answeredLateProb'] = 'deterministic', prob(lab)
            out.write(json.dumps(item) + '\n')


def test_a_candidate_is_replayed_against_what_answered_on_the_same_stops(tmp_path):
    export(tmp_path / 'export.jsonl')
    # A weak incumbent: the same 0.3 for every stop.
    with_incumbent(tmp_path / 'export.jsonl', tmp_path / 'weak.jsonl', lambda lab: 0.3)
    result, _ = retrain.train(retrain.load_export(tmp_path / 'weak.jsonl'))
    assert result.replay.verdict == 'BETTER' and result.replay.answered_by == ['deterministic']
    assert result.replay.stops == result.holdout_labels
    assert result.hand_over
    # An incumbent that knew the answer: the candidate is worse and is not handed over.
    with_incumbent(tmp_path / 'export.jsonl', tmp_path / 'oracle.jsonl', lambda lab: 0.98 if lab['late'] else 0.02)
    result, _ = retrain.train(retrain.load_export(tmp_path / 'oracle.jsonl'))
    assert result.replay.verdict == 'WORSE'
    assert not result.hand_over
    assert retrain.main(['--export', str(tmp_path / 'oracle.jsonl'), '--version', '0.0.2', '--out', str(tmp_path / 'o'),
                         '--reference', 'none']) == 2


def test_stops_with_no_arrival_are_not_trained_on_and_too_few_labels_are_not_enough(tmp_path):
    export(tmp_path / 'none.jsonl', labelled=False)
    with pytest.raises(ValueError, match='no labelled stops'):
        retrain.train(retrain.load_export(tmp_path / 'none.jsonl'))
    export(tmp_path / 'few.jsonl', routes=60, seed=3)
    result, _ = retrain.train(retrain.load_export(tmp_path / 'few.jsonl'))
    assert result.verdict == 'NOT_ENOUGH'
    assert retrain.main(['--export', str(tmp_path / 'few.jsonl'), '--version', '0.0.1', '--out', str(tmp_path / 'o'),
                         '--reference', 'none']) == 2


@pytest.mark.parametrize('with_reference', [False, True])
def test_the_service_serves_a_retrained_model_from_the_same_features(tmp_path, with_reference):
    export(tmp_path / 'export.jsonl')
    tables = planning.reference_tables(ROOT.parent / 'data' / 'General Data') if with_reference else None
    result, artifact = retrain.train(retrain.load_export(tmp_path / 'export.jsonl', tables))
    assert (artifact['features'] == planning.FEATURES + planning.REFERENCE) == with_reference
    entry = retrain.write(result, artifact, 'waypoint-planning', '0.1.0', tmp_path / 'out')
    models = tmp_path / 'models'
    shutil.copytree(registry.MODELS_DIR, models)
    shutil.copy(tmp_path / 'out' / next(iter(entry['artifacts'])), models)
    manifest = json.loads(registry.MANIFEST.read_text())
    manifest['models'] = [entry if m['kind'] == 'delivery_risk' else m for m in manifest['models']]
    (tmp_path / 'manifest.json').write_text(json.dumps(manifest))

    loaded = registry.load(tmp_path / 'manifest.json', models)
    assert loaded.task1 is None and loaded.planning is not None
    assert loaded.task2a is not None, 'the forecast model is unaffected'
    body = request_body(pd.read_csv(FIXTURES / 'orders.csv'), pd.read_csv(FIXTURES / 'route_legs.csv'), general())
    routes = [Route(**r) for r in body['routes']]
    rows = scoring.delivery_risk(loaded, routes, body['reference'])
    x = planning.features([r.model_dump() for r in routes], scoring.general(body['reference']) if with_reference else None)
    expected = np.clip(artifact['late'].predict_proba(x[artifact['features']])[:, 1], 5e-4, 1 - 5e-4)
    assert [d for d, *_ in rows] == list(x.deliveryId)
    assert np.allclose([p for _, _, p, _ in rows], expected.round(4))
    assert {rc for *_, rc in rows} == {'fallback'}, 'it does not use road conditions'
    assert all(s > 0 for _, s, _, _ in rows)


def test_the_export_is_fetched_page_by_page_and_never_over_plain_http_elsewhere():
    with pytest.raises(SystemExit):
        fetch.check_url('http://waypoint.example.com')
    assert fetch.check_url('http://localhost:8080/') == 'http://localhost:8080'
    assert fetch.check_url('https://waypoint.example.com') == 'https://waypoint.example.com'

    seen = []

    class Answer:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return json.dumps(self.body).encode()

    def opener(req):
        seen.append(req.full_url)
        assert req.headers['Cookie'] == 'SESSION=x'
        return Answer({'items': [1, 2], 'nextCursor': 'c1'} if 'after=' not in req.full_url
                      else {'items': [3], 'nextCursor': None})

    items = list(fetch.pages('http://localhost:8080', 'KDY', '2026-01-01', '2026-02-01', 'SESSION=x', opener))
    assert items == [1, 2, 3]
    assert 'after=c1' in seen[1]
    assert '/api/ml/training/routes?' in seen[0]

    seen.clear()
    items = list(fetch.pages('http://localhost:8080', 'KDY', '2026-01-01', '2026-02-01', 'SESSION=x', opener,
                             kind='orders'))
    assert items == [1, 2, 3]
    assert all('/api/ml/training/orders?' in u for u in seen), 'the demand history is the orders export (#7)'
    with pytest.raises(ValueError):
        list(fetch.pages('http://localhost:8080', 'KDY', '2026-01-01', '2026-02-01', 'SESSION=x', opener,
                         kind='plans'))


def test_the_scheduled_run_trains_on_the_last_finished_weeks_of_every_depot(tmp_path):
    import datetime as dt
    from training import run
    assert run.window(dt.date(2026, 10, 8), 2) == (dt.date(2026, 9, 21), dt.date(2026, 10, 4))

    export(tmp_path / 'export.jsonl')
    lines = [json.loads(line) for line in open(tmp_path / 'export.jsonl')]
    asked = []

    class Answer:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return json.dumps(self.body).encode()

    def opener(req):
        asked.append(req.full_url)
        if '/api/ml/training/reference?' in req.full_url:
            return Answer(reference_export.tables())
        half = len(lines) // 2
        return Answer({'items': lines[:half] if 'depot=A' in req.full_url else lines[half:], 'nextCursor': None})

    code, build = run.run('http://localhost:8080', ['A', 'B'], 26, tmp_path / 'builds', 'SESSION=x',
                          dt.date(2026, 10, 8), opener=opener)
    assert code == 0
    # R-ML-36: the reference is the backend's, once, from before the window to a year ahead.
    ref, *routes = asked
    assert '/api/ml/training/reference?' in ref and 'depot=A&depot=B' in ref
    assert 'from=2026-02-05' in ref and 'to=2027-10-13' in ref
    assert (build / 'reference.json').exists()
    assert len(routes) == 2 and 'from=2026-04-06' in routes[0] and 'to=2026-10-04' in routes[0]
    assert sum(1 for _ in open(build / 'export.jsonl')) == len(lines)
    entry = json.loads((build / 'manifest-entry.json').read_text())
    assert entry['version'] == '2026.10.08' and entry['pipeline'] == 'planning'
