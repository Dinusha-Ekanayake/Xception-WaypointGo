#!/usr/bin/env python3
"""Draws the two submission diagrams as PNGs (and SVGs).

    python3 scripts/generate-diagrams.py [output-dir]

Writes architecture.png, data-model.png and delivery-flow.png (plus .svg) to docs/img by default.
Needs: pip install playwright, and a Chromium (set CHROMIUM_PATH if it is not
found automatically). The diagrams are drawn from code so they can be edited
and regenerated; nothing here reads the database.
"""
import os
import sys
from pathlib import Path
from xml.sax.saxutils import escape

FONT = "Inter, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif"
MONO = "'JetBrains Mono', 'SF Mono', Consolas, 'DejaVu Sans Mono', monospace"

INK = "#0F172A"
MUTED = "#475569"
FAINT = "#94A3B8"
LINE = "#64748B"
BG = "#FFFFFF"
CARD = "#FFFFFF"
BORDER = "#CBD5E1"

# One colour per module / schema, used identically in both diagrams.
SCHEMA = {
    "ref": "#3E4C59",
    "iam": "#3E4C59",
    "ordering": "#3E4C59",
    "warehouse": "#3E4C59",
    "planning": "#3E4C59",
    "loading": "#3E4C59",
    "execution": "#3E4C59",
    "receipt": "#3E4C59",
    "issues": "#3E4C59",
    "notification": "#3E4C59",
    "sync": "#3E4C59",
    "ml": "#3E4C59",
    "integration": "#3E4C59",
}


def tint(hex_colour, amount=0.9):
    h = hex_colour.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    r = int(r + (255 - r) * amount)
    g = int(g + (255 - g) * amount)
    b = int(b + (255 - b) * amount)
    return f"#{r:02X}{g:02X}{b:02X}"


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = []

    def add(self, s):
        self.parts.append(s)

    def rect(self, x, y, w, h, fill=CARD, stroke=BORDER, sw=1.2, rx=10, dash=None, shadow=False):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        f = ' filter="url(#sh)"' if shadow else ""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
                 f'stroke="{stroke}" stroke-width="{sw}"{d}{f}/>')

    def text(self, x, y, s, size=13, weight=400, fill=INK, anchor="start", family=FONT, spacing=None, opacity=None):
        ls = f' letter-spacing="{spacing}"' if spacing else ""
        op = f' opacity="{opacity}"' if opacity else ""
        self.add(f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
                 f'fill="{fill}" text-anchor="{anchor}"{ls}{op}>{escape(s)}</text>')

    def lines(self, x, y, rows, size=12, fill=MUTED, gap=17, anchor="start", weight=400):
        for i, r in enumerate(rows):
            self.text(x, y + i * gap, r, size=size, fill=fill, anchor=anchor, weight=weight)

    def path(self, d, stroke=LINE, sw=1.6, dash=None, end="arrow", start=None, fill="none"):
        a = f' stroke-dasharray="{dash}"' if dash else ""
        me = f' marker-end="url(#{end})"' if end else ""
        ms = f' marker-start="url(#{start})"' if start else ""
        self.add(f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" '
                 f'stroke-linejoin="round" stroke-linecap="round"{a}{me}{ms}/>')

    def pill(self, x, y, label, fill="#FFFFFF", stroke=BORDER, colour=MUTED, size=11, weight=500, pad=9, h=20):
        w = len(label) * size * 0.58 + pad * 2
        self.add(f'<rect x="{x - w / 2}" y="{y - h / 2}" width="{w}" height="{h}" rx="{h / 2}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="1"/>')
        self.text(x, y + size * 0.36, label, size=size, weight=weight, fill=colour, anchor="middle")

    def chip(self, x, y, label, colour, w=None, h=26, size=12):
        w = w or len(label) * size * 0.6 + 20
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h / 2}" fill="{tint(colour, .86)}" '
                 f'stroke="{colour}" stroke-width="1"/>')
        self.text(x + w / 2, y + h / 2 + size * 0.36, label, size=size, weight=600, fill=colour, anchor="middle")
        return w

    def render(self):
        defs = f'''
<defs>
  <filter id="sh" x="-5%" y="-5%" width="110%" height="115%">
    <feDropShadow dx="0" dy="1.5" stdDeviation="2.2" flood-color="#0F172A" flood-opacity="0.10"/>
  </filter>
  <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
    <path d="M0,0 L10,5 L0,10 z" fill="{LINE}"/>
  </marker>
  <marker id="arrowDark" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
    <path d="M0,0 L10,5 L0,10 z" fill="{INK}"/>
  </marker>
  <marker id="dot" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6">
    <circle cx="5" cy="5" r="4" fill="{LINE}"/>
  </marker>
</defs>'''
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}">{defs}<rect width="{self.w}" height="{self.h}" fill="{BG}"/>'
                + "".join(self.parts) + "</svg>")


# --------------------------------------------------------------------------- architecture

def icon_person(s, cx, cy, colour):
    s.add(f'<circle cx="{cx}" cy="{cy - 4}" r="5" fill="none" stroke="{colour}" stroke-width="1.8"/>')
    s.add(f'<path d="M{cx - 9},{cy + 11} Q{cx - 9},{cy + 3} {cx},{cy + 3} Q{cx + 9},{cy + 3} {cx + 9},{cy + 11}" '
          f'fill="none" stroke="{colour}" stroke-width="1.8" stroke-linecap="round"/>')


def module_chip(s, x, y, w, h, name, sub, key):
    c = SCHEMA[key]
    s.rect(x, y, w, h, fill=CARD, stroke=BORDER, sw=1, rx=9)
    s.add(f'<rect x="{x}" y="{y}" width="5" height="{h}" rx="2.5" fill="{c}"/>')
    s.text(x + 17, y + 24, name, size=13.5, weight=650, fill=INK)
    s.text(x + 17, y + 43, sub, size=11, fill=MUTED)
    s.text(x + w - 10, y + 16, key, size=9.5, weight=600, fill=c, anchor="end", family=MONO)


