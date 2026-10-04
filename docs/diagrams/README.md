# Diagrams

The rendered images that [architecture.md](../architecture.md) and [data-model.md](../data-model.md) show. Each image follows the Mermaid source on its page, and the Mermaid is the authority: if the two disagree, the Mermaid is right and the image is redrawn.

| File | Shown in | What it shows |
| --- | --- | --- |
| `01-system-context.png` | architecture.md | Who uses the system and which outside services it talks to |
| `02-runtime-containers.png` | architecture.md | What `docker compose up` runs and how requests travel |
| `03-modules-and-events.png` | architecture.md | The modules inside the backend and the events between them |
| `04-module-layers.png` | architecture.md | The five layers inside every module |
| `05-command-path.png` | architecture.md | One write from the screen to the database and on to other modules |
| `06-data-lifecycle.png` | data-model.md | How one order travels through the schemas during the day |
| `07-schema-map.png` | data-model.md | Every schema, its table count and the foreign keys between schemas |

## Making or redrawing an image

Each prompt below is written for an image model and is complete on its own. Generate at 16:9, 2400 px wide or more, and save the file under the name in its heading. Check every label against the list in the prompt before committing: image models misspell text, and a wrong label is worse than no image. Never use an em dash or an en dash in a label.

Shared style for every image (it is repeated in each prompt so a prompt can be pasted alone): a clean technical architecture diagram on a white background, flat vector style, no 3D, no people illustrations or clip art, no gradients, thin rounded rectangles with 1.5 px dark grey outlines, a sans-serif font such as Inter, dark grey text, generous white space, arrows with small solid heads, and one accent colour per group: blue `#2563EB` for the frontend and people, green `#16A34A` for backend modules, amber `#D97706` for data stores, purple `#7C3AED` for outside services, grey `#6B7280` for operations and platform. Every box label must be spelled exactly as given.

### 01-system-context.png

```text
A clean technical system context diagram, 16:9, white background, flat vector style, thin rounded rectangles with dark grey outlines, Inter sans-serif font, dark grey text, generous white space, arrows with small solid heads. No 3D, no clip art, no gradients. Spell every label exactly as written.

Centre: one large rounded rectangle in green #16A34A outline with light green fill, titled "Waypoint Dispatch", subtitle "one web app · one backend · one PostgreSQL database".

Left side, a vertical column of five blue #2563EB boxes labelled "People", each with a small simple line icon:
1. "Store manager" (icon: shop)
2. "Dispatcher" (icon: desktop monitor)
3. "Loader" with small text "shared dock tablet" (icon: tablet)
4. "Driver" (icon: phone)
5. "Admin and auditor" (icon: shield)
One bracket groups all five and a single arrow goes from the bracket into "Waypoint Dispatch", labelled "one responsive web app, HTTPS".

Top: one purple #7C3AED box "AI assistant" with small text "Claude, ChatGPT and others". Arrow down into "Waypoint Dispatch" labelled "MCP · read only · OAuth".

Right side, two purple #7C3AED boxes:
- "Warehouse API" with small text "stock and product catalogue". Arrow from "Waypoint Dispatch" to it labelled "StockPort · circuit breaker".
- "Browser push services" with small text "Web Push". Arrow from "Waypoint Dispatch" to it labelled "push notifications · VAPID".

Bottom, a thin grey #6B7280 caption bar: "Each person sees only their own role's screens. Policy decides actions, scope decides rows. An AI assistant acts as the person who connected it, nothing more."

Title at the top left in bold: "System context".
```

### 02-runtime-containers.png

```text
A clean technical container deployment diagram, 16:9, white background, flat vector style, thin rounded rectangles with dark grey outlines, Inter sans-serif font, dark grey text, generous white space, arrows with small solid heads. No 3D, no clip art, no gradients, no cloud vendor logos. Spell every label exactly as written.

Title top left in bold: "What docker compose up runs".

Far left, a dashed blue #2563EB frame titled "Browser (phone, tablet, desktop)" containing three stacked boxes:
- "Role screens" small text "React · Next.js"
- "Service worker" small text "offline app shell"
- "Offline write queue" drawn as a cylinder, small text "IndexedDB"
Thin lines connect "Role screens" to the other two.

Middle and right, a large dashed grey frame titled "Docker Compose" containing these boxes:
- "waypoint" (blue) small text "Next.js server · same-origin /api proxy"
- "backend" (green, the largest box) small text "Spring Boot modular monolith · Java 17"
- "db" drawn as a cylinder (amber #D97706) small text "PostgreSQL 16 · one schema per module · row-level security"
- "ml" (green) small text "FastAPI · trained forecast models"
- "mcp" (purple) small text "MCP adapter · Node"
- "init" (grey) small text "migrate · import-reference · runs once"
- a grey group box "Observability" containing four small boxes "alloy", "loki", "prometheus", "grafana", small text "logs · metrics · dashboards"

Arrows, each with its label:
- "Role screens" to "waypoint": "HTTPS"
- "Offline write queue" to "waypoint": "POST /api/sync on reconnect"
- "waypoint" to "backend": "/api/*"
- "waypoint" to "mcp": "/mcp"
- "mcp" to "backend": "fixed read-only GETs"
- "backend" to "db": "JDBC · SET LOCAL ROLE per module"
- "backend" to "ml": "forecasts · plan scoring"
- "init" to "db": "before start", dashed
- "backend" to "Observability": "logs · /prometheus", dashed

Bottom caption bar in grey: "Offline is tiered by role: driver full, loader and store manager resilient, dispatcher online only. Builds and requests never migrate or seed. Production adds nginx with TLS in front of the same services."
```

