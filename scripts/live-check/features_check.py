#!/usr/bin/env python3
"""
Today's features on the running local stack, after live_check.py has driven a
delivery day: each check sends real commands as the role that would, then reads
the outcome back as the other roles. Nothing is mocked. Prints PASS or FAIL per
check and a summary. Local only: it changes the database and the demo clock.

    python3 scripts/live-check/live_check.py        # a delivery day first
    python3 scripts/live-check/features_check.py
"""
import datetime as dt
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(__file__))
from live_check import Api  # noqa: E402

BASE = os.environ.get("BASE", "http://127.0.0.1:8080")
PASSWORD = os.environ.get("SEED_PASSWORD", "Waypoint2026!")
DEPOT = "Peliyagoda"
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((ok, name, detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  - ' + detail if detail and not ok else ''}")
    return ok


def poll(fn, until, timeout=120, every=2):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = fn()
            if until(last):
                return last
        except Exception as e:  # a read that fails is retried until the timeout
            last = e
        time.sleep(every)
    return last


a = Api(BASE, PASSWORD)
for role in ("admin", "dispatcher", "loader", "driver", "store_manager", "simdriver"):
    a.sign_in(role)
demo = a.json("admin", "/api/demo")
now = dt.datetime.fromisoformat(demo["now"].replace("Z", "+00:00"))
day = (now + dt.timedelta(hours=5, minutes=30)).date()
print(f"Features on {BASE}, depot {DEPOT}, business day {day} (demo clock {demo['now']})")


def trips():
    return a.json("loader", "/api/loading/trips", depot=DEPOT, date=day.isoformat())


def vehicles_of(role):
    return set(a.json(role, "/api/execution/vehicles", date=day.isoformat()))


# ---- item 9: each stop's store manager and phone, read for the trip at once ----
print("\n== Store phone on the trip (item 9, R-IAM-41)")
profile = a.json("store_manager", "/api/profile")
a.must("store_manager", "iam:UpdateOwnProfile", {"displayName": profile.get("displayName", "Demo store manager"), "phone": "0771234567"},
       version=profile["rowVersion"])
released = [t for t in trips() if t["status"] == "COMPLETED"]
check("a released trip exists from the live check", len(released) > 0)
sheet = [x for x in a.json("dispatcher", "/api/execution/run-sheets", date=day.isoformat(), depot=DEPOT) if released and x["vehicleId"] == released[0]["vehicleId"]]
outlets = sorted({s["outletId"] for x in sheet for s in x["stops"]})
contacts = a.json("dispatcher", "/api/outlets/contacts", ids=",".join(outlets)) if outlets else {}
phones = [c.get("phone") for v in contacts.values() for c in v]
check("the dispatcher reads every stop's store managers in one request", set(contacts) == set(outlets), str(contacts))
check("the store manager's own phone is on the trip", "0771234567" in phones, str(contacts))
far = a.get("dispatcher", "/api/outlets/contacts", ids=",".join(outlets + ["OUT076"]))
check("an outlet of another depot refuses the whole read (404)", far.status_code == 404, str(far.status_code))

# ---- item 5: a vehicle out of use before its trip, the plan follows ------------
print("\n== Vehicle not available before the trip (item 5, R-FLT-06)")
mine = vehicles_of("driver")
waiting = [t for t in trips() if t["status"] == "NOT_STARTED" and t["vehicleId"] in mine]
check("the demo driver has a published trip not yet loaded", len(waiting) > 0)
if waiting:
    lost = waiting[0]["vehicleId"]
    other_driver = a.get("simdriver", "/api/commands")  # warm the session
    r, _ = a.command("simdriver", "vehicle:SetDayStatus", {"vehicleId": lost, "serviceDate": day.isoformat(), "status": "unavailable"})
    check("another driver cannot mark this vehicle (403)", r.status_code == 403, str(r.status_code))
    a.must("driver", "vehicle:SetDayStatus", {"vehicleId": lost, "serviceDate": day.isoformat(), "status": "unavailable"})
    draft = poll(lambda: a.json("dispatcher", "/api/plans/draft", depot=DEPOT, date=day.isoformat()),
                 lambda d: isinstance(d, dict) and d.get("supersedes") and lost in (d.get("revisionReason") or ""), timeout=60)
    ok = isinstance(draft, dict) and lost in (draft.get("revisionReason") or "")
    check("Planning drafts a revision naming the vehicle", ok, str(draft)[:300])
    if ok:
        left = {t["tripId"] for t in trips() if t["status"] != "NOT_STARTED"}
        check("only the lost vehicle's trips that already left stay on it (FLT-08)",
              all(t["tripId"] in left for t in draft["trips"] if t["vehicleId"] == lost))
    titles = poll(lambda: [n["title"] for n in a.json("dispatcher", "/api/notifications")["items"]],
                  lambda ts: isinstance(ts, list) and any(f"{lost} out of use" in t for t in ts), timeout=60)
    check("the dispatcher is told at once", isinstance(titles, list) and any(f"{lost} out of use" in t for t in titles), str(titles)[:200])
    if ok:
        a.must("dispatcher", "plan:Publish", {"planId": draft["planId"]}, version=draft["rowVersion"])
        board = poll(trips, lambda ts: isinstance(ts, list) and all(t["vehicleId"] != lost or t["status"] != "NOT_STARTED" for t in ts), timeout=60)
        check("after the update is sent, the loader has nothing left to load on the lost vehicle",
              isinstance(board, list) and all(t["vehicleId"] != lost or t["status"] != "NOT_STARTED" for t in board))
    a.must("driver", "vehicle:SetDayStatus", {"vehicleId": lost, "serviceDate": day.isoformat(), "status": "available"})
    titles = poll(lambda: [n["title"] for n in a.json("dispatcher", "/api/notifications")["items"]],
                  lambda ts: isinstance(ts, list) and any(f"{lost} back in use" in t for t in ts), timeout=60)
    check("back in use is told too", isinstance(titles, list) and any(f"{lost} back in use" in t for t in titles))

# ---- item 15: a simulated truck records its own stops ---------------------------
print("\n== Demo trucks record their stops (item 15, R-DEMO-04)")
sim_vehicles = vehicles_of("simdriver")
candidates = [t for t in trips() if t["status"] == "NOT_STARTED" and t["vehicleId"] in sim_vehicles and t["stopCount"] >= 2]
check("the second driver has a trip to load", len(candidates) > 0)
if candidates:
    t = min(candidates, key=lambda x: x["stopCount"])
    trip, vehicle = t["tripId"], t["vehicleId"]
    a.must("simdriver", "delivery:ArriveAtDepot", {"vehicleId": vehicle, "serviceDate": day.isoformat()})
    time.sleep(3)
    loader = a.users["loader"]
    a.sessions["loader"].post(a.base + "/api/session/operator", json={"userId": loader, "pin": os.environ.get("DEMO_LOADER_PIN", "2468")}, timeout=15)
    manifest = a.json("loader", f"/api/loading/trips/{trip}/manifest")
    result, _ = a.must("loader", "loading:Start", {"tripId": trip}, version=manifest["rowVersion"], acting=loader)
    version = result["rowVersion"]
    for line in manifest["lines"]:
        for item in line["items"]:
            r, _ = a.command("loader", "loading:Check", {"tripId": trip, "orderId": line["orderId"], "lineNo": item["lineNo"], "status": "LOADED"},
                             version=version, acting=loader)
            version = r.json()["result"]["rowVersion"]
    a.must("loader", "loading:Release", {"tripId": trip, "doorsSealed": True, "ordersSecured": True, "driverPresent": True},
           version=version, acting=loader)
    # The run sheet is built from the release through the outbox; a person would not be quicker.
    poll(lambda: [x for x in a.json("simdriver", "/api/execution/run-sheets", date=day.isoformat()) if x["vehicleId"] == vehicle],
         lambda xs: isinstance(xs, list) and len(xs) > 0, timeout=60)
    started = poll(lambda: a.command("admin", "demo:StartSimulation", {"reason": "Features check", "vehicleId": vehicle, "serviceDate": day.isoformat()})[0].status_code,
                   lambda code: code == 200, timeout=30, every=3)
    check("the admin starts the simulation of the released trip", started == 200, str(started))
    # The demo driver's own released trip is left to a person.
    person = released[0]["vehicleId"] if released else None
    if person:
        r, _ = a.command("admin", "demo:StartSimulation", {"reason": "Features check", "vehicleId": person, "serviceDate": day.isoformat()})
        check("the demo driver's truck is not simulated", r.status_code == 409 and "a person drives it" in r.text, f"{r.status_code} {r.text[:200]}")
    sims = poll(lambda: [s for s in a.json("admin", "/api/demo/simulations") if s["vehicle_id"] == vehicle],
                lambda ss: isinstance(ss, list) and ss and ss[0]["status"] in ("finished", "failed"), timeout=600, every=5)
    sim = sims[0] if isinstance(sims, list) and sims else {}
    check("the simulation finishes", sim.get("status") == "finished", str(sim)[:300])
    check("it recorded every stop it drove to", sim.get("stops_recorded") == sim.get("stops_total") and (sim.get("stops_total") or 0) >= 2, str(sim)[:300])
    stops = [s for x in a.json("dispatcher", "/api/execution/run-sheets", date=day.isoformat(), depot=DEPOT) if x["vehicleId"] == vehicle for s in x["stops"]]
    check("the dispatcher sees each stop delivered", stops and all(s["outcome"] == "DELIVERED" for s in stops), str([s["outcome"] for s in stops]))
    check("each arrival came first", stops and all(s.get("arrivedAt") for s in stops))

# ---- item 8: the permission editor, end to end ----------------------------------
print("\n== Permission editor (item 8, R-IAM-42)")
name = "E2EAuditReader" + uuid.uuid4().hex[:6]
loader_id = a.users["loader"]
doc_none = {"Version": "e2e", "Statement": [{"Sid": "Nothing", "Effect": "Allow", "Action": ["iam:RegisterDevice"], "Resource": ["wpt:iam:device:none"]}]}
doc_audit = {"Version": "e2e", "Statement": [{"Sid": "Audit", "Effect": "Allow", "Action": ["audit:Read"], "Resource": ["wpt:platform:*"]}]}
a.must("admin", "iam:CreatePolicy", {"name": name, "description": "features check", "document": doc_none})
pol = lambda: next(p for p in a.json("admin", "/api/policies") ["items"] if p["name"] == name) if False else next(p for p in a.get("admin", "/api/policies", limit=200).json()["items"] if p["name"] == name)
a.must("admin", "iam:AttachPolicy", {"name": name, "principalType": "user", "principalId": loader_id}, version=pol()["rowVersion"])
check("before: the loader cannot read the audit log", a.get("loader", "/api/audit").status_code == 403)
preview = a.sessions["admin"].post(a.base + f"/api/policies/{name}/preview-change", json={"document": doc_audit}, timeout=30).json()
gains = [c for c in preview.get("changes", []) if "audit:Read" in c.get("gained", [])]
check("What changes names the loader gaining audit:Read", any("Demo loader" in " ".join(c["people"]) for c in gains), str(preview)[:300])
bad = a.sessions["admin"].post(a.base + f"/api/policies/{name}/preview-change",
                               json={"document": {"Statement": [{"Sid": "Typo", "Effect": "Allow", "Action": ["audit:Raed"], "Resource": ["*"]}]}}, timeout=30).json()
check("a misspelt action is named beside its rule", any("audit:Raed" in p["message"] for p in bad.get("problems", [])), str(bad))
denied = a.sessions["loader"].post(a.base + f"/api/policies/{name}/preview-change", json={"document": doc_audit}, timeout=30)
check("a loader cannot preview (403)", denied.status_code == 403, str(denied.status_code))
created, _ = a.must("admin", "iam:CreatePolicyVersion", {"name": name, "document": doc_audit, "reason": "features check"}, version=pol()["rowVersion"])
a.must("admin", "iam:SetDefaultPolicyVersion", {"name": name, "versionNumber": created["versionNumber"], "reason": "features check"}, version=pol()["rowVersion"])
check("after save: the loader can read the audit log on the next request", a.get("loader", "/api/audit").status_code == 200)
a.must("admin", "iam:SetDefaultPolicyVersion", {"name": name, "versionNumber": 1, "reason": "restore"}, version=pol()["rowVersion"])
check("after restore: refused again", a.get("loader", "/api/audit").status_code == 403)
a.must("admin", "iam:DetachPolicy", {"name": name, "principalType": "user", "principalId": loader_id}, version=pol()["rowVersion"])

# ---- the AI parts on this day ----------------------------------------------------
print("\n== Jev reads a driver's typed report (R-ML-33)")
if released:
    thread = a.json("driver", "/api/threads/by-subject", type="trip", id=released[0]["tripId"])["threadId"]
    a.must("driver", "message:Post", {"threadId": thread, "to": "dispatch", "report": "road_disruption", "body": "Road flooded near the Kelani bridge, cannot pass"})
    health = poll(lambda: a.json("admin", "/api/ml/ai-health", days=1)["jev"], lambda j: isinstance(j, dict) and j.get("answered", 0) >= 1, timeout=150, every=10)
    check("Jev answered the report in shadow", isinstance(health, dict) and health.get("answered", 0) >= 1, str(health)[:200])

ok = sum(1 for r in RESULTS if r[0])
print(f"\n== Summary: {ok} PASS, {len(RESULTS) - ok} FAIL")
for passed, name, detail in RESULTS:
    if not passed:
        print(f"  FAIL  {name}  {detail[:300]}")
sys.exit(0 if ok == len(RESULTS) else 1)
