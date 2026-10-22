"""Issue #7: the demand forecast retrained on our own orders, gated on a rolling backtest."""
import datetime as dt
import hashlib
import json
import shutil

import pytest

from app import registry, scoring
from app.schemas import Week
from tests import reference_export
from tests.demand_orders import orders, write
from training import retrain_demand as rd
from training import run
from training.retrain_demand import NotTrained, Wape


@pytest.fixture(scope='module')
def reference():
    return rd.load_reference()


@pytest.fixture(scope='module')
def trained(tmp_path_factory, reference):
    """78 weeks of orders that follow the calendar: the case a retrain exists for."""
    path = write(tmp_path_factory.mktemp('orders') / 'orders.jsonl', orders())
    history, counts = rd.load_export(path)
    cal, outlets = reference
    return rd.train(history, counts, cal, outlets)


def test_a_model_that_beats_the_naive_forecast_is_written(trained, tmp_path):
    result, model = trained
    assert result.verdict == 'BETTER' and result.hand_over
    assert len(result.origins) >= rd.MIN_ORIGINS
    assert result.candidate.total < result.naive.total and result.candidate.chilled < result.naive.chilled
    entry = rd.write(result, model, '2025.06.30', tmp_path)
    assert entry['kind'] == 'demand_forecast' and entry['name'] == 'waypoint-demand'
    (file, digest), = entry['artifacts'].items()
    assert hashlib.sha256((tmp_path / file).read_bytes()).hexdigest() == digest
    assert entry['metrics']['total_wape'] == result.candidate.total
    assert json.loads((tmp_path / 'manifest-entry.json').read_text()) == entry
    assert json.loads((tmp_path / 'report.json').read_text())['verdict'] == 'BETTER'


def test_every_backtest_fit_sees_only_orders_before_its_start(trained):
    result, _ = trained
    first = dt.date.fromisoformat(result.trained_from)
    for origin in result.origins:
        start = dt.date.fromisoformat(origin)
        assert start.weekday() == 0, 'a forecast starts on a Monday'
        assert start - first >= dt.timedelta(weeks=rd.MIN_TRAIN_WEEKS)
    last = dt.date.fromisoformat(result.origins[-1]) + dt.timedelta(weeks=rd.HORIZON_WEEKS, days=-1)
    assert last <= dt.date.fromisoformat(result.trained_to), 'every scored week has finished'


def test_under_a_year_of_orders_is_not_trained(tmp_path):
    path = write(tmp_path / 'orders.jsonl', orders(end='2024-09-29'))
    code = rd.main(['--export', str(path), '--version', '0.1', '--out', str(tmp_path / 'out')])
    assert code == 2
    assert '52' in json.loads((tmp_path / 'out' / 'report.json').read_text())['notTrained']
    assert not list((tmp_path / 'out').glob('*.joblib'))


def test_an_order_day_the_calendar_does_not_hold_is_refused(tmp_path, reference):
    rows = orders(start='2025-07-01', end='2026-06-28', brands=('Style',))
    rows.append(dict(rows[-1], orderId='00000000-0000-7000-8000-999999999999', placedDate='2026-08-03'))
    history, counts = rd.load_export(write(tmp_path / 'orders.jsonl', rows))
    with pytest.raises(NotTrained, match='calendar'):
        rd.train(history, counts, *reference)


def test_an_order_without_a_temperature_is_left_out_and_counted(tmp_path):
    rows = orders(start='2024-01-01', end='2024-01-14', brands=('Style',))
    rows[0] = dict(rows[0], temperature=None)
    rows[1] = dict(rows[1], volumeM3=None)
    history, counts = rd.load_export(write(tmp_path / 'orders.jsonl', rows))
    assert counts == dict(without_temperature=1, without_volume=1)
    assert len(history) == len(rows) - 1
    assert history.order_volume_m3.isna().sum() == 1, 'kept for the schedule, not the size regression'


def test_the_gate_hands_over_only_a_candidate_better_than_both():
    naive, current = Wape(0.2, 0.2), Wape(0.05, 0.05)
    assert rd.gate(Wape(0.1, 0.1), naive, None) == ('BETTER', True)
    assert rd.gate(Wape(0.1, 0.3), naive, None) == ('NOT_BETTER_THAN_NAIVE', False), 'chilled counts as much'
    assert rd.gate(Wape(0.1, 0.1), naive, current) == ('WORSE_THAN_CURRENT', False)
    assert rd.gate(Wape(0.04, 0.05), naive, current) == ('BETTER', True)


def test_the_service_serves_a_demand_model_retrained_here(trained, tmp_path, reference):
    result, model = trained
    models = tmp_path / 'models'
    shutil.copytree(registry.MODELS_DIR, models)
    entry = rd.write(result, model, '2025.06.30', tmp_path / 'out')
    shutil.copy(tmp_path / 'out' / next(iter(entry['artifacts'])), models)
    manifest = json.loads(registry.MANIFEST.read_text())
    manifest['models'] = [entry if m['kind'] == 'demand_forecast' else m for m in manifest['models']]
    (tmp_path / 'manifest.json').write_text(json.dumps(manifest))

    loaded = registry.load(tmp_path / 'manifest.json', models)
    assert loaded.info['demand_forecast'].name == 'waypoint-demand'
    cal, outlets = reference
    rows = scoring.demand_forecast(
        loaded, json.loads(cal.to_json(orient='records')), json.loads(outlets.to_json(orient='records')),
        [Week(depot='Kandy', brand='Fresh', isoYear=2025, isoWeek=30)])
    (_depot, _brand, _y, _w, total, chilled), = rows
    assert total > 0 and 0 < chilled <= total


def test_the_scheduled_run_builds_a_demand_candidate_from_the_order_export(tmp_path):
    rows = orders()
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
        depot = 'Kandy' if 'depot=Kandy' in req.full_url else 'Peliyagoda'
        return Answer({'items': [r for r in rows if r['depot'] == depot], 'nextCursor': None})

    code, build = run.run_demand('http://localhost:8080', ['Kandy', 'Peliyagoda'], 104, tmp_path, 'SESSION=x',
                                 dt.date(2025, 6, 30), opener=opener)
    ref, *exports = asked
    assert '/api/ml/training/reference?' in ref, 'the calendar is the backend one (R-ML-38)'
    assert all('/api/ml/training/orders?' in u for u in exports) and len(exports) == 2
    report = json.loads((build / 'report.json').read_text())
    assert report['current'] is not None, 'replayed against the model the manifest serves'
    assert code == (0 if report['hand_over'] else 2)
    assert (build / 'manifest-entry.json').exists() and build.name == 'demand'
