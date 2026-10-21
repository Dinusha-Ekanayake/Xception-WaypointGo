#!/usr/bin/env python3
"""Live check: one delivery day kept in sync across the four field roles.

Drives a real delivery day through POST /api/commands on a running local stack
(dispatcher, loader, driver, store manager, with the admin for the demo clock)
and measures, after every step, how long the other roles' reads and streams
take to show it. See README.md beside this file.

Requires Python 3.9+ and requests. Exit code 0 only when no check FAILs.
"""

import argparse
import urllib.parse
import datetime as dt
import json
import os
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from zoneinfo import ZoneInfo

import requests

ZONE = ZoneInfo("Asia/Colombo")
SLOW_S = 3.0
TIMEOUT_S = 30.0
JOB_TIMEOUT_S = 130.0
ROLES = ("admin", "dispatcher", "loader", "driver", "store_manager")


class Abort(Exception):
    """A step the rest of the run depends on did not work."""


# ---------------------------------------------------------------- utilities


def iso(instant):
    return instant.astimezone(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_instant(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def depot_time(day, hhmmss):
    return dt.datetime.combine(day, dt.time.fromisoformat(hhmmss), tzinfo=ZONE)


def short(text, limit=400):
    text = text if isinstance(text, str) else json.dumps(text)
    return text if len(text) <= limit else text[:limit] + "..."


# ---------------------------------------------------------------- API client


class Api:
    def __init__(self, base, password):
        self.base = base.rstrip("/")
        self.password = password
        self.sessions = {}
        self.users = {}
        self.scopes = {}

    def sign_in(self, role):
        s = requests.Session()
        r = s.post(self.base + "/api/session", json={"email": f"{role}@waypoint.local", "password": self.password}, timeout=15)
        if r.status_code != 200:
            raise Abort(f"sign-in as {role}@waypoint.local -> {r.status_code} {short(r.text)}")
        body = r.json()
        self.sessions[role] = s
        self.users[role] = body["userId"]
        self.scopes[role] = body.get("scope") or []

    def get(self, role, path, **params):
        return self.sessions[role].get(self.base + path, params=params, timeout=15)

    def json(self, role, path, **params):
        r = self.get(role, path, **params)
        if r.status_code != 200:
            raise RuntimeError(f"GET {path} -> {r.status_code} {short(r.text, 200)}")
        return r.json()

    def command(self, role, kind, payload, version=None, acting=None):
        """Sends one command. Returns (response, time sent)."""
        body = {
            "commandId": str(uuid.uuid4()),
            "kind": kind,
            "expectedVersion": version,
            "payload": payload,
            "clientRecordedAt": iso(dt.datetime.now(dt.timezone.utc)),
        }
        if acting:
            body["actingUserId"] = acting
        t0 = time.time()
        r = self.sessions[role].post(self.base + "/api/commands", json=body, timeout=30)
        return r, t0

    def must(self, role, kind, payload, version=None, acting=None):
        r, t0 = self.command(role, kind, payload, version, acting)
        ms = (time.time() - t0) * 1000
        if r.status_code != 200:
            print(f"  {role} {kind} -> {r.status_code} ({ms:.0f} ms)\n    request payload: {short(payload)}\n    response: {short(r.text, 800)}")
            raise Abort(f"{role} {kind} -> {r.status_code}")
        print(f"  {role} {kind} -> 200 ({ms:.0f} ms)")
        return r.json().get("result"), t0


# ---------------------------------------------------------------- streams


class Stream(threading.Thread):
    """Reads one server-sent event stream and keeps every event with its arrival time."""

    def __init__(self, api, role, path, label):
        super().__init__(daemon=True)
        self.api, self.role, self.path, self.label = api, role, path, label
        self.events = []
        self.lock = threading.Lock()
        self.opened = None
        self.status = None
        self.stop = False

    def run(self):
        s = requests.Session()
        s.cookies.update(self.api.sessions[self.role].cookies)
        while not self.stop:
            try:
                t = time.time()
                r = s.get(self.api.base + self.path, stream=True, timeout=(5, 90))
                self.status = r.status_code
                if r.status_code != 200:
                    self._add("http-error", f"{r.status_code} {short(r.text, 200)}")
                    time.sleep(5)
                    continue
                if self.opened is None:
                    self.opened = time.time() - t
                event = "message"
                for line in r.iter_lines(decode_unicode=True):
                    if self.stop:
                        break
                    if line is None:
                        continue
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        self._add(event, line[5:].strip())
                    elif line == "":
                        event = "message"
            except Exception as e:  # noqa: BLE001 reconnect on any read failure
                if not self.stop:
                    self._add("reconnect", repr(e))
                    time.sleep(1)

    def _add(self, event, data):
        with self.lock:
            self.events.append((time.time(), event, data))

    def find(self, since, pred):
        with self.lock:
            for ts, ev, data in self.events:
                if ts >= since and pred(ev, data):
                    return ts
        return None

    def last(self, before, event):
        with self.lock:
            found = None
            for ts, ev, data in self.events:
                if ts < before and ev == event:
                    found = data
            return found


# ---------------------------------------------------------------- checks


class Results:
    def __init__(self):
        self.rows = []
        self.lock = threading.Lock()

    def add(self, step, status, role, what, how, delay, detail=""):
        with self.lock:
            self.rows.append((step, status, role, what, how, delay, detail))
        shown = "-" if delay is None else f"{delay:.2f} s"
        line = f"  {status:<4}  {role:<13} {what} ({how})  {shown}"
        if detail:
            line += f"\n        {detail}"
        print(line, flush=True)


RESULTS = Results()


class Step:
    """One command and the checks that measure it, run in parallel from the command's time."""

    def __init__(self, name):
        self.name = name
        self.checks = []

    def poll(self, role, what, fn, pred, timeout=TIMEOUT_S, slow=SLOW_S):
        self.checks.append(("poll", role, what, fn, pred, timeout, slow))
        return self

    def stream(self, role, what, stream, pred, timeout=TIMEOUT_S, slow=SLOW_S):
        self.checks.append(("stream", role, what, stream, pred, timeout, slow))
        return self

    def absent(self, role, what, stream, pred, window=4.0):
        self.checks.append(("absent", role, what, stream, pred, window, None))
        return self

    def run(self, t0):
        with ThreadPoolExecutor(max_workers=max(1, len(self.checks))) as pool:
            futures = [pool.submit(self._one, t0, *c) for c in self.checks]
            out = [f.result() for f in futures]
        for row in out:
            RESULTS.add(self.name, *row)
        return out

    @staticmethod
    def _one(t0, how, role, what, target, pred, timeout, slow):
        last = None
        if how == "absent":
            time.sleep(timeout)
            ts = target.find(t0, pred)
            if ts is None:
                return ("PASS", role, what, "stream, none expected", None, "")
            return ("FAIL", role, what, "stream, none expected", ts - t0, "an event arrived that should not have")
        while time.time() - t0 < timeout:
            if how == "poll":
                try:
                    last = target()
                    if pred(last):
                        delay = time.time() - t0
                        return ("SLOW" if delay > slow else "PASS", role, what, "poll", delay, "")
                except Exception as e:  # noqa: BLE001 a failed read is reported, not fatal
                    last = repr(e)
                time.sleep(0.25)
            else:
                ts = target.find(t0, pred)
                if ts is not None:
                    delay = ts - t0
                    return ("SLOW" if delay > slow else "PASS", role, what, "stream", delay, "")
                time.sleep(0.1)
        detail = f"not seen within {timeout:.0f} s"
        if how == "poll":
            detail += f"; last read: {short(str(last), 300)}"
        elif target.status not in (None, 200):
            detail += f"; stream status {target.status}"
        return ("FAIL", role, what, how, None, detail)


def topic(name):
    def pred(ev, data):
        if ev != "change":
            return False
        try:
            return json.loads(data).get("topic") == name
        except ValueError:
            return False
    return pred


def unread_above(stream, t0):
    base = stream.last(t0, "unread")
    base_count = json.loads(base)["count"] if base else 0

    def pred(ev, data):
        return ev == "unread" and json.loads(data).get("count", 0) > base_count
    return pred


def position_pred(vehicle, lat):
    def pred(ev, data):
        if ev != "positions":
            return False
        for p in json.loads(data):
            if p.get("vehicleId") == vehicle and abs(float(p.get("latitude", 0)) - lat) < 1e-6:
                return True
        return False
    return pred


# ---------------------------------------------------------------- the run


class LiveCheck:
    def __init__(self, args):
        self.args = args
        self.api = Api(args.base, args.password)
        self.depot = args.depot
        self.day = None
        self.streams = {}

    # -- clock and day

    def demo(self):
        return self.api.json("admin", "/api/demo")

    def now(self):
        return parse_instant(self.demo()["now"])

    def set_clock(self, target, reason):
        v = self.demo()["rowVersion"]
        self.api.must("admin", "demo:SetClock", {"reason": reason, "target": iso(target)}, version=v)

    @staticmethod
    def reachable(day):
        """The demo clock may sit at most seven days from real time, and a run moves it into
        the morning of its day: a day whose 07:00 is past that cannot be run."""
        limit = dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=7) - dt.timedelta(minutes=10)
        return depot_time(day, "07:00:00") <= limit

    WINDOW_USED = ("The local demo window is used up: the demo clock may only be within 7 days of real time, and\n"
                   "  every operating day it can reach already has a run or leftover orders. Run again tomorrow, or on a\n"
                   "  fresh local database (scripts/dev.sh setup, then seed-delivery-day).")

    def has_orders(self, day):
        return len(self.api.json("dispatcher", "/api/orders/day", depot=self.depot, date=day.isoformat())) > 0

    def published(self, day):
        return self.api.get("dispatcher", "/api/plans/published", depot=self.depot, date=day.isoformat()).status_code == 200

    def driver_has_vehicles(self, day):
        return len(self.api.json("driver", "/api/execution/vehicles", date=day.isoformat())) > 0

    def reset_day(self, day=None):
        if self.depot != "Peliyagoda":
            raise Abort("demo:ResetDay prepares Peliyagoda only; give --date of a day with confirmed orders for this depot")
        if day is not None and not self.reachable(day):
            raise Abort(f"{day} is more than 7 days from real time, which the demo clock cannot reach.")
        if day is None:
            # demo:ResetDay alone takes the next operating day, which may hold orders
            # deferred from an earlier run; name the first empty operating day instead.
            today = self.now().astimezone(ZONE).date()
            for offset in range(1, 8):
                candidate = today + dt.timedelta(days=offset)
                calendar = self.api.json("admin", f"/api/reference/calendar/{candidate.isoformat()}")
                if not self.reachable(candidate):
                    break
                if calendar.get("operating") and not self.has_orders(candidate):
                    day = candidate
                    break
            if day is None:
                raise Abort(self.WINDOW_USED)
        payload = {"reason": "live check: a fresh delivery day"}
        if day:
            payload["serviceDate"] = day.isoformat()
        v = self.demo()["rowVersion"]
        r, _ = self.api.command("admin", "demo:ResetDay", payload, version=v)
        if r.status_code != 200:
            raise Abort(
                f"admin demo:ResetDay -> {r.status_code} {short(r.text, 400)}\n"
                "  No empty operating day within seven days of the business clock. Either move the clock forward\n"
                "  (Admin > Demo, or demo:SetClock) so a new empty day comes into range, or give --date of a day\n"
                "  that already has confirmed orders and no published plan.")
        result = r.json()["result"]
        run_id, served = result["runId"], dt.date.fromisoformat(result["serviceDate"])
        if not self.reachable(served):
            raise Abort(f"demo:ResetDay prepared {served}, which the demo clock cannot reach.\n  " + self.WINDOW_USED)
        print(f"  admin demo:ResetDay -> 200, preparing {served}")
        end = time.time() + 90
        while time.time() < end:
            for run in self.api.json("admin", "/api/demo/scenario-runs"):
                if str(run.get("id")) == run_id and run.get("outcome") in ("completed", "failed"):
                    if run["outcome"] == "failed":
                        raise Abort(f"demo:ResetDay failed: {short(run.get('details'))}")
                    print(f"  demo day {served} prepared")
                    return served
            time.sleep(2)
        raise Abort("demo:ResetDay did not finish within 90 s (is the scheduler running?)")

    def choose_day(self):
        today = self.now().astimezone(ZONE).date()
        if self.args.date:
            day = dt.date.fromisoformat(self.args.date)
            if day < today:
                raise Abort(f"{day} is before the business day {today}; the clock cannot go back. Give a later --date.")
            if self.published(day) or not self.has_orders(day):
                why = "already has a published plan" if self.published(day) else "has no orders"
                if self.args.no_reset:
                    raise Abort(
                        f"{day} {why}. Prepare a fresh day first: as admin, send demo:ResetDay\n"
                        "  (Admin > Demo > Reset day), or rerun without --no-reset, or give another --date.")
                print(f"  {day} {why}; preparing a fresh day")
                return self.reset_day(None if self.published(day) else day)
            return day
        for offset in range(0, 8):
            day = today + dt.timedelta(days=offset)
            if not self.reachable(day):
                break
            if self.has_orders(day) and not self.published(day) and self.driver_has_vehicles(day):
                print(f"  using {day}: confirmed orders, no published plan, the driver has vehicles")
                return day
        if self.args.no_reset:
            raise Abort("No day within seven days has orders and no published plan. Run demo:ResetDay as admin, "
                        "or run seed-delivery-day on a fresh database, or rerun without --no-reset.")
        print("  no unplanned day with orders in the next seven days; preparing one")
        return self.reset_day()

    # -- streams

    def open_stream(self, key, role, path):
        s = Stream(self.api, role, path, key)
        s.start()
        self.streams[key] = s
        return s

    # -- small reads

    def sheet(self, role, vehicle):
        params = {"date": self.day.isoformat()}
        if role == "dispatcher":
            params["depot"] = self.depot
        for x in self.api.json(role, "/api/execution/run-sheets", **params):
            if x["vehicleId"] == vehicle and any(st["tripId"] == self.trip for st in x["stops"]):
                return [st for st in x["stops"] if st["tripId"] == self.trip]
        return []

    def stop_of(self, role, vehicle, delivery_id):
        for st in self.sheet(role, vehicle):
            if st["deliveryId"] == delivery_id:
                return st
        return None

    def store_delivery(self, outlet, delivery_id):
        for x in self.api.json("store_manager", "/api/execution/deliveries", outlet=outlet, date=self.day.isoformat()):
            if x["deliveryId"] == delivery_id:
                return x
        return None

    def dock(self, role):
        for t in self.api.json(role, "/api/loading/trips", depot=self.depot, date=self.day.isoformat()):
            if t["tripId"] == self.trip:
                return t
        return None

    def positions(self, role, vehicle, **where):
        for p in self.api.json(role, "/api/execution/positions", date=self.day.isoformat(), **where):
            if p.get("vehicleId") == vehicle:
                return p
        return None

    def inbox_ids(self, role):
        return {n["notificationId"] for n in self.api.json(role, "/api/notifications")["items"]}

    def new_notification(self, role, before, event_type, contains=None):
        def read():
            for n in self.api.json(role, "/api/notifications")["items"]:
                if n["notificationId"] not in before and n["eventType"] == event_type:
                    if contains is None or contains in json.dumps(n):
                        return n
            return None
        return read

    def notify_checks(self, step, role, event_type, before, t0, timeout=TIMEOUT_S, contains=None):
        step.poll(role, f"inbox has a new {event_type}", self.new_notification(role, before, event_type, contains),
                  lambda n: n is not None, timeout=timeout, slow=SLOW_S if timeout == TIMEOUT_S else timeout)
        stream = self.streams[f"notif-{role}"]
        step.stream(role, "unread count rises", stream, unread_above(stream, t0), timeout=timeout,
                    slow=SLOW_S if timeout == TIMEOUT_S else timeout)

    def fixes(self, points):
        # A phone stamps its fixes with its own clock, real time, not the demo clock (DEMO-12).
        end = dt.datetime.now(dt.timezone.utc)
        out = []
        for i, (lat, lon) in enumerate(points):
            at = end - dt.timedelta(seconds=10 * (len(points) - 1 - i) + 2)
            out.append({"recordedAt": iso(at), "latitude": lat, "longitude": lon, "accuracyM": 10})
        return out

    # -- the day

    def run(self):
        a = self.api
        print(f"Live check against {a.base}, depot {self.depot}")
        print("\n== Sign in")
        for role in ROLES:
            a.sign_in(role)
            print(f"  {role}: {a.users[role]}")

        print("\n== Demo clock and day")
        d = self.demo()
        if not d["enabled"]:
            a.must("admin", "demo:Enable", {"reason": "live check"}, version=d["rowVersion"])
        self.day = self.choose_day()
        day = self.day
        business = self.now().astimezone(ZONE)
        if business.date() < day:
            self.set_clock(dt.datetime.combine(day, dt.time(2, 30), tzinfo=ZONE), "live check: service day morning")
        print(f"  service day {day}; business time {self.now().astimezone(ZONE):%Y-%m-%d %H:%M} depot time")

        print("\n== Streams")
        self.open_stream("changes", "dispatcher", f"/api/changes/stream?depot={self.depot}")
        self.open_stream("pos-depot", "dispatcher", f"/api/execution/positions/stream?depot={self.depot}&date={day}")
        for role in ("dispatcher", "loader", "driver", "store_manager"):
            self.open_stream(f"notif-{role}", role, "/api/notifications/stream")
        end = time.time() + TIMEOUT_S
        while time.time() < end and any(s.opened is None for s in self.streams.values()):
            time.sleep(0.2)
        for key, s in self.streams.items():
            state = "not open yet" if s.opened is None else f"open in {s.opened:.2f} s"
            print(f"  {key}: {state}")
            status = "FAIL" if s.opened is None else ("SLOW" if s.opened > SLOW_S else "PASS")
            RESULTS.add("streams", status, "dispatcher" if key in ("changes", "pos-depot") else key[6:],
                        f"{key} stream answers", "stream", s.opened, "" if s.opened else f"status {s.status}")

        self.plan_and_publish()
        self.load_and_release()
        self.drive()
        self.receipt_and_messages()

    def plan_and_publish(self):
        a, day = self.api, self.day
        print("\n== plan:Generate (dispatcher)")
        result, t0 = a.must("dispatcher", "plan:Generate", {"depotCode": self.depot, "serviceDate": day.isoformat()})
        job_id = result["jobId"]
        job = {}

        def read_job():
            job.update(a.json("dispatcher", f"/api/plans/jobs/{job_id}", depot=self.depot))
            return job.get("status")

        Step("generate").poll("dispatcher", "generation job DONE", read_job, lambda s: s == "DONE", timeout=120, slow=30) \
            .stream("dispatcher", "change topic plan (draft ready)", self.streams["changes"], topic("plan"), timeout=120, slow=30) \
            .poll("dispatcher", "draft plan readable", lambda: a.get("dispatcher", "/api/plans/draft", depot=self.depot, date=day.isoformat()).status_code,
                  lambda c: c == 200, timeout=120, slow=30).run(t0)
        if job.get("status") != "DONE":
            raise Abort(f"plan generation did not finish: {short(job)}")
        plan_id, version = job["result"]["planId"], job["result"]["rowVersion"]
        print(f"  plan {plan_id}: {job['result'].get('served')} served, {job['result'].get('deferred')} deferred")

        print("\n== plan:Publish (dispatcher)")
        before = self.inbox_ids("driver")
        _, t0 = a.must("dispatcher", "plan:Publish", {"planId": plan_id}, version=version)
        step = Step("publish")
        step.stream("dispatcher", "change topic plan", self.streams["changes"], topic("plan"))
        step.poll("dispatcher", "published plan readable",
                  lambda: a.get("dispatcher", "/api/plans/published", depot=self.depot, date=day.isoformat()).status_code, lambda c: c == 200)
        step.poll("loader", "dock board lists the trips", lambda: a.json("loader", "/api/loading/trips", depot=self.depot, date=day.isoformat()),
                  lambda x: len(x) > 0)
        self.notify_checks(step, "driver", "plan.published", before, t0)
        step.run(t0)

    def pick_trip(self):
        a = self.api
        vehicles = set(a.json("driver", "/api/execution/vehicles", date=self.day.isoformat()))
        best = None
        for t in a.json("loader", "/api/loading/trips", depot=self.depot, date=self.day.isoformat()):
            if t["status"] != "NOT_STARTED" or t["stopCount"] < 2 or t["vehicleId"] not in vehicles:
                continue
            if best is None or (t["stopCount"], t["orderCount"]) < (best["stopCount"], best["orderCount"]):
                best = t
        if best is None:
            raise Abort("no trip with two or more stops that the demo driver may drive; is seed-delivery-day or demo:ResetDay done?")
        return best

    def ensure_outlet(self, outlet):
        a = self.api
        if f"outlet:{outlet}" in a.scopes["store_manager"]:
            return
        uid = a.users["store_manager"]
        version = a.json("admin", f"/api/accounts/{uid}")["rowVersion"]
        a.must("admin", "iam:GrantScope", {"userId": uid, "outletId": outlet}, version=version)
        a.scopes["store_manager"].append(f"outlet:{outlet}")

    def load_and_release(self):
        a, day = self.api, self.day
        trip = self.pick_trip()
        self.trip, self.vehicle = trip["tripId"], trip["vehicleId"]
        manifest = a.json("loader", f"/api/loading/trips/{self.trip}/manifest")
        stops = sorted({(l["stopSequence"], l["outletId"], l["orderId"], l["plannedArrival"], l["windowOpen"], l["windowClose"]) for l in manifest["lines"]})
        self.stops = [dict(zip(("seq", "outlet", "order", "planned", "open", "close"), s)) for s in stops]
        first, second = self.stops[0], self.stops[1]
        print(f"\n== Trip {self.trip}: {self.vehicle} trip {trip['tripNumber']}, stops "
              + ", ".join(f"{s['seq']}:{s['outlet']} {s['planned'][:5]}" for s in self.stops))
        print("  store manager scope for the first two stops")
        self.ensure_outlet(first["outlet"])
        self.ensure_outlet(second["outlet"])
        self.open_stream("pos-outlet1", "store_manager", f"/api/execution/positions/stream?outlet={first['outlet']}&date={day}")
        self.open_stream("pos-outlet2", "store_manager", f"/api/execution/positions/stream?outlet={second['outlet']}&date={day}")

        # R-LOD-13: a trip is taken for loading only once its driver has reported at the depot.
        print("\n== delivery:ArriveAtDepot (driver)")
        a.must("driver", "delivery:ArriveAtDepot", {"vehicleId": self.vehicle, "serviceDate": day.isoformat()})
        time.sleep(3)  # Loading hears it through the outbox relay

        print("\n== Loader PIN operator")
        loader = a.users["loader"]
        r = a.sessions["loader"].post(a.base + "/api/session/operator", json={"userId": loader, "pin": self.args.loader_pin}, timeout=15)
        if r.status_code != 200:
            raise Abort(f"POST /api/session/operator -> {r.status_code} {short(r.text)}; set --loader-pin to DEMO_LOADER_PIN")
        print("  loader unlocked the dock screen")

        print("\n== loading:Start (loader)")
        result, t0 = a.must("loader", "loading:Start", {"tripId": self.trip}, version=manifest["rowVersion"], acting=loader)
        Step("loading start").stream("dispatcher", "change topic loading", self.streams["changes"], topic("loading")) \
            .poll("dispatcher", "dock board shows loading started", lambda: self.dock("dispatcher")["status"], lambda s: s != "NOT_STARTED").run(t0)
        version = result["rowVersion"]
        checks = 0
        for line in manifest["lines"]:
            for item in line["items"]:
                r, _ = a.command("loader", "loading:Check", {"tripId": self.trip, "orderId": line["orderId"], "lineNo": item["lineNo"], "status": "LOADED"},
                                 version=version, acting=loader)
                if r.status_code != 200:
                    print(f"  loader loading:Check -> {r.status_code} {short(r.text, 600)}")
                    raise Abort("loading:Check refused")
                version = r.json()["result"]["rowVersion"]
                checks += 1
        print(f"  loader loading:Check x{checks} -> 200, trip {r.json()['result']['status']}")

        print("\n== loading:Release (loader)")
        before = {role: self.inbox_ids(role) for role in ("driver", "store_manager")}
        _, t0 = a.must("loader", "loading:Release", {"tripId": self.trip, "doorsSealed": True, "ordersSecured": True, "driverPresent": True},
                       version=version, acting=loader)
        step = Step("release")
        step.stream("dispatcher", "change topic trip", self.streams["changes"], topic("trip"))
        step.poll("loader", "dock board shows COMPLETED", lambda: self.dock("loader")["status"], lambda s: s == "COMPLETED")
        step.poll("dispatcher", "Live run sheet has the trip", lambda: self.sheet("dispatcher", self.vehicle), lambda x: len(x) > 0)
        step.poll("driver", "run sheet has the trip", lambda: self.sheet("driver", self.vehicle), lambda x: len(x) > 0)
        for s in (first, second):
            step.poll("store_manager", f"{s['outlet']} deliveries list the stop",
                      lambda o=s["outlet"]: [x for x in a.json("store_manager", "/api/execution/deliveries", outlet=o, date=day.isoformat()) if x["tripId"] == self.trip],
                      lambda x: len(x) > 0)
        self.notify_checks(step, "driver", "trip.released", before["driver"], t0)
        self.notify_checks(step, "store_manager", "trip.released", before["store_manager"], t0)
        step.run(t0)
        sheet = self.sheet("driver", self.vehicle)
        if not sheet:
            raise Abort("the driver's run sheet never showed the released trip")
        by_seq = {st["sequence"]: st for st in sheet}
        self.s1, self.s2 = by_seq[first["seq"]], by_seq[second["seq"]]

    def record_positions(self, name, points, extra=None):
        a = self.api
        print(f"\n== delivery:RecordPositions (driver) {name}")
        lat = points[-1][0]
        _, t0 = a.must("driver", "delivery:RecordPositions", {"vehicleId": self.vehicle, "tripId": self.trip, "points": self.fixes(points)})
        step = Step(f"positions {name}")
        step.poll("dispatcher", "positions read has the fix", lambda: self.positions("dispatcher", self.vehicle, depot=self.depot),
                  lambda p: p is not None and abs(float(p["latitude"]) - lat) < 1e-6)
        step.stream("dispatcher", "positions stream has the fix", self.streams["pos-depot"], position_pred(self.vehicle, lat))
        if extra:
            extra(step, lat)
        step.run(t0)

    def drive(self):
        a, day = self.api, self.day
        first, second = self.stops[0], self.stops[1]
        depot = a.json("dispatcher", f"/api/reference/depots/{self.depot}")["location"]
        out1 = a.json("store_manager", f"/api/reference/outlets/{first['outlet']}")["location"]
        out2 = a.json("store_manager", f"/api/reference/outlets/{second['outlet']}")["location"]
        dlat, dlon = float(depot["latitude"]), float(depot["longitude"])
        lat1, lon1 = float(out1["latitude"]), float(out1["longitude"])
        lat2, lon2 = float(out2["latitude"]), float(out2["longitude"])

        def toward(f, la, lo):
            return (round(dlat + (la - dlat) * f, 6), round(dlon + (lo - dlon) * f, 6))

        def store_stream(key, outlet):
            def extra(step, lat):
                step.poll("store_manager", f"{outlet} positions read has the vehicle",
                          lambda: self.positions("store_manager", self.vehicle, outlet=outlet),
                          lambda p: p is not None and abs(float(p["latitude"]) - lat) < 1e-6)
                step.stream("store_manager", f"{outlet} positions stream has the fix", self.streams[key], position_pred(self.vehicle, lat))
            return extra

        self.record_positions("leaving the depot", [toward(0.0, lat1, lon1), toward(0.2, lat1, lon1), toward(0.4, lat1, lon1)],
                              store_stream("pos-outlet1", first["outlet"]))

        print("\n== delivery:Start (driver), first stop")
        before = self.inbox_ids("store_manager")
        started, t0 = a.must("driver", "delivery:Start", {"deliveryId": self.s1["deliveryId"]}, version=self.s1["rowVersion"])
        self.s1_version = started["rowVersion"]
        step = Step("start")
        step.stream("dispatcher", "change topic delivery", self.streams["changes"], topic("delivery"))
        step.poll("dispatcher", "Live run sheet shows started", lambda: self.stop_of("dispatcher", self.vehicle, self.s1["deliveryId"])["startedAt"],
                  lambda x: x is not None)
        step.poll("store_manager", f"{first['outlet']} delivery shows started", lambda: self.store_delivery(first["outlet"], self.s1["deliveryId"])["startedAt"],
                  lambda x: x is not None)
        self.notify_checks(step, "store_manager", "delivery.started", before, t0)
        step.run(t0)

        print("\n== First stop late estimate (R-EXE-29): business clock past the first stop's planned time")
        planned = max(depot_time(day, first["planned"]), depot_time(day, first["open"]))
        target = planned + dt.timedelta(minutes=40)
        before = self.inbox_ids("store_manager")
        if self.now() < target:
            self.set_clock(target, "live check: the vehicle is late for its first stop")
        t0 = time.time()
        print(f"  business time {self.now().astimezone(ZONE):%H:%M}, first stop planned {first['planned'][:5]}; waiting for the eta refresh job (every 2 min)")
        step = Step("first-stop estimate")
        step.poll("store_manager", f"{first['outlet']} expectedArrival set before arrival",
                  lambda: self.store_delivery(first["outlet"], self.s1["deliveryId"])["expectedArrival"], lambda x: x is not None,
                  timeout=JOB_TIMEOUT_S, slow=JOB_TIMEOUT_S)
        step.stream("dispatcher", "change topic eta", self.streams["changes"], topic("eta"), timeout=JOB_TIMEOUT_S, slow=JOB_TIMEOUT_S)
        self.notify_checks(step, "store_manager", "eta.changed", before, t0, timeout=JOB_TIMEOUT_S)
        step.run(t0)

        self.record_positions("at the first stop", [toward(0.7, lat1, lon1), toward(0.9, lat1, lon1), (lat1, lon1)],
                              store_stream("pos-outlet1", first["outlet"]))

        print("\n== delivery:RecordArrival (driver), late, with deviceArrivedAt from the business clock")
        before = self.inbox_ids("store_manager")
        result, t0 = a.must("driver", "delivery:RecordArrival", {"deliveryId": self.s1["deliveryId"], "deviceArrivedAt": iso(self.now())},
                            version=self.s1_version)
        print(f"  arrival: lateMinutes {result.get('lateMinutes')}, timingUncertain {result.get('timingUncertain')}")
        if result.get("timingUncertain"):
            RESULTS.add("arrival", "FAIL", "driver", "arrival timing is certain", "command", None, "timingUncertain true with the business-clock device time")
        step = Step("arrival")
        step.stream("dispatcher", "change topic delivery", self.streams["changes"], topic("delivery"))
        step.poll("dispatcher", "Live run sheet shows arrived", lambda: self.stop_of("dispatcher", self.vehicle, self.s1["deliveryId"])["arrivedAt"],
                  lambda x: x is not None)
        step.poll("store_manager", f"{first['outlet']} delivery shows arrived", lambda: self.store_delivery(first["outlet"], self.s1["deliveryId"])["arrivedAt"],
                  lambda x: x is not None)
        step.poll("store_manager", f"{second['outlet']} (next stop) has an expectedArrival",
                  lambda: self.store_delivery(second["outlet"], self.s2["deliveryId"])["expectedArrival"], lambda x: x is not None)
        step.poll("driver", "run sheet next stop has an expectedArrival", lambda: self.stop_of("driver", self.vehicle, self.s2["deliveryId"])["expectedArrival"],
                  lambda x: x is not None)
        self.notify_checks(step, "store_manager", "delivery.arrived", before, t0)
        step.run(t0)

        print("\n== delivery:Record DELIVERED (driver)")
        before = self.inbox_ids("store_manager")
        payload = {"deliveryId": self.s1["deliveryId"], "outcome": "DELIVERED", "deliveredUnits": None, "reason": None, "dispositionNote": None}
        r, t0 = a.command("driver", "delivery:Record", payload, version=result["rowVersion"])
        if r.status_code == 409 and "R-EXE-05" in r.text:
            payload["reason"] = "Live check: held up on the way"
            r, t0 = a.command("driver", "delivery:Record", payload, version=result["rowVersion"])
        if r.status_code != 200:
            print(f"  driver delivery:Record -> {r.status_code} {short(r.text, 800)}")
            raise Abort("delivery:Record refused")
        print("  driver delivery:Record -> 200")
        step = Step("delivered")
        step.stream("dispatcher", "change topic delivery", self.streams["changes"], topic("delivery"))
        step.poll("dispatcher", "Live run sheet shows DELIVERED", lambda: self.stop_of("dispatcher", self.vehicle, self.s1["deliveryId"])["outcome"],
                  lambda x: x == "DELIVERED")
        step.poll("store_manager", f"{first['outlet']} delivery shows DELIVERED", lambda: self.store_delivery(first["outlet"], self.s1["deliveryId"])["outcome"],
                  lambda x: x == "DELIVERED")
        step.poll("store_manager", "receipt is pending the store's answer", lambda: a.json("store_manager", "/api/receipts/pending", outlet=first["outlet"]),
                  lambda x: any(p.get("orderId") == self.s1["orderId"] for p in x))
        self.notify_checks(step, "store_manager", "delivery.completed", before, t0)
        step.run(t0)

        def after_first(step, lat):
            store_stream("pos-outlet2", second["outlet"])(step, lat)
            step.absent("store_manager", f"{first['outlet']} (delivered) gets no more fixes", self.streams["pos-outlet1"], position_pred(self.vehicle, lat))

        self.record_positions("toward the second stop", [(lat1, lon1), (round((lat1 + lat2) / 2 + 0.001, 6), round((lon1 + lon2) / 2, 6))], after_first)

    def receipt_and_messages(self):
        a = self.api
        order = self.s1["orderId"]
        print("\n== receipt:Confirm (store manager)")
        receipt = a.json("store_manager", f"/api/receipts/{order}")
        _, t0 = a.must("store_manager", "receipt:Confirm", {"orderId": order}, version=receipt["rowVersion"])

        def answer():
            r = a.get("driver", f"/api/receipts/{order}/answer")
            return r.json().get("status") if r.status_code == 200 else r.status_code

        Step("receipt").stream("dispatcher", "change topic receipt", self.streams["changes"], topic("receipt")) \
            .poll("driver", "sees the store's answer CONFIRMED", answer, lambda s: s == "CONFIRMED") \
            .poll("dispatcher", "order shows received", lambda: a.json("dispatcher", f"/api/orders/{order}")["status"], lambda s: "RECEIV" in str(s)).run(t0)

        print("\n== message:Post (dispatcher to the driver)")
        thread = a.json("dispatcher", "/api/threads/by-subject", type="trip", id=self.trip)["threadId"]
        body = f"Live check {uuid.uuid4().hex[:6]}: take the bypass"
        before = self.inbox_ids("driver")
        _, t0 = a.must("dispatcher", "message:Post", {"threadId": thread, "to": "driver", "body": body})

        def messages(role):
            return a.json(role, f"/api/threads/{thread}/messages")["items"]

        step = Step("message to driver")
        step.stream("dispatcher", "change topic message", self.streams["changes"], topic("message"))
        step.poll("driver", "thread shows the message", lambda: messages("driver"), lambda x: any(m["body"] == body for m in x))
        self.notify_checks(step, "driver", "message.posted", before, t0, contains=body)
        step.run(t0)

        note = self.new_notification("driver", before, "message.posted", body)()
        if note is None:
            RESULTS.add("message read", "FAIL", "driver", "notification to mark read", "poll", None, "no message.posted notification found")
        else:
            print("\n== notification:MarkRead (driver)")
            _, t0 = a.must("driver", "notification:MarkRead", {"notificationIds": [note["notificationId"]]})

            def seen():
                return [m for m in messages("dispatcher") if m["body"] == body][0].get("seenByDriver")

            Step("message read").poll("dispatcher", "message shows seenByDriver", seen, lambda x: x is True).run(t0)

        print("\n== message:Post (store manager on the trip channel)")
        body2 = f"Live check {uuid.uuid4().hex[:6]}: our dock is clear"
        before = self.inbox_ids("dispatcher")
        _, t0 = a.must("store_manager", "message:Post", {"threadId": thread, "to": "channel", "body": body2})
        step = Step("store message")
        step.stream("dispatcher", "change topic message", self.streams["changes"], topic("message"))
        step.poll("dispatcher", "thread shows the store's message", lambda: messages("dispatcher"), lambda x: any(m["body"] == body2 for m in x))
        self.notify_checks(step, "dispatcher", "message.posted", before, t0, contains=body2)
        step.run(t0)


def summary():
    rows = RESULTS.rows
    counts = {k: sum(1 for r in rows if r[1] == k) for k in ("PASS", "SLOW", "FAIL")}
    print("\n== Summary")
    print(f"  {'status':<6} {'step':<34} {'role':<13} {'delay':>8}  check")
    for step, status, role, what, how, delay, _ in rows:
        shown = "-" if delay is None else f"{delay:.2f} s"
        print(f"  {status:<6} {step:<34} {role:<13} {shown:>8}  {what} ({how})")
    print(f"\n  {counts['PASS']} PASS, {counts['SLOW']} SLOW, {counts['FAIL']} FAIL")
    return counts["FAIL"]


def main():
    p = argparse.ArgumentParser(description="Cross-role live sync check of one delivery day on a local stack.")
    p.add_argument("--base", default="http://127.0.0.1:8080", help="backend base URL (default %(default)s)")
    p.add_argument("--depot", default="Peliyagoda", help="depot code (default %(default)s)")
    p.add_argument("--date", help="service day YYYY-MM-DD (default: the next unplanned day with orders, else a fresh demo:ResetDay day)")
    p.add_argument("--password", default=os.environ.get("SEED_PASSWORD", "Waypoint2026!"), help="demo account password (default SEED_PASSWORD or Waypoint2026!)")
    p.add_argument("--loader-pin", default=os.environ.get("DEMO_LOADER_PIN", "2468"), help="loader PIN (default DEMO_LOADER_PIN or 2468)")
    p.add_argument("--no-reset", action="store_true", help="never run demo:ResetDay; stop and say what to run instead")
    p.add_argument("--allow-remote", action="store_true",
                   help="run against a backend that is not on this machine (it resets a day, grants scopes and moves the demo clock there)")
    args = p.parse_args()
    host = urllib.parse.urlparse(args.base).hostname or ""
    if host not in ("127.0.0.1", "localhost", "::1") and not args.allow_remote:
        print(f"Refusing {args.base}: this check changes data and the demo clock. Run it on a local stack, "
              "or pass --allow-remote if you really mean that server.")
        sys.exit(2)

    check = LiveCheck(args)
    aborted = None
    try:
        check.run()
    except Abort as e:
        aborted = str(e)
    except requests.ConnectionError as e:
        aborted = f"cannot reach {args.base}: {e}. Start the stack with scripts/dev.sh."
    finally:
        for s in check.streams.values():
            s.stop = True
    if aborted:
        RESULTS.add("aborted", "FAIL", "-", aborted.splitlines()[0], "run", None)
        print(f"\nStopped: {aborted}")
    fails = summary()
    if check.day:
        print(f"  service day {check.day}; the demo clock was left where the run moved it")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
