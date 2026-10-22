"""Fetch the training export to this machine (roadmap MF-05, issues #7 and #12).

Pages ``GET /api/ml/training/routes`` (scored routes with their outcomes) or
``GET /api/ml/training/orders`` (every order as demand) for one depot and date
range and writes one item per line. ``--kind reference`` reads
``GET /api/ml/training/reference`` instead: the outlets, service allowances,
traffic speed and calendar the backend scores with (R-ML-38), as one JSON file. It needs an administrator's session cookie in
``WAYPOINT_SESSION`` (the export is ``ml:ExportTrainingData``), refuses plain
HTTP to any host but this one, and never prints what it fetched.
"""
from __future__ import annotations

import argparse
import json
import os
import urllib.parse
import urllib.request

LOCAL = {'localhost', '127.0.0.1', '::1'}
KINDS = ('routes', 'orders')
REFERENCE_TABLES = ('outlets', 'service_allowance', 'traffic_speed', 'calendar')


def check_url(base: str) -> str:
    u = urllib.parse.urlparse(base)
    if u.scheme not in ('http', 'https') or not u.hostname:
        raise SystemExit(f'not a URL: {base}')
    if u.scheme == 'http' and u.hostname not in LOCAL:
        raise SystemExit('refusing plain HTTP to another host: the export holds operational data')
    return base.rstrip('/')


def pages(base: str, depot: str, start: str, end: str, cookie: str, opener=urllib.request.urlopen,
          kind: str = 'routes'):
    if kind not in KINDS:
        raise ValueError(f'kind is one of {KINDS}')
    after = None
    while True:
        q = dict(depot=depot, **{'from': start}, to=end, limit=200)
        if after:
            q['after'] = after
        req = urllib.request.Request(f'{base}/api/ml/training/{kind}?{urllib.parse.urlencode(q)}',
                                     headers={'Cookie': cookie, 'Accept': 'application/json'})
        with opener(req) as r:
            page = json.loads(r.read())
        yield from page['items']
        after = page.get('nextCursor')
        if not after:
            return


def reference(base: str, depots: list[str], start: str, end: str, cookie: str,
              opener=urllib.request.urlopen) -> dict:
    """The reference tables as the backend scores with them, for these depots and calendar days."""
    if not depots:
        raise ValueError('name at least one depot')
    q = urllib.parse.urlencode([('depot', d) for d in depots] + [('from', start), ('to', end)])
    req = urllib.request.Request(f'{base}/api/ml/training/reference?{q}',
                                 headers={'Cookie': cookie, 'Accept': 'application/json'})
    with opener(req) as r:
        tables = json.loads(r.read())
    missing = [t for t in REFERENCE_TABLES if t not in tables]
    if missing:
        raise ValueError(f'the reference export lacks {missing}')
    return tables


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Fetch the training export (MF-05).')
    ap.add_argument('--url', required=True, help='the backend, for example http://localhost:8080')
    ap.add_argument('--depot', required=True, action='append', help='repeat for each depot (reference only)')
    ap.add_argument('--from', dest='start', required=True)
    ap.add_argument('--to', dest='end', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--kind', choices=KINDS + ('reference',), default='routes',
                    help='routes for late risk, orders for demand, reference for the tables both train with')
    a = ap.parse_args(argv)
    cookie = os.environ.get('WAYPOINT_SESSION')
    if not cookie:
        raise SystemExit('set WAYPOINT_SESSION to an administrator session cookie')
    if a.kind == 'reference':
        tables = reference(check_url(a.url), a.depot, a.start, a.end, cookie)
        with open(a.out, 'w', encoding='utf-8') as f:
            json.dump(tables, f)
        print(f'reference for {len(a.depot)} depots, {len(tables["calendar"])} calendar days written to {a.out}')
        return 0
    if len(a.depot) != 1:
        raise SystemExit(f'--kind {a.kind} takes one --depot')
    n = 0
    with open(a.out, 'w', encoding='utf-8') as f:
        for item in pages(check_url(a.url), a.depot[0], a.start, a.end, cookie, kind=a.kind):
            f.write(json.dumps(item) + '\n')
            n += 1
    print(f'{n} {a.kind} written to {a.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
