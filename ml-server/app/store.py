"""The model store: models retraining produced, served beside the image's (issue #12, R-ML-39).

The store is a directory the service only reads (mounted read only); the
scheduled retraining run writes it. Each model is a directory named
``<name>@<version>`` holding ``entry.json`` (the manifest entry ``retrain``
writes) and the files it names:

    store/
      waypoint-planning@2026.10.25/
        entry.json
        waypoint-planning-2026.10.25.joblib

An entry is loaded only after every file matches its SHA-256 and passes the
pickle allowlist, exactly as the image's models are (``registry.check_entry``).
One that does not is listed as rejected with its reason and is never loaded, so
a bad file is visible on ``/health`` and the service keeps answering with the
models it has (rule 9). A loaded model is never replaced: a new version is a new
directory. Nothing is unloaded; an entry is removed by hand when it is retired.
"""
from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from . import pickle_scan, registry

ENTRY = 'entry.json'
# How often a scan may run unasked (on /health), and how often one asked for by a request naming an unknown model.
REFRESH_SECONDS = 30.0
FORCED_REFRESH_SECONDS = 5.0


@dataclass(frozen=True)
class StoredModel:
    info: registry.ModelInfo
    model: object
    directory: str


class Store:
    def __init__(self, root: Path, image_labels: set, clock=time.monotonic):
        self.root = Path(root)
        self.image_labels = set(image_labels)
        self.clock = clock
        self._loaded: dict[str, StoredModel] = {}
        # directory -> (stamp, reason): a rejected entry is looked at again only once its files change.
        self._rejected: dict[str, tuple[tuple, str]] = {}
        self._last = None
        self._lock = threading.Lock()

    def models(self) -> list[StoredModel]:
        return sorted(self._loaded.values(), key=lambda s: s.info.label)

    def rejected(self) -> list[dict]:
        return [{'entry': d, 'reason': reason} for d, (_, reason) in sorted(self._rejected.items())]

    def find(self, kind: str, label: str):
        """(info, model) for a loaded label of this kind, or None."""
        s = self._loaded.get(label)
        if s is None or s.info.kind != kind:
            return None
        return s.info, s.model

    def refresh(self, force: bool = False) -> bool:
        """Loads any new entry. Returns whether a scan ran (throttled; a forced scan less so)."""
        with self._lock:
            now = self.clock()
            wait = FORCED_REFRESH_SECONDS if force else REFRESH_SECONDS
            if self._last is not None and now - self._last < wait:
                return False
            self._last = now
            if not self.root.is_dir():
                return True
            allowed = pickle_scan.load_allowlist()
            # A name starting with '.' is an entry still being copied in (training.backend.publish).
            for directory in sorted(p for p in self.root.iterdir() if p.is_dir() and not p.name.startswith('.')):
                key = directory.name
                if any(s.directory == key for s in self._loaded.values()):
                    continue
                stamp = _stamp(directory)
                if key in self._rejected and self._rejected[key][0] == stamp:
                    continue
                try:
                    stored = self._load(directory, allowed)
                except (registry.ManifestMismatch, registry.UnsafeModel, OSError, ValueError, KeyError,
                        TypeError) as e:
                    self._rejected[key] = (stamp, str(e)[:500])
                    continue
                self._rejected.pop(key, None)
                self._loaded[stored.info.label] = stored
            return True

    def _load(self, directory: Path, allowed: set) -> StoredModel:
        entry_path = directory / ENTRY
        if not entry_path.is_file():
            raise registry.ManifestMismatch(f'{directory.name} has no {ENTRY}')
        entry = json.loads(entry_path.read_text(encoding='utf-8'))
        if not isinstance(entry, dict):
            raise registry.ManifestMismatch(f'{ENTRY} is not an object')
        info = registry.check_entry(entry, directory, allowed)
        if directory.name != info.label:
            raise registry.ManifestMismatch(f'the directory is named {directory.name}, the entry names {info.label}')
        if info.label in self.image_labels:
            raise registry.ManifestMismatch(f'{info.label} is already served from the image')
        return StoredModel(info, registry.load_model(info, directory), directory.name)


def _stamp(directory: Path) -> tuple:
    """What changes when an entry's files change: their names, sizes and modification times."""
    out = []
    for p in sorted(directory.iterdir()):
        if p.is_file():
            st = p.stat()
            out.append((p.name, st.st_size, st.st_mtime_ns))
    return tuple(out)
