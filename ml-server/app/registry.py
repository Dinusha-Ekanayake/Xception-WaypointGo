"""Loads the shipped models once, after proving they are the files the manifest names and safe to load.

A model file that does not match its SHA-256 is not served: the backend would
otherwise record predictions against a version that is not what produced them.
A file that names a class or function outside `model-globals.txt` is not loaded
at all, since loading a pickle runs what it names (app/pickle_scan.py).

Beside the image's models, a model store (`ML_STORE_DIR`, issue #12) holds the
models retraining produced; `app/store.py` checks each entry the same way. The
image's model of each kind answers a request that names none.
"""
import hashlib
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS_DIR = Path(os.environ.get('ML_MODELS_DIR', ROOT / 'models'))
MANIFEST = Path(os.environ.get('ML_MANIFEST', ROOT / 'manifest.json'))
STORE_DIR = Path(os.environ['ML_STORE_DIR']) if os.environ.get('ML_STORE_DIR') else None

# The pickled models name their classes as top-level modules (`parallel_model`,
# `mc_task1`, `task2a_demand`), so the vendored folder goes on the import path.
sys.path.insert(0, str(ROOT / 'datathon'))

import joblib  # noqa: E402

import inference  # noqa: E402 (vendored)

from . import pickle_scan  # noqa: E402

KINDS = ('delivery_risk', 'demand_forecast')
PIPELINES = ('datathon', 'planning')


class ManifestMismatch(RuntimeError):
    """An artifact is missing or is not the file the manifest names."""


class UnsafeModel(RuntimeError):
    """An artifact names a global outside the allowlist, or cannot be read far enough to tell."""


@dataclass(frozen=True)
class ModelInfo:
    kind: str
    name: str
    version: str
    trained_from: str
    trained_to: str
    metrics: dict = field(default_factory=dict)
    # 'datathon' for the vendored Datathon models; 'planning' for a model retrained on our own deliveries (MF-05).
    pipeline: str = 'datathon'
    artifacts: tuple = ()

    @property
    def label(self) -> str:
        """How the backend names a model: ``name@version``."""
        return f'{self.name}@{self.version}'

    def as_json(self):
        return {'kind': self.kind, 'name': self.name, 'version': self.version, 'trainedFrom': self.trained_from,
                'trainedTo': self.trained_to, 'metrics': self.metrics}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def check_entry(m: dict, models_dir: Path, allowed: set) -> ModelInfo:
    """One manifest entry: every file present, equal to its SHA-256 and safe to load. Raises naming the first bad file."""
    for key in ('kind', 'name', 'version', 'artifacts'):
        if key not in m:
            raise ManifestMismatch(f'the entry has no {key}')
    if m['kind'] not in KINDS:
        raise ManifestMismatch(f'kind {m["kind"]!r} is not one of {KINDS}')
    pipeline = m.get('pipeline', 'datathon')
    if pipeline not in PIPELINES:
        raise ManifestMismatch(f'pipeline {pipeline!r} is not one of {PIPELINES}')
    if not m['artifacts']:
        raise ManifestMismatch(f'{m["name"]}@{m["version"]} names no files')
    for name, expected in m['artifacts'].items():
        path = models_dir / name
        if Path(name).name != name:
            raise ManifestMismatch(f'{name} is not a plain file name')
        if not path.exists():
            raise ManifestMismatch(f'{name} is missing from {models_dir}')
        actual = _sha256(path)
        if actual != expected:
            raise ManifestMismatch(f'{name} has SHA-256 {actual}, the manifest names {expected}')
        if path.suffix in pickle_scan.PICKLE_SUFFIXES:
            problems = pickle_scan.check(path, allowed)
            if problems:
                raise UnsafeModel('; '.join(problems))
    return ModelInfo(m['kind'], m['name'], m['version'], m.get('trainedFrom', ''), m.get('trainedTo', ''),
                     m.get('metrics', {}), pipeline, tuple(m['artifacts']))


def verify(manifest_path: Path = MANIFEST, models_dir: Path = MODELS_DIR) -> dict:
    """Returns {kind: ModelInfo}, or raises ManifestMismatch or UnsafeModel naming the first bad file."""
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    allowed = pickle_scan.load_allowlist()
    out = {}
    for m in manifest['models']:
        out[m['kind']] = check_entry(m, models_dir, allowed)
    return out


@dataclass
class Loaded:
    info: dict
    # The Task 1 ParallelModel: primary and the copy trained without road conditions.
    task1: object = None
    task2a: object = None
    # A late-risk model retrained on our own deliveries, when the manifest names one (MF-05).
    planning: dict | None = None
    # The model store (issue #12): models retraining produced, loaded beside these.
    store: object = None

    def default(self, kind: str):
        """(info, model) of the image's model of a kind, or None when the image has none."""
        info = self.info.get(kind)
        if info is None:
            return None
        if kind == 'demand_forecast':
            return info, self.task2a
        return info, self.planning if info.pipeline == 'planning' else self.task1

    def resolve(self, kind: str, label: str | None = None):
        """(info, model) answering a request for ``label`` of a kind; the image's model when no label is named.

        None when that model is not loaded. A label the store does not hold yet
        makes the store look again, at most every few seconds.
        """
        default = self.default(kind)
        if label is None or (default is not None and default[0].label == label):
            return default
        if self.store is None:
            return None
        found = self.store.find(kind, label)
        if found is None and self.store.refresh(force=True):
            found = self.store.find(kind, label)
        return found

    def available(self) -> list[dict]:
        """Every model loaded, the image's first: what a request may name (R-ML-39)."""
        out = [{**i.as_json(), 'source': 'image'} for i in self.info.values()]
        if self.store is not None:
            out += [{**s.info.as_json(), 'source': 'store'} for s in self.store.models()]
        return out


def load_model(info: ModelInfo, models_dir: Path):
    """The model object an entry names, refused unless it is the kind of object its pipeline serves."""
    path = models_dir / info.artifacts[0]
    model = joblib.load(path)
    if info.kind == 'delivery_risk' and info.pipeline == 'planning':
        if not isinstance(model, dict) or not {'late', 'features'} <= set(model):
            raise ManifestMismatch(f'{path.name} is not a planning model')
    elif info.kind == 'delivery_risk':
        if not (hasattr(model, 'predict') and hasattr(model, 'ctx') and hasattr(model, 'parts')):
            raise ManifestMismatch(f'{path.name} is not a Task 1 parallel model')
    else:
        import task2a_demand
        if not isinstance(model, task2a_demand.DemandModel):
            raise ManifestMismatch(f'{path.name} is not a demand model')
    return model


def load(manifest_path: Path = MANIFEST, models_dir: Path = MODELS_DIR, store_dir: Path | None = STORE_DIR) -> Loaded:
    info = verify(manifest_path, models_dir)
    loaded = Loaded(info=info)
    risk = info.get('delivery_risk')
    if risk is not None and risk.pipeline == 'planning':
        loaded.planning = joblib.load(models_dir / risk.artifacts[0])
    elif risk is not None:
        loaded.task1 = joblib.load(models_dir / inference.TASK1)
    demand = info.get('demand_forecast')
    if demand is not None:
        # The Datathon model or one retrained on our own orders (#7): both are a DemandModel, named by the manifest.
        loaded.task2a = joblib.load(models_dir / demand.artifacts[0])
    if store_dir is not None:
        from .store import Store
        loaded.store = Store(store_dir, {i.label for i in info.values()})
        loaded.store.refresh(force=True)
    return loaded
