"""Loads the shipped models once, after proving they are the files the manifest names.

A model file that does not match its SHA-256 is not served: the backend would
otherwise record predictions against a version that is not what produced them.
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

# The pickled models name their classes as `src.<module>`, so the vendored
# Datathon package keeps its name and its parent goes on the import path.
sys.path.insert(0, str(ROOT / 'datathon'))
os.environ.setdefault('MODELS_DIR', str(MODELS_DIR))

import joblib  # noqa: E402


class ManifestMismatch(RuntimeError):
    """An artifact is missing or is not the file the manifest names."""


@dataclass(frozen=True)
class ModelInfo:
    kind: str
    name: str
    version: str
    trained_from: str
    trained_to: str
    metrics: dict = field(default_factory=dict)
    # 'datathon' for the vendored blend; 'planning' for a model retrained on our own deliveries (MF-05).
    pipeline: str = 'datathon'
    artifacts: tuple = ()

    def as_json(self):
        return {'kind': self.kind, 'name': self.name, 'version': self.version, 'trainedFrom': self.trained_from,
                'trainedTo': self.trained_to, 'metrics': self.metrics}


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def verify(manifest_path: Path = MANIFEST, models_dir: Path = MODELS_DIR) -> dict:
    """Returns {kind: ModelInfo}, or raises ManifestMismatch naming the first bad file."""
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    out = {}
    for m in manifest['models']:
        for name, expected in m['artifacts'].items():
            path = models_dir / name
            if not path.exists():
                raise ManifestMismatch(f'{name} is missing from {models_dir}')
            actual = _sha256(path)
            if actual != expected:
                raise ManifestMismatch(f'{name} has SHA-256 {actual}, the manifest names {expected}')
        out[m['kind']] = ModelInfo(m['kind'], m['name'], m['version'], m['trainedFrom'], m['trainedTo'],
                                   m.get('metrics', {}), m.get('pipeline', 'datathon'), tuple(m['artifacts']))
    return out


@dataclass
class Loaded:
    info: dict
    task1_a: object = None
    task1_b: object = None
    task1_fallback: object = None
    blend: dict | None = None
    task2a: object = None
    # A late-risk model retrained on our own deliveries, when the manifest names one (MF-05).
    planning: dict | None = None


def load(manifest_path: Path = MANIFEST, models_dir: Path = MODELS_DIR) -> Loaded:
    info = verify(manifest_path, models_dir)
    loaded = Loaded(info=info)
    risk = info.get('delivery_risk')
    if risk is not None and risk.pipeline == 'planning':
        loaded.planning = joblib.load(models_dir / risk.artifacts[0])
    elif risk is not None:
        loaded.task1_a = joblib.load(models_dir / 'task1_A.joblib')
        loaded.task1_b = joblib.load(models_dir / 'task1_B.joblib')
        loaded.task1_fallback = joblib.load(models_dir / 'task1_B_no_road_conditions.joblib')
        loaded.blend = json.loads((models_dir / 'task1_blend.json').read_text(encoding='utf-8'))
    if 'demand_forecast' in info:
        loaded.task2a = joblib.load(models_dir / 'task2a.joblib')
    return loaded
