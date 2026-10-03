# Data model

Generated from the live migrations by `scripts/data-model.py`: 95 tables in 13 schemas. Regenerate after any migration; do not edit by hand. The design reasoning, findings and target model are in [DATA-MODEL-REVIEW.md](architecture/DATA-MODEL-REVIEW.md); the components that own each schema are in [architecture.md](architecture.md).

## How the schemas connect

Each module owns one schema and is the only writer to it. Foreign keys point only into `ref` and `iam`; every other reference between modules is by id with no foreign key, so one module's migration never waits on another's (AGENTS.md, Data and Migration Rules). Lines below are real foreign keys between schemas.

```mermaid
flowchart LR
  ref["ref<br/>Reference data<br/>16 tables"]
  iam["iam<br/>Identity and access<br/>18 tables"]
  ordering["ordering<br/>Ordering<br/>4 tables"]
  warehouse["warehouse<br/>Warehouse<br/>6 tables"]
  planning["planning<br/>Planning<br/>9 tables"]
  loading["loading<br/>Loading<br/>6 tables"]
  execution["execution<br/>Execution<br/>8 tables"]
  receipt["receipt<br/>Receipt<br/>6 tables"]
  issues["issues<br/>Issues<br/>7 tables"]
  notification["notification<br/>Notification<br/>5 tables"]
  sync["sync<br/>Sync<br/>1 table"]
  ml["ml<br/>Intelligence<br/>4 tables"]
  integration["integration<br/>Platform (outbox, inbox, receipts, audit, jobs)<br/>5 tables"]
  execution -->|5 FK| ref
  iam -->|2 FK| ref
  issues -->|1 FK| iam
  issues -->|2 FK| ref
  loading -->|2 FK| ref
  notification -->|2 FK| iam
  ordering -->|1 FK| ref
  planning -->|7 FK| ref
  receipt -->|3 FK| ref
  sync -->|1 FK| iam
```

Conventions: UUIDv7 surrogate keys with dataset identifiers kept as unique natural keys, `timestamptz` everywhere, `numeric` with explicit precision for weight, volume and fuel, and `row_version` checked on every update. Operational tables have forced row-level security by depot, outlet or actor, and module roles hold no `DELETE`: operational rows reach terminal states instead. Diagrams show the columns that connect rows (keys, references, status, versions); the table under each lists every table's purpose from its `COMMENT ON TABLE`.

## `ref`: Reference data

