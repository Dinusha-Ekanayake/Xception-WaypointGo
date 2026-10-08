"""Roadmap MF-05, R-ML-18: retraining on our own deliveries, and serving what it produced."""
import json
import shutil

import numpy as np
import pandas as pd
import pytest

from app import registry, scoring
from app.schemas import Route
from tests.test_service import FIXTURES, general, request_body
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


def test_stops_with_no_arrival_are_not_trained_on_and_too_few_labels_are_not_enough(tmp_path):
    export(tmp_path / 'none.jsonl', labelled=False)
    with pytest.raises(ValueError, match='no labelled stops'):
        retrain.train(retrain.load_export(tmp_path / 'none.jsonl'))
    export(tmp_path / 'few.jsonl', routes=60, seed=3)
    result, _ = retrain.train(retrain.load_export(tmp_path / 'few.jsonl'))
    assert result.verdict == 'NOT_ENOUGH'
    assert retrain.main(['--export', str(tmp_path / 'few.jsonl'), '--version', '0.0.1', '--out', str(tmp_path / 'o')]) == 2


def test_the_service_serves_a_retrained_model_from_the_same_features(tmp_path):
    export(tmp_path / 'export.jsonl')
    result, artifact = retrain.train(retrain.load_export(tmp_path / 'export.jsonl'))
    entry = retrain.write(result, artifact, 'waypoint-planning', '0.1.0', tmp_path / 'out')
    models = tmp_path / 'models'
    shutil.copytree(registry.MODELS_DIR, models)
    shutil.copy(tmp_path / 'out' / next(iter(entry['artifacts'])), models)
    manifest = json.loads(registry.MANIFEST.read_text())
    manifest['models'] = [entry if m['kind'] == 'delivery_risk' else m for m in manifest['models']]
    (tmp_path / 'manifest.json').write_text(json.dumps(manifest))

    loaded = registry.load(tmp_path / 'manifest.json', models)
    assert loaded.task1_a is None and loaded.planning is not None
    assert loaded.task2a is not None, 'the forecast model is unaffected'
    body = request_body(pd.read_csv(FIXTURES / 'orders.csv'), pd.read_csv(FIXTURES / 'route_legs.csv'), general())
    routes = [Route(**r) for r in body['routes']]
    rows = scoring.delivery_risk(loaded, routes, body['reference'])
    x = planning.features([r.model_dump() for r in routes])
    expected = np.clip(artifact['late'].predict_proba(x[planning.FEATURES])[:, 1], 5e-4, 1 - 5e-4)
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
