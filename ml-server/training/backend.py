"""Hands a candidate to the pipeline (issue #12, R-ML-37): into the model store, then into the backend's registry.

A candidate the gates passed is copied into the store directory the model
service reads, checked first exactly as the service will check it, and
registered with the backend through the command endpoint (``ml:RegisterModel``),
under the administrator session the run already uses. A late-risk candidate is
then made the shadow of its kind (``ml:ShadowModel``), so it scores every
published plan beside the active model without answering (R-ML-38). Nothing
here activates a model: that stays an administrator's decision (R-ML-39).

Runs on our own server; it sends the backend only the model's name, version,
training range and holdout figures.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import shutil
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from app import pickle_scan, registry

ENTRY = 'entry.json'


class Refused(RuntimeError):
    """The backend refused a command: its status and the problem's code and detail."""

    def __init__(self, status: int, code: str | None, detail: str | None):
        super().__init__(f'{status} {code}: {detail}')
        self.status, self.code, self.detail = status, code, detail


def publish(build: str | Path, store: str | Path) -> Path:
    """Copies the candidate in ``build`` (``manifest-entry.json`` and its files) into the store.

    The files are checked as the service checks them before anything is copied,
    and the entry appears at once: it is written under a hidden name and renamed,
    so the service never reads half of it. Publishing the same entry again is a
    no-op; a different entry under the same name and version is refused.
    """
    build, store = Path(build), Path(store)
    entry = json.loads((build / 'manifest-entry.json').read_text(encoding='utf-8'))
    info = registry.check_entry(entry, build, pickle_scan.load_allowlist())
    target = store / info.label
    if target.exists():
        if json.loads((target / ENTRY).read_text(encoding='utf-8')) == entry:
            return target
        raise ValueError(f'{info.label} is already in the store with other files: give the candidate a new version')
    store.mkdir(parents=True, exist_ok=True)
    partial = store / f'.{info.label}.{uuid.uuid4().hex}'
    partial.mkdir()
    try:
        for name in entry['artifacts']:
            shutil.copy2(build / name, partial / name)
        (partial / ENTRY).write_text(json.dumps(entry, indent=2) + '\n', encoding='utf-8')
        os.replace(partial, target)
    except BaseException:
        shutil.rmtree(partial, ignore_errors=True)
        raise
    return target


def command(base: str, cookie: str, kind: str, payload: dict, expected: int | None = None,
            opener=urllib.request.urlopen) -> dict:
    """One command through ``POST /api/commands``, as the screens send it; the answer's ``result``."""
    body = dict(commandId=str(uuid.uuid4()), kind=kind, expectedVersion=expected, payload=payload,
                clientRecordedAt=dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z'))
    req = urllib.request.Request(f'{base}/api/commands', data=json.dumps(body).encode(), method='POST',
                                 headers={'Cookie': cookie, 'Content-Type': 'application/json',
                                          'Accept': 'application/json'})
    try:
        with opener(req) as r:
            return json.loads(r.read()).get('result') or {}
    except urllib.error.HTTPError as e:
        try:
            problem = json.loads(e.read() or b'{}')
        except ValueError:
            problem = {}
        raise Refused(e.code, problem.get('code'), problem.get('detail')) from None


def models(base: str, cookie: str, opener=urllib.request.urlopen) -> list[dict]:
    req = urllib.request.Request(f'{base}/api/ml/models', headers={'Cookie': cookie, 'Accept': 'application/json'})
    with opener(req) as r:
        return json.loads(r.read())


def register(base: str, cookie: str, entry: dict, shadow: bool, opener=urllib.request.urlopen) -> str:
    """Registers the entry's model, and makes it the shadow of its kind when ``shadow``; returns its status.

    A model already registered is not an error, so a rerun of the same week
    carries on. A model already shadow or active is left as it is.
    """
    payload = dict(name=entry['name'], version=entry['version'], kind=entry['kind'],
                   trainedFrom=entry.get('trainedFrom') or None, trainedTo=entry.get('trainedTo') or None,
                   metrics={k: v for k, v in entry.get('metrics', {}).items()
                            if isinstance(v, (int, float)) and not isinstance(v, bool)})
    try:
        command(base, cookie, 'ml:RegisterModel', payload, opener=opener)
    except Refused as e:
        if e.status != 409 or e.code != 'CONFLICT':
            raise
    if not shadow:
        return 'REGISTERED'
    row = next((m for m in models(base, cookie, opener)
                if m['name'] == entry['name'] and m['version'] == entry['version']), None)
    if row is None:
        raise Refused(404, 'NOT_FOUND', f'{entry["name"]}@{entry["version"]} is not in the registry')
    if row['status'] in ('SHADOW', 'ACTIVE'):
        return row['status']
    if row['status'] == 'RETIRED':
        raise Refused(409, 'CONFLICT', f'{entry["name"]}@{entry["version"]} is retired')
    command(base, cookie, 'ml:ShadowModel', dict(name=entry['name'], version=entry['version']),
            expected=row['rowVersion'], opener=opener)
    return 'SHADOW'


def hand_to_pipeline(build: str | Path, store: str | Path | None, base: str | None, cookie: str | None,
                     shadow: bool, opener=urllib.request.urlopen) -> dict:
    """Publish, then register when the backend is given. What happened, for the run's report."""
    out = {}
    if store is not None:
        out['store'] = str(publish(build, store))
    if base is not None:
        entry = json.loads((Path(build) / 'manifest-entry.json').read_text(encoding='utf-8'))
        out['registry'] = register(base, cookie, entry, shadow, opener)
    return out
