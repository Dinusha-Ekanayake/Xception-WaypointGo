# Architecture

Waypoint Dispatch is one web application for four field roles, served by a modular monolith over one PostgreSQL database. This page draws what is built and deployed today. The system and backend module diagrams are [architecture-diagram.jpeg](architecture-diagram.jpeg) and [backend-modules-diagram.jpeg](backend-modules-diagram.jpeg); each section below has its Mermaid source; the reasoning and the target design are in [SYSTEM-ARCHITECTURE.md](../SYSTEM-ARCHITECTURE.md), each module's contract in [MODULES.md](architecture/MODULES.md), and every table in [data-model.md](data-model.md).

## Who uses it and what it talks to

![Waypoint GO high-level UML component diagram](architecture-diagram.jpeg)

```mermaid
flowchart LR
  subgraph People
    SM[Store manager]
    DI[Dispatcher]
    LO[Loader on the shared dock tablet]
    DR[Driver]
    AD[Admin and auditor]
  end
  AI[AI assistant<br/>Claude, ChatGPT and others]
  WP((Waypoint Dispatch))
  WH[Warehouse API<br/>stock and catalogue]
  PUSH[Browser push services]
  SM & DI & LO & DR & AD -->|one responsive web app| WP
  AI -->|MCP, read only, OAuth| WP
  WP -->|StockPort, circuit breaker| WH
  WP -->|Web Push, VAPID| PUSH
```

Each role sees its own screens; the server decides what each person may do (policy) and which rows they may see (scope). An AI assistant connects as a person, with that person's policy and scope and nothing more ([R-IAM-30](architecture/RULES-AND-POLICIES.md)).

## What runs

The judge path, `docker compose up` with [compose.yaml](../compose.yaml):

```mermaid
flowchart LR
  subgraph Browser
    UI[Role screens<br/>React, Next.js]
    SW[Service worker<br/>offline shell]
    Q[(Offline write queue<br/>IndexedDB)]
    UI --- SW
    UI --- Q
  end
  subgraph Containers
    FE[waypoint<br/>Next.js server<br/>same-origin /api proxy]
    BE[backend<br/>Spring Boot modular monolith]
    DB[(db<br/>PostgreSQL 16<br/>one schema per module)]
    ML[ml<br/>FastAPI, trained Datathon models]
    MCP[mcp<br/>MCP adapter, Node]
    INIT[init<br/>migrate, import-reference]
    OBS[alloy, loki, prometheus, grafana<br/>logs, metrics and dashboards]
  end
  UI -->|HTTPS| FE
  Q -->|POST /api/sync on reconnect| FE
  FE -->|/api/*| BE
  FE -->|/mcp, /.well-known| MCP
  MCP -->|fixed read-only GETs| BE
  BE --> DB
  BE -->|plan scoring, forecasts| ML
  INIT -->|before start| DB
  BE -. logs, /prometheus .-> OBS
```

- **Offline is tiered by role** ([tiers.ts](../frontend/src/shared/offline/tiers.ts)): the driver works fully offline, the loader and store manager are resilient to drops, the dispatcher is online only.
- **Builds and requests never migrate or seed.** `init` runs `migrate` and `import-reference` once, explicitly.
- **Production** puts nginx with TLS in front of the same services; the managed-database and single-VPS variants are in [deployment.md](deployment.md).

## Modules and how they connect

![Waypoint GO backend modules](backend-modules-diagram.jpeg)

Thirteen business modules in one process, each owning one schema, plus the platform (command bus, outbox, audit, scheduler in `integration`) and an opt-in `demo` module that drives the judge scenarios. They connect in three ways only: a domain event through the outbox, a read through another module's published contract, or a port for anything outside the process.

```mermaid
flowchart TB
  REF[Reference data<br/>ref]
  IAM[Identity and access<br/>iam]
  ORD[Ordering<br/>ordering]
  WHS[Warehouse<br/>warehouse]
  PLN[Planning<br/>planning]
  LOD[Loading<br/>loading]
  EXE[Execution<br/>execution]
  RCP[Receipt<br/>receipt]
  ISS[Issues<br/>issues]
  NOT[Notification<br/>notification]
  INT[Intelligence<br/>ml]
  SYN[Sync<br/>sync]
  MSG[Messaging<br/>messaging]

  ORD -->|orders.closed, order.placed| PLN
  ORD <-->|StockPort| WHS
  PLN -->|plan.published, plan.revised| LOD
  PLN -->|plan.published| EXE
  PLN -->|order.deferred| ORD
  LOD -->|trip.released| EXE
  LOD -->|loading.shortfall| ISS
  EXE -->|delivery.completed, delivery.failed| RCP
  EXE -->|delivery.failed, vehicle.fault_reported| ISS
  RCP -->|receipt.disputed| ISS
  RCP -->|receipt.confirmed| ORD
  ISS -->|redelivery.requested| ORD
  ISS -->|shortfall.resolved| LOD
  PLN -. PredictionQuery .-> INT
  SYN -->|replays queued commands| ORD & LOD & EXE & RCP
  PLN -->|plan.published, plan.revised| MSG
  ISS -->|issue.raised, issue.resolved| MSG
  ORD & PLN & LOD & EXE & RCP & ISS -->|events| NOT
  REF -. ReferenceQuery, read by all .- ORD
  IAM -. IdentityQuery, read by all .- PLN
```