### 03-modules-and-events.png

```text
A clean technical module and event flow diagram, 16:9, white background, flat vector style, thin rounded rectangles with dark grey outlines, Inter sans-serif font, dark grey text, generous white space. No 3D, no clip art, no gradients. Spell every label exactly as written.

Title top left in bold: "Modules and how they connect". Subtitle: "One process · each module owns one schema · solid arrows are events through the outbox · dotted lines are contract reads".

Main row, left to right, the delivery day as six green #16A34A boxes, each showing the module name and below it the schema name in monospace:
"Ordering" (ordering) -> "Planning" (planning) -> "Loading" (loading) -> "Execution" (execution) -> "Receipt" (receipt)
with "Issues" (issues) placed below the row between Loading and Receipt.

Solid event arrows with these exact labels:
- Ordering to Planning: "orders.closed · order.placed"
- Planning to Ordering, curved back above: "order.deferred"
- Planning to Loading: "plan.published · plan.revised"
- Planning to Execution, curved above Loading: "plan.published"
- Loading to Execution: "trip.released"
- Loading to Issues: "loading.shortfall"
- Execution to Receipt: "delivery.completed · delivery.failed"
- Execution to Issues: "delivery.failed · vehicle.fault_reported"
- Receipt to Issues: "receipt.disputed"
- Receipt to Ordering, long curve along the top: "receipt.confirmed"
- Issues to Ordering, long curve along the bottom: "redelivery.requested"
- Issues to Loading: "shortfall.resolved"

Second row, below, green boxes with schema names:
- "Messaging" (messaging): incoming arrows from Planning "plan.published · plan.revised" and from Issues "issue.raised · issue.resolved"; small text "trip channels"
- "Notification" (notification): one bundled arrow from Ordering, Planning, Loading, Execution, Receipt and Issues labelled "events"; small text "in-app and Web Push"
- "Sync" (sync): small text "replays queued offline commands", dotted arrows to Ordering, Loading, Execution, Receipt labelled "through the command bus"
- "Warehouse" (warehouse): double arrow with Ordering labelled "StockPort"
- "Intelligence" (ml): dotted line from Planning labelled "PredictionQuery"

Top band, two light green boxes spanning the width: "Reference data" (ref) and "Identity and access" (iam), with a dotted line down to the row labelled "read by all through ReferenceQuery and IdentityQuery".

Bottom band, a grey #6B7280 bar spanning the width: "Platform · integration schema: command bus · idempotency receipts · audit log · outbox and relay · scheduler". A small grey box at the far right: "Demo (demo) · opt-in judge scenarios".

Footer small text: "Boundaries enforced by ModuleBoundaryTest and EventCatalogueTest."
```

### 04-module-layers.png

```text
A clean technical layered component diagram, 16:9, white background, flat vector style, thin rounded rectangles with dark grey outlines, Inter sans-serif font, dark grey text, generous white space, arrows with small solid heads. No 3D, no clip art, no gradients. Spell every label exactly as written.

Title top left in bold: "Inside a module". Subtitle: "Every module has the same five packages".

Left to right, four boxes:
1. "web" (blue #2563EB) small text "thin controllers · reads"
2. "application" (green #16A34A, largest) small text "handlers · queries · event consumers · jobs" and a second line in bold "opens the transaction · decides authorization"
3. "domain" (green, solid fill, white text) small text "pure rules and state machines" and a second line "no Spring · no SQL · no clock"
4. "infrastructure" (amber #D97706) small text "JDBC repositories · ports"

Arrows: "web" to "application"; "application" to "domain"; "application" to "infrastructure"; "infrastructure" to "domain".

Above them, a purple #7C3AED box spanning the width: "contract" small text "views · queries · commands · events · the only package other modules may import". Arrow from "application" up to "contract".

To the right, outside a dashed boundary line, a faded grey box "another module" with one arrow pointing only at "contract", and a red cross over a faded arrow pointing at "domain", labelled "never".
```

### 05-command-path.png