def architecture():
    W, H = 1800, 1190
    s = Svg(W, H)

    # Title
    s.text(40, 54, "Waypoint Dispatch", size=30, weight=750)
    s.text(40, 80, "System architecture: a modular monolith on PostgreSQL behind one responsive web app for four field roles",
           size=14.5, fill=MUTED)

    # Legend
    lx, ly = 1180, 38
    s.path(f"M{lx},{ly + 8} L{lx + 40},{ly + 8}", end="arrow")
    s.text(lx + 50, ly + 12, "Request", size=12, fill=MUTED)
    s.path(f"M{lx + 120},{ly + 8} L{lx + 160},{ly + 8}", dash="6 5", end="arrow")
    s.text(lx + 170, ly + 12, "Optional or opt-in", size=12, fill=MUTED)
    s.rect(lx + 320, ly, 22, 16, fill=tint("#4F46E5", .86), stroke="#4F46E5", rx=4)
    s.text(lx + 350, ly + 12, "Module colour = DB schema", size=12, fill=MUTED)

    MAINX, MAINW = 40, 1240
    RX, RW = 1360, 400

    # ---- users
    users = [
        ("Store manager", "Desktop or phone", "places orders, confirms receipt", "ordering"),
        ("Dispatcher", "Desktop", "plans, publishes, tracks the day", "planning"),
        ("Loader", "Shared dock tablet", "loads by stop order, flags shortfalls", "loading"),
        ("Driver", "Personal phone", "delivers, records proof, works offline", "execution"),
        ("Admin and auditor", "Desktop", "policy, access, audit trail", "iam"),
    ]
    uw, ug = 227, 26
    for i, (name, dev, what, key) in enumerate(users):
        x = MAINX + i * (uw + ug)
        c = SCHEMA[key]
        s.rect(x, 112, uw, 92, shadow=True)
        s.add(f'<circle cx="{x + 30}" cy="{112 + 32}" r="19" fill="{tint(c, .86)}" stroke="{c}" stroke-width="1.2"/>')
        icon_person(s, x + 30, 112 + 32, c)
        s.text(x + 58, 112 + 30, name, size=14, weight=650)
        s.text(x + 58, 112 + 48, dev, size=11.5, fill=c, weight=600)
        s.text(x + 16, 112 + 76, what, size=11.5, fill=MUTED)
        s.path(f"M{x + uw / 2},{204} L{x + uw / 2},{236}", end="arrow")
    s.pill(MAINX + MAINW / 2, 220, "HTTPS", size=10.5)

    # ---- AI assistant (right)
    s.rect(RX, 112, RW, 92, fill=CARD, stroke=BORDER, shadow=True)
    s.add(f'<circle cx="{RX + 30}" cy="{112 + 32}" r="19" fill="{tint("#9333EA", .86)}" stroke="#9333EA" stroke-width="1.2"/>')
    s.text(RX + 30, 112 + 38, "AI", size=13, weight=750, fill="#9333EA", anchor="middle")
    s.text(RX + 58, 112 + 30, "AI assistant", size=14, weight=650)
    s.text(RX + 58, 112 + 48, "Claude, ChatGPT and others", size=11.5, fill="#9333EA", weight=600)
    s.text(RX + 16, 112 + 76, "connects as a person: same policy and scope", size=11.5, fill=MUTED)

    # ---- browser container
    by, bh = 242, 160
    s.rect(MAINX, by, MAINW, bh, fill="#FFFFFF", stroke="#94A3B8", sw=1.4, rx=14, shadow=True)
    s.add(f'<rect x="{MAINX}" y="{by}" width="{MAINW}" height="34" rx="14" fill="#E2E8F0"/>')
    s.add(f'<rect x="{MAINX}" y="{by + 20}" width="{MAINW}" height="14" fill="#E2E8F0"/>')
    s.text(MAINX + 18, by + 22, "CLIENT", size=11, weight=700, fill=MUTED, spacing=1.6)
    s.text(MAINX + 80, by + 22, "Browser: progressive web app (Next.js, React)", size=13.5, weight=650)
    boxes = [
        ("Role workspaces", ["Store, dispatcher, loader, driver", "and admin screens. Responsive;", "phone-first for driver and loader."], "#2563EB"),
        ("Service worker", ["Caches the app shell so every role", "can open the app with no signal."], "#7C3AED"),
        ("Offline write queue", ["IndexedDB. Commands queue offline and", "replay with POST /api/sync on reconnect."], "#D97706"),
    ]
    bw, bg_ = 384, 24
    for i, (t, rows, c) in enumerate(boxes):
        x = MAINX + 22 + i * (bw + bg_)
        s.rect(x, by + 50, bw, 92, fill=tint(c, .93), stroke=c, sw=1.1, rx=9)
        s.text(x + 16, by + 74, t, size=13.5, weight=650, fill=INK)
        s.lines(x + 16, by + 96, rows, size=11.5, gap=16)
    s.pill(MAINX + MAINW - 215, by + 17, "Offline tiers: driver full, loader and store resilient", size=10, fill="#FFFFFF")

    # arrow client -> edge
    s.path(f"M{MAINX + MAINW / 2},{by + bh} L{MAINX + MAINW / 2},{by + bh + 30}", end="arrow")
    s.pill(MAINX + MAINW / 2 + 70, by + bh + 15, "/api/*, /mcp", size=10.5)

    # ---- edge
    ey, eh = 432, 84
    s.rect(MAINX, ey, MAINW, eh, fill="#FFFFFF", stroke="#94A3B8", sw=1.4, rx=14, shadow=True)
    s.text(MAINX + 18, ey + 24, "EDGE", size=11, weight=700, fill=MUTED, spacing=1.6)
    nx = MAINX + 22
    s.rect(nx, ey + 36, 420, 38, fill="#F1F5F9", stroke=BORDER, rx=8, dash="5 4")
    s.text(nx + 14, ey + 53, "nginx: TLS reverse proxy", size=13, weight=650)
    s.text(nx + 14, ey + 67, "production and VPS only; local uses :3000 directly", size=10.5, fill=MUTED)
    fx = nx + 420 + 54
    s.rect(fx, ey + 36, 700, 38, fill=tint("#2563EB", .93), stroke="#2563EB", sw=1.1, rx=8)
    s.text(fx + 14, ey + 53, "Next.js server (waypoint container)", size=13, weight=650)
    s.text(fx + 14, ey + 67, "serves the PWA, same-origin /api proxy to the backend, /mcp proxy to the MCP adapter", size=10.5, fill=MUTED)
    s.path(f"M{nx + 420},{ey + 55} L{fx},{ey + 55}", dash="5 4", end="arrow")

    # ---- backend container
    ky, kh = 548, 372
    s.rect(MAINX, ky, MAINW, kh, fill="#FFFFFF", stroke="#94A3B8", sw=1.4, rx=14, shadow=True)
    s.add(f'<rect x="{MAINX}" y="{ky}" width="{MAINW}" height="34" rx="14" fill="#E2E8F0"/>')
    s.add(f'<rect x="{MAINX}" y="{ky + 20}" width="{MAINW}" height="14" fill="#E2E8F0"/>')
    s.text(MAINX + 18, ky + 22, "APPLICATION", size=11, weight=700, fill=MUTED, spacing=1.6)
    s.text(MAINX + 130, ky + 22, "Backend: Spring Boot modular monolith, one process, twelve modules", size=13.5, weight=650)
    s.path(f"M{MAINX + MAINW / 2},{ey + eh} L{MAINX + MAINW / 2},{ky}", end="arrow")

    # command path
    cy = ky + 48
    s.rect(MAINX + 20, cy, 230, 58, fill="#F1F5F9", stroke=BORDER, rx=9)
    s.text(MAINX + 36, cy + 24, "REST API", size=13.5, weight=650)
    s.text(MAINX + 36, cy + 42, "thin controllers, read queries", size=11, fill=MUTED)
    s.rect(MAINX + 290, cy, 930, 58, fill=tint("#0F172A", .93), stroke="#334155", sw=1.2, rx=9)
    s.text(MAINX + 306, cy + 24, "CommandBus: the one write path, online or replayed from the offline queue", size=13.5, weight=650)
    s.text(MAINX + 306, cy + 42, "policy AND scope (else 403 + audit)  ·  idempotency receipt  ·  expectedVersion (409 on stale)  ·  RFC 9457 problem details",
           size=11, fill=MUTED)
    s.path(f"M{MAINX + 250},{cy + 29} L{MAINX + 290},{cy + 29}", end="arrow")

    # delivery flow modules
    fy = cy + 84
    s.text(MAINX + 20, fy, "DELIVERY FLOW", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    flow = [
        ("Ordering", "orders, cutoff, day close", "ordering"),
        ("Planning", "allocation, deferral, fuel", "planning"),
        ("Loading", "dock checks, shortfalls", "loading"),
        ("Execution", "trips, stops, proof", "execution"),
        ("Receipt", "store confirmation, PIN", "receipt"),
        ("Issues", "disputes, redelivery", "issues"),
    ]
    mw, mg = 178, 30
    my = fy + 10
    for i, (n, sub, k) in enumerate(flow):
        x = MAINX + 20 + i * (mw + mg)
        module_chip(s, x, my, mw, 56, n, sub, k)
        if i < len(flow) - 1:
            s.path(f"M{x + mw + 3},{my + 28} L{x + mw + mg - 3},{my + 28}", end="arrow", sw=1.4)

    # supporting modules
    sy = my + 56 + 30
    s.text(MAINX + 20, sy, "SUPPORTING MODULES", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    sup = [
        ("Identity and access", "roles, policy, scope, PIN", "iam"),
        ("Reference data", "outlets, fleet, road, calendar", "ref"),
        ("Warehouse", "StockPort, circuit breaker", "warehouse"),
        ("Notification", "inbox and web push", "notification"),
        ("Sync", "replays queued commands", "sync"),
        ("Intelligence", "predictions, forecasts", "ml"),
    ]
    sy2 = sy + 10
    for i, (n, sub, k) in enumerate(sup):
        x = MAINX + 20 + i * (mw + mg)
        module_chip(s, x, sy2, mw, 56, n, sub, k)

    # platform bar
    py = sy2 + 56 + 18
    s.rect(MAINX + 20, py, 1200, 44, fill="#0F172A", stroke="#0F172A", rx=9)
    s.text(MAINX + 38, py + 27, "PLATFORM", size=10.5, weight=700, fill="#94A3B8", spacing=1.4)
    s.text(MAINX + 130, py + 27,
           "Transactional outbox relay (at-least-once events)   ·   consumer inbox (idempotent)   ·   audit log   ·   scheduled jobs",
           size=12.5, weight=550, fill="#F8FAFC")

    # ---- database container
    dy, dh = 962, 188
    s.rect(MAINX, dy, MAINW, dh, fill="#FFFFFF", stroke="#94A3B8", sw=1.4, rx=14, shadow=True)
    s.add(f'<rect x="{MAINX}" y="{dy}" width="{MAINW}" height="34" rx="14" fill="#E2E8F0"/>')
    s.add(f'<rect x="{MAINX}" y="{dy + 20}" width="{MAINW}" height="14" fill="#E2E8F0"/>')
    s.text(MAINX + 18, dy + 22, "DATA", size=11, weight=700, fill=MUTED, spacing=1.6)
    s.text(MAINX + 74, dy + 22, "PostgreSQL 16: one database, one schema per module, 99 tables", size=13.5, weight=650)
    s.text(MAINX + MAINW - 18, dy + 22, "compose: db container   ·   production: managed PostgreSQL", size=11, fill=MUTED, anchor="end")
    s.path(f"M{MAINX + MAINW / 2},{ky + kh} L{MAINX + MAINW / 2},{dy}", end="arrow")
    s.pill(MAINX + MAINW / 2 + 150, ky + kh + 21, "JDBC as waypoint_app, adopts a module role per transaction", size=10.5)
    cx = MAINX + 22
    cyy = dy + 56
    for k in ["ref", "iam", "ordering", "warehouse", "planning", "loading", "execution", "receipt", "issues",
              "notification", "sync", "ml", "integration"]:
        w = s.chip(cx, cyy, k, SCHEMA[k], size=12)
        cx += w + 9
    facts = [
        "Forced row-level security by depot, outlet or actor   ·   module roles hold no DELETE (rows reach terminal states)",
        "UUIDv7 keys   ·   optimistic locking with row_version   ·   foreign keys only into ref and iam, other links by id",
        "Outbox, inbox and audit tables live in the integration schema   ·   proof photos and signatures stored in the database by default",
    ]
    for i, f in enumerate(facts):
        s.add(f'<circle cx="{MAINX + 30}" cy="{dy + 114 + i * 22 - 4}" r="2.5" fill="{FAINT}"/>')
        s.text(MAINX + 42, dy + 114 + i * 22, f, size=12, fill=MUTED)

    # ---- right column: MCP, ML, warehouse, push, observability, init
    def side(y, h, title, sub_rows, colour, dashed=False, tag=None):
        s.rect(RX, y, RW, h, fill=CARD, stroke=colour if not dashed else FAINT, sw=1.2 if not dashed else 1.3,
               rx=11, dash="6 5" if dashed else None, shadow=not dashed)
        s.add(f'<rect x="{RX}" y="{y}" width="5" height="{h}" rx="2.5" fill="{colour}"/>')
        s.text(RX + 20, y + 26, title, size=14, weight=650)
        if tag:
            s.text(RX + RW - 14, y + 18, tag, size=9.5, weight=600, fill=colour, anchor="end", family=MONO)
        s.lines(RX + 20, y + 47, sub_rows, size=11.5, gap=16)

    side(432, 100, "MCP adapter (Node)", ["Read tools plus confirmed issue writes (raise,", "assign) for AI assistants. OAuth, scopes and", "rate limits. Off unless MCP_ENABLED is set."],
         "#9333EA", tag="mcp")
    side(596, 84, "ML service (FastAPI)", ["Trained lateness, service-time and demand", "models. Deterministic fallback if it is down."],
         SCHEMA["ml"], tag="ml")
    side(704, 84, "Warehouse API (external)", ["Stock reservation and product catalogue.", "Cached projection when it is slow or down."],
         SCHEMA["warehouse"], tag="external")
    side(812, 84, "Browser push services", ["Web Push with VAPID keys. Blank keys turn", "push off, visibly."],
         SCHEMA["notification"], tag="external")
    side(924, 100, "Observability (opt-in profile)", ["Alloy collects container logs into Loki,", "Prometheus scrapes /prometheus, Grafana", "shows both. docker compose --profile observability."],
         "#475569", dashed=True, tag="optional")
    side(1056, 94, "init job (runs once)", ["migrate, import reference data, create demo accounts", "and seed a delivery day. Idempotent, before the backend."],
         SCHEMA["integration"], tag="init")

    # AI -> MCP
    s.path(f"M{RX + RW / 2},204 L{RX + RW / 2},432", end="arrow")
    s.pill(RX + RW / 2, 318, "MCP over HTTPS, OAuth", size=10.5)
    # edge -> MCP
    s.path(f"M{MAINX + MAINW},{ey + 55} L{RX},{ey + 55}", dash="5 4", end="arrow")
    s.pill((MAINX + MAINW + RX) / 2, ey + 41, "/mcp", size=10)
    # MCP -> backend
    s.path(f"M{RX + RW / 2},{432 + 100} L{RX + RW / 2},{ky + 14} L{MAINX + MAINW},{ky + 14}", dash="5 4", end="arrow")
    s.pill(RX + RW / 2 - 90, ky + 14, "reads, confirmed writes", size=10)
    # backend -> ML, warehouse, push
    for yy, lab, dash in [(638, "score, forecast", None), (746, "StockPort", None), (854, "Web Push", None)]:
        s.path(f"M{MAINX + MAINW},{yy} L{RX},{yy}", end="arrow", dash=dash)
        s.pill((MAINX + MAINW + RX) / 2, yy - 14, lab, size=10)
    # backend -> observability
    s.path(f"M{MAINX + MAINW},{ky + kh - 12} L{MAINX + MAINW + 36},{ky + kh - 12} L{MAINX + MAINW + 36},{974} L{RX},{974}",
           dash="5 4", end="arrow")
    s.pill(MAINX + MAINW + 40, 950, "logs, metrics", size=10)
    # init -> db
    s.path(f"M{RX},{1103} L{MAINX + MAINW},{1103}", end="arrow")
    s.pill((MAINX + MAINW + RX) / 2, 1089, "migrate", size=10)

    # footer
    s.text(40, 1176, "Source of truth: compose.yaml, docs/architecture.md and docs/data-model.md. Module boundaries are enforced by ModuleBoundaryTest and EventCatalogueTest.",
           size=11, fill=FAINT)
    return s


# --------------------------------------------------------------------------- data model

CARD_W = 262
ROW_H = 23
HEAD_H = 36


class Table:
    def __init__(self, schema, name, cols, x, y, title=None):
        self.schema, self.name, self.cols, self.x, self.y = schema, name, cols, x, y
        self.title = title or name
        self.h = HEAD_H + len(cols) * ROW_H + 8
        self.w = CARD_W

    def left(self, row=None):
        return (self.x, self.y + (HEAD_H + (row + .5) * ROW_H if row is not None else self.h / 2))

    def right(self, row=None):
        return (self.x + self.w, self.y + (HEAD_H + (row + .5) * ROW_H if row is not None else self.h / 2))

    def top(self, dx=None):
        return (self.x + (dx if dx is not None else self.w / 2), self.y)

    def bottom(self, dx=None):
        return (self.x + (dx if dx is not None else self.w / 2), self.y + self.h)

    def row_of(self, col):
        for i, c in enumerate(self.cols):
            if c[0] == col:
                return i
        raise KeyError(col)


def draw_table(s, t):
    c = SCHEMA[t.schema]
    s.rect(t.x, t.y, t.w, t.h, fill=CARD, stroke=BORDER, sw=1.1, rx=10, shadow=True)
    s.add(f'<path d="M{t.x},{t.y + HEAD_H} L{t.x},{t.y + 10} Q{t.x},{t.y} {t.x + 10},{t.y} L{t.x + t.w - 10},{t.y} '
          f'Q{t.x + t.w},{t.y} {t.x + t.w},{t.y + 10} L{t.x + t.w},{t.y + HEAD_H} Z" fill="{c}"/>')
    s.text(t.x + 14, t.y + 23, t.title, size=13.5, weight=700, fill="#FFFFFF", family=FONT)
    s.text(t.x + t.w - 12, t.y + 23, t.schema, size=10, weight=600, fill="#FFFFFF", anchor="end", family=MONO, opacity=0.85)
    for i, (name, typ, key) in enumerate(t.cols):
        yy = t.y + HEAD_H + i * ROW_H
        if i % 2 == 1:
            s.add(f'<rect x="{t.x + 1}" y="{yy}" width="{t.w - 2}" height="{ROW_H}" fill="#F8FAFC"/>')
        ty = yy + 16
        s.text(t.x + 40, ty, name, size=12, weight=600 if "PK" in key else 450, fill=INK, family=MONO)
        s.text(t.x + t.w - 12, ty, typ, size=10.5, fill=FAINT, anchor="end", family=MONO)
        bx = t.x + 10
        if key:
            labels = key.split()
            for j, k in enumerate(labels[:1]):
                col = {"PK": "#B45309", "FK": "#1D4ED8", "UK": "#6D28D9"}[k]
                bgc = {"PK": "#FEF3C7", "FK": "#DBEAFE", "UK": "#EDE9FE"}[k]
                s.add(f'<rect x="{bx}" y="{yy + 4}" width="24" height="15" rx="4" fill="{bgc}"/>')
                s.text(bx + 12, yy + 15, k, size=9, weight=700, fill=col, anchor="middle")
    # bottom padding already in h


def crow(s, x, y, direction, colour=LINE):
    """Crow's foot at (x,y); direction is the unit vector pointing from the line towards the table edge."""
    dx, dy = direction
    px, py = -dy, dx
    bx, by = x - dx * 12, y - dy * 12
    for sgn in (-1, 1):
        s.add(f'<path d="M{bx},{by} L{x + px * 7 * sgn},{y + py * 7 * sgn}" stroke="{colour}" stroke-width="1.6" fill="none" stroke-linecap="round"/>')
    s.add(f'<path d="M{bx},{by} L{x},{y}" stroke="{colour}" stroke-width="1.6" fill="none" stroke-linecap="round"/>')


def bar(s, x, y, direction, colour=LINE, offset=14):
    dx, dy = direction
    px, py = -dy, dx
    cx, cy = x - dx * offset, y - dy * offset
    s.add(f'<path d="M{cx + px * 6},{cy + py * 6} L{cx - px * 6},{cy - py * 6}" stroke="{colour}" stroke-width="1.8" fill="none" stroke-linecap="round"/>')
    cx, cy = x - dx * (offset - 6), y - dy * (offset - 6)
    s.add(f'<path d="M{cx + px * 6},{cy + py * 6} L{cx - px * 6},{cy - py * 6}" stroke="{colour}" stroke-width="1.8" fill="none" stroke-linecap="round"/>')


def circle_end(s, x, y, direction, colour=LINE):
    dx, dy = direction
    cx, cy = x - dx * 20, y - dy * 20
    s.add(f'<circle cx="{cx}" cy="{cy}" r="4.5" fill="#F8FAFC" stroke="{colour}" stroke-width="1.6"/>')


def relation(s, pts, one_end, many_end, label=None, dashed=False, label_at=None, optional_one=False):
    """pts: polyline of (x,y). one_end is at pts[0], many_end at pts[-1]."""
    d = "M" + " L".join(f"{x},{y}" for x, y in pts)
    s.path(d, stroke=LINE, sw=1.6, dash="6 5" if dashed else None, end=None)

    def unit(a, b):
        ax, ay = a
        bx, by = b
        L = max(((bx - ax) ** 2 + (by - ay) ** 2) ** .5, 1e-6)
        return ((bx - ax) / L, (by - ay) / L)

    # many end (towards the table at pts[-1])
    u_many = unit(pts[-2], pts[-1])
    crow(s, pts[-1][0], pts[-1][1], u_many)
    if one_end:
        u_one = unit(pts[1], pts[0])
        bar(s, pts[0][0], pts[0][1], u_one)
    if label:
        lx, ly = label_at or ((pts[0][0] + pts[-1][0]) / 2, (pts[0][1] + pts[-1][1]) / 2)
        s.pill(lx, ly, label, size=10, h=18)


def data_model():
    W, H = 2060, 1256
    s = Svg(W, H)

    s.text(40, 54, "Waypoint Dispatch", size=30, weight=750)
    s.text(40, 80, "Data model: the delivery day as the tables hold it, from order to receipt (core entities of 99 tables in 13 schemas)",
           size=14.5, fill=MUTED)

    lx, ly = 1330, 36
    s.path(f"M{lx},{ly + 8} L{lx + 44},{ly + 8}", end=None)
    crow(s, lx + 44, ly + 8, (1, 0))
    s.text(lx + 60, ly + 12, "Foreign key", size=12, fill=MUTED)
    s.path(f"M{lx + 160},{ly + 8} L{lx + 204},{ly + 8}", dash="6 5", end=None)
    crow(s, lx + 204, ly + 8, (1, 0))
    s.text(lx + 220, ly + 12, "Reference by id, no FK (across modules)", size=12, fill=MUTED)
    for i, (k, lbl) in enumerate([("PK", "primary key"), ("FK", "foreign key"), ("UK", "unique")]):
        col = {"PK": "#B45309", "FK": "#1D4ED8", "UK": "#6D28D9"}[k]
        bgc = {"PK": "#FEF3C7", "FK": "#DBEAFE", "UK": "#EDE9FE"}[k]
        x0 = lx + i * 118
        s.add(f'<rect x="{x0}" y="{ly + 30}" width="24" height="15" rx="4" fill="{bgc}"/>')
        s.text(x0 + 12, ly + 41, k, size=9, weight=700, fill=col, anchor="middle")
        s.text(x0 + 32, ly + 42, lbl, size=11.5, fill=MUTED)

    laneY = 112
    lane_h = 1062

    def lane(name, key, x):
        c = SCHEMA[key]
        s.rect(x - 14, laneY, CARD_W + 28, lane_h, fill=tint(c, .955), stroke=tint(c, .7), sw=1, rx=14)
        s.text(x, laneY + 30, name.upper(), size=11, weight=750, fill=c, spacing=1.6)

    X = [40, 380, 720, 1060, 1400, 1740]
    lane("Reference", "ref", X[0])
    lane("Ordering", "ordering", X[1])
    lane("Planning", "planning", X[2])
    lane("Loading", "loading", X[3])
    lane("Execution", "execution", X[4])
    lane("Receipt and issues", "receipt", X[5])

    TOP = 160
    T = {}
    T["outlet"] = Table("ref", "outlet_registry", [("outlet_id", "text", "PK")], X[0], TOP)
    T["vehicle"] = Table("ref", "vehicle_registry", [("vehicle_id", "text", "PK")], X[0], 653)
    T["orders"] = Table("ordering", "orders", [
        ("order_id", "uuid", "PK"), ("order_ref", "text", "UK"), ("outlet_id", "text", "FK"),
        ("depot_code", "text", ""), ("brand_code", "text", ""), ("status", "text", ""),
        ("redelivery_of", "uuid", "FK"), ("trip_id", "uuid", ""), ("row_version", "bigint", ""),
    ], X[1], TOP)
    T["defer"] = Table("planning", "deferrals", [
        ("plan_id", "uuid", "PK"), ("order_id", "uuid", "PK"), ("rule_id", "text", ""),
        ("service_date", "date", ""), ("actor_id", "uuid", ""),
    ], X[2], TOP)
    T["runs"] = Table("planning", "runs", [
        ("plan_id", "uuid", "PK"), ("depot_code", "text", "UK"), ("service_date", "date", "UK"),
        ("plan_version", "integer", "UK"), ("status", "text", ""), ("rule_set_id", "uuid", "FK"),
        ("supersedes", "uuid", "FK"), ("row_version", "bigint", ""),
    ], X[2], 349)
    T["trips"] = Table("planning", "trips", [
        ("trip_id", "uuid", "PK"), ("plan_id", "uuid", "PK"), ("vehicle_id", "text", "FK"),
        ("trip_number", "smallint", "UK"), ("brand_code", "text", ""),
    ], X[2], 607)
    T["alloc"] = Table("planning", "allocations", [
        ("plan_id", "uuid", "PK"), ("order_id", "uuid", "PK"), ("trip_id", "uuid", "FK"),
        ("outlet_id", "text", "FK"), ("stop_sequence", "integer", "UK"),
    ], X[2], 796)
    T["ltrips"] = Table("loading", "trips", [
        ("trip_id", "uuid", "PK"), ("plan_version", "integer", "PK"), ("vehicle_id", "text", "FK"),
    ], X[3], TOP)
    T["lstops"] = Table("loading", "stops", [
        ("trip_id", "uuid", "PK"), ("plan_version", "integer", "PK"), ("order_id", "uuid", "PK"),
        ("outlet_id", "text", "FK"),
    ], X[3], 303)
    T["short"] = Table("loading", "shortfalls", [
        ("shortfall_id", "uuid", "PK"), ("trip_id", "uuid", "FK"), ("plan_version", "integer", "FK"),
        ("order_id", "uuid", "FK"),
    ], X[3], 469)
    T["etrips"] = Table("execution", "trips", [
        ("trip_id", "uuid", "PK"), ("plan_id", "uuid", ""), ("vehicle_id", "text", "FK"),
    ], X[4], TOP)
    T["deliv"] = Table("execution", "delivery_records", [
        ("delivery_id", "uuid", "PK"), ("trip_id", "uuid", "FK"), ("order_id", "uuid", "UK"),
        ("outlet_id", "text", "FK"), ("vehicle_id", "text", "FK"), ("proof_id", "uuid", "FK"),
    ], X[4], 607)
    T["proof"] = Table("execution", "proofs", [
        ("proof_id", "uuid", "PK"), ("delivery_id", "uuid", "FK"),
    ], X[4], 819)
    T["conf"] = Table("receipt", "confirmations", [
        ("receipt_id", "uuid", "PK"), ("delivery_id", "uuid", "UK"), ("order_id", "uuid", "UK"),
        ("outlet_id", "text", "FK"), ("status", "text", ""),
    ], X[5], 607)
    T["subj"] = Table("issues", "issue_subjects", [
        ("issue_id", "uuid", "PK"), ("subject_type", "text", "PK"), ("subject_id", "text", "PK"),
    ], X[5], 810)
    T["issue"] = Table("issues", "issues", [
        ("issue_id", "uuid", "PK"), ("issue_type", "text", ""), ("severity", "text", ""),
        ("status", "text", ""), ("outlet_id", "text", "FK"), ("row_version", "bigint", ""),
    ], X[5], 965)
    s.tables = T

    for t in T.values():
        draw_table(s, t)

    o, r, tr, al, de = T["orders"], T["runs"], T["trips"], T["alloc"], T["defer"]
    ou, ve = T["outlet"], T["vehicle"]
    lt, ls_, sh = T["ltrips"], T["lstops"], T["short"]
    et, dv, pf, cf, sj, iss = T["etrips"], T["deliv"], T["proof"], T["conf"], T["subj"], T["issue"]

    def y_of(t, col):
        return t.left(t.row_of(col))[1]

    def mid(a, b, dx=0):
        return (a.x + a.w / 2 + dx, (a.bottom()[1] + b.top()[1]) / 2)

    # ---- foreign keys (solid)
    y0, y1 = y_of(ou, "outlet_id"), y_of(o, "outlet_id")
    relation(s, [(ou.x + ou.w, y0), (o.x - 16, y0), (o.x - 16, y1), (o.x, y1)], True, True, "places", label_at=(ou.x + ou.w + 28, y0))
    yv = y_of(ve, "vehicle_id")
    relation(s, [(ve.x + ve.w, yv), (tr.x, y_of(tr, "vehicle_id"))], True, True, "drives", label_at=(ve.x + ve.w + 200, yv))
    relation(s, [r.top(), de.bottom()], True, True, "defers", label_at=mid(de, r, 34))
    relation(s, [r.bottom(), tr.top()], True, True, "has", label_at=mid(r, tr, 28))
    relation(s, [tr.bottom(), al.top()], True, True, "carries", label_at=mid(tr, al, 34))
    relation(s, [lt.bottom(), ls_.top()], True, True, "has", label_at=mid(lt, ls_, 28))
    relation(s, [ls_.bottom(), sh.top()], True, True, "flags", label_at=mid(ls_, sh, 28))
    relation(s, [et.bottom(), dv.top()], True, True, "records", label_at=(et.x + et.w / 2 + 40, 420))
    relation(s, [dv.bottom(), pf.top()], True, True, "proves", label_at=mid(dv, pf, 34))
    relation(s, [iss.top(), sj.bottom()], True, True, "about", label_at=mid(sj, iss, 28))

    # ---- id references across modules (dashed)
    ox = o.x + o.w
    yo = y_of(o, "order_id")
    trk = ox + 28
    yd, ya = y_of(de, "order_id"), y_of(al, "order_id")
    relation(s, [(ox, yo), (trk, yo), (trk, yd), (de.x, yd)], True, True, None, dashed=True)
    relation(s, [(ox, yo), (trk, yo), (trk, ya), (al.x, ya)], True, True, "order_id", dashed=True, label_at=(trk, 520))
    tx = tr.x + tr.w
    # planning trip -> loading trip
    t0 = y_of(tr, "trip_id")
    trk2 = tx + 30
    relation(s, [(tx, t0), (trk2, t0), (trk2, y_of(lt, "trip_id")), (lt.x, y_of(lt, "trip_id"))], True, True, "trip_id",
             dashed=True, label_at=(trk2, 520))
    # planning trip -> execution trip (across the free band under the loading lane)
    ty = y_of(tr, "plan_id")
    gate = dv.x - 28
    relation(s, [(tx, ty), (gate, ty), (gate, y_of(et, "trip_id")), (et.x, y_of(et, "trip_id"))], True, True, "trip_id",
             dashed=True, label_at=((tx + gate) / 2, ty))
    # delivery -> confirmation
    dx_ = dv.x + dv.w
    j = dx_ + 30
    yy_d, yy_c = y_of(dv, "delivery_id"), y_of(cf, "delivery_id")
    relation(s, [(dx_, yy_d), (j, yy_d), (j, yy_c), (cf.x, yy_c)], True, True, "delivery_id", dashed=True,
             label_at=(1701, yy_d - 18))
    # shortfall / failed delivery / dispute -> issue subject
    outer, inner = dx_ + 50, dx_ + 12
    ys = y_of(sh, "shortfall_id")
    relation(s, [(sh.x + sh.w, ys), (outer, ys), (outer, y_of(sj, "issue_id")), (sj.x, y_of(sj, "issue_id"))], True, True, "shortfall",
             dashed=True, label_at=(outer, 590))
    yf = y_of(dv, "vehicle_id")
    relation(s, [(dx_, yf), (inner, yf), (inner, y_of(sj, "subject_type")), (sj.x, y_of(sj, "subject_type"))], True, True, "delivery",
             dashed=True, label_at=(inner, 804))
    relation(s, [cf.bottom(), sj.top()], True, True, "receipt", dashed=True, label_at=mid(cf, sj, 34))

    # note under orders
    s.rect(o.x, 440, o.w, 118, fill="#FFFFFF", stroke=tint("#0D9488", .5), sw=1, rx=10, dash="5 4")
    s.text(o.x + 14, 464, "order_id is the thread", size=12.5, weight=700, fill="#0D9488")
    s.lines(o.x + 14, 484, ["Carried by allocations, deferrals,", "delivery_records and confirmations.",
                            "No cross-module foreign key, so one", "module's migration never waits on another."],
            size=11.5, gap=17)
    s.rect(o.x, 578, o.w, 76, fill="#FFFFFF", stroke=tint("#4F46E5", .5), sw=1, rx=10, dash="5 4")
    s.text(o.x + 14, 602, "trip_id fans out", size=12.5, weight=700, fill="#4F46E5")
    s.lines(o.x + 14, 622, ["Loading and execution each keep their", "own trips table, keyed by the same id."], size=11.5, gap=17)

    s.text(40, 1206, "Read left to right: a store places an order; planning allocates it to a trip on a vehicle or records a deferral; loading checks the trip's stops; the driver's delivery record and proof close the stop;",
           size=12, fill=MUTED)
    s.text(40, 1226, "the store's confirmation closes the order; a shortfall, failed delivery or dispute is recorded as an issue about that subject. Full per-schema diagrams and table dictionary: docs/data-model.md.",
           size=12, fill=MUTED)
    return s


# --------------------------------------------------------------------------- delivery-day flow

def fbox(s, x, y, w, h, title, rows, key, dashed=False):
    c = SCHEMA[key]
    s.rect(x, y, w, h, fill=CARD, stroke=c if not dashed else FAINT, sw=1.2, rx=10, dash="6 5" if dashed else None, shadow=not dashed)
    s.add(f'<rect x="{x}" y="{y}" width="5" height="{h}" rx="2.5" fill="{c}"/>')
    s.text(x + 18, y + 25, title, size=13.5, weight=650)
    s.lines(x + 18, y + 45, rows, size=11.5, gap=16)


def flow():
    W, H = 2100, 1330
    s = Svg(W, H)
    s.text(40, 54, "Waypoint Dispatch", size=30, weight=750)
    s.text(40, 80, "Delivery day flow: who does what, which event hands the work to the next role, and how the system recovers when things go wrong",
           size=14.5, fill=MUTED)

    lx, ly = 1500, 36
    s.path(f"M{lx},{ly + 8} L{lx + 40},{ly + 8}", end="arrow")
    s.text(lx + 50, ly + 12, "Hand-off to the next role", size=12, fill=MUTED)
    s.path(f"M{lx + 250},{ly + 8} L{lx + 290},{ly + 8}", dash="6 5", end="arrow")
    s.text(lx + 300, ly + 12, "Notification or exception", size=12, fill=MUTED)

    X0, CW = 200, 310
    stages = [("1  Order", "before 16:00"), ("2  Close", "16:00 cutoff"), ("3  Plan", "after the close"),
              ("4  Load", "at the dock"), ("5  Deliver", "on the road"), ("6  Receive", "at the outlet")]
    for i, (t, sub) in enumerate(stages):
        x = X0 + i * CW
        s.add(f'<path d="M{x},112 L{x + CW - 14},112 L{x + CW},131 L{x + CW - 14},150 L{x},150 L{x + 14},131 Z" fill="#E2E8F0"/>')
        s.text(x + 38, 136, t, size=13.5, weight=700)
        s.text(x + CW - 34, 136, sub, size=11, fill=MUTED, anchor="end")

    lanes = [("Store manager", "Desktop or phone", "ordering"), ("Dispatcher", "Desktop", "planning"),
             ("Loader", "Shared dock tablet", "loading"), ("Driver", "Personal phone", "execution")]
    LY0, LH, LG = 170, 150, 10
    for i, (n, d, k) in enumerate(lanes):
        y = LY0 + i * (LH + LG)
        c = SCHEMA[k]
        s.rect(40, y, 2020, LH, fill=tint(c, .955), stroke=tint(c, .75), sw=1, rx=12)
        s.rect(40, y, 150, LH, fill=tint(c, .86), stroke=tint(c, .75), sw=1, rx=12)
        s.text(56, y + LH / 2 - 4, n, size=14, weight=700, fill=c)
        s.text(56, y + LH / 2 + 16, d, size=11, fill=MUTED)

    BW, BH = 270, 96

    def pos(lane, col):
        return (X0 + col * CW + (CW - BW) / 2, LY0 + lane * (LH + LG) + (LH - BH) / 2)

    def cx(col): return X0 + col * CW + CW / 2
    def cy(lane): return LY0 + lane * (LH + LG) + LH / 2

    boxes = [
        (0, 0, "Place order", ["Follows the brand's delivery schedule.", "Stock is reserved with the warehouse."], "ordering", False),
        (1, 1, "Close orders", ["16:00 cutoff on the server clock", "(Asia/Colombo). One queue of orders."], "planning", False),
        (1, 2, "Plan and allocate", ["Capacity, temperature, outlet access,", "windows, fuel quota. Defers the rest", "with a reason. Review, then publish."], "planning", False),
        (0, 2, "Told why it was deferred", ["The reason shows on the store", "manager's order."], "ordering", True),
        (2, 3, "Load in stop order", ["Check every item, flag shortfalls,", "seal and release the trip."], "loading", False),
        (1, 3, "Resolve a shortfall", ["Raised as an issue; loading is", "unblocked once it is resolved."], "issues", True),
        (3, 4, "Run the route", ["Start stop, arrive, deliver, capture", "proof. Report road or vehicle faults."], "execution", False),
        (1, 4, "Track the day", ["Live map, ETAs, failed deliveries,", "vehicle status."], "planning", False),
        (0, 5, "Confirm receipt", ["Store shows a handover PIN; confirm,", "confirm partly, or dispute."], "receipt", False),
        (1, 5, "Resolve issues", ["Assign, decide, request redelivery."], "issues", False),
    ]
    P = {}
    for lane, col, t, rows, k, dashed in boxes:
        x, y = pos(lane, col)
        fbox(s, x, y, BW, BH, t, rows, k, dashed)
        P[(lane, col)] = (x, y)

    def L(lane, col): x, y = P[(lane, col)]; return (x, y + BH / 2)
    def R(lane, col): x, y = P[(lane, col)]; return (x + BW, y + BH / 2)
    def Tp(lane, col): x, y = P[(lane, col)]; return (x + BW / 2, y)
    def Bt(lane, col): x, y = P[(lane, col)]; return (x + BW / 2, y + BH)

    def elbow(a, b, xm=None, ym=None):
        if xm is not None:
            return f"M{a[0]},{a[1]} L{xm},{a[1]} L{xm},{b[1]} L{b[0]},{b[1]}"
        return f"M{a[0]},{a[1]} L{a[0]},{ym} L{b[0]},{ym} L{b[0]},{b[1]}"

    # 1 -> 2
    a, b = R(0, 0), L(1, 1)
    xm = (a[0] + b[0]) / 2
    s.path(elbow(a, b, xm=xm), end="arrow")
    s.pill(xm, (a[1] + b[1]) / 2, "orders.closed", size=10.5)
    # 2 -> 3
    s.path(f"M{R(1, 1)[0]},{R(1, 1)[1]} L{L(1, 2)[0]},{L(1, 2)[1]}", end="arrow")
    # plan -> deferral notice
    s.path(f"M{Tp(1, 2)[0]},{Tp(1, 2)[1]} L{Bt(0, 2)[0]},{Bt(0, 2)[1]}", end="arrow", dash="6 5")
    s.pill(Tp(1, 2)[0] + 70, (Tp(1, 2)[1] + Bt(0, 2)[1]) / 2, "order.deferred", size=10.5)
    # plan -> load
    a, b = R(1, 2), L(2, 3)
    xm = (a[0] + b[0]) / 2
    s.path(elbow(a, b, xm=xm), end="arrow")
    s.pill(xm, (a[1] + b[1]) / 2 + 16, "plan.published", size=10.5)
    # load <-> shortfall
    s.path(f"M{Tp(2, 3)[0]},{Tp(2, 3)[1]} L{Bt(1, 3)[0]},{Bt(1, 3)[1]}", end="arrow", start="arrow", dash="6 5")
    s.pill(Tp(2, 3)[0], (Tp(2, 3)[1] + Bt(1, 3)[1]) / 2, "loading.shortfall", size=10.5)
    # load -> drive
    a, b = R(2, 3), L(3, 4)
    xm = (a[0] + b[0]) / 2
    s.path(elbow(a, b, xm=xm), end="arrow")
    s.pill(xm, (a[1] + b[1]) / 2, "trip.released", size=10.5)
    # drive -> track the day
    s.path(f"M{Tp(3, 4)[0]},{Tp(3, 4)[1]} L{Bt(1, 4)[0]},{Bt(1, 4)[1]}", end="arrow", dash="6 5")
    s.pill(Tp(3, 4)[0], (Tp(3, 4)[1] + Bt(1, 4)[1]) / 2 + 6, "eta.changed, delivery.failed", size=10.5)
    # drive -> receive
    a, b = R(3, 4), L(0, 5)
    xm = a[0] + 20
    s.path(elbow(a, b, xm=xm), end="arrow")
    s.pill(xm, cy(2) + 50, "delivery.completed", size=10.5)
    # confirm -> resolve
    s.path(f"M{Bt(0, 5)[0]},{Bt(0, 5)[1]} L{Tp(1, 5)[0]},{Tp(1, 5)[1]}", end="arrow", dash="6 5")
    s.pill(Bt(0, 5)[0], (Bt(0, 5)[1] + Tp(1, 5)[1]) / 2, "receipt.disputed", size=10.5)
    # redelivery loop back to the start
    a = R(1, 5)
    top_y = 160
    d = f"M{a[0]},{a[1]} L{2056},{a[1]} L{2056},{top_y} L{Tp(0, 0)[0]},{top_y} L{Tp(0, 0)[0]},{Tp(0, 0)[1]}"
    s.path(d, end="arrow", dash="6 5")
    s.pill(1180, top_y, "redelivery.requested: the order goes round again as a new order", size=10.5)

    s.text(X0 + 20, LY0 + 3 * (LH + LG) + LH - 14, "Every event above also reaches the right role as an in-app notification and a web push.",
           size=11, fill=FAINT)

    # ---------------- bottom panels
    PY, PH = 830, 450
    def panel(x, w, title, sub):
        s.rect(x, PY, w, PH, fill="#FFFFFF", stroke="#94A3B8", sw=1.3, rx=14, shadow=True)
        s.add(f'<rect x="{x}" y="{PY}" width="{w}" height="38" rx="14" fill="#E2E8F0"/>')
        s.add(f'<rect x="{x}" y="{PY + 22}" width="{w}" height="16" fill="#E2E8F0"/>')
        s.text(x + 20, PY + 25, title, size=14, weight=700)
        s.text(x + w - 20, PY + 25, sub, size=11.5, fill=MUTED, anchor="end")

    ax, aw = 40, 1000
    panel(ax, aw, "Offline and recovery", "driver works fully offline  ·  loader and store manager queue retries  ·  dispatcher is online only")
    steps = [
        ("Write with no signal", ["Stops, arrivals, proof and", "reports are saved on the phone."], "execution"),
        ("Offline write queue", ["IndexedDB. Each command keeps", "its id and expected version."], "sync"),
        ("Connection returns", ["The queue is sent as one batch:", "POST /api/sync."], "sync"),
        ("Replay via CommandBus", ["Same policy, scope, idempotency", "and version checks as online."], "integration"),
    ]
    sw_, sg = 218, 30
    sy = PY + 62
    for i, (t, rows, k) in enumerate(steps):
        x = ax + 22 + i * (sw_ + sg)
        fbox(s, x, sy, sw_, 92, t, rows, k)
        if i < len(steps) - 1:
            s.path(f"M{x + sw_ + 2},{sy + 46} L{x + sw_ + sg - 2},{sy + 46}", end="arrow")
    outs = [("APPLIED", "#16A34A"), ("CONFLICT: resolve or discard", "#D97706"), ("REJECTED: rule shown", "#E11D48")]
    s.text(ax + 22, sy + 128, "Outcome of each replayed command:", size=11.5, weight=650, fill=MUTED)
    chip_x = ax + 270
    for t, c in outs:
        w = len(t) * 6.6 + 26
        s.add(f'<rect x="{chip_x}" y="{sy + 111}" width="{w}" height="26" rx="13" fill="{tint(c, .86)}" stroke="{c}" stroke-width="1"/>')
        s.text(chip_x + w / 2, sy + 128, t, size=11, weight=650, fill=c, anchor="middle")
        chip_x += w + 12
    s.text(ax + 22, sy + 162, "A replay never overwrites silently: a stale expectedVersion is a CONFLICT the person resolves or discards.", size=11.5, fill=MUTED)

    s.text(ax + 22, PY + 262, "WHEN A DEPENDENCY FAILS", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    deg = [
        ("Warehouse slow or down", "Orders wait as stock_unknown and are retried; stock still unresolved at the cutoff auto-defers with reason stock_unresolved."),
        ("ML service down", "Plans fall back to deterministic estimates and say so on every plan."),
        ("Push keys missing", "Web push turns off visibly; in-app notifications still arrive."),
        ("Duplicate request", "The idempotency receipt answers a repeated command from its first result."),
        ("Dispatcher offline", "Planning stays online-only: two offline plans would be irreconcilable."),
    ]
    for i, (t, d) in enumerate(deg):
        yy = PY + 288 + i * 30
        s.add(f'<circle cx="{ax + 30}" cy="{yy - 4}" r="3" fill="{SCHEMA["issues"]}"/>')
        s.text(ax + 44, yy, t, size=12, weight=650)
        s.text(ax + 250, yy, d, size=11.5, fill=MUTED)

    bx, bw_ = 1070, 990
    panel(bx, bw_, "Order status lifecycle", "terminal states have a heavy border")
    def chip(cxx, yy, label, w=None, terminal=False, colour="#0D9488"):
        w = w or len(label) * 7.2 + 28
        s.add(f'<rect x="{cxx - w / 2}" y="{yy}" width="{w}" height="34" rx="17" fill="{tint(colour, .88)}" '
              f'stroke="{colour}" stroke-width="{2.6 if terminal else 1.2}"/>')
        s.text(cxx, yy + 22, label, size=12, weight=650, fill=INK, anchor="middle", family=MONO)
        return w
    my = PY + 150
    main = [("confirmed", False), ("allocated", False), ("loading", False), ("in_transit", False), ("delivered", False), ("received", True)]
    step = 160
    mx0 = bx + 100
    cols = {}
    for i, (n, term) in enumerate(main):
        cols[n] = mx0 + i * step
        chip(cols[n], my, n, w=140, terminal=term)
        if i < len(main) - 1:
            s.path(f"M{cols[n] + 72},{my + 17} L{mx0 + (i + 1) * step - 72},{my + 17}", end="arrow", sw=1.4)
    # entry states
    chip(cols["confirmed"] + 60, my - 78, "stock_unknown or partially_reserved", w=300, colour="#64748B")
    s.path(f"M{cols['confirmed']},{my - 44} L{cols['confirmed']},{my - 1}", end="arrow", sw=1.4)
    s.text(cols["confirmed"] + 12, my - 18, "warehouse confirms stock", size=10.5, fill=MUTED)
    # branches
    by = my + 112
    chip(cols["confirmed"], by, "deferred", w=140, colour="#D97706")
    chip(cols["allocated"], by, "unservable", w=140, colour="#E11D48")
    chip(cols["in_transit"] - 20, by, "failed", w=120, terminal=True, colour="#E11D48")
    chip(cols["delivered"] - 10, by, "partially_delivered", w=172, colour="#D97706")
    chip(cols["received"] - 10, by, "unconfirmed", w=130, terminal=True, colour="#64748B")
    s.path(f"M{cols['confirmed']},{my + 34} L{cols['confirmed']},{by - 1}", end="arrow", sw=1.4, dash="6 5")
    s.path(f"M{cols['allocated']},{my + 34} L{cols['allocated']},{by - 1}", end="arrow", sw=1.4, dash="6 5")
    s.path(f"M{cols['in_transit'] - 20},{my + 34} L{cols['in_transit'] - 20},{by - 1}", end="arrow", sw=1.4, dash="6 5")
    s.path(f"M{cols['in_transit'] + 40},{my + 34} L{cols['in_transit'] + 40},{by - 40} L{cols['delivered'] - 10},{by - 40} L{cols['delivered'] - 10},{by - 1}", end="arrow", sw=1.4, dash="6 5")
    s.path(f"M{cols['delivered'] + 40},{my + 34} L{cols['delivered'] + 40},{by - 22} L{cols['received'] - 10},{by - 22} L{cols['received'] - 10},{by - 1}", end="arrow", sw=1.4, dash="6 5")
    notes = [
        "Solid arrows are the normal path; dashed arrows are the main exits. A deferred order is planned again on the next run.",
        "partially_delivered and delivered both end as received (store confirmed) or unconfirmed (no confirmation in time).",
        "Legal moves are enforced in one place, OrderStateMachine, and every change is written to order_status_history.",
    ]
    for i, n in enumerate(notes):
        yy = PY + 340 + i * 26
        s.add(f'<circle cx="{bx + 30}" cy="{yy - 4}" r="2.5" fill="{FAINT}"/>')
        s.text(bx + 44, yy, n, size=11.5, fill=MUTED)
    s.text(40, 1310, "Sources: docs/architecture/MODULES.md (events), OrderStateMachine, sync.operations statuses, RULES-AND-POLICIES.md (R-ORD-01, R-STK-06), frontend offline tiers.",
           size=11, fill=FAINT)
    return s


# --------------------------------------------------------------------------- architecture (icon style)


G = {  # 24x24 line glyphs, drawn white on a coloured tile
 "laptop": '<rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 19h20"/>',
 "phone": '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/>',
 "tablet": '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M11 17h2"/>',
 "shield": '<path d="M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6z"/><path d="M9.5 12l2 2 3.5-4"/>',
 "window": '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M6.5 6.5h.01M9 6.5h.01"/>',
 "server": '<rect x="4" y="4" width="16" height="6" rx="1.5"/><rect x="4" y="14" width="16" height="6" rx="1.5"/><path d="M8 7h.01M8 17h.01"/>',
 "db": '<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6"/><path d="M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>',
 "chip": '<rect x="7" y="7" width="10" height="10" rx="1.5"/><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>',
 "plug": '<path d="M9 3v5M15 3v5"/><path d="M6 8h12v3a6 6 0 0 1-12 0z"/><path d="M12 17v4"/>',
 "cloud": '<path d="M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9.5 4.3 4.3 0 0 1 17.5 18z"/>',
 "bell": '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 21h4"/>',
 "chart": '<path d="M5 20V11M11 20V4M17 20v-6M2 20h20"/>',
 "refresh": '<path d="M20 8a8 8 0 0 0-14-2L4 8"/><path d="M4 4v4h4"/><path d="M4 16a8 8 0 0 0 14 2l2-2"/><path d="M20 20v-4h-4"/>',
 "tray": '<path d="M3 13l3-8h12l3 8"/><path d="M3 13v6h18v-6h-5a4 4 0 0 1-8 0z"/>',
 "clip": '<rect x="6" y="4" width="12" height="17" rx="2"/><path d="M9 4V3h6v1M9 10h6M9 14h6"/>',
 "route": '<circle cx="6" cy="6" r="2"/><circle cx="18" cy="18" r="2"/><path d="M8 6h6a3 3 0 0 1 0 6h-4a3 3 0 0 0 0 6h6"/>',
 "box": '<path d="M3 8l9-5 9 5v8l-9 5-9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/>',
 "truck": '<rect x="2" y="6" width="12" height="10" rx="1"/><path d="M14 9h4l4 4v3h-8z"/><circle cx="7" cy="18" r="2"/><circle cx="18" cy="18" r="2"/>',
 "ok": '<circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/>',
 "alert": '<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18h.01"/>',
 "key": '<circle cx="8" cy="14" r="4"/><path d="M11 11l9-8M16 6l3 3"/>',
 "book": '<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/><path d="M5 17a3 3 0 0 1 3-3h11"/>',
 "building": '<path d="M3 21V10l9-6 9 6v11"/><path d="M8 21v-7h8v7"/>',
 "cog": '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>',
 "spark": '<path d="M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z"/>',
 "lock": '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
 "layers": '<path d="M12 3l9 5-9 5-9-5z"/><path d="M3 13l9 5 9-5"/>',
 "monitor": '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4M6 13l3-3 3 2 5-5"/>',
 "branch": '<circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="9" r="2"/><path d="M6 7v10M18 11c0 4-6 3-12 6"/>',
 "doc": '<path d="M7 3h8l4 4v14H7z"/><path d="M15 3v4h4M10 12h6M10 16h6"/>',
 "flag": '<path d="M5 21V4M5 4h11l-2 4 2 4H5"/>',
}


def tile(s, x, y, size, colour, glyph, badge=None, dashed=False):
    """AWS-style icon tile: coloured rounded square with a white line glyph."""
    s.add(f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{size * .18}" fill="{colour}" filter="url(#sh)"/>')
    sc = size * .56 / 24
    off = (size - 24 * sc) / 2
    s.add(f'<g transform="translate({x + off},{y + off}) scale({sc})" fill="none" stroke="#FFFFFF" stroke-width="{1.75}" '
          f'stroke-linecap="round" stroke-linejoin="round">{G[glyph]}</g>')
    if badge:
        badgec(s, x - 4, y - 4, badge)


def badgec(s, cx, cy, n, r=11):
    s.add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="#1D4ED8" stroke="#FFFFFF" stroke-width="2"/>')
    s.text(cx, cy + 4.5, str(n), size=12, weight=750, fill="#FFFFFF", anchor="middle")


def label(s, cx, y, title, sub=None, size=12.5, w=None):
    s.text(cx, y, title, size=size, weight=650, anchor="middle")
    if sub:
        for i, row in enumerate(sub if isinstance(sub, list) else [sub]):
            s.text(cx, y + 15 + i * 14, row, size=10.5, fill=MUTED, anchor="middle")


def zone(s, x, y, w, h, title, colour="#94A3B8", dashed=True, fill=None, glyph="flag"):
    s.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{fill or "none"}" stroke="{colour}" '
          f'stroke-width="1.6"{" stroke-dasharray=\"7 6\"" if dashed else ""}/>')
    tw = len(title) * 7.2 + 44
    s.add(f'<rect x="{x + 14}" y="{y - 13}" width="{tw}" height="26" rx="13" fill="{BG}" stroke="{colour}" stroke-width="1.2"/>')
    s.add(f'<g transform="translate({x + 22},{y - 8}) scale(.62)" fill="none" stroke="{colour}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">{G[glyph]}</g>')
    s.text(x + 40, y + 4.5, title, size=12, weight=700, fill=colour)


def arrow(s, pts, dash=None, both=False, colour=LINE, sw=1.9):
    d = "M" + " L".join(f"{a},{b}" for a, b in pts)
    s.path(d, stroke=colour, sw=sw, dash=dash, end="arrow", start="arrow" if both else None)


def tag(s, x, y, text, size=10.5):
    s.pill(x, y, text, size=size)


def architecture_icons():
    W, H = 2300, 1330
    s = Svg(W, H)
    s.text(40, 54, "Waypoint Dispatch", size=30, weight=750)
    s.text(40, 80, "System architecture: one responsive web app for four field roles, a modular Spring Boot monolith, and PostgreSQL as the single source of truth", size=14.5, fill=MUTED)

    # ------------------------------------------------ zone: people
    zone(s, 40, 130, 250, 730, "People and devices", "#0D9488")
    ppl = [("Store manager", "Desktop or phone", "laptop", "ordering"), ("Dispatcher", "Desktop", "laptop", "planning"),
           ("Loader", "Shared dock tablet", "tablet", "loading"), ("Driver", "Personal phone, offline", "phone", "execution"),
           ("Admin and auditor", "Desktop", "laptop", "iam")]
    py = {}
    for i, (n, d, g, k) in enumerate(ppl):
        y = 180 + i * 136
        tile(s, 135, y, 60, SCHEMA[k], g)
        label(s, 165, y + 82, n, d)
        py[n] = y + 30

    # legend: numbered request flow
    s.rect(40, 890, 250, 330, fill="#FFFFFF", stroke=BORDER, rx=12)
    s.text(56, 916, "How a request flows", size=12.5, weight=700)
    steps = ["HTTPS to nginx (TLS)", "Proxied by the Next.js server", "/api/* reaches the backend",
             "One transaction in PostgreSQL", "Events fan out through the outbox", "Scores, stock and push leave the box",
             "AI assistants enter through MCP"]
    for i, t in enumerate(steps):
        yy = 946 + i * 36
        badgec(s, 68, yy - 4, i + 1, r=10)
        s.text(88, yy, t, size=11, fill=MUTED)

    # ------------------------------------------------ platform zone
    zone(s, 330, 130, 1660, 1090, "Waypoint platform: docker compose up", "#2563EB", fill="#FFFFFF")

    # edge
    zone(s, 360, 200, 240, 560, "Edge", "#64748B")
    tile(s, 450, 250, 60, "#475569", "shield")
    label(s, 480, 332, "nginx", ["TLS reverse proxy", "production and VPS"])
    tile(s, 450, 450, 60, "#2563EB", "window")
    label(s, 480, 532, "Next.js server", ["serves the PWA", "same-origin /api proxy"])
    arrow(s, [(480, 372 + 6), (480, 450)])
    tile(s, 450, 626, 60, "#7C3AED", "refresh")
    label(s, 480, 708, "Service worker", ["offline shell + write queue", "(runs in the browser)"])
    arrow(s, [(480, 584), (480, 626)], dash="6 5", both=True)

    # application
    zone(s, 640, 200, 880, 560, "Application: Spring Boot modular monolith, one process", "#4F46E5")
    tile(s, 680, 244, 52, "#334155", "window")
    label(s, 706, 316, "REST API", ["controllers"])
    tile(s, 800, 244, 52, "#0F172A", "cog")
    label(s, 826, 316, "CommandBus", ["policy and scope, idempotency,", "version check, audit"], w=200)
    arrow(s, [(732, 270), (800, 270)])
    s.text(1150, 262, "The one write path, online or replayed from the offline queue.", size=11.5, fill=MUTED)
    s.text(1150, 280, "A refusal is an RFC 9457 problem; a stale version is a 409.", size=11.5, fill=MUTED)

    s.text(680, 372, "DELIVERY FLOW", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    flow = [("Ordering", "clip", "ordering"), ("Planning", "route", "planning"), ("Loading", "box", "loading"),
            ("Execution", "truck", "execution"), ("Receipt", "ok", "receipt"), ("Issues", "alert", "issues")]
    for i, (n, g, k) in enumerate(flow):
        x = 680 + i * 138
        tile(s, x, 384, 50, SCHEMA[k], g)
        label(s, x + 25, 456, n, size=12)
        if i < len(flow) - 1:
            arrow(s, [(x + 54, 409), (x + 134, 409)], sw=1.4)
    s.text(680, 506, "SUPPORTING MODULES", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    sup = [("Identity", "key", "iam"), ("Reference", "book", "ref"), ("Warehouse", "building", "warehouse"),
           ("Notification", "bell", "notification"), ("Sync", "refresh", "sync"), ("Intelligence", "chip", "ml")]
    for i, (n, g, k) in enumerate(sup):
        x = 680 + i * 138
        tile(s, x, 518, 50, SCHEMA[k], g)
        label(s, x + 25, 590, n, size=12)
    s.rect(670, 624, 840, 112, fill="#F1F5F9", stroke="#CBD5E1", rx=10)
    s.text(684, 644, "PLATFORM", size=10.5, weight=700, fill=MUTED, spacing=1.4)
    plat = [("Outbox relay", "tray"), ("Consumer inbox", "tray"), ("Audit log", "doc"), ("Scheduled jobs", "cog")]
    for i, (n, g) in enumerate(plat):
        x = 700 + i * 200
        tile(s, x, 670, 40, "#334155", g, badge=5 if i == 0 else None)
        s.text(x + 52, 693, n, size=12, weight=650)
        s.text(x + 52, 709, ["at-least-once", "idempotent", "every decision", "cutoff, retries"][i], size=10.5, fill=MUTED)

    # intelligence and agents
    zone(s, 1560, 200, 400, 300, "Intelligence and agents", "#9333EA")
    tile(s, 1600, 260, 60, SCHEMA["ml"], "chip")
    label(s, 1630, 342, "ML service", ["FastAPI, trained models", "lateness, service time,", "demand forecast"])
    tile(s, 1790, 260, 60, "#7C3AED", "plug")
    label(s, 1820, 342, "MCP adapter", ["Node.js, OAuth, scopes", "reads and confirmed", "issue writes"])
    s.text(1600, 456, "Deterministic fallback if ML is down.", size=10.5, fill=MUTED)
    s.text(1600, 472, "MCP is off unless MCP_ENABLED is set.", size=10.5, fill=MUTED)

    # data layer
    zone(s, 640, 800, 880, 360, "Data layer", "#16A34A")
    tile(s, 690, 850, 76, "#336791", "db")
    label(s, 728, 950, "PostgreSQL 16", ["compose: db container", "production: managed"])
    schemas = ["ref", "iam", "ordering", "warehouse", "planning", "loading", "execution", "receipt", "issues", "notification", "sync", "ml", "integration"]
    cx, cy = 830, 860
    for k in schemas:
        w = s.chip(cx, cy, k, SCHEMA[k], size=11.5, h=24)
        cx += w + 8
        if cx > 1480:
            cx, cy = 830, cy + 32
    facts = ["99 tables in 13 schemas, one per module; foreign keys only into ref and iam",
             "Forced row-level security by depot, outlet or actor; module roles hold no DELETE",
             "The app logs in as waypoint_app and adopts one module role per transaction",
             "Outbox, inbox, audit, proof photos and signatures live in the same database"]
    for i, f in enumerate(facts):
        s.add(f'<circle cx="840" cy="{934 + i * 24}" r="2.5" fill="{FAINT}"/>')
        s.text(852, 938 + i * 24, f, size=11.5, fill=MUTED)

    for i, (n, g, sub) in enumerate([("Outbox and inbox", "tray", ["events delivered at least", "once, applied once"]),
                                     ("Audit log", "doc", ["monthly partitions,", "every decision recorded"]),
                                     ("Proof store", "lock", ["delivery photos and", "signatures, in the database"])]):
        x = 860 + i * 210
        tile(s, x, 1040, 44, "#16A34A", g)
        s.text(x + 56, 1058, n, size=12, weight=650)
        s.text(x + 56, 1074, sub[0], size=10.5, fill=MUTED)
        s.text(x + 56, 1088, sub[1], size=10.5, fill=MUTED)

    # start-up and ops
    zone(s, 1560, 800, 400, 150, "Start-up", "#475569")
    tile(s, 1596, 840, 52, "#0F172A", "cog")
    label(s, 1622, 916, "init job", size=12)
    s.text(1666, 860, "migrate, import reference", size=11, fill=MUTED)
    s.text(1666, 876, "data, create demo accounts,", size=11, fill=MUTED)
    s.text(1666, 892, "seed a delivery day.", size=11, fill=MUTED)
    zone(s, 1560, 990, 400, 170, "Observability (opt-in)", "#475569")
    obs = [("Alloy", "refresh"), ("Loki", "doc"), ("Prometheus", "chart"), ("Grafana", "monitor")]
    for i, (n, g) in enumerate(obs):
        x = 1584 + i * 94
        tile(s, x, 1036, 44, "#64748B", g)
        label(s, x + 22, 1104, n, size=11)
    s.text(1584, 1140, "docker compose --profile observability", size=10, fill=FAINT, family=MONO)

    # ------------------------------------------------ external
    zone(s, 2030, 130, 230, 730, "Outside the platform", "#64748B")
    tile(s, 2115, 190, 60, "#9333EA", "spark")
    label(s, 2145, 272, "AI assistants", ["Claude, ChatGPT,", "and others"])
    tile(s, 2115, 480, 60, SCHEMA["warehouse"], "building")
    label(s, 2145, 562, "Warehouse API", ["stock and catalogue", "circuit breaker, cache"])
    tile(s, 2115, 670, 60, SCHEMA["notification"], "bell")
    label(s, 2145, 752, "Browser push", ["Web Push, VAPID"])

    # ------------------------------------------------ flows
    # 1 people -> nginx
    arrow(s, [(290, 460), (360, 460)], sw=2.2)
    badgec(s, 325, 460, 1)
    # people tiles bus
    s.path("M 262,210 L 262,830", stroke=FAINT, sw=1.4, end=None)
    for n, y in py.items():
        s.path(f"M 195,{y} L 262,{y}", stroke=FAINT, sw=1.4, end=None)
    s.path("M 262,460 L 290,460", stroke=LINE, sw=1.9, end=None)
    # 2 nginx -> next
    badgec(s, 496, 410, 2)
    # 3 edge -> app
    arrow(s, [(600, 480), (640, 480)], sw=2.2)
    badgec(s, 620, 480, 3)
    tag(s, 620, 506, "/api/*", size=9.5)
    # 4 app -> db
    arrow(s, [(900, 760), (900, 800)], sw=2.2)
    badgec(s, 900, 780, 4)
    tag(s, 1000, 780, "module role + RLS", size=10)
    # 5 outbox badge placed on tile; relay -> subscribers
    # 6 app -> ML, warehouse, push
    arrow(s, [(1520, 290), (1598, 290)], sw=2.0)
    badgec(s, 1545, 290, 6)
    arrow(s, [(1520, 585), (2030, 585)], sw=2.0)
    tag(s, 1780, 571, "StockPort", size=10)
    badgec(s, 1700, 585, 6)
    arrow(s, [(1520, 700), (2030, 700)], sw=2.0)
    tag(s, 1780, 686, "Web Push", size=10)
    badgec(s, 1700, 700, 6)
    # 7 AI -> MCP -> backend
    arrow(s, [(2030, 290), (1850, 290)], sw=2.0)
    badgec(s, 1940, 290, 7)
    tag(s, 1940, 268, "MCP, OAuth", size=10)
    arrow(s, [(1820, 396), (1820, 424), (1520, 424)], dash="6 5", sw=1.8)
    tag(s, 1670, 410, "reads, confirmed writes", size=9.5)
    # init -> db
    arrow(s, [(1560, 880), (1520, 880)], sw=1.9)
    # observability
    arrow(s, [(1560, 1066), (1540, 1066), (1540, 730), (1520, 730)], dash="6 5", sw=1.5)
    tag(s, 1540, 960, "logs, metrics", size=9.5)

    # ------------------------------------------------ delivery pipeline band
    zone(s, 330, 1250, 1660, 62, "Delivery pipeline", "#475569", dashed=True)
    pipe = [("Pull request", "branch"), ("Checks: MCP, model, backend tests", "ok"), ("dev: preview deploy", "cloud"), ("main: production deploy", "server")]
    x = 362
    for i, (t, g) in enumerate(pipe):
        tile(s, x, 1262, 34, "#475569", g)
        s.text(x + 44, 1284, t, size=11.5, weight=600)
        if i < len(pipe) - 1:
            w = len(t) * 6.2 + 60
            arrow(s, [(x + w - 16, 1279), (x + w + 22, 1279)], sw=1.5)
            x += w + 40
        else:
            pass
    s.text(1500, 1284, "GitHub Actions, deployed to one VPS with Docker Compose", size=11.5, fill=MUTED)
    return s


# --------------------------------------------------------------------------- render


LOGO_DIR = Path(os.environ.get("LOGO_DIR") or Path(__file__).resolve().parent.parent / "docs" / "img" / "logo")
LOGO_COLOUR = {"nextdotjs": "#111111", "react": "#149ECA", "postgresql": "#336791", "springboot": "#5FA12F",
               "fastapi": "#009688", "nodedotjs": "#5FA04E", "nginx": "#009639", "docker": "#2496ED",
               "grafana": "#F46800", "prometheus": "#E6522C", "neon": "#00A88F"}


def logo(s, name, x, y, size=26):
    """Embeds a Simple Icons SVG (single path, 24x24) at x,y. Silently skipped if the file is missing."""
    import re
    f = LOGO_DIR / f"{name}.svg"
    if not f.exists():
        return
    inner = re.sub(r"<title>.*?</title>", "", f.read_text(encoding="utf-8"), flags=re.S)
    inner = re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", inner, flags=re.S)
    s.add(f'<svg x="{x}" y="{y}" width="{size}" height="{size}" viewBox="0 0 24 24" fill="{LOGO_COLOUR[name]}">{inner}</svg>')


def titled(s, cx, ty, title, names, size=15, ls=26):
    """Bold title centred at cx with logos to its left."""
    tw = len(title) * size * 0.58
    left = cx - tw / 2 - (ls + 8) * len(names)
    x = left
    for n in names:
        logo(s, n, x, ty - ls + 6, ls); x += ls + 8
    return

# --- plain architecture diagram ---
A_INK = "#1F2933"; A_MUTE = "#52606D"; A_LINE = "#7B8794"; A_ACC = "#1D4ED8"; 
A_FILL = "#F5F7FA"; A_EDGE = "#CBD2D9"

def box(s, x, y, w, h, title, sub=None, fill=A_FILL, stroke=A_INK, dash=None, tsize=17, sw=1.6):
    s.rect(x, y, w, h, fill=fill, stroke=stroke, sw=sw, rx=4, dash=dash)
    if sub is None:
        s.text(x + w / 2, y + h / 2 + 6, title, size=tsize, weight=600, fill=A_INK, anchor="middle")
    else:
        s.text(x + w / 2, y + 30, title, size=tsize, weight=600, fill=A_INK, anchor="middle")
        for i, r in enumerate(sub):
            s.text(x + w / 2, y + 56 + i * 21, r, size=14, fill=A_MUTE, anchor="middle")

def label(s, x, y, t, w=None):
    w = w or len(t) * 7.6 + 14
    s.rect(x - w / 2, y - 12, w, 22, fill=BG, stroke="none", sw=0, rx=2)
    s.text(x, y + 4, t, size=13, fill=A_MUTE, anchor="middle")

def arrow(s, d, dash=None, colour=A_LINE, sw=1.8):
    s.path(d, stroke=colour, sw=sw, dash=dash)

def cylinder(s, x, y, w, h, title, sub):
    e = 18
    s.add(f'<path d="M{x},{y+e} L{x},{y+h-e} A{w/2},{e} 0 0 0 {x+w},{y+h-e} L{x+w},{y+e} Z" fill="{A_FILL}" stroke="{A_INK}" stroke-width="1.6"/>')
    s.add(f'<ellipse cx="{x+w/2}" cy="{y+e}" rx="{w/2}" ry="{e}" fill="#FFFFFF" stroke="{A_INK}" stroke-width="1.6"/>')
    s.text(x + w / 2, y + 112, title, size=17, weight=600, anchor="middle")
    for i, r in enumerate(sub):
        s.text(x + w / 2, y + 138 + i * 21, r, size=14, fill=A_MUTE, anchor="middle")

def architecture_plain():
    s = Svg(1800, 1040)
    s.text(40, 52, "Waypoint Dispatch: system architecture", size=28, weight=700)
    

    # users
    box(s, 40, 170, 210, 160, "Users", ["Store manager", "Dispatcher", "Loader", "Driver", "Admin and auditor"])
    box(s, 40, 600, 210, 80, "AI assistant")

    # browser
    s.rect(330, 150, 320, 340, fill=BG, stroke=A_INK, sw=1.6, rx=4)
    s.text(490, 182, "Browser (PWA)", size=17, weight=700, anchor="middle")
    box(s, 350, 200, 280, 80, "Next.js and React", ["Role screens"], tsize=15)
    box(s, 350, 300, 280, 80, "Service worker", ["Caches the app shell"], tsize=15)
    box(s, 350, 400, 280, 70, "Offline queue", ["IndexedDB, replayed on reconnect"], tsize=15)

    # edge
    s.rect(730, 150, 230, 340, fill=BG, stroke=A_INK, sw=1.6, rx=4)
    s.text(845, 182, "Server edge", size=17, weight=700, anchor="middle")
    box(s, 750, 200, 190, 80, "nginx", ["TLS proxy, production"], tsize=15)
    box(s, 750, 320, 190, 110, "Next.js server", ["Serves the PWA, proxies", "/api to the backend and", "/mcp to the MCP adapter"], tsize=15)
    box(s, 750, 600, 190, 80, "MCP adapter", ["Node, OAuth, optional"], tsize=15, dash="6 4", stroke=A_LINE)

    # backend
    s.rect(1050, 150, 460, 610, fill=BG, stroke=A_INK, sw=1.6, rx=4)
    s.text(1280, 182, "Spring Boot backend", size=17, weight=700, anchor="middle")
    box(s, 1070, 200, 420, 52, "REST API", tsize=15)
    box(s, 1070, 270, 420, 80, "CommandBus: the one write path", ["Policy and scope, idempotency, version check"], tsize=15)
    s.rect(1070, 370, 420, 260, fill=BG, stroke=A_EDGE, sw=1.4, rx=4)
    s.text(1090, 398, "13 modules, one schema each", size=14, weight=600, fill=A_MUTE)
    left = ["Ordering", "Planning", "Loading", "Execution", "Receipt", "Issues"]
    right = ["Identity", "Reference data", "Warehouse", "Notification", "Sync", "Intelligence", "Messaging"]
    for i, t in enumerate(left):
        s.text(1100, 432 + i * 31, t, size=15, fill=A_INK)
    for i, t in enumerate(right):
        s.text(1290, 432 + i * 31, t, size=15, fill=A_INK)
    box(s, 1070, 650, 420, 90, "Platform", ["Outbox relay, inbox, audit log, scheduled jobs"], tsize=15)

    # data
    cylinder(s, 1610, 270, 150, 250, "PostgreSQL 16", ["14 schemas", "106 tables", "Row-level security"])

    # side services
    box(s, 1070, 850, 200, 90, "ML service", ["FastAPI models, read-only;", "fallback if it is down"], tsize=15)
    box(s, 1290, 850, 200, 90, "Warehouse API", ["Stock and catalogue", "(external)"], tsize=15)
    box(s, 1510, 850, 200, 90, "Web Push", ["VAPID, browser", "notifications"], tsize=15)
    box(s, 750, 850, 220, 90, "Observability", ["Alloy, Loki, Prometheus,", "Grafana (opt-in)"], tsize=15, dash="6 4", stroke=A_LINE)

    # arrows (main path in accent)
    arrow(s, "M250,250 L350,250", colour=A_ACC)
    arrow(s, "M650,240 L750,240", colour=A_ACC)
    arrow(s, "M845,280 L845,320", colour=A_ACC)
    arrow(s, "M940,375 L1050,375", colour=A_ACC)
    arrow(s, "M1510,395 L1610,395", colour=A_ACC)
    label(s, 300, 236, "HTTPS"); label(s, 995, 361, "/api")
    label(s, 1560, 381, "SQL")
    arrow(s, "M250,640 L750,640", dash="6 4"); label(s, 500, 626, "MCP, OAuth")
    arrow(s, "M940,640 L1050,640", dash="6 4"); label(s, 995, 626, "API")
    arrow(s, "M845,430 L845,600", dash="6 4"); label(s, 845, 505, "/mcp")
    for x, t in [(1170, "forecasts"), (1390, "stock")]:
        arrow(s, f"M{x},760 L{x},850"); label(s, x, 805, t)
    arrow(s, "M1480,760 L1480,805 L1610,805 L1610,850"); label(s, 1545, 805, "push")
    arrow(s, "M1050,720 L970,880", dash="6 4"); label(s, 1010, 790, "logs")
    # technology logos
    titled(s, 490, 224, "Next.js and React", [])
    logo(s, "nextdotjs", 362, 214, 24); logo(s, "react", 392, 214, 24)
    logo(s, "nginx", 762, 212, 24)
    logo(s, "nextdotjs", 762, 332, 24)
    logo(s, "nodedotjs", 762, 612, 24)
    logo(s, "springboot", 1070, 164, 26)
    logo(s, "fastapi", 1082, 862, 24)
    logo(s, "grafana", 760, 866, 20); logo(s, "prometheus", 784, 866, 20)
    logo(s, "postgresql", 1663, 322, 44)
    logo(s, "docker", 40, 962, 28)
    s.text(78, 982, "Server side packaged and run with Docker Compose", size=14, fill=A_MUTE)
    s.text(40, 1010, "Solid blue: the request path. Dashed: optional or opt-in. Source of truth: compose.yaml, docs/architecture.md, docs/data-model.md.", size=13, fill=A_MUTE)
    return s



def data_model_clean():
    W, H = 2060, 1110
    s = Svg(W, H)
    s.text(40, 54, "Waypoint Dispatch: data model", size=28, weight=700)

    # legend
    lx, ly = 1500, 40
    s.path(f"M{lx},{ly} L{lx + 40},{ly}", end=None); crow(s, lx + 40, ly, (1, 0))
    s.text(lx + 56, ly + 4, "Foreign key", size=12, fill=MUTED)
    s.path(f"M{lx + 150},{ly} L{lx + 190},{ly}", dash="6 5", end=None); crow(s, lx + 190, ly, (1, 0))
    s.text(lx + 206, ly + 4, "Reference by id across modules, no FK", size=12, fill=MUTED)
    for i, (k, lbl) in enumerate([("PK", "primary key"), ("FK", "foreign key"), ("UK", "unique")]):
        col = {"PK": "#B45309", "FK": "#1D4ED8", "UK": "#6D28D9"}[k]
        bgc = {"PK": "#FEF3C7", "FK": "#DBEAFE", "UK": "#EDE9FE"}[k]
        x0 = lx + i * 118
        s.add(f'<rect x="{x0}" y="{ly + 22}" width="24" height="15" rx="4" fill="{bgc}"/>')
        s.text(x0 + 12, ly + 33, k, size=9, weight=700, fill=col, anchor="middle")
        s.text(x0 + 32, ly + 34, lbl, size=12, fill=MUTED)
    # what the line ends mean
    by = ly + 62
    bar(s, lx + 20, by, (1, 0), offset=4)
    s.path(f"M{lx},{by} L{lx + 20},{by}", end=None)
    s.text(lx + 34, by + 4, "one (the parent row)", size=12, fill=MUTED)
    s.path(f"M{lx + 190},{by} L{lx + 210},{by}", end=None); crow(s, lx + 210, by, (1, 0))
    s.text(lx + 226, by + 4, "many (child rows that point to it)", size=12, fill=MUTED)

    X = [40, 380, 720, 1060, 1400, 1740]
    heads = ["REFERENCE", "ORDERING", "PLANNING", "LOADING", "EXECUTION", "RECEIPT AND ISSUES"]
    for x, h in zip(X, heads):
        s.text(x, 134, h, size=11, weight=700, fill=MUTED, spacing=1.6)
        s.add(f'<path d="M{x},144 L{x + CARD_W},144" stroke="{BORDER}" stroke-width="1.2"/>')

    T = {}
    def tb(key, schema, name, cols, x, y):
        T[key] = Table(schema, name, cols, x, y)
    tb("outlet", "ref", "outlet_registry", [("outlet_id", "text", "PK")], X[0], 170)
    tb("vehicle", "ref", "vehicle_registry", [("vehicle_id", "text", "PK")], X[1], 588)
    tb("orders", "ordering", "orders", [("order_id", "uuid", "PK"), ("order_ref", "text", "UK"), ("outlet_id", "text", "FK"),
        ("status", "text", ""), ("redelivery_of", "uuid", "FK")], X[1], 170)
    tb("defer", "planning", "deferrals", [("plan_id", "uuid", "PK"), ("order_id", "uuid", "PK"), ("rule_id", "text", "")], X[2], 170)
    tb("runs", "planning", "runs", [("plan_id", "uuid", "PK"), ("depot_code", "text", "UK"), ("service_date", "date", "UK"),
        ("plan_version", "integer", "UK"), ("status", "text", "")], X[2], 333)
    tb("trips", "planning", "trips", [("trip_id", "uuid", "PK"), ("plan_id", "uuid", "PK"), ("vehicle_id", "text", "FK"),
        ("trip_number", "smallint", "UK")], X[2], 542)
    tb("alloc", "planning", "allocations", [("plan_id", "uuid", "PK"), ("order_id", "uuid", "PK"), ("trip_id", "uuid", "FK"),
        ("outlet_id", "text", "FK")], X[2], 728)
    tb("ltrips", "loading", "trips", [("trip_id", "uuid", "PK"), ("plan_version", "integer", "PK"), ("vehicle_id", "text", "FK")], X[3], 170)
    tb("lstops", "loading", "stops", [("trip_id", "uuid", "PK"), ("plan_version", "integer", "PK"), ("order_id", "uuid", "PK"),
        ("outlet_id", "text", "FK")], X[3], 333)
    tb("short", "loading", "shortfalls", [("shortfall_id", "uuid", "PK"), ("trip_id", "uuid", "FK"), ("plan_version", "integer", "FK"),
        ("order_id", "uuid", "FK")], X[3], 519)
    tb("etrips", "execution", "trips", [("trip_id", "uuid", "PK"), ("plan_id", "uuid", ""), ("vehicle_id", "text", "FK")], X[4], 170)
    tb("deliv", "execution", "delivery_records", [("delivery_id", "uuid", "PK"), ("trip_id", "uuid", "FK"), ("order_id", "uuid", "UK"),
        ("outlet_id", "text", "FK"), ("proof_id", "uuid", "FK")], X[4], 542)
    tb("proof", "execution", "proofs", [("proof_id", "uuid", "PK"), ("delivery_id", "uuid", "FK")], X[4], 751)
    tb("conf", "receipt", "confirmations", [("receipt_id", "uuid", "PK"), ("delivery_id", "uuid", "UK"), ("order_id", "uuid", "UK"),
        ("outlet_id", "text", "FK"), ("status", "text", "")], X[5], 519)
    tb("subj", "issues", "issue_subjects", [("issue_id", "uuid", "PK"), ("subject_type", "text", "PK"), ("subject_id", "text", "PK")], X[5], 728)
    tb("issue", "issues", "issues", [("issue_id", "uuid", "PK"), ("issue_type", "text", ""), ("severity", "text", ""),
        ("status", "text", ""), ("outlet_id", "text", "FK")], X[5], 891)
    s.tables = T
    for tt in T.values():
        draw_table(s, tt)

    o, r, tr, al, de = T["orders"], T["runs"], T["trips"], T["alloc"], T["defer"]
    ou, ve = T["outlet"], T["vehicle"]
    lt, ls_, sh = T["ltrips"], T["lstops"], T["short"]
    et, dv, pf, cf, sj, iss = T["etrips"], T["deliv"], T["proof"], T["conf"], T["subj"], T["issue"]
    def yr(t, col):
        return t.left(t.row_of(col))[1]
    def mid(a, b, dx=0):
        return (a.x + a.w / 2 + dx, (a.bottom()[1] + b.top()[1]) / 2)

    # foreign keys
    y0, y1 = yr(ou, "outlet_id"), yr(o, "outlet_id")
    relation(s, [(ou.x + ou.w, y0), (o.x - 20, y0), (o.x - 20, y1), (o.x, y1)], True, True)
    relation(s, [(ve.x + ve.w, yr(ve, "vehicle_id")), (tr.x, yr(tr, "vehicle_id"))], True, True)
    relation(s, [r.top(), de.bottom()], True, True)
    relation(s, [r.bottom(), tr.top()], True, True)
    relation(s, [tr.bottom(), al.top()], True, True)
    relation(s, [lt.bottom(), ls_.top()], True, True)
    relation(s, [ls_.bottom(), sh.top()], True, True)
    relation(s, [et.bottom(), dv.top()], True, True)
    relation(s, [dv.bottom(), pf.top()], True, True)
    relation(s, [iss.top(), sj.bottom()], True, True)

    # references by id (dashed)
    ox = o.x + o.w
    yo = yr(o, "order_id")
    trk = ox + 38
    relation(s, [(ox, yo), (trk, yo), (trk, yr(al, "order_id")), (al.x, yr(al, "order_id"))], True, True, dashed=True)
    tx = tr.x + tr.w
    t_y = yr(tr, "trip_id")
    relation(s, [(tx, t_y), (tx + 30, t_y), (tx + 30, yr(lt, "trip_id")), (lt.x, yr(lt, "trip_id"))], True, True, dashed=True)
    dx_ = dv.x + dv.w
    relation(s, [(dx_, yr(dv, "delivery_id")), (cf.x, yr(cf, "delivery_id"))], True, True, dashed=True)
    relation(s, [cf.bottom(), sj.top()], True, True, dashed=True)
    a = dx_ + 22
    relation(s, [(dx_, yr(dv, "outlet_id")), (a, yr(dv, "outlet_id")), (a, yr(sj, "issue_id")), (sj.x, yr(sj, "issue_id"))], True, True, dashed=True)
    b = dx_ + 48
    sy = yr(sh, "shortfall_id")
    relation(s, [(sh.x + sh.w, sy), (sh.x + sh.w + 38, sy), (sh.x + sh.w + 38, 880), (b, 880), (b, yr(sj, "subject_type")), (sj.x, yr(sj, "subject_type"))], True, True, dashed=True)

    s.text(40, 1080, "Left to right: a store places an order, planning allocates it to a trip on a vehicle, loading checks the stops, the driver records delivery and proof, the store confirms receipt. "
           "A shortfall, failed delivery or dispute becomes an issue. Core tables only; the full schema is 106 tables in 14 schemas.", size=12, fill=MUTED)
    return s


# --------------------------------------------------------------------------- plain flow diagrams

def _small(s, x, y, t, anchor="middle", size=13, fill=None, weight=400):
    s.text(x, y, t, size=size, fill=fill or A_MUTE, anchor=anchor, weight=weight)


def flow_day():
    W, H = 2160, 860
    s = Svg(W, H)
    s.text(40, 52, "Waypoint Dispatch: the delivery day", size=28, weight=700)
    # legend
    s.path("M1560,44 L1610,44", stroke=A_LINE, sw=1.8); s.text(1624, 49, "Hand-off to the next role", size=13, fill=A_MUTE)
    s.path("M1860,44 L1910,44", stroke=A_LINE, sw=1.8, dash="6 4"); s.text(1924, 49, "Exception or notice", size=13, fill=A_MUTE)

    LX, PITCH, BW = 200, 320, 240
    phases = [("1 Order", "before 16:00"), ("2 Close", "16:00 cutoff"), ("3 Plan", "after the close"),
              ("4 Load", "at the dock"), ("5 Deliver", "on the road"), ("6 Receive", "at the outlet")]
    for i, (a, b) in enumerate(phases):
        x = LX + i * PITCH
        s.text(x, 100, a, size=15, weight=700, fill=A_INK)
        s.text(x + BW, 100, b, size=13, fill=A_MUTE, anchor="end")
        s.add(f'<path d="M{x},110 L{x + BW},110" stroke="{A_EDGE}" stroke-width="1.4"/>')

    lanes = ["Store manager", "Dispatcher", "Loader", "Driver"]
    LY, LH = 160, 160
    for i, n in enumerate(lanes):
        y = LY + i * LH
        s.add(f'<path d="M40,{y} L{W - 40},{y}" stroke="{A_EDGE}" stroke-width="1.2"/>')
        s.text(40, y + LH / 2 + 5, n, size=16, weight=650, fill=A_INK)
    s.add(f'<path d="M40,{LY + 4 * LH} L{W - 40},{LY + 4 * LH}" stroke="{A_EDGE}" stroke-width="1.2"/>')

    def col(i): return LX + i * PITCH
    def row(i): return LY + i * LH + 35
    BH = 90
    pos = {}
    def step(key, c, r, title, sub, dashed=False):
        x, y = col(c), row(r)
        box(s, x, y, BW, BH, title, sub, dash="6 4" if dashed else None, stroke=A_LINE if dashed else A_INK, tsize=16)
        pos[key] = (x, y)

    step("place", 0, 0, "Place order", ["Stock reserved with", "the warehouse"])
    step("close", 1, 1, "Close orders", ["16:00 cutoff on the server", "clock, one queue"])
    step("plan", 2, 1, "Plan and allocate", ["Capacity, windows, fuel;", "defers the rest, then publishes"])
    step("told", 2, 0, "Told why deferred", ["The reason shows on", "the order"], dashed=True)
    step("load", 3, 2, "Load in stop order", ["Check items, flag shortfalls,", "seal and release the trip"])
    step("short", 3, 1, "Resolve a shortfall", ["Loading waits until it", "is resolved"], dashed=True)
    step("route", 4, 3, "Run the route", ["Arrive, deliver, capture", "proof, report faults"])
    step("track", 4, 1, "Track the day", ["Live map, ETAs, failed", "deliveries, vehicle status"])
    step("conf", 5, 0, "Confirm receipt", ["Handover PIN: confirm,", "confirm partly, or dispute"])
    step("issues", 5, 1, "Resolve issues", ["Assign, decide, or request", "a redelivery"])

    def R(k): x, y = pos[k]; return (x + BW, y + BH / 2)
    def L(k): x, y = pos[k]; return (x, y + BH / 2)
    def T(k): x, y = pos[k]; return (x + BW / 2, y)
    def B(k): x, y = pos[k]; return (x + BW / 2, y + BH)

    def lab(x, y, t): label(s, x, y, t)

    # place -> close
    (x1, y1), (x2, y2) = R("place"), L("close")
    mx = x1 + 40
    arrow(s, f"M{x1},{y1} L{mx},{y1} L{mx},{y2} L{x2},{y2}", colour=A_ACC)
    lab(mx, (y1 + y2) / 2, "orders.closed")
    # close -> plan
    arrow(s, f"M{R('close')[0]},{R('close')[1]} L{L('plan')[0]},{L('plan')[1]}", colour=A_ACC)
    # plan -> told (up, dashed)
    arrow(s, f"M{T('plan')[0]},{T('plan')[1]} L{B('told')[0]},{B('told')[1]}", dash="6 4")
    lab(T('plan')[0], (T('plan')[1] + B('told')[1]) / 2, "order.deferred")
    # plan -> load
    (x1, y1), (x2, y2) = R("plan"), L("load")
    mx = x1 + 40
    arrow(s, f"M{x1},{y1} L{mx},{y1} L{mx},{y2} L{x2},{y2}", colour=A_ACC)
    lab(mx, (y1 + y2) / 2 + 30, "plan.published")
    # load -> short (dashed up)
    arrow(s, f"M{T('load')[0]},{T('load')[1]} L{B('short')[0]},{B('short')[1]}", dash="6 4")
    lab(T('load')[0], (T('load')[1] + B('short')[1]) / 2, "loading.shortfall")
    # load -> route
    (x1, y1), (x2, y2) = R("load"), L("route")
    mx = x1 + 40
    arrow(s, f"M{x1},{y1} L{mx},{y1} L{mx},{y2} L{x2},{y2}", colour=A_ACC)
    lab(mx, (y1 + y2) / 2, "trip.released")
    # route -> track (dashed up)
    arrow(s, f"M{T('route')[0]},{T('route')[1]} L{B('track')[0]},{B('track')[1]}", dash="6 4")
    lab(T('route')[0], (T('route')[1] + B('track')[1]) / 2 + 40, "eta.changed, delivery.failed")
    # route -> conf
    (x1, y1), (x2, y2) = R("route"), L("conf")
    mx = x1 + 40
    arrow(s, f"M{x1},{y1} L{mx},{y1} L{mx},{y2} L{x2},{y2}", colour=A_ACC)
    lab(mx, (y1 + y2) / 2 + 80, "delivery.completed")
    # conf -> issues (dashed down)
    arrow(s, f"M{B('conf')[0]},{B('conf')[1]} L{T('issues')[0]},{T('issues')[1]}", dash="6 4")
    lab(B('conf')[0], (B('conf')[1] + T('issues')[1]) / 2, "receipt.disputed")
    # issues -> place order (redelivery loop)
    x1, y1 = R("issues"); x2, y2 = T("place")
    ry = LY - 14
    arrow(s, f"M{x1},{y1} L{x1 + 50},{y1} L{x1 + 50},{ry} L{x2},{ry} L{x2},{y2}", dash="6 4")
    lab(1100, ry, "redelivery.requested: the order goes round again as a new order")

    s.text(40, 836, "Every event above also reaches the right role as an in-app notification and a web push.", size=13, fill=A_MUTE)
    return s


def lifecycle():
    W, H = 2000, 640
    s = Svg(W, H)
    s.text(40, 52, "Waypoint Dispatch: order status lifecycle", size=28, weight=700)
    s.add(f'<rect x="1560" y="32" width="46" height="24" rx="4" fill="{A_FILL}" stroke="{A_INK}" stroke-width="3.2"/>')
    s.text(1620, 49, "Heavy border: final state", size=13, fill=A_MUTE)

    def st(x, y, w, name, final=False, sub=None, h=56):
        s.rect(x, y, w, h, fill=A_FILL, stroke=A_INK, sw=3.2 if final else 1.6, rx=4)
        s.text(x + w / 2, y + (h / 2 + 5 if not sub else 26), name, size=16, weight=600, fill=A_INK, anchor="middle", family=MONO)
        if sub:
            s.text(x + w / 2, y + 45, sub, size=12.5, fill=A_MUTE, anchor="middle")
        return (x, y, w, h)

    Y = 210
    a = st(40, Y - 12, 250, "stock_unknown", sub="or partially_reserved", h=80)
    b = st(350, Y, 190, "confirmed")
    c = st(600, Y, 190, "allocated")
    d = st(850, Y, 190, "loading")
    e = st(1100, Y, 190, "in_transit")
    f = st(1340, Y - 12, 250, "delivered", sub="or partially_delivered", h=80)
    g = st(1800, 150, 170, "received", final=True)
    h = st(1800, 280, 170, "unconfirmed", final=True)

    def mid_r(r): return (r[0] + r[2], r[1] + r[3] / 2)
    def mid_l(r): return (r[0], r[1] + r[3] / 2)
    def mid_b(r): return (r[0] + r[2] / 2, r[1] + r[3])
    def mid_t(r): return (r[0] + r[2] / 2, r[1])

    for p, q in [(a, b), (b, c), (c, d), (d, e), (e, f)]:
        x1, y1 = mid_r(p); x2, y2 = mid_l(q)
        arrow(s, f"M{x1},{y1} L{x2},{y1}", colour=A_ACC)
    # delivered -> received / unconfirmed
    x1, y1 = mid_r(f)
    arrow(s, f"M{x1},{y1} L{x1 + 40},{y1} L{x1 + 40},{g[1] + 28} L{g[0]},{g[1] + 28}", colour=A_ACC)
    arrow(s, f"M{x1 + 40},{y1} L{x1 + 40},{h[1] + 28} L{h[0]},{h[1] + 28}", colour=A_ACC)
    s.text(x1 + 50, g[1] + 20, "store confirms", size=13, fill=A_MUTE)
    s.text(x1 + 50, h[1] + 52, "no confirmation in time", size=13, fill=A_MUTE)

    # in_transit -> failed
    fl = st(1100, 430, 190, "failed", final=True)
    arrow(s, f"M{mid_b(e)[0]},{mid_b(e)[1]} L{mid_t(fl)[0]},{mid_t(fl)[1]}")
    label(s, mid_t(fl)[0], 380, "delivery fails", w=110)
    s.text(1310, 465, "Never redelivered in place: Issues asks for a", size=13, fill=A_MUTE)
    s.text(1310, 485, "new, linked order.", size=13, fill=A_MUTE)

    # deferred / unservable
    df = st(430, 430, 190, "deferred")
    un = st(680, 430, 190, "unservable")
    arrow(s, f"M{mid_b(b)[0]},{mid_b(b)[1]} L{mid_b(b)[0]},{df[1] - 60} L{mid_t(df)[0]},{df[1] - 60} L{mid_t(df)[0]},{df[1]}", dash="6 4")
    arrow(s, f"M{mid_b(c)[0] + 30},{mid_b(c)[1]} L{mid_b(c)[0] + 30},{un[1] - 60} L{mid_t(un)[0]},{un[1] - 60} L{mid_t(un)[0]},{un[1]}", dash="6 4")
    s.text(430, 535, "Planning can defer an order or mark it unservable.", size=13, fill=A_MUTE)
    s.text(430, 555, "Both are planned again on a later run, back to allocated.", size=13, fill=A_MUTE)

    # cancelled
    cn = st(40, 430, 250, "cancelled", final=True)
    arrow(s, f"M{mid_b(a)[0]},{mid_b(a)[1]} L{mid_t(cn)[0]},{mid_t(cn)[1]}", dash="6 4")
    s.text(40, 535, "The store can cancel up to and including allocated;", size=13, fill=A_MUTE)
    s.text(40, 555, "never once loading has started.", size=13, fill=A_MUTE)

    s.text(40, 610, "Solid blue: the normal path. Dashed: exits. Legal moves are enforced in one place, OrderStateMachine, and every change is written to order_status_history.", size=13, fill=A_MUTE)
    return s


def offline_recovery():
    W, H = 2000, 760
    s = Svg(W, H)
    s.text(40, 52, "Waypoint Dispatch: offline and recovery", size=28, weight=700)
    s.text(40, 94, "WHAT HAPPENS WITH NO SIGNAL", size=12, weight=700, fill=A_MUTE, spacing=1.6)
    bw, gap, y0 = 400, 80, 112
    steps = [("Write with no signal", ["Stops, arrivals, proof and", "reports are saved on the phone"]),
             ("Offline write queue", ["IndexedDB. Each command keeps", "its id and expected version"]),
             ("Connection returns", ["The queue is sent as one batch:", "POST /api/sync"]),
             ("Replay via CommandBus", ["Same policy, scope, idempotency", "and version checks as online"])]
    for i, (t, sub) in enumerate(steps):
        x = 40 + i * (bw + gap)
        box(s, x, y0, bw - 40, 96, t, sub, tsize=16)
        if i < 3:
            arrow(s, f"M{x + bw - 40},{y0 + 48} L{x + bw + gap - 4},{y0 + 48}", colour=A_ACC)
    s.text(40, 262, "Who can work offline: driver fully; loader and store manager queue their writes and retry; dispatcher is online only,", size=14, fill=A_MUTE)
    s.text(40, 284, "because two offline plans could not be reconciled.", size=14, fill=A_MUTE)

    s.text(40, 340, "OUTCOME OF EACH REPLAYED COMMAND", size=12, weight=700, fill=A_MUTE, spacing=1.6)
    outs = [("APPLIED", "Accepted, same as if sent online"), ("CONFLICT", "The version is stale: the person resolves or discards"),
            ("REJECTED", "A rule refused it: the rule is shown")]
    for i, (t, sub) in enumerate(outs):
        x = 40 + i * 640
        box(s, x, 358, 600, 70, t, [sub], tsize=15)
    s.text(40, 452, "A replay never overwrites silently.", size=14, fill=A_MUTE)

    s.text(40, 510, "WHEN A DEPENDENCY FAILS", size=12, weight=700, fill=A_MUTE, spacing=1.6)
    rows = [("Warehouse slow or down", "Orders wait as stock_unknown and are retried; stock still unresolved at the cutoff auto-defers with reason stock_unresolved."),
            ("ML service down", "Plans fall back to deterministic estimates and say so on every plan."),
            ("Push keys missing", "Web push turns off visibly; in-app notifications still arrive."),
            ("Duplicate request", "The idempotency receipt answers a repeated command from its first result."),
            ("Dispatcher offline", "Planning stays online-only.")]
    for i, (a, b) in enumerate(rows):
        y = 540 + i * 36
        s.add(f'<path d="M40,{y - 14} L{W - 40},{y - 14}" stroke="{A_EDGE}" stroke-width="1"/>')
        s.text(40, y + 8, a, size=15, weight=650, fill=A_INK)
        s.text(380, y + 8, b, size=15, fill=A_MUTE)
    return s


def deployment():
    W, H = 2000, 930
    s = Svg(W, H)
    s.text(40, 52, "Waypoint Dispatch: deployment", size=28, weight=700)

    s.text(40, 100, "HOW CODE GETS THERE", size=12, weight=700, fill=A_MUTE, spacing=1.6)
    s.add(f'<path d="M40,110 L{W - 40},110" stroke="{A_EDGE}" stroke-width="1.2"/>')
    y1, h1 = 130, 150
    box(s, 40, y1, 210, h1, "Developer", ["Pull request into", "dev or main"], tsize=16)
    box(s, 310, y1, 260, h1, "GitHub repository", ["dev: preview", "main: production"], tsize=16)
    box(s, 630, y1, 420, h1, "Checks (GitHub Actions)",
        ["MCP tests and image build", "Model service tests (pytest)", "Backend tests on PostgreSQL 16", "Frontend checks", "Browser tests for each role"], tsize=16)
    box(s, 1110, y1, 520, h1, "Deploy over SSH",
        ["A push to dev deploys the preview, a push to main deploys production,", "after the checks pass. One forced command per environment",
         "runs deploy/vps/deploy.sh on the server."], tsize=16)
    arrow(s, f"M250,{y1 + 75} L310,{y1 + 75}", colour=A_ACC)
    arrow(s, f"M570,{y1 + 75} L630,{y1 + 75}", colour=A_ACC)
    arrow(s, f"M1050,{y1 + 75} L1110,{y1 + 75}", colour=A_ACC)
    s.text(1670, y1 + 30, "deploy.sh, in order:", size=13, weight=650, fill=A_INK)
    for i, r in enumerate(["1 pull code and model files", "2 back up the database", "3 run init (migrations)", "4 start the services", "5 check the HTTPS address"]):
        s.text(1670, y1 + 54 + i * 20, r, size=13, fill=A_MUTE)

    s.text(40, 350, "WHERE IT RUNS", size=12, weight=700, fill=A_MUTE, spacing=1.6)
    s.add(f'<path d="M40,360 L{W - 40},360" stroke="{A_EDGE}" stroke-width="1.2"/>')
    box(s, 40, 480, 190, 90, "Judges and users", ["Browser"], tsize=16)
    box(s, 290, 480, 220, 90, "Cloudflare", ["DNS and proxy, TLS", "Full (strict)"], tsize=16)
    vx, vy, vw, vh = 590, 380, 1370, 490
    s.rect(vx, vy, vw, vh, fill=BG, stroke=A_INK, sw=1.6, rx=4)
    s.text(vx + 56, vy + 32, "VPS: Ubuntu 24.04, Docker Compose", size=17, weight=700)
    logo(s, "docker", vx + 16, vy + 12, 28)
    arrow(s, "M230,525 L290,525", colour=A_ACC)
    arrow(s, "M510,525 L" + str(vx) + ",525", colour=A_ACC)
    label(s, 550, 511, "HTTPS", w=60)

    box(s, vx + 20, vy + 56, 1330, 80, "Edge: nginx and certbot", ["TLS 1.2 and 1.3, HTTP redirect, rate limits; Let's Encrypt certificates; one edge for both environments"], tsize=16)
    logo(s, "nginx", vx + 40, vy + 70, 28)

    def stack(x, name, branch, path, host, extra=None):
        sx, sy, sw_, sh_ = x, vy + 190, 650, 270
        s.rect(sx, sy, sw_, sh_, fill=BG, stroke=A_LINE, sw=1.4, rx=4)
        s.text(sx + 20, sy + 30, name, size=17, weight=700)
        s.text(sx + sw_ - 20, sy + 30, host, size=14, fill=A_ACC, anchor="end", weight=600)
        s.text(sx + 20, sy + 54, f"Branch {branch}, checkout {path}", size=13, fill=A_MUTE)
        svc = [("waypoint", "Next.js", "nextdotjs"), ("backend", "Spring Boot", "springboot"), ("ml", "FastAPI", "fastapi"),
               ("mcp", "Node.js", "nodedotjs"), ("db", "PostgreSQL 16", "postgresql")]
        for i, (n, sub, lg) in enumerate(svc):
            bx = sx + 20 + i * 122
            s.rect(bx, sy + 76, 112, 96, fill=A_FILL, stroke=A_INK, sw=1.4, rx=4)
            logo(s, lg, bx + 44, sy + 86, 24)
            s.text(bx + 56, sy + 130, n, size=14, weight=650, anchor="middle")
            s.text(bx + 56, sy + 150, sub, size=11.5, fill=A_MUTE, anchor="middle")
        s.text(sx + 20, sy + 198, "The init job runs first: migrations, reference data.", size=13, fill=A_MUTE)
        s.text(sx + 20, sy + 220, "Its own database volume and its own accounts.", size=13, fill=A_MUTE)
        if extra:
            logo(s, "grafana", sx + 20, sy + 234, 20); logo(s, "prometheus", sx + 46, sy + 234, 20)
            s.text(sx + 74, sy + 250, extra, size=13, fill=A_MUTE)
        return (sx, sy, sw_, sh_)

    p = stack(vx + 20, "Production", "main", "/opt/waypoint/app", "waypointgo.live")
    q = stack(vx + 700, "Preview", "dev", "/opt/waypoint/preview", "preview.waypointgo.live",
              extra="Preview also runs the log and metrics stack (opt-in).")
    arrow(s, f"M{p[0] + p[2] / 2},{vy + 136} L{p[0] + p[2] / 2},{p[1]}", colour=A_ACC)
    arrow(s, f"M{q[0] + q[2] / 2},{vy + 136} L{q[0] + q[2] / 2},{q[1]}", colour=A_ACC)

    # deploy arrow into the VPS
    arrow(s, f"M1370,{y1 + h1} L1370,{vy}", dash="6 4")
    label(s, 1370, 315, "SSH as deploy", w=110)
    s.text(40, 908, "Production and preview run from the same compose.yaml. Nothing but nginx is reachable from outside. Sources: .github/workflows, deploy/vps, docs/deployment.md.", size=13, fill=A_MUTE)
    return s


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "docs" / "img"
    out.mkdir(parents=True, exist_ok=True)
    from playwright.sync_api import sync_playwright

    jobs = [("architecture", architecture_plain()), ("data-model", data_model_clean()), ("delivery-flow", flow_day()), ("order-lifecycle", lifecycle()), ("offline-recovery", offline_recovery()), ("deployment", deployment())]
    exe = os.environ.get("CHROMIUM_PATH")
    with sync_playwright() as p:
        kw = {"executable_path": exe} if exe else {}
        b = p.chromium.launch(args=["--no-sandbox"], **kw)
        for name, svg in jobs:
            svgtext = svg.render()
            (out / f"{name}.svg").write_text(svgtext, encoding="utf-8")
            page = b.new_page(viewport={"width": svg.w, "height": svg.h}, device_scale_factor=2)
            page.set_content(f'<html><body style="margin:0;background:{BG}">{svgtext}</body></html>')
            page.wait_for_timeout(300)
            page.screenshot(path=str(out / f"{name}.png"), clip={"x": 0, "y": 0, "width": svg.w, "height": svg.h})
            page.close()
            print("wrote", out / f"{name}.png")
        b.close()


if __name__ == "__main__":
    main()
