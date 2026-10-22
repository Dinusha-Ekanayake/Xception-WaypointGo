"""Issue #12, R-ML-37: the model store, served beside the image's models, each entry checked before it loads."""
import json
import os
import pickle
import shutil

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app import main, registry, scoring, store
from app.schemas import Route
from tests.test_retrain import export
from tests.test_service import FIXTURES, general, request_body
from training import planning, retrain

LABEL = 'waypoint-planning@0.1.0'


class Evil:
    def __reduce__(self):
        return os.system, ('echo the store let this through',)


@pytest.fixture(scope='module')
def built(tmp_path_factory):
    """A late-risk model retrained here, as the scheduled run writes it."""
    d = tmp_path_factory.mktemp('build')
    export(d / 'export.jsonl')
    result, artifact = retrain.train(retrain.load_export(d / 'export.jsonl'))
    entry = retrain.write(result, artifact, 'waypoint-planning', '0.1.0', d)
    return d, entry, artifact


def put(root, build, entry, name=None):
    """An entry in the store as `training.backend.publish` leaves it."""
    d = root / (name or f'{entry["name"]}@{entry["version"]}')
    d.mkdir(parents=True)
    for f in entry['artifacts']:
        shutil.copy(build / f, d / f)
    (d / store.ENTRY).write_text(json.dumps(entry), encoding='utf-8')
    return d


def loaded_with(root):
    return registry.load(registry.MANIFEST, registry.MODELS_DIR, root)


def body():
    return request_body(pd.read_csv(FIXTURES / 'orders.csv'), pd.read_csv(FIXTURES / 'route_legs.csv'), general())


def test_a_stored_model_answers_when_named_and_the_image_model_when_not(tmp_path, built):
    build, entry, artifact = built
    put(tmp_path / 'store', build, entry)
    loaded = loaded_with(tmp_path / 'store')
    b = body()
    routes = [Route(**r) for r in b['routes']]

    named = scoring.delivery_risk(loaded, routes, b['reference'], LABEL)
    x = planning.features([r.model_dump() for r in routes])
    expected = np.clip(artifact['late'].predict_proba(x[artifact['features']])[:, 1], 5e-4, 1 - 5e-4)
    assert np.allclose([p for _, _, p, _ in named], expected.round(4))

    default = scoring.delivery_risk(loaded, routes, b['reference'])
    assert {rc for *_, rc in default} == {'used'}, 'the image model, which reads road conditions'
    assert [p for _, _, p, _ in default] != [p for _, _, p, _ in named]
    image = loaded.info['delivery_risk'].label
    assert scoring.delivery_risk(loaded, routes, b['reference'], image) == default

    labels = {(m['name'] + '@' + m['version'], m['source']) for m in loaded.available()}
    assert (LABEL, 'store') in labels and (image, 'image') in labels


def test_a_tampered_or_unsafe_entry_is_rejected_with_its_reason_and_never_loaded(tmp_path, built):
    build, entry, _ = built
    root = tmp_path / 'store'
    tampered = put(root, build, entry)
    (tampered / next(iter(entry['artifacts']))).write_bytes(b'not the model')

    evil = root / 'evil-model@1'
    evil.mkdir()
    (evil / 'evil.pkl').write_bytes(pickle.dumps(Evil()))
    import hashlib
    digest = hashlib.sha256((evil / 'evil.pkl').read_bytes()).hexdigest()
    (evil / store.ENTRY).write_text(json.dumps(dict(
        kind='delivery_risk', name='evil-model', version='1', pipeline='planning', artifacts={'evil.pkl': digest})))

    loaded = loaded_with(root)
    rejected = {r['entry']: r['reason'] for r in loaded.store.rejected()}
    assert 'SHA-256' in rejected[LABEL]
    assert 'system' in rejected['evil-model@1'], 'the pickle allowlist, before anything is loaded'
    assert loaded.store.models() == []
    with pytest.raises(scoring.BadRequest, match='not loaded'):
        scoring.resolve(loaded, 'delivery_risk', LABEL)


def test_an_entry_must_be_named_for_its_model_and_not_shadow_the_image(tmp_path, built):
    build, entry, _ = built
    root = tmp_path / 'store'
    put(root, build, entry, name='waypoint-planning@9.9.9')
    image = json.loads(registry.MANIFEST.read_text())['models'][0]
    copied = put(root, registry.MODELS_DIR, image)
    loaded = loaded_with(root)
    rejected = {r['entry']: r['reason'] for r in loaded.store.rejected()}
    assert 'the entry names waypoint-planning@0.1.0' in rejected['waypoint-planning@9.9.9']
    assert 'already served from the image' in rejected[copied.name]


def test_a_new_entry_is_picked_up_without_a_restart_and_a_rejected_one_once_its_files_change(tmp_path, built):
    build, entry, _ = built
    root = tmp_path / 'store'
    root.mkdir()
    now = [0.0]
    s = store.Store(root, set(), clock=lambda: now[0])
    s.refresh(force=True)
    assert s.models() == []

    d = put(root, build, entry)
    artifact = d / next(iter(entry['artifacts']))
    good = artifact.read_bytes()
    artifact.write_bytes(b'half copied')
    assert not s.refresh(), 'throttled'
    now[0] += store.REFRESH_SECONDS
    assert s.refresh()
    assert [r['entry'] for r in s.rejected()] == [LABEL]

    artifact.write_bytes(good)
    now[0] += store.REFRESH_SECONDS
    s.refresh()
    assert s.rejected() == [] and [m.info.label for m in s.models()] == [LABEL]


def test_health_lists_what_is_available_and_what_was_rejected(tmp_path, built, monkeypatch):
    build, entry, _ = built
    root = tmp_path / 'store'
    put(root, build, entry)
    (root / 'broken@1').mkdir()
    monkeypatch.setattr(main, 'LOADED', loaded_with(root))
    client = TestClient(main.app)

    health = client.get('/health').json()
    assert [m['kind'] for m in health['models']] == ['delivery_risk', 'demand_forecast'], 'the defaults, as before'
    assert {m['name'] + '@' + m['version'] for m in health['available'] if m['source'] == 'store'} == {LABEL}
    assert health['rejected'][0]['entry'] == 'broken@1' and 'entry.json' in health['rejected'][0]['reason']

    b = body()
    answer = client.post('/v1/delivery-risk', json={**b, 'model': LABEL})
    assert answer.status_code == 200, answer.text
    assert (answer.json()['modelName'], answer.json()['modelVersion']) == ('waypoint-planning', '0.1.0')
    unknown = client.post('/v1/delivery-risk', json={**b, 'model': 'nobody@1'})
    assert unknown.status_code == 422 and 'nobody@1 is not loaded' in unknown.json()['detail']
    bad = client.post('/v1/delivery-risk', json={**b, 'model': 'Not A Label'})
    assert bad.status_code == 422