Solid arrows are events (asynchronous, at least once, consumers idempotent); dotted lines are contract reads. The full catalogue of events with producers and consumers is in [MODULES.md](architecture/MODULES.md#module-connection-summary). Boundaries are enforced by `ModuleBoundaryTest` and `EventCatalogueTest`.

## Inside a module

```mermaid
flowchart LR
  WEB[web<br/>thin controllers, reads] --> APP
  APP[application<br/>handlers, queries, consumers, jobs<br/>transactions and authorization] --> DOM
  APP --> INF
  DOM[domain<br/>pure rules and state machines<br/>no Spring, SQL or clock]
  INF[infrastructure<br/>JDBC repositories, ports] --> DOM
  CON[contract<br/>views, queries, commands, events<br/>the only thing other modules import]
  APP --> CON
```

## The planning engine

Planning shows the layers at work. The engines are pure domain code; the infrastructure layer chains them as decorators behind the `AllocationEngine` port, and the application layer runs the chain as a queued job outside any transaction.

```mermaid
flowchart LR
  subgraph application
    GEN[GeneratePlanHandler<br/>queues one job per depot and day]
    WRK[PlanGenerationWorker<br/>runs the engine, no transaction open,<br/>re-checks demand before writing]
  end
  subgraph infrastructure
    VAL[ValidatingEngine<br/>outermost, re-checks both plans]
    COST[CostImprovingEngine]
    IMP[ImprovingEngine]
    PRI[PriorityInsertionEngine]
  end
  subgraph domain
    REG[ConstraintRegistry<br/>one definition of every rule]
    SFR[ScarceFleetReplan<br/>exact refrigerated re-plan]
    ALNS[CostReplan<br/>ALNS cost stage]
    POL[PriorityPolicy<br/>versioned rank table]
  end
  GEN --> WRK --> VAL --> COST --> IMP --> PRI
  PRI --> POL
  PRI --> REG
  IMP --> SFR --> REG
  COST --> ALNS --> REG
  VAL --> REG
```

The same `ConstraintRegistry` is read by the dispatcher's manual edits and the publication gate, so no path can break a rule the engine keeps. Why this chain, measured against an exact MIP on 62 days: [benchmark notebook](issues/219-planning-v2/benchmark/planning-benchmark.ipynb) and [issue #219 walkthrough](issues/219-planning-v2/WALKTHROUGH.md).

## One command, end to end

Every write goes through one path, whether it was sent online or replayed from the offline queue.

```mermaid
sequenceDiagram
  autonumber
  participant C as Client or offline queue
  participant B as CommandBus
  participant H as Handler (module)
  participant D as PostgreSQL
  participant R as Outbox relay
  participant S as Subscribers (other modules)
  C->>B: POST /api/commands (command id, expectedVersion)
  B->>B: policy AND scope, else 403 plus audit
  B->>D: idempotency receipt: seen before? answer from it
  B->>H: handle
  H->>D: SET LOCAL ROLE waypoint_module, actor
  H->>D: UPDATE ... WHERE id = ? AND row_version = ?
  H->>D: outbox event, audit row, receipt (one transaction)
  D-->>C: ack, or RFC 9457 problem with violations
  R->>D: claim batch FOR UPDATE SKIP LOCKED
  R->>S: deliver, each in its own transaction
  S->>D: consumer inbox claim, then its own change
```

A refused rule comes back as `application/problem+json` naming every rule that failed; a stale `expectedVersion` is `409`, never a silent overwrite.

## Where to read more

| Question | Document |
| --- | --- |
| Why these choices | [SYSTEM-ARCHITECTURE.md](../SYSTEM-ARCHITECTURE.md) |
| What each module owns, its commands and events | [architecture/MODULES.md](architecture/MODULES.md) |
| Every table and key | [data-model.md](data-model.md) |
| Every rule and where it is enforced | [architecture/RULES-AND-POLICIES.md](architecture/RULES-AND-POLICIES.md) |
| Every edge case and its test | [architecture/EDGE-CASES.md](architecture/EDGE-CASES.md) |
| What is built and what is left | [development-docs/STATUS.md](development-docs/STATUS.md) |
