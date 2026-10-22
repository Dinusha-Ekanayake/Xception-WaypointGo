"""Issue #12: a passing candidate goes into the model store and the registry as the shadow, never active."""
import io
import json
import urllib.error

import pytest

from app import registry, store
from tests.test_store import LABEL, built  # noqa: F401 (fixture)
from training import backend


class Answer:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def read(self):
        return json.dumps(self.body).encode()


class FakeBackend:
    """The command endpoint and the registry read, as the backend answers them."""

    def __init__(self, registered=False, status='REGISTERED'):
        self.sent = []
        self.registered = registered
        self.status = status

    def __call__(self, req):
        assert req.headers['Cookie'] == 'SESSION=x'
        if req.full_url.endswith('/api/ml/models'):
            return Answer([{'name': 'waypoint-planning', 'version': '0.1.0', 'kind': 'delivery_risk',
                            'status': self.status, 'rowVersion': 3}])
        body = json.loads(req.data)
        self.sent.append(body)
        if body['kind'] == 'ml:RegisterModel' and self.registered:
            raise urllib.error.HTTPError(req.full_url, 409, 'Conflict', {}, io.BytesIO(json.dumps(
                {'code': 'CONFLICT', 'detail': 'Model waypoint-planning@0.1.0 is already registered'}).encode()))
        self.registered = True
        return Answer({'commandId': body['commandId'], 'kind': body['kind'], 'replayed': False, 'result': {}})


def test_a_published_candidate_is_whole_in_the_store_and_the_service_loads_it(tmp_path, built):
    build, entry, _ = built
    target = backend.publish(build, tmp_path / 'store')
    assert target.name == LABEL and json.loads((target / store.ENTRY).read_text()) == entry
    assert not [p for p in (tmp_path / 'store').iterdir() if p.name.startswith('.')], 'no partial copy left'
    s = store.Store(tmp_path / 'store', set())
    s.refresh(force=True)
    assert [m.info.label for m in s.models()] == [LABEL] and s.rejected() == []

    assert backend.publish(build, tmp_path / 'store') == target, 'the same week run again changes nothing'
    other = dict(entry, metrics={**entry['metrics'], 'late_auc_last6w': 0.5})
    (build / 'manifest-entry.json').write_text(json.dumps(other))
    try:
        with pytest.raises(ValueError, match='new version'):
            backend.publish(build, tmp_path / 'store')
    finally:
        (build / 'manifest-entry.json').write_text(json.dumps(entry))


def test_a_file_the_service_would_refuse_never_reaches_the_store(tmp_path, built):
    build, entry, _ = built
    bad = tmp_path / 'bad'
    bad.mkdir()
    (bad / 'manifest-entry.json').write_text(json.dumps(entry))
    (bad / next(iter(entry['artifacts']))).write_bytes(b'not the model')
    with pytest.raises(registry.ManifestMismatch):
        backend.publish(bad, tmp_path / 'store')
    assert not (tmp_path / 'store').exists()


def test_a_late_risk_candidate_is_registered_and_made_the_shadow_never_activated(built):
    _, entry, _ = built
    fake = FakeBackend()
    assert backend.register('http://localhost:8080', 'SESSION=x', entry, shadow=True, opener=fake) == 'SHADOW'
    kinds = [b['kind'] for b in fake.sent]
    assert kinds == ['ml:RegisterModel', 'ml:ShadowModel'], 'R-ML-41: activation stays a person\'s'
    register, shadow = fake.sent
    assert register['payload']['name'] == 'waypoint-planning' and register['payload']['kind'] == 'delivery_risk'
    assert all(isinstance(v, (int, float)) for v in register['payload']['metrics'].values())
    assert shadow['expectedVersion'] == 3 and shadow['payload'] == {'name': 'waypoint-planning', 'version': '0.1.0'}
    assert len({b['commandId'] for b in fake.sent}) == 2


def test_a_rerun_finds_the_model_registered_and_leaves_a_shadow_or_active_one_alone(built):
    _, entry, _ = built
    again = FakeBackend(registered=True)
    assert backend.register('http://localhost:8080', 'SESSION=x', entry, shadow=True, opener=again) == 'SHADOW'
    assert [b['kind'] for b in again.sent] == ['ml:RegisterModel', 'ml:ShadowModel']

    active = FakeBackend(registered=True, status='ACTIVE')
    assert backend.register('http://localhost:8080', 'SESSION=x', entry, shadow=True, opener=active) == 'ACTIVE'
    assert [b['kind'] for b in active.sent] == ['ml:RegisterModel']

    retired = FakeBackend(registered=True, status='RETIRED')
    with pytest.raises(backend.Refused, match='retired'):
        backend.register('http://localhost:8080', 'SESSION=x', entry, shadow=True, opener=retired)


def test_a_demand_candidate_is_registered_only(built):
    _, entry, _ = built
    fake = FakeBackend()
    assert backend.register('http://localhost:8080', 'SESSION=x', dict(entry, kind='demand_forecast'),
                            shadow=False, opener=fake) == 'REGISTERED'
    assert [b['kind'] for b in fake.sent] == ['ml:RegisterModel']