```mermaid
erDiagram
  brands {
    uuid reference_version_id PK
    text brand_code PK
  }
  calendar_days {
    date calendar_date PK
  }
  calendar_overrides {
    date calendar_date PK
  }
  depots {
    uuid reference_version_id PK
    text depot_code PK
  }
  district_travel {
    uuid reference_version_id PK
    text district_name PK
  }
  districts {
    uuid reference_version_id PK
    text district_name PK
    text depot_code FK
  }
  outlet_details {
    text outlet_id PK
    bigint row_version
  }
  outlet_registry {
    text outlet_id PK
  }
  outlets {
    uuid reference_version_id PK
    text outlet_id PK
    text brand_code FK
    text district_name FK
  }
  reference_versions {
    uuid reference_version_id PK
    text content_hash UK
  }
  road_conditions {
    text district_name PK
    date condition_date PK
  }
  service_allowances {
    uuid reference_version_id PK
    text brand_code PK
    text dock_type PK
  }
  traffic_speed {
    uuid reference_version_id PK
    text district_name PK
    smallint hour PK
    boolean monsoon PK
  }
  vehicle_day_status {
    text vehicle_id PK
    date service_date PK
    text status
  }
  vehicle_registry {
    text vehicle_id PK
  }
  vehicles {
    uuid reference_version_id PK
    text vehicle_id PK
    text depot_code FK
  }
  reference_versions ||--o{ brands : "reference_version_id"
  reference_versions ||--o{ depots : "reference_version_id"
  reference_versions ||--o{ districts : "reference_version_id"
  depots ||--o{ districts : "reference_version_id, depot_code"
  reference_versions ||--o{ outlets : "reference_version_id"
  outlet_registry ||--o{ outlets : "outlet_id"
  brands ||--o{ outlets : "reference_version_id, brand_code"
  districts ||--o{ outlets : "reference_version_id, district_name"
  reference_versions ||--o{ vehicles : "reference_version_id"
  vehicle_registry ||--o{ vehicles : "vehicle_id"
  depots ||--o{ vehicles : "reference_version_id, depot_code"
  reference_versions ||--o{ district_travel : "reference_version_id"
  districts ||--o{ district_travel : "reference_version_id, district_name"
  reference_versions ||--o{ service_allowances : "reference_version_id"
  brands ||--o{ service_allowances : "reference_version_id, brand_code"
  reference_versions ||--o{ traffic_speed : "reference_version_id"
  districts ||--o{ traffic_speed : "reference_version_id, district_name"
  vehicle_registry ||--o{ vehicle_day_status : "vehicle_id"
  outlet_registry ||--o{ outlet_details : "outlet_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `brands` |  | 3 |
| `calendar_days` |  | 13 |
| `calendar_overrides` | A person overruling the calendar for one day. Read on top of ref.calendar_days when a snapshot is loaded, so it survives a reference import. | 5 |
| `depots` |  | 4 |
| `district_travel` |  | 8 |
| `districts` |  | 3 |
| `outlet_details` | A store's own delivery window, dock type and contacts, set by its manager. Read on top of the current reference version when a snapshot is loaded, so it survives an import (R-REF-01). | 10 |
| `outlet_registry` |  | 1 |
| `outlets` |  | 12 |
| `reference_versions` |  | 6 |
| `road_conditions` |  | 3 |
| `service_allowances` |  | 4 |
| `traffic_speed` |  | 5 |
| `vehicle_day_status` |  | 6 |
| `vehicle_registry` |  | 1 |
| `vehicles` |  | 10 |

## `iam`: Identity and access

```mermaid
erDiagram
  action_catalogue {
    text action PK
  }
  devices {
    uuid device_id PK
    text depot_code
    bigint row_version
    uuid registered_by FK
  }
  login_attempts {
  }
  oauth_authorization_codes {
    text code_hash PK
    uuid client_id FK
    uuid user_id FK
  }
  oauth_clients {
    uuid client_id PK
  }
  pin_attempts {
    uuid user_id FK
    bigint attempt_id PK
  }
  policies {
    uuid policy_id PK
    text name UK
    uuid created_by FK
    bigint row_version
  }
  policy_attachments {
    uuid attachment_id PK
    uuid policy_id FK
    text principal_type UK
    text principal_id UK
    uuid attached_by FK
  }
  policy_generation {
    boolean singleton PK
  }
  policy_versions {
    uuid policy_version_id PK
    uuid policy_id FK
    integer version_number UK
    uuid created_by FK
  }
  roles {
    text role_code PK
  }
  session_operators {
    bigint interval_id PK
    uuid user_id FK
  }
  sessions {
    text token_hash PK
    uuid user_id FK
    uuid device_id FK
    uuid operator_user_id FK
    uuid oauth_client_id FK
  }
  user_depot_access {
    uuid user_id PK
    text depot_code PK
  }
  user_outlet_access {
    uuid user_id PK
    text outlet_id PK
  }
  user_roles {
    uuid user_id PK
    text role_code PK
  }
  users {
    uuid user_id PK
    text email UK
    bigint row_version
    text employee_code UK
  }
  vehicle_driver_assignments {
    uuid assignment_id PK
    text vehicle_id FK
    uuid driver_user_id FK
    uuid assigned_by FK
    bigint row_version
  }
  users ||--o{ user_roles : "user_id"
  roles ||--o{ user_roles : "role_code"
  users ||--o{ user_depot_access : "user_id"
  users ||--o{ user_outlet_access : "user_id"
  ref_outlet_registry ||--o{ user_outlet_access : "outlet_id"
  ref_vehicle_registry ||--o{ vehicle_driver_assignments : "vehicle_id"
  users ||--o{ vehicle_driver_assignments : "driver_user_id"
  users ||--o{ vehicle_driver_assignments : "assigned_by"
  users ||--o{ sessions : "user_id"
  devices ||--o{ sessions : "device_id"
  users ||--o{ policies : "created_by"
  policies ||--o{ policy_versions : "policy_id"
  users ||--o{ policy_versions : "created_by"
  policies ||--o{ policy_attachments : "policy_id"
  users ||--o{ policy_attachments : "attached_by"
  users ||--o{ sessions : "operator_user_id"
  users ||--o{ session_operators : "user_id"
  users ||--o{ pin_attempts : "user_id"
  users ||--o{ devices : "registered_by"
  oauth_clients ||--o{ oauth_authorization_codes : "client_id"
  users ||--o{ oauth_authorization_codes : "user_id"
  oauth_clients ||--o{ sessions : "oauth_client_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `action_catalogue` |  | 4 |
| `devices` |  | 10 |
| `login_attempts` |  | 5 |
| `oauth_authorization_codes` | One-time codes between sign-in and token exchange. Only the hash is stored; consumed_at makes a code single use. | 10 |
| `oauth_clients` | Public OAuth clients that registered themselves for the remote MCP endpoint. No secret is ever issued. | 6 |
| `pin_attempts` | Shared across replicas, or a second replica is a bypass. Five failures pause PIN entry for that person. | 5 |
| `policies` |  | 7 |
| `policy_attachments` |  | 6 |
| `policy_generation` |  | 3 |
| `policy_versions` |  | 7 |
| `roles` |  | 2 |
| `session_operators` | Who operated a shared device and when. A queued write is accepted for the operator whose interval covers its recorded time. | 7 |
| `sessions` |  | 12 |
| `user_depot_access` |  | 2 |
| `user_outlet_access` |  | 2 |
| `user_roles` |  | 2 |
| `users` |  | 12 |
| `vehicle_driver_assignments` |  | 7 |

## `ordering`: Ordering

```mermaid
erDiagram
  day_closures {
    text depot_code PK
    date service_date PK
  }
  order_lines {
    uuid order_id PK
    integer revision PK
    text product_id PK
  }
  order_status_history {
    bigint history_id PK
    uuid order_id FK
    uuid actor_id
    uuid event_id
  }
  orders {
    uuid order_id PK
    text order_ref UK
    text outlet_id FK
    text depot_code
    text brand_code
    text status
    uuid redelivery_of FK
    uuid source_issue_id UK
    uuid trip_id
    uuid command_id
    bigint row_version
  }
  ref_outlet_registry ||--o{ orders : "outlet_id"
  orders ||--o{ orders : "redelivery_of"
  orders ||--o{ order_lines : "order_id"
  orders ||--o{ order_status_history : "order_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `day_closures` | orders.closed happened for this depot and day. Amendments stop, new orders roll forward. | 5 |
| `order_lines` | Descriptive only. The current lines are those at orders.line_revision. | 4 |
| `order_status_history` | Every status change with its actor, reason and time (architecture rule 8). Null actor is the system. | 8 |
| `orders` | One outlet's demand for one delivery date. Status moves only along OrderStateMachine. | 26 |

## `warehouse`: Warehouse

```mermaid
erDiagram
  catalogue_syncs {
    uuid sync_id PK
  }
  discrepancies {
    uuid discrepancy_id PK
    uuid order_id
  }
  inbound_events {
    uuid inbound_event_id PK
    text source_system UK
    text source_event_id UK
    text event_type
    text status
  }
  placements {
    uuid placement_id PK
    text order_ref UK
    uuid order_id
    text depot_code
    text warehouse_code
    text warehouse_order_ref UK
    bigint row_version
  }
  products {
    text product_id PK
    text base_product_id
  }
  status_requests {
    uuid request_id PK
    uuid order_id
    text warehouse_order_ref UK
    text target_status UK
    bigint row_version
  }
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `catalogue_syncs` |  | 7 |
| `discrepancies` |  | 9 |
| `inbound_events` |  | 15 |
| `placements` | What Waypoint asked the warehouse and what it answered. The memory that makes a retry a lookup, not a second order (R-STK-11). | 23 |
| `products` | Cached projection of the warehouse catalogue (CAT-01). Reconstructed, accurate to ~1%; display only, never capacity. | 10 |
| `status_requests` |  | 12 |

## `planning`: Planning

```mermaid
erDiagram
  allocations {
    uuid plan_id PK
    uuid order_id PK
    text depot_code
    text outlet_id FK
    uuid trip_id FK
    integer stop_sequence UK
  }
  deferrals {
    uuid plan_id PK
    uuid order_id PK
    text depot_code
    text outlet_id FK
    date service_date
    text rule_id
    uuid actor_id
  }
  fuel_usage {
    uuid plan_id PK
    text vehicle_id PK
    text depot_code
    date service_date
  }
  policy_versions {
    uuid policy_version_id PK
    text depot_code
  }
  route_legs {
    uuid trip_id PK
    integer leg_sequence PK
    uuid plan_id PK
    text depot_code
    text from_outlet_id FK
    text to_outlet_id FK
  }
  rule_parameters {
    uuid rule_set_id PK
    text parameter_key PK
  }
  rule_sets {
    uuid rule_set_id PK
  }
  runs {
    uuid plan_id PK
    text depot_code UK
    date service_date UK
    integer plan_version UK
    bigint row_version
    text status
    uuid reference_version_id FK
    uuid rule_set_id FK
    uuid priority_policy_version_id FK
    uuid supersedes FK
    uuid command_id
  }
  trips {
    uuid trip_id PK
    uuid plan_id PK
    text depot_code
    text vehicle_id FK
    smallint trip_number UK
    text brand_code
  }
  runs ||--o{ trips : "plan_id"
  ref_vehicle_registry ||--o{ trips : "vehicle_id"
  rule_sets ||--o{ rule_parameters : "rule_set_id"
  ref_reference_versions ||--o{ runs : "reference_version_id"
  rule_sets ||--o{ runs : "rule_set_id"
  policy_versions ||--o{ runs : "priority_policy_version_id"
  runs ||--o{ runs : "supersedes"
  runs ||--o{ allocations : "plan_id"
  ref_outlet_registry ||--o{ allocations : "outlet_id"
  runs ||--o{ deferrals : "plan_id"
  ref_outlet_registry ||--o{ deferrals : "outlet_id"
  runs ||--o{ route_legs : "plan_id"
  ref_outlet_registry ||--o{ route_legs : "from_outlet_id"
  ref_outlet_registry ||--o{ route_legs : "to_outlet_id"
  runs ||--o{ fuel_usage : "plan_id"
  ref_vehicle_registry ||--o{ fuel_usage : "vehicle_id"
  trips ||--o{ allocations : "plan_id, trip_id"
  trips ||--o{ route_legs : "trip_id, plan_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `allocations` |  | 14 |
| `deferrals` | Who deferred which order, why, and how often it has now been skipped (rule 8, R-PLN-20). The engine is the system actor. | 10 |
| `fuel_usage` | Litres per vehicle per run. Weekly usage counts published runs only; a draft never consumes quota (D-K, B15). | 6 |
| `policy_versions` | Effective-dated decision tables. keys is the ordered PriorityPolicy.Key list, highest priority first (R-PLN-21). | 9 |
| `route_legs` | Planned times only. Actual times belong to Execution. | 9 |
| `rule_parameters` |  | 4 |
| `rule_sets` | Effective-dated rule parameters. Immutable except closing effective_to when a successor starts. | 6 |
| `runs` | One planning run for a depot and day. plan_version is the business revision, row_version the concurrency revision. | 23 |
| `trips` |  | 13 |

## `loading`: Loading

```mermaid
erDiagram
  item_checks {
    uuid check_id PK
    uuid trip_id FK
    integer plan_version FK
    uuid order_id FK
    integer line_no FK
    integer attempt UK
    text status
    uuid shortfall_id FK
    uuid actor_user_id
    uuid device_id
    uuid command_id
  }
  items {
    uuid trip_id PK
    integer plan_version PK
    uuid order_id PK
    integer line_no PK
    text product_id
  }
  sessions {
    uuid trip_id PK
    text depot_code
    integer plan_version
    text status
    uuid holder_user_id
    text holder_code
    bigint row_version
  }
  shortfalls {
    uuid shortfall_id PK
    uuid trip_id FK
    integer plan_version FK
    uuid order_id FK
    uuid photo_attachment_id
    uuid device_id
  }
  stops {
    uuid trip_id PK
    integer plan_version PK
    uuid order_id PK
    text outlet_id FK
  }
  trips {
    uuid trip_id PK
    integer plan_version PK
    uuid plan_id
    text depot_code
    date service_date
    text vehicle_id FK
    text brand_code
    text dock_code
  }
  ref_vehicle_registry ||--o{ trips : "vehicle_id"
  ref_outlet_registry ||--o{ stops : "outlet_id"
  trips ||--o{ stops : "trip_id, plan_version"
  stops ||--o{ items : "trip_id, plan_version, order_id"
  items ||--o{ item_checks : "trip_id, plan_version, order_id, line_no"
  stops ||--o{ shortfalls : "trip_id, plan_version, order_id"
  shortfalls ||--o{ item_checks : "shortfall_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `item_checks` | Append only. The latest attempt per item line is its state; earlier attempts are the custody record. | 15 |
| `items` | The item lines of an order: one product and its unit count. Descriptive; the product id is inferred, never a verified SKU. | 6 |
| `sessions` | The dock work on one trip. row_version moves on by one per accepted command and per plan revision. | 16 |
| `shortfalls` | An item flagged before departure. Not loaded; the dispatcher and store are told (loading.shortfall). Never deleted. | 14 |
| `stops` | One order on one trip version, in delivery order. Measures copied from Ordering; authoritative for capacity. | 11 |
| `trips` | A published trip as Loading received it, one row per plan version. The current row has superseded_at null. | 17 |

## `execution`: Execution

```mermaid
erDiagram
  attachments {
    uuid attachment_id PK
    uuid delivery_id FK
  }
  delivery_lines {
    uuid delivery_id PK
    text product_id PK
  }
  delivery_records {
    uuid delivery_id PK
    uuid trip_id FK
    uuid order_id UK
    text outlet_id FK
    text depot_code
    text vehicle_id FK
    date service_date
    uuid proof_id FK
    uuid device_id
    bigint row_version
  }
  proof_content {
    text storage_key PK
  }
  proofs {
    uuid proof_id PK
    uuid delivery_id FK
    integer attempt UK
    uuid photo_attachment_id
    uuid signature_attachment_id
    uuid device_id
    uuid command_id
  }
  road_reports {
    uuid report_id PK
    text vehicle_id FK
    text depot_code
    date service_date
    uuid delivery_id FK
    uuid device_id
    uuid command_id
  }
  trips {
    uuid trip_id PK
    uuid plan_id
    integer plan_version
    text depot_code
    text vehicle_id FK
    date service_date
  }
  vehicle_reports {
    uuid report_id PK
    text vehicle_id FK
    text depot_code
    date service_date
    text status
    uuid delivery_id FK
    uuid device_id
    uuid command_id
  }
  delivery_records ||--o{ vehicle_reports : "delivery_id"
  ref_vehicle_registry ||--o{ road_reports : "vehicle_id"
  ref_vehicle_registry ||--o{ trips : "vehicle_id"
  ref_vehicle_registry ||--o{ vehicle_reports : "vehicle_id"
  trips ||--o{ delivery_records : "trip_id"
  ref_outlet_registry ||--o{ delivery_records : "outlet_id"
  ref_vehicle_registry ||--o{ delivery_records : "vehicle_id"
  delivery_records ||--o{ attachments : "delivery_id"
  delivery_records ||--o{ proofs : "delivery_id"
  proofs ||--o{ delivery_records : "proof_id"
  delivery_records ||--o{ road_reports : "delivery_id"
  delivery_records ||--o{ delivery_lines : "delivery_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `attachments` | A proof artifact held in the proof store. The id is minted on the device, so a repeated upload is a no-op. | 12 |
| `delivery_lines` | The order's products at release, and what arrived of each. The product id is the warehouse's inferred candidate, never a verified SKU. | 4 |
| `delivery_records` | One order at one stop of a released trip, and what happened there. row_version moves on by one per accepted command. | 36 |
| `proof_content` | The bytes of a proof photo or signature, keyed as execution.attachments.storage_key. Cleared, never deleted, past retention. | 6 |
| `proofs` | Append only. The latest attempt is the delivery's proof; earlier ones stay as evidence. recipient_name is personal data. | 13 |
| `road_reports` | A road fault or delay a driver reported (R-EXE-07). | 12 |
| `trips` | A trip as it left the dock (trip.released). announced_delay_minutes is the delay last told to the stops ahead. | 8 |
| `vehicle_reports` | What a driver reported about the vehicle (R-EXE-06). A report changes nothing in reference data; the dispatcher applies vehicle:SetDayStatus. | 13 |

## `receipt`: Receipt

```mermaid
erDiagram
  confirmation_history {
    bigint history_id PK
    uuid receipt_id FK
    uuid actor_id
    uuid event_id
  }
  confirmation_lines {
    uuid receipt_id PK
    text product_id PK
  }
  confirmations {
    uuid receipt_id PK
    uuid order_id UK
    uuid delivery_id UK
    uuid trip_id
    text outlet_id FK
    text depot_code
    text status
    bigint row_version
  }
  handover_history {
    bigint history_id PK
    uuid receipt_id FK
    uuid actor_id
  }
  handovers {
    uuid receipt_id PK
    uuid order_id UK
    text outlet_id FK
    text depot_code
    text vehicle_id FK
    date service_date
    bigint row_version
  }
  parameters {
    text parameter_key PK
    date effective_from PK
  }
  ref_outlet_registry ||--o{ confirmations : "outlet_id"
  confirmations ||--o{ confirmation_lines : "receipt_id"
  confirmations ||--o{ confirmation_history : "receipt_id"
  confirmations ||--o{ handovers : "receipt_id"
  ref_outlet_registry ||--o{ handovers : "outlet_id"
  ref_vehicle_registry ||--o{ handovers : "vehicle_id"
  handovers ||--o{ handover_history : "receipt_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `confirmation_history` | Every status change with its actor, reason and time (rule 8). The system actor closes; a person answers. | 8 |
| `confirmation_lines` | Per product: what the order said would arrive and what the store says did (D-E). | 4 |
| `confirmations` | The store's acceptance of one delivered order, separate from the driver's proof (R-RCP-04). | 18 |
| `handover_history` |  | 5 |
| `handovers` | The one-time PIN the store shows and the driver enters. Evidence of presence, never a gate (R-RCP-09). | 17 |
| `parameters` |  | 6 |

## `issues`: Issues

```mermaid
erDiagram
  attachment_content {
    text storage_key PK
  }
  attachments {
    uuid attachment_id PK
    text outlet_id FK
    text depot_code
    uuid order_id
    uuid receipt_id
    text storage_key UK
  }
  issue_attachments {
    uuid issue_id PK
    uuid attachment_id PK
  }
  issue_history {
    bigint history_id PK
    uuid issue_id FK
    uuid actor_id
    uuid event_id
  }
  issue_subjects {
    uuid issue_id PK
    text subject_type PK
    text subject_id PK
  }
  issues {
    uuid issue_id PK
    text status
    text depot_code
    text outlet_id FK
    text source_key UK
    uuid assignee_user_id FK
    bigint row_version
  }
  parameters {
    text parameter_key PK
    date effective_from PK
  }
  ref_outlet_registry ||--o{ issues : "outlet_id"
  iam_users ||--o{ issues : "assignee_user_id"
  issues ||--o{ issue_subjects : "issue_id"
  issues ||--o{ issue_history : "issue_id"
  issues ||--o{ issue_attachments : "issue_id"
  ref_outlet_registry ||--o{ attachments : "outlet_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `attachment_content` | The bytes of an issue photo, keyed as issues.attachments.storage_key. Cleared, never deleted, past retention. | 6 |
| `attachments` | A photo of a delivery problem, uploaded by the store for an order. Linked to issues through issues.issue_attachments. | 13 |
| `issue_attachments` |  | 3 |
| `issue_history` | Every change with its actor, reason and time (rule 8), escalations included. | 9 |
| `issue_subjects` |  | 3 |
| `issues` | One operational problem: owner, lifecycle, recorded resolution. Never deleted. | 21 |
| `parameters` |  | 5 |

## `notification`: Notification

```mermaid
erDiagram
  deliveries {
    uuid delivery_id PK
    uuid notification_id FK
    text channel UK
    uuid subscription_id FK
    text status
    bigint row_version
  }
  notifications {
    uuid notification_id PK
    uuid recipient_user_id FK
    uuid event_id UK
    text event_type
    text target_key UK
    integer rule_version FK
    text subject_id
    bigint row_version
  }
  push_subscriptions {
    uuid subscription_id PK
    uuid user_id FK
    uuid device_id
    text status
    bigint row_version
  }
  routing_rules {
    integer rule_version PK
    text event_type PK
    text recipient_role PK
  }
  routing_versions {
    integer rule_version PK
  }
  routing_versions ||--o{ routing_rules : "rule_version"
  iam_users ||--o{ notifications : "recipient_user_id"
  routing_versions ||--o{ notifications : "rule_version"
  iam_users ||--o{ push_subscriptions : "user_id"
  notifications ||--o{ deliveries : "notification_id"
  push_subscriptions ||--o{ deliveries : "subscription_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `deliveries` | Each channel a notification went out on, with every attempt counted. A push that keeps failing reaches dead and stays. | 11 |
| `notifications` | One message to one person about one event. Kept forever; read state only ever moves from unread to read. | 13 |
| `push_subscriptions` | A browser push endpoint per person and device. One browser has one endpoint, so it is active for one person at a time. | 10 |
| `routing_rules` | Event type to recipient role and scope, with the message template. {name} is filled from the event. | 10 |
| `routing_versions` | One version of the routing matrix is current. A change is a new version, never an edit. | 4 |

## `sync`: Sync

```mermaid
erDiagram
  operations {
    uuid operation_id PK
    uuid actor_id FK
    uuid device_id
    text status
    text problem_code
    bigint row_version
  }
  iam_users ||--o{ operations : "actor_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `operations` |  | 18 |

## `ml`: Intelligence

```mermaid
erDiagram
  delivery_predictions {
    uuid prediction_id PK
    uuid plan_id FK
    uuid order_id UK
    uuid trip_id
    text outlet_id
    text depot_code
    uuid model_version_id FK
    text model_label UK
  }
  demand_forecasts {
    uuid forecast_id PK
    uuid run_id UK
    text depot_code UK
    text brand_code UK
    integer iso_year UK
    smallint iso_week UK
    uuid model_version_id FK
  }
  model_versions {
    uuid model_version_id PK
    text model_name UK
    text model_version UK
    text status
    bigint row_version
  }
  plan_scorings {
    uuid plan_id PK
    text depot_code
    date service_date
    integer plan_version
    text status
    uuid model_version_id FK
    bigint row_version
  }
  model_versions ||--o{ plan_scorings : "model_version_id"
  plan_scorings ||--o{ delivery_predictions : "plan_id"
  model_versions ||--o{ delivery_predictions : "model_version_id"
  model_versions ||--o{ demand_forecasts : "model_version_id"
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `delivery_predictions` |  | 13 |
| `demand_forecasts` | Weekly depot x brand forecasts. Every run is kept; a read takes the newest per week. | 12 |
| `model_versions` | Model registry. A model is used only while active and only when the serving process reports the same name and version. | 17 |
| `plan_scorings` | One per published plan: whether its stops were scored by a model or by the deterministic estimator, and why. | 14 |

## `integration`: Platform (outbox, inbox, receipts, audit, jobs)

```mermaid
erDiagram
  audit_log {
    bigint audit_id PK
    timestamptz occurred_at PK
    uuid actor_id
    uuid device_id
    text correlation_id
    uuid command_id
    text target_id
  }
  command_receipts {
    uuid command_id PK
    uuid actor_id PK
  }
  consumed_events {
    text consumer PK
    uuid event_id PK
  }
  job_runs {
    bigint run_id PK
  }
  outbox_events {
    uuid event_id PK
    text aggregate_id
    text event_type
    text status
    text correlation_id
    uuid actor_id
  }
```

| Table | Purpose | Columns |
| --- | --- | --- |
| `audit_log` |  | 15 |
| `command_receipts` |  | 7 |
| `consumed_events` | One row per event a consumer has applied. Makes at-least-once delivery idempotent. | 3 |
| `job_runs` | One row per run of a scheduled job. error holds an exception class, never data. | 6 |
| `outbox_events` |  | 16 |