```text
A clean technical sequence diagram, 16:9, white background, flat vector style, Inter sans-serif font, dark grey text, generous white space, thin vertical lifelines, numbered steps in small circles. No 3D, no clip art, no gradients. Spell every label exactly as written.

Title top left in bold: "One command, end to end". Subtitle: "The same path whether sent online or replayed from the offline queue".

Six lifelines left to right, each headed by a box:
"Client or offline queue" (blue), "CommandBus" (grey), "Handler (module)" (green), "PostgreSQL" (amber cylinder icon), "Outbox relay" (grey), "Subscribers (other modules)" (green).

Numbered messages:
1. Client to CommandBus: "POST /api/commands · command id · expectedVersion"
2. CommandBus to itself: "policy AND scope, else 403 and audit"
3. CommandBus to PostgreSQL: "idempotency receipt: seen before? answer from it"
4. CommandBus to Handler: "handle"
5. Handler to PostgreSQL: "SET LOCAL ROLE waypoint_module · actor"
6. Handler to PostgreSQL: "UPDATE ... WHERE id = ? AND row_version = ?"
7. Handler to PostgreSQL: "outbox event · audit row · receipt"
8. PostgreSQL back to Client, dashed: "ack, or RFC 9457 problem with violations"
9. Outbox relay to PostgreSQL: "claim batch FOR UPDATE SKIP LOCKED"
10. Outbox relay to Subscribers: "deliver, each in its own transaction"
11. Subscribers to PostgreSQL: "consumer inbox claim, then its own change"

Draw a light amber shaded band behind steps 3 to 7 labelled "one transaction". Draw a light grey band behind steps 9 to 11 labelled "afterwards, at least once".

Bottom caption: "A stale expectedVersion is 409, never a silent overwrite. A refused rule names every failed constraint."
```

### 06-data-lifecycle.png

```text
A clean technical data flow diagram, 16:9, white background, flat vector style, thin rounded rectangles with dark grey outlines, Inter sans-serif font, dark grey text, monospace for table names, generous white space, arrows with small solid heads. No 3D, no clip art, no gradients. Spell every label exactly as written.

Title top left in bold: "How one order travels through the data". Subtitle: "Schemas link by id and by event, never by a foreign key between modules".

A horizontal flow of six amber #D97706 database cylinders, each with the schema name on top and table names inside in monospace:
1. "ordering": "orders", "order_lines", "order_status_history"
2. "planning": "runs", "trips", "allocations", "route_legs", "deferrals"
3. "loading": "trips", "stops", "items", "item_checks", "shortfalls"
4. "execution": "trips", "delivery_records", "proofs", "vehicle_positions"
5. "receipt": "confirmations", "confirmation_lines", "handovers"
6. "issues": placed below, between loading and receipt: "issues", "attachments"

Arrows between the cylinders, each labelled with the event that carries the data:
- ordering to planning: "orders.closed"
- planning to loading: "plan.published"
- loading to execution: "trip.released"
- execution to receipt: "delivery.completed"
- loading, execution and receipt to issues: "shortfall · failed delivery · dispute"
- receipt back to ordering, curved along the top: "receipt.confirmed"

Above the flow, two light amber cylinders: "ref" with small text "depots · outlets · vehicles · districts · versioned" and "iam" with small text "users · roles · policies · depot and outlet scope". Thin solid lines labelled "foreign keys only point here" go from the row of cylinders up to them.

Below the flow, a grey #6B7280 cylinder spanning the width: "integration" with small text "command_receipts · audit_log · outbox_events · consumed_events · scheduled jobs" and the label "every write leaves a receipt, an audit row and its events".

Right edge, a small legend: "row_version on every update · UUIDv7 keys · timestamptz · numeric for weight and volume · rows reach terminal states, never deleted".
```

### 07-schema-map.png

```text
A clean technical database schema map, 16:9, white background, flat vector style, Inter sans-serif font, dark grey text, monospace for schema names, generous white space, arrows with small solid heads. No 3D, no clip art, no gradients. Spell every label and number exactly as written.

Title top left in bold: "One PostgreSQL database, one schema per module". Subtitle: "110 tables in 15 schemas · arrows are real foreign keys between schemas".

Two large amber #D97706 cylinders at the centre top:
- "ref" · "Reference data" · "18 tables"
- "iam" · "Identity and access" · "20 tables"

Around and below them, smaller amber cylinders, each with schema name, owner and table count:
- "ordering" · "Ordering" · "5 tables"
- "warehouse" · "Warehouse" · "6 tables"
- "planning" · "Planning" · "11 tables"
- "loading" · "Loading" · "6 tables"
- "execution" · "Execution" · "9 tables"
- "receipt" · "Receipt" · "6 tables"
- "issues" · "Issues" · "7 tables"
- "messaging" · "Messaging" · "3 tables"
- "notification" · "Notification" · "5 tables"
- "sync" · "Sync" · "1 table"
- "ml" · "Intelligence" · "5 tables"
- "demo" · "Demo, opt-in" · "3 tables"
And one grey #6B7280 cylinder at the bottom: "integration" · "Platform" · "5 tables".

Arrows with counts, pointing to ref:
planning "7 FK", execution "6 FK", receipt "3 FK", issues "2 FK", loading "2 FK", iam "2 FK", ordering "1 FK".
Arrows pointing to iam:
notification "2 FK", issues "1 FK", sync "1 FK".
warehouse, messaging, ml, demo and integration have no foreign key arrows.

Right side legend box titled "Rules":
"Each module is the only writer to its schema"
"Foreign keys point only into ref and iam"
"Forced row-level security by depot, outlet or actor"
"Module roles hold no DELETE"
```
