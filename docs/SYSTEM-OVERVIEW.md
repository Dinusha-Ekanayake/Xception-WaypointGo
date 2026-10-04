# Waypoint Dispatch: system overview

Audience: the team and the agents building Waypoint Dispatch. This is the **target product vision**: who uses the system, what each of them does, how orders, trips and vehicles move through their states, and how people talk to each other when something goes wrong. It says what the product must be, not how far the build has got.

How this sits beside the other documents:

| Question | Document |
| --- | --- |
| What must the product do, and for whom | **This document** |
| How it is built: principles, modules, layers | [SYSTEM-ARCHITECTURE.md](../SYSTEM-ARCHITECTURE.md), [MODULES.md](architecture/MODULES.md) |
| Which rule decides what | [RULES-AND-POLICIES.md](architecture/RULES-AND-POLICIES.md) |
| What happens when things fail | [EDGE-CASES.md](architecture/EDGE-CASES.md) |
| What is built and what is left | [STATUS.md](development-docs/STATUS.md) |
| Which word to use on screen | [GLOSSARY.md](architecture/GLOSSARY.md) |

Where this vision differs from what is built today, the difference is listed in [Appendix A](#appendix-a-where-the-vision-differs-from-the-build). That appendix is the work list that turns the build into this vision.

---

## 1. What the system is for

Waypoint Dispatch plans and runs next-day deliveries from depots to outlets. Store managers order, a dispatcher turns the day's orders into a plan of trips, loaders load each vehicle, drivers deliver, and the store confirms what arrived.

Today the people in that chain coordinate by phone. Calls are not recorded, nobody else hears them, and afterwards nobody can say what was agreed or why. Waypoint replaces that with **one common ground**: every person on a trip sees the same state and talks in the same place, and every decision is kept.

### 1.1 Principles

1. **One connected flow, not separate tools.** Ordering, planning, loading, delivery and receipt are one chain. Each step starts from what the previous step published, and a change anywhere is visible everywhere it matters.
2. **The system gives people the means; people decide.** Waypoint does not try to solve every operational problem automatically. When something unusual happens (a cancel on the road, a breakdown, a closed outlet), the system makes sure the right people hear about it, gives them a channel to agree, and offers every action they might choose (skip a stop, defer an order, send a replacement vehicle). The person with the authority picks one.
3. **Hard rules are hard.** Capacity, temperature, vehicle access, delivery windows and fuel quotas are never broken, by the engine or by a person. A screen offers only the moves the rules allow and says why the others are blocked.
4. **Every decision is recorded.** A deferral, a cancellation, a breakdown decision or a failed delivery carries who decided, why and when. The conversation that led to it is kept beside it.
5. **The channel is the record.** Messages, calls and system events on a trip form one timeline. Afterwards it shows what happened, who knew what and when, and which kind of case it was.

---

## 2. Roles

| Role | Scope | Main device | In one line |
| --- | --- | --- | --- |
| **Store manager** | One outlet | Phone or counter desktop | Orders, follows the delivery, receives it, reports problems |
| **Dispatcher** | One or more depots | Large screen | Turns orders into a plan, publishes it, runs the day, decides exceptions |
| **Loader** | One depot | Dock tablet or phone | Loads each vehicle to its manifest |
| **Driver** | One vehicle on one day | Phone | Takes the vehicle out, runs the stops, hands over |
| **Admin** | Global | Desktop | Accounts, roles, scopes, outlets, the fleet and the calendar |

The admin also manages the fleet: with the driver, the admin is the one who puts a vehicle in the workshop and takes it out again.

---

## 3. A day in the system

```
 any time           16:00 cutoff         evening            early morning           during the day
 ─────────          ────────────         ───────            ─────────────           ──────────────
 Store places  ──►  Orders for       ──► Dispatcher     ──► Loader loads      ──►  Driver runs stops
 orders, edits      tomorrow are         reviews the         each vehicle,          arrive · unload · leave
 freely             fixed                optimised plan,     marks complete         store gets PIN handover,
                    later orders go      decides, publishes  Driver starts trip     confirms receipt
                    to the next day
                                         ◄──────────── trip channel open from publish to trip end ────────────►
```

---

## 4. Store manager

### 4.1 Profile and outlet

- Sees and edits their own profile: name and mobile number.
- Sees the outlet they are assigned to: its name, type, brand, district, depot and access details.
- Changes the outlet's delivery settings that are theirs to declare: the **default gate** (rear dock, street, front door and similar) and the delivery window. A mall outlet stays inside the mall's own hours.

### 4.2 Placing an order

- Chooses the products and units, and the **delivery date**: tomorrow, or a chosen future date. There are no recurring orders.
- On sending, the order goes to the warehouse for a **stock check**. The store sees the result at once:

| Result | What the store sees | What the store can do |
| --- | --- | --- |
| Stock is available | Order placed, with its delivery date | Nothing more; it may still edit until the order is fixed |
| Some lines are short | Each short line with the units available | **Edit and resend**, or **accept the partial** order |
| The warehouse cannot be reached | Not placed, "the warehouse is not answering, try again" | Try again; nothing was saved |

- An order is never shown as placed until the warehouse has confirmed the stock.

### 4.3 Editing, fixing and cancel requests

- Until **16:00 the day before the delivery date**, the store edits or cancels the order freely.
- At 16:00 the order is **fixed**. From then on the store cannot change it.
- After the order is fixed, the only change a store can ask for is a **cancel request**. The request goes to the depot's dispatchers, and **the dispatcher alone** approves or refuses it, at any point in the order's life: before the plan is published, during loading, or with the goods on the road. The decision is recorded with its reason and the store is told.
- What happens to goods already loaded or on the road is agreed in the trip channel (see [section 9](#9-trip-channel)). The system offers the actions: take the stop off the run sheet, bring the goods back to the depot, or deliver anyway.

### 4.4 Following and receiving the delivery

- Sees the order's state at every step (see [section 10.1](#101-order)), the planned and expected arrival, and the vehicle on the map once it is on the way.
- Has a thread with the dispatcher, the loader and the driver for each delivery (see [section 9](#9-trip-channel)).
- At the door:
  1. Gives the driver a **handover PIN**. The driver enters it to prove the goods were handed over.
  2. **Confirms received** when the delivery is complete.
  3. **Reports a problem** (short, damaged, wrong goods) in the stop thread. A reported problem becomes an issue for the dispatcher.

---

## 5. Dispatcher

### 5.1 Before the cutoff

- Sees every order coming in for the depot, by delivery date, with its state.
- Sees the **forecast** (see [section 8](#8-forecast)) to know which coming days will be heavy.

### 5.2 After the cutoff: planning

- At 16:00 the next day's order set is fixed. An order placed after 16:00 belongs to the following day.
- The system produces an **optimised draft plan**: every fixed order is either placed on a trip or deferred, and every deferral names the rule that decided it.
- The dispatcher reviews and changes the plan: defers or brings back an order, swaps orders, moves an order to another trip, fixes a stop order, edits a trip. **Every edit is checked against the same rules as the engine.** Only allowed moves are offered. A blocked move says which rule blocks it. No rule can be broken, with or without a reason.
- **AI help** makes the dispatcher faster:
  - **Explain the plan:** why each order was placed where it is or deferred, in plain words.
  - **Suggest changes:** proposed swaps or deferrals to reconsider. The dispatcher accepts or rejects each one, and the choice is recorded.
  - **Risk warnings:** trips likely to run late, outlets deferred more than once, vehicles near their weekly fuel quota.
- When satisfied, the dispatcher **publishes** the plan. Publishing fixes the trips, sends each loader its manifests, gives each driver a run sheet, tells each store when to expect its delivery, and opens the trip channels.

### 5.3 During the day

- Watches every trip live: loading progress, departure, arrival and departure at each stop, delays, and positions on the map.
- Is a member of **every trip channel of the depot** (all of the depot's dispatchers are).
- Decides the exceptions, always after hearing the people involved in the channel:

| Situation | The dispatcher chooses from |
| --- | --- |
| Cancel request from a store | Approve or refuse. If the goods are already loaded or on the road, also what happens to them |
| Vehicle broken on the road | Wait for the repair, send a replacement vehicle and transfer the goods, or bring the vehicle back and defer the remaining stops |
| Delivery failed at a stop (outlet closed, goods refused) | Redeliver (the order goes first into the next plan) or cancel |
| Problem reported by a store | Resolve the issue: write off, no fault found, or another recorded outcome |

---

## 6. Loader

- After publication, receives the **manifest** of each trip at the depot: the orders and product lines in loading order.
- Loads each vehicle and checks each line.
- When goods are **short or damaged**, the loader records it and the vehicle **leaves with what there is**. The store is told automatically what will not arrive, and the shortfall appears in the trip channel.
- Marks **loading complete**. The driver cannot start the trip before that.

---

## 7. Driver

- Sees the vehicle assigned for the day, its run sheet and its loading state.
- Moves the vehicle through its day with a tap at each step, and the system fills in the rest (see [section 10.2](#102-vehicle)):
  1. **Arrived at depot.** The vehicle is ready for loading.
  2. **Start trip.** Only possible once the loader has marked loading complete.
  3. At each stop: **arrived**, **unloading**, **left**. The dispatcher and the store see each change at once.
  4. After the last stop, the vehicle is set to **returning** automatically. The driver taps **trip end** when back at the depot.
- Enters the store's **handover PIN** at each delivery.
- Reports a **breakdown** at any moment, and the dispatcher decides what happens next.
- Reports the vehicle **in workshop** or **back from workshop**.
- Works without signal: taps and messages are kept on the phone and sent when the connection returns.

---

## 8. Forecast

The forecast tells the dispatcher which coming days will be heavy, so they can prepare instead of discovering it at 16:00.

- **Demand per depot per day** for the weeks ahead: expected orders, weight and volume, split by brand and temperature.
- Set against the depot's **fleet capacity** for the same day.
- **Days where demand exceeds capacity are highlighted**, so the dispatcher can prepare: an earlier start, an extra vehicle, or deferrals planned in advance.
- A forecast says when it comes from a simpler fallback rather than the trained model.

---

## 9. Trip channel

The trip channel is the main feature of the system. It replaces the phone calls between dispatcher, loader, driver and store with one place to talk that everyone involved can see and that is kept afterwards.

### 9.1 Shape

```
 Trip VEH043 · Trip 1
 ├── Trip thread           dispatchers of the depot · loader · driver
 ├── Stop 1 thread         dispatchers · loader · driver · store manager of stop 1
 ├── Stop 2 thread         dispatchers · loader · driver · store manager of stop 2
 └── ...
```

- Each trip has **one trip thread** for the people who run it: every dispatcher of the depot, the loader and the driver.
- Each stop has **its own thread** with that stop's store manager. **Stores never see each other's threads** or the trip thread.

### 9.2 Lifetime

- A trip's channel **opens when the plan is published**.
- It stays open while the trip is in operation.
- When the trip finishes, it becomes **read-only** and is **kept** as the record of that trip.

### 9.3 Members and changes

- Members follow the roles on the trip: all depot dispatchers, the loader of the trip, the driver of the vehicle, and the store manager of each stop.
- When a person changes (the driver is swapped, a loader hands the trip to another, a store manager is replaced):
  - the **new member sees the thread from the moment they joined**, not the history before;
  - the **old member keeps read-only access** to what was said while they were a member;
  - the change appears in the thread as a system line ("Driver changed: K. Perera → S. Silva · 07:15").

### 9.4 How people talk

The channel steers people to the fastest and most recordable way to communicate, in this order:

1. **Template messages.** Pre-written messages for the common cases ("Running about 20 min late", "Outlet closed on arrival", "Short 2 units of a line", "Vehicle broken down", "Please cancel this order"). Each template carries a **case type**.
2. **Typed text or a voice note**, when no template fits.
3. **A call**, as a last resort. On a phone the call button opens the dialer; where calling is not possible the number is shown. The call is **logged in the thread** ("Driver called store · 06:42"); it is not recorded.

Driver messages and voice notes work **offline**: they wait on the phone, are sent on reconnect, and are marked as sent late.

### 9.5 System lines

Events from the rest of the system appear in the thread's timeline beside the messages, so the conversation and the facts read as one story:

```
 Plan published · 18:05
 Loading started · 04:10
 Short: 2 units of a line for stop 3 · 04:32 · the store has been told
 Trip started · 04:55
 Arrived at stop 1 · 05:40
 Left stop 1 · 05:58
 Cancel request for stop 3 approved by the dispatcher · 06:10
```

Notifications still go to each person's inbox as well. The thread is the shared timeline; the inbox is the personal alert.

### 9.6 Cases and issues

- A message sent from a template carries its **case type**, so a thread can be identified later as, for example, a late arrival, a closed outlet or a breakdown.
- A thread with a case can be **turned into an issue** in one step, linked to the trip, the stop and the order.
- Case types are counted in reports, so the depot can see which problems happen most and where.

---

## 10. States

Every state below is shown to people with the words from the [glossary](architecture/GLOSSARY.md). Each transition names who or what moves it.

### 10.1 Order

```
             ┌──────────── short: edit and resend ───────────┐
             ▼                                                │
  [placed] ──► [stock check] ──► stock confirmed ──► [placed] ──► 16:00 day before ──► [fixed]
                   │                                                                    │
                   ├─ short: accept partial ──► [placed]                                │ plan
                   └─ warehouse down: refused, nothing saved                            ▼
                                                                            [planned]  or  [deferred] ──► next day's plan
                                                                                │
                                                                         publish│
                                                                                ▼
     [received] ◄── store confirms ── [delivered] ◄── [arrived] ◄── [on the way] ◄── [loading] ◄── [published]
                                                         │
                                                         └──► [failed] ──► dispatcher: redeliver (next plan, first) or cancel

     [cancelled]: from any state, by the store before it is fixed, or by an approved cancel request after
```

| From | To | Moved by |
| --- | --- | --- |
| placed | stock check | The store sends the order |
| stock check | placed | The warehouse confirms stock, or the store accepts a partial |
| stock check | placed (again) | The store edits a short order and resends it |
| placed | fixed | 16:00 the day before the delivery date |
| fixed | planned / deferred | The plan, as the dispatcher leaves it |
| deferred | planned | A later day's plan |
| planned | published | The dispatcher publishes |
| published | loading | The loader starts the trip's loading |
| loading | on the way | The driver starts the trip |
| on the way | arrived | The driver taps arrived at the stop |
| arrived | delivered | Handover done (PIN entered, goods unloaded) |
| arrived | failed | The delivery could not be made; the driver records why |
| delivered | received | The store confirms |
| any | cancelled | The store before 16:00, or the dispatcher approving a cancel request |

A problem with goods that did arrive (short, damaged, wrong) does not change the order's state. It is an issue linked to the order.

### 10.2 Vehicle

One live status per vehicle. It is the only availability the planner reads: **the vehicle's status at the 16:00 cutoff decides whether it can be planned for the next day.** A vehicle in the workshop or broken at 16:00 is not planned.

```
                 ┌──────────────────── trip end (driver) ◄── [returning] ◄── after last stop (auto)
                 ▼                                                   ▲
  [in workshop] ──► [idle] ──► [ready at depot] ──► [loading] ──► [loaded] ──► [on the way] ──► [arrived] ──► [unloading]
        ▲            no trip    driver: arrived     loader         loader      driver: start                   │
        │                       at depot            starts         completes   trip          ◄── left stop ────┘
        │                                                                            │
        │                                                     breakdown (driver) ────┤ from any moving state
        │                                                                            ▼
        └──────────────────────── dispatcher decides ◄──────────────────────── [broken] ──► [on the way] (repaired)
```

| State | Meaning | Set by |
| --- | --- | --- |
| idle | At the depot or parked, no trip assigned now | Trip end, or back from the workshop |
| in workshop | Under repair | The driver or the admin |
| ready at depot | At the depot dock for its trip, waiting to load | The driver taps arrived at depot |
| loading | Being loaded | The loader starts loading |
| loaded | Loading complete, waiting to leave | The loader marks loading complete |
| on the way | Driving to the next stop | The driver starts the trip, or leaves a stop |
| arrived | At an outlet | The driver taps arrived |
| unloading | Handing over at an outlet | The driver taps unloading |
| returning | Last stop done, driving back | Automatic after the last stop |
| broken | Broken down | The driver reports it; the dispatcher then decides: back on the way once repaired, or into the workshop with the stops handled as in [section 5.3](#53-during-the-day) |

A vehicle runs at most two trips a day. After the first trip it goes through idle or straight to ready at depot for the second.

### 10.3 Trip

```
  [planned] ──► publish ──► [fixed] ──► loading starts ──► [in operation] ──► trip end ──► [finished]
      │                        │                                │
      └────────────────────────┴────────────────────────────────┴──► [cancelled]
```

| State | Meaning |
| --- | --- |
| planned | In a draft plan; the dispatcher may still change it |
| fixed | Published; the loader, the driver and the stores have been told. A change now is a recorded revision |
| in operation | From the start of loading, through departure and every stop, until the vehicle is back. Loading and road progress are read from the vehicle and the stops |
| finished | The driver ended the trip; the channel becomes read-only |
| cancelled | Called off by the dispatcher, with a reason; its orders are deferred or cancelled |

---

## 11. Rules every plan and every edit obeys

These are the hard rules from principle 3. Their exact definitions and parameters are in [RULES-AND-POLICIES.md](architecture/RULES-AND-POLICIES.md) and [ASSUMPTIONS.md](architecture/ASSUMPTIONS.md).

- **Capacity** is read from the order's own weight and volume, never from summed product lines.
- **Temperature** comes from the order. Chilled goods need a refrigerated vehicle. A trip carries one temperature class.
- **Vehicle access:** a van-only outlet gets a van, a mall outlet is served inside the mall's window.
- **Delivery windows** are met; a stop that cannot be reached in its window is not planned there.
- **Trip time budgets** per brand, and **at most two trips** per vehicle a day.
- **Weekly fuel quota** per vehicle, counting the return leg.
- **Cutoff** at 16:00, depot time (Asia/Colombo), the day before delivery.
- Each vehicle serves only its **home depot**.

---

## 12. Edge cases and how the vision answers them

| Case | What happens |
| --- | --- |
| Order placed after 16:00 | Accepted for the next delivery day; the store sees the date before confirming |
| Order for a future date | Fixed at 16:00 the day before that date, editable until then |
| Stock short at placement | Store sees the short lines and available units; edits and resends, or accepts the partial |
| Warehouse not answering at placement | Order refused with the reason; the store tries again. Nothing is saved in an unknown state |
| Store wants to change an order after 16:00 | Only a cancel request; the dispatcher decides, at any time |
| Cancel approved with the goods on the road | Agreed in the channel; the system offers taking the stop off the run sheet and bringing the goods back, or delivering anyway. The decision is recorded |
| Demand exceeds the fleet | The plan defers by priority and names the rule for each deferral; the forecast should have warned days before |
| Dispatcher tries a move that breaks a rule | Not offered; the screen names the blocking rule |
| Vehicle in the workshop at 16:00 | Not planned for the next day |
| Goods short or damaged at the dock | The vehicle leaves with what there is; the store is told automatically; the shortfall is a line in the channel |
| Driver tries to start before loading is complete | Not possible |
| Vehicle breaks down on the road | Driver reports it; the dispatcher decides: wait, replacement vehicle, or return and defer the remaining stops |
| Outlet closed or goods refused | Delivery failed with the reason; the dispatcher redelivers (first in the next plan) or cancels |
| Store finds goods short or damaged on receipt | Reports a problem in the stop thread; it becomes an issue; the order's state does not change |
| Driver has no signal | Taps and messages wait on the phone and are sent on reconnect, marked late |
| Driver, loader or store manager changes mid-trip | The new person joins the threads from that moment; the old one keeps read-only access to their part; a system line records the change |
| Nothing fits a template | Text or a voice note; a call as a last resort, logged in the thread |

---

## Appendix A: where the vision differs from the build

Each row is a change the build needs to reach this vision. Ids refer to [EDGE-CASES.md](architecture/EDGE-CASES.md), [ASSUMPTIONS.md](architecture/ASSUMPTIONS.md) and [RULES-AND-POLICIES.md](architecture/RULES-AND-POLICIES.md).

| Area | Built today | Vision |
| --- | --- | --- |
| Warehouse down at placement | Order saved as `stock_unknown`, retried, auto-deferred at cutoff if unresolved (STK-03, STK-04) | Refused with the reason; the store tries again |
| Order states | `stock_unknown`, `partially_reserved`, `confirmed`, `allocated`, `deferred`, `unservable`, `loading`, `in_transit`, `delivered`, `partially_delivered`, `failed`, `received`, `unconfirmed`, `cancelled` | Adds **fixed** and **published**; ends delivered → received, or failed; receipt problems are issues, not order states |
| Change after cutoff | An amend after allocation is a version conflict, after loading a rejection (ORD-05, ORD-06); a store cancels directly until loaded (ORD-10) | Store fixed at 16:00 the day before; afterwards only a cancel request, the dispatcher decides at any time |
| Vehicle | Per-day availability: `available`, `in_workshop`, `unavailable` | One live status (section 10.2) that replaces day availability; the status at 16:00 decides planning |
| Trip | No trip status; the plan has `draft`, `published`, `superseded`, `cancelled` | planned, fixed, in operation, finished, cancelled |
| Shortfall at the dock | Departure blocked until the dispatcher records a replacement (LOD-01) | The vehicle leaves short, the store is told automatically |
| Trip channel | Not built; notifications are one-way | Trip and per-stop threads, templates with case types, voice notes, logged calls, system lines (section 9) |
| Forecast | Weekly per depot and brand; the busiest day is a weekly average (A-38, A-41) | Daily demand against daily capacity, with heavy days highlighted |
| Driver depot taps | Driver app has no "arrived at depot" or "trip end" step | Driver moves the vehicle through ready, start, stops and trip end |
| AI help on the plan | Plan explanations per deferral and late risk exist; no suggestions to accept or reject | Explain, suggest changes, risk warnings |
| Future delivery date | The delivery date follows the cutoff and the calendar (ORD-01, ORD-02) | The store picks any future date |
| Fleet management | No live vehicle status authoring for drivers or the admin | Driver and admin move a vehicle in and out of the workshop |
