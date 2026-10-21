"""The static model scan (app/pickle_scan.py): allowlisted globals only, read without loading.

Each malicious file here names os.system through __reduce__. Writing it runs
nothing; loading it would, and the scan never loads it. The cases that matter
are the ones the public scanners miss on joblib files: a global after a numpy
array, and one inside an object array's nested pickle.
"""
import os
import pickle
import shutil

import joblib
import numpy as np
import pytest

from app import pickle_scan, registry
from app.pickle_scan import Global, check, load_allowlist, scan


class Evil:
    def __reduce__(self):
        return os.system, ('echo the scan let this through',)


@pytest.fixture(scope='module')
def allowed():
    return load_allowlist()


def dump(tmp_path, obj, name='model.joblib', compress=3):
    path = tmp_path / name
    joblib.dump(obj, path, compress=compress)
    return path


def test_the_served_models_name_only_allowlisted_globals(allowed):
    for path in pickle_scan.model_files(registry.MODELS_DIR):
        assert check(path, allowed) == [], path.name
    task1 = scan(registry.MODELS_DIR / 'task1_parallel_model.joblib')
    assert task1.arrays > 0, 'the arrays were stepped over, not missed'
    assert Global('parallel_model', 'ParallelModel') in task1.globals


def test_a_plain_pickle_naming_os_system_is_refused(tmp_path, allowed):
    path = tmp_path / 'evil.pkl'
    path.write_bytes(pickle.dumps(Evil()))
    problems = check(path, allowed)
    assert len(problems) == 1 and 'system' in problems[0]


def test_a_global_after_a_numpy_array_is_found(tmp_path, allowed):
    """picklescan and modelscan stop at the first array's raw bytes; this payload sits after one."""
    clean = dump(tmp_path, {'weights': np.arange(1000, dtype='f8')}, 'clean.joblib')
    assert check(clean, allowed) == []
    evil = dump(tmp_path, {'weights': np.arange(1000, dtype='f8'), 'hook': Evil()}, 'evil.joblib')
    report = scan(evil)
    assert report.arrays == 1
    assert any(g.name == 'system' for g in report.globals)
    assert check(evil, allowed)


def test_a_global_inside_an_object_array_is_found(tmp_path, allowed):
    """joblib writes an object array as a nested pickle; the scan walks it too."""
    evil = dump(tmp_path, {'cells': np.array([1, Evil()], dtype=object)})
    assert any('system' in p for p in check(evil, allowed))


@pytest.mark.parametrize('compress', [0, 3, ('gzip', 3), ('bz2', 3), ('xz', 3), ('lzma', 3)])
def test_every_compression_joblib_writes_is_read(tmp_path, allowed, compress):
    evil = dump(tmp_path, {'weights': np.ones((20, 3)), 'hook': Evil()}, compress=compress)
    assert any('system' in p for p in check(evil, allowed))


def test_a_builtin_outside_the_list_is_refused(tmp_path, allowed):
    """getattr is the classic way to reach a function a scanner does not list; only exact pairs pass."""
    path = tmp_path / 'getattr.pkl'
    path.write_bytes(b'\x80\x04cbuiltins\ngetattr\n(cbuiltins\nslice\nX\x05\x00\x00\x00startt' b'R.')
    problems = check(path, allowed)
    assert problems and 'builtins.getattr' in problems[0]


def test_bytes_after_the_pickle_or_a_cut_file_are_refused(tmp_path, allowed):
    good = dump(tmp_path, {'weights': np.arange(10.0)}, compress=0)
    trailing = tmp_path / 'trailing.joblib'
    trailing.write_bytes(good.read_bytes() + pickle.dumps(Evil()))
    assert 'refused' in check(trailing, allowed)[0]
    cut = tmp_path / 'cut.joblib'
    cut.write_bytes(good.read_bytes()[:-20])
    assert 'refused' in check(cut, allowed)[0]


def test_a_model_retrained_here_passes(tmp_path, allowed):
    from tests.test_retrain import export
    from training import retrain
    export(tmp_path / 'export.jsonl')
    result, artifact = retrain.train(retrain.load_export(tmp_path / 'export.jsonl', None))
    entry = retrain.write(result, artifact, 'waypoint-planning', '0.1.0', tmp_path / 'out')
    assert check(tmp_path / 'out' / next(iter(entry['artifacts'])), allowed) == []


def test_the_allowlist_holds_exact_pairs_only():
    for line in pickle_scan.ALLOWLIST.read_text(encoding='utf-8').splitlines():
        line = line.split('#', 1)[0].strip()
        if line:
            module, name = line.split(' ')
            assert '*' not in line and name, line


def test_the_service_refuses_a_model_the_scan_refuses(tmp_path):
    """The hashes match, so only the scan stands between this file and joblib.load."""
    import json
    models = tmp_path / 'models'
    shutil.copytree(registry.MODELS_DIR, models)
    joblib.dump({'hook': Evil()}, models / 'task2a_model.joblib', compress=3)
    manifest = json.loads(registry.MANIFEST.read_text())
    for m in manifest['models']:
        if 'task2a_model.joblib' in m['artifacts']:
            m['artifacts']['task2a_model.joblib'] = registry._sha256(models / 'task2a_model.joblib')
    (tmp_path / 'manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(registry.UnsafeModel, match='system'):
        registry.load(tmp_path / 'manifest.json', models)


def test_scanning_never_imports_what_the_file_names(tmp_path, monkeypatch):
    path = tmp_path / 'evil.pkl'
    path.write_bytes(pickle.dumps(Evil()))
    calls = []
    monkeypatch.setattr(os, 'system', lambda *a: calls.append(a))
    assert any(g.name == 'system' for g in scan(path).globals)
    assert calls == [], 'named, never called'
