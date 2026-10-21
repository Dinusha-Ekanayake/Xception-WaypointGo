"""Fetch the training export to this machine (roadmap MF-05, issue #7).

Pages ``GET /api/ml/training/routes`` (scored routes with their outcomes) or
``GET /api/ml/training/orders`` (every order as demand) for one depot and date
range and writes one item per line. It needs an administrator's session cookie in
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


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='Fetch the training export (MF-05).')
    ap.add_argument('--url', required=True, help='the backend, for example http://localhost:8080')
    ap.add_argument('--depot', required=True)
    ap.add_argument('--from', dest='start', required=True)
    ap.add_argument('--to', dest='end', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--kind', choices=KINDS, default='routes', help='routes for late risk, orders for demand')
    a = ap.parse_args(argv)
    cookie = os.environ.get('WAYPOINT_SESSION')
    if not cookie:
        raise SystemExit('set WAYPOINT_SESSION to an administrator session cookie')
    n = 0
    with open(a.out, 'w', encoding='utf-8') as f:
        for item in pages(check_url(a.url), a.depot, a.start, a.end, cookie, kind=a.kind):
            f.write(json.dumps(item) + '\n')
            n += 1
    print(f'{n} {a.kind} written to {a.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
