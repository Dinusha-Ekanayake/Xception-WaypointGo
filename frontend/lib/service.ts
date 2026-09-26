import { Database, type DbRow } from "./database.ts";
import {
  randomBytes,
  createHash,
  pbkdf2Sync,
  timingSafeEqual,
} from "node:crypto";
import {
  loadReference,
  csv,
  allocate,
  validateRoute,
  eligibleDay,
  nextOperating,
} from "./domain.ts";
import {
  CommandSchema,
  OrderSchema,
  PlanSchema,
  parseOrThrow,
  zodMessage,
  REASONS,
  type AppEvent,
  type AppState,
  type Command,
  type Order,
  type Plan,
  type ReferenceData,
  type Reservations,
  type User,
} from "./types.ts";
import { z } from "zod";
import {
  seedScenarios,
  dayReference,
  openingFuel,
  SCENARIOS,
} from "./scenarios.ts";
import type { AssignmentOption } from "./types.ts";

export class DomainError extends Error {
  status: number;
  constructor(message: string, status = 400) {
    super(message);
    this.status = status;
  }
}

export function requireThat(
  ok: unknown,
  message: string,
  status = 400,
): asserts ok {
  if (!ok) throw new DomainError(message, status);
}

const hash = (x: string): string =>
  createHash("sha256").update(x).digest("hex");

const passwordHash = (p: string, s: string): Buffer =>
  pbkdf2Sync(p, s, 200000, 32, "sha256");

// --- Zod-backed field validators (messages preserved for compat) ---
const NonEmptyText = z
  .string()
  .trim()
  .min(1, "A non-empty explanation is required.")
  .max(500, "A non-empty explanation is required.");

const text = (v: unknown, max = 500): string => {
  const parsed = z
    .string()
    .trim()
    .min(1, "A non-empty explanation is required.")
    .max(max, "A non-empty explanation is required.")
    .safeParse(v);
  requireThat(parsed.success, "A non-empty explanation is required.");
  return (parsed as { data: string }).data.trim();
};

const number = (v: unknown, min = 0, max = 1e6, integer = false): number => {
  const schema = integer
    ? z.number().finite().int().min(min).max(max)
    : z.number().finite().min(min).max(max);
  requireThat(schema.safeParse(v).success, "Invalid quantity.");
  return v as number;
};

const imageData = (v: unknown): string => {
  const s = z
    .string()
    .max(1500000)
    .regex(
      /^data:image\/(png|jpeg);base64,[A-Za-z0-9+/]+=*$/,
      "Use a PNG/JPEG image smaller than 1 MB.",
    )
    .safeParse(v);
  requireThat(
    s.success,
    typeof v === "string" && (v as string).length >= 1500000
      ? "Use a PNG/JPEG image smaller than 1 MB."
      : "Use a PNG/JPEG image smaller than 1 MB.",
  );
  const b = Buffer.from((v as string).split(",")[1] as string, "base64");
  requireThat(
    b.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex")) ||
      b.subarray(0, 3).equals(Buffer.from("ffd8ff", "hex")),
    "Invalid image format.",
  );
  return v as string;
};

function week(day: string): string {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}

type Commander = User;

interface StoredCommand {
  id: string;
  user_id: string;
  fingerprint: string;
  response: string;
}

export class Service {
  db: Database;
  ref: ReferenceData;

  constructor(db: Database) {
    this.db = db;
    this.ref = loadReference();
  }

  async all(sql: string, ...params: unknown[]): Promise<DbRow[]> { return this.db.all(sql, ...params); }
  async get(sql: string, ...params: unknown[]): Promise<DbRow | undefined> { return this.db.get(sql, ...params); }
  async run(sql: string, ...params: unknown[]): Promise<void> { return this.db.run(sql, ...params); }
  async transaction<T>(fn: () => Promise<T>): Promise<T> { return this.db.transaction(fn); }

  now(): string {
    return process.env.DEMO_MODE === "0"
      ? new Date().toISOString()
      : process.env.DEMO_NOW || "2026-02-13T15:30:00+05:30";
  }

  async save(o: Order): Promise<void> {
    const parsed = OrderSchema.safeParse(o);
    requireThat(parsed.success, "Invalid order record.");
    await this.transaction(async () => {
      const stored = structuredClone(parsed.data);
      const images: { id: string; kind: string; mime: string; bytes: Buffer }[] = [];
      for (const kind of ["photo", "signature"] as const) {
        const data = stored.proof?.[kind];
        if (!data) continue;
        imageData(data);
        const bytes = Buffer.from(data.split(",")[1], "base64");
        const id = hash(`${stored.id}:${kind}:${data}`);
        images.push({ id, kind, mime: data.slice(5, data.indexOf(";")), bytes });
        stored.proof![`${kind}_id`] = id;
        delete stored.proof![kind];
      }
      await this.run("INSERT INTO orders(id,body) VALUES($1,$2) ON CONFLICT(id) DO UPDATE SET body=excluded.body", stored.id, JSON.stringify(stored));
      for (const image of images)
        await this.run("INSERT INTO proof_images(id,order_id,kind,content_type,bytes) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING", image.id, stored.id, image.kind, image.mime, image.bytes);
    });
  }

  async proofImage(user: User, orderId: string, imageId: string): Promise<{ data: string }> {
    return this.transaction(async () => {
      const record = await this.get("SELECT body FROM orders WHERE id=$1", orderId);
      requireThat(record, "Order not found.", 404);
      const order = JSON.parse(String(record.body)) as Order;
      requireThat(this.allowed(user, order), "Record is outside your assignment.", 403);
      requireThat(order.proof?.photo_id === imageId || order.proof?.signature_id === imageId, "Proof not found.", 404);
      const image = await this.get("SELECT content_type,bytes FROM proof_images WHERE id=$1 AND order_id=$2", imageId, orderId);
      requireThat(image && Buffer.isBuffer(image.bytes), "Proof not found.", 404);
      return { data: `data:${image.content_type};base64,${(image.bytes as Buffer).toString("base64")}` };
    });
  }

  async checkLoginRate(email: string): Promise<void> {
    const accepted = await this.transaction(async () => {
      const key = email.toLowerCase().slice(0, 200);
      const now = Date.now();
      await this.run("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", `login:${key}`);
      await this.run("DELETE FROM login_attempts WHERE attempted_at<$1", now - 60000);
      const row = await this.get("SELECT count(*)::integer AS count FROM login_attempts WHERE email=$1 AND attempted_at>=$2", key, now - 60000);
      if (Number(row?.count) >= 15) return false;
      await this.run("INSERT INTO login_attempts VALUES($1,$2)", key, now);
      return true;
    });
    requireThat(accepted, "Too many attempts. Wait one minute.", 429);
  }

  async orders(user?: User): Promise<Order[]> {
    const column = user?.role === "store" ? "outlet_id" : user?.role === "loader" ? "depot" : user?.role === "driver" ? "vehicle_id" : null;
    const rows = column ? await this.all(`SELECT body FROM orders WHERE ${column}=$1 ORDER BY id`, user!.scope) : await this.all("SELECT body FROM orders ORDER BY id");
    return rows.map((r) => {
      const raw = JSON.parse(String(r.body)) as unknown;
      const parsed = OrderSchema.safeParse(raw);
      // Lenient read: accept legacy rows, but new writes are strict.
      return (parsed.success ? parsed.data : raw) as Order;
    });
  }

  async plans(): Promise<Plan[]> {
    return (await this.all("SELECT body FROM plans ORDER BY day")).map((r) => {
      const raw = JSON.parse(String(r.body)) as unknown;
      const parsed = PlanSchema.safeParse(raw);
      return (parsed.success ? parsed.data : raw) as Plan;
    });
  }

  async seed(): Promise<void> {
    requireThat(process.env.SEED_PASSWORD && process.env.SEED_PASSWORD.length >= 12, "Set SEED_PASSWORD to at least 12 characters before seeding.");
    (await this.transaction(async () => {
      await this.run("LOCK TABLE settings IN EXCLUSIVE MODE");
      if ((await this.get("SELECT 1 FROM settings WHERE key=$1", "seeded"))) return;
      const outlets = Object.fromEntries(
        this.ref.outlets.map((o) => [o.outlet_id, o]),
      ) as Record<string, (typeof this.ref.outlets)[number]>;
      const orders: Order[] = csv("Training Data/deliveries_train.csv")
        .filter((r) => r.order_date === "2026-02-14")
        .map((r) => {
          const outlet = outlets[r.outlet_id];
          if (!outlet) throw new Error(`Unknown outlet ${r.outlet_id}`);
          return parseOrThrow(OrderSchema, {
            ...outlet,
            id: r.delivery_id,
            day: "2026-02-14",
            weight: Number(r.order_weight_kg),
            volume: Number(r.order_volume_m3),
            units: Number(r.order_units),
            temp: r.temp_requirement,
            status: "confirmed_order",
            version: 0,
            skips: 0,
          });
        });
      const sampleOutlet = outlets["OUT001"];
      if (!sampleOutlet) throw new Error("Missing outlet OUT001");
      orders.push(
        parseOrThrow(OrderSchema, {
          ...sampleOutlet,
          id: "OVERLOAD-001",
          day: "2026-02-14",
          weight: 1150,
          volume: 6.8,
          units: 115,
          temp: "chilled",
          status: "confirmed_order",
          version: 0,
          skips: 1,
          source: {
            file: "Walkthrough capacity fixture",
            order_ref: "OVERLOAD-001",
            kind: "synthetic",
          },
        }),
      );
      const plan = allocate(orders, this.ref, "2026-02-14");
      const target = orders.find(
        (o) => o.outlet_id === "OUT001" && o.id !== "OVERLOAD-001",
      );
      const vehicle = plan.routes.find((r) =>
        r.order_ids.includes(target!.id),
      )!.vehicle_id;
      for (const o of orders) (await this.save(o));
      for (const [role, scope] of [
        ["dispatcher", "all"],
        ["loader", "Peliyagoda"],
        ["driver", vehicle],
        ["store", "OUT001"],
      ] as const) {
        const salt = randomBytes(16).toString("hex");
        (await this.run(
          "INSERT INTO users VALUES($1,$2,$3,$4,$5)",
          role + "@waypoint.local",
          role,
          scope,
          salt,
          passwordHash(
            process.env.SEED_PASSWORD!,
            salt,
          ).toString("hex"),
        ));
      }
      (await this.run("INSERT INTO settings VALUES($1,$2)", "seeded", "1"));
    }));
    (await seedScenarios(this));
  }

  allowed(u: User, o: Order): boolean {
    return (
      u.role === "dispatcher" ||
      (u.role === "loader" && o.depot === u.scope) ||
      (u.role === "driver" && o.vehicle_id === u.scope) ||
      (u.role === "store" && o.outlet_id === u.scope)
    );
  }

  async login(email: unknown, password: unknown): Promise<{ token: string; user: User }> {
    requireThat(
      typeof email === "string" &&
        typeof password === "string" &&
        password.length < 200,
      "Invalid credentials.",
      401,
    );
    const row = (await this.get(
      "SELECT * FROM users WHERE id=$1",
      (email as string).toLowerCase(),
    ));
    const p = passwordHash(
      password as string,
      (row?.salt as string) || "missing-user-salt",
    );
    requireThat(
      row && timingSafeEqual(p, Buffer.from(row.hash as string, "hex")),
      "Invalid email or password.",
      401,
    );
    const token = randomBytes(40).toString("hex");
    (await this.run("DELETE FROM sessions WHERE expires<$1", Date.now()));
    (await this.run(
      "INSERT INTO sessions VALUES($1,$2,$3)",
      hash(token),
      row.id as string,
      Date.now() + 86400000,
    ));
    return {
      token,
      user: {
        id: row.id as string,
        role: row.role as User["role"],
        scope: row.scope as string,
      },
    };
  }

  async session(token: string): Promise<User> {
    const u = (await this.get(
      "SELECT u.id,u.role,u.scope FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.token=$1 AND s.expires>$2",
      hash(token),
      Date.now(),
    ));
    requireThat(u, "Please sign in again.", 401);
    return { ...(u as unknown as User) };
  }

  async logout(token: string): Promise<void> {
    (await this.run("DELETE FROM sessions WHERE token=$1", hash(token)));
  }

  async state(u: User): Promise<AppState> {
    return this.transaction(() => this.readState(u));
  }

  private async readState(u: User): Promise<AppState> {
    const orders = await this.orders(u);
    const ids = new Set(orders.map((o) => o.id));
    let plans = (await this.plans());
    if (u.role !== "dispatcher")
      plans = plans
        .filter((p) => p.published)
        .map((p) => ({
          ...p,
          orders: p.orders?.filter((o) => ids.has(o.id)),
          routes: p.routes
            .filter((r) => r.order_ids.some((i) => ids.has(i)))
            .map((r) => ({
              ...r,
              order_ids: r.order_ids.filter((i) => ids.has(i)),
              stops: r.stops.filter((s) => ids.has(s.order_id)),
            })),
          deferred: p.deferred.filter((d) => ids.has(d.order_id)),
        }));
    const allPlans = plans;
    const planning = u.role === "dispatcher" ? Object.fromEntries(await Promise.all([...new Set([...orders.map((o) => o.day), ...plans.map((p) => p.day), ...SCENARIOS.map((s) => s.day)])].map(async (day) => [day, { vehicles: this.reference(day).vehicles, reservations: await this.reservations(day, allPlans) }]))) : undefined;
    const state = {
      user: u,
      orders,
      plans,
      events: (await this.all("SELECT * FROM events ORDER BY id DESC")).filter(
        (e) =>
          ids.has(String(e.order_id)) ||
          (u.role === "dispatcher" && e.order_id === "*"),
      ),
      vehicles: this.ref.vehicles.filter(
        (v) =>
          u.role === "dispatcher" ||
          (u.role === "loader" && v.depot === u.scope) ||
          (u.role === "driver" && v.vehicle_id === u.scope),
      ),
      outlets: this.ref.outlets.filter(
        (o) =>
          u.role === "dispatcher" ||
          (u.role === "store" && o.outlet_id === u.scope),
      ),
      now: this.now(),
      demo: process.env.DEMO_MODE !== "0",
      updated: new Date().toISOString(),
      scenarios: process.env.DEMO_MODE === "0" ? [] : SCENARIOS,
      planning,
    };
    return state as AppState;
  }

  async reservations(day: string, plans?: Plan[]): Promise<Reservations> {
    const used: Reservations = openingFuel(
      this.ref,
      day,
      process.env.DEMO_MODE !== "0",
    );
    for (const p of plans || (await this.plans())) {
      if (!p.published || p.day === day || week(p.day) !== week(day)) continue;
      for (const r of p.routes) {
        used[r.vehicle_id] ??= { fuel: 0, trips: 0, end: 210 };
        used[r.vehicle_id]!.fuel += r.fuel;
      }
    }
    return used;
  }

  reference(day: string): ReferenceData {
    return dayReference(this.ref, day, process.env.DEMO_MODE !== "0");
  }

  async validatePlan(plan: Plan, byid: Record<string, Order>, reserved?: Reservations): Promise<void> {
    const usage = structuredClone(reserved || await this.reservations(plan.day));
    const ref = this.reference(plan.day);
    for (const r of [...plan.routes].sort(
      (a, b) => a.start - b.start || a.id.localeCompare(b.id),
    )) {
      const v = ref.vehicles.find((v) => v.vehicle_id === r.vehicle_id);
      requireThat(v, "Vehicle no longer exists.");
      requireThat(
        r.order_ids.every((id) => byid[id]),
        "Orders changed. Generate a fresh plan.",
        409,
      );
      const used = (usage[v.vehicle_id] ??= { fuel: 0, trips: 0, end: 210 });
      const result = validateRoute(
        r.order_ids.map((id) => byid[id]),
        v,
        ref,
        Math.max(r.start, used.end),
        used.fuel,
        used.trips,
      );
      requireThat(
        !result.errors.length,
        "Invalid assignment: " +
          result.errors.map((k) => REASONS[k]).join(", "),
      );
      Object.assign(r, result);
      used.fuel += r.fuel;
      used.trips++;
      used.end = r.end;
    }
  }

  async assignment(
    plan: Plan,
    orderId: string,
    routeId: string,
    byid: Record<string, Order>,
    reserved?: Reservations,
  ): Promise<Plan> {
    const usage = reserved || await this.reservations(plan.day);
    requireThat(byid[orderId], "Order not found.", 404);
    const base = structuredClone(plan);
    for (const r of base.routes)
      r.order_ids = r.order_ids.filter((id) => id !== orderId);
    let target = base.routes.find((r) => r.id === routeId);
    if (!target && routeId.startsWith("NEW-")) {
      const vehicleId = routeId.slice(4);
      requireThat(
        this.reference(plan.day).vehicles.some(
          (v) => v.vehicle_id === vehicleId,
        ),
        "Vehicle not found.",
      );
      const end = Math.max(
        210,
        ...base.routes
          .filter((r) => r.vehicle_id === vehicleId)
          .map((r) => r.end),
      );
      target = {
        id: `RUN-${randomBytes(4).toString("hex")}`,
        vehicle_id: vehicleId,
        order_ids: [],
        stops: [],
        start: end,
        end,
        fuel: 0,
        distance: 0,
        errors: [],
      };
      base.routes.push(target);
    }
    requireThat(target, "Select a valid route.");
    const targetId = target.id;
    base.deferred = base.deferred.filter((d) => d.order_id !== orderId);
    let best: Plan | undefined;
    let error: unknown;
    // Try every insertion point, validating both trips and the week's fuel each time.
    for (let index = 0; index <= target.order_ids.length; index++) {
      const candidate = structuredClone(base);
      candidate.routes
        .find((r) => r.id === targetId)!
        .order_ids.splice(index, 0, orderId);
      candidate.routes = candidate.routes.filter((r) => r.order_ids.length);
      try {
        (await this.validatePlan(candidate, byid, usage));
        if (
          !best ||
          candidate.routes.reduce((n, r) => n + r.end, 0) <
            best.routes.reduce((n, r) => n + r.end, 0)
        )
          best = candidate;
      } catch (e) {
        if (!(e instanceof DomainError)) throw e;
        error = e;
      }
    }
    if (!best)
      throw error || new DomainError("No feasible position in this trip.");
    return best;
  }

  async previewAssignments(
    u: User,
    day: string,
    orderId: string,
  ): Promise<AssignmentOption[]> {
    return this.transaction(() => this.readAssignments(u, day, orderId));
  }

  private async readAssignments(u: User, day: string, orderId: string): Promise<AssignmentOption[]> {
    requireThat(u.role === "dispatcher", "Dispatcher access required.", 403);
    const usage = await this.reservations(day);
    const plan = (await this.plans()).find((p) => p.day === day);
    requireThat(plan && !plan.published, "An editable draft is required.", 409);
    const byid = Object.fromEntries(
      (await this.orders())
        .filter((o) => o.day === day)
        .map((o) => [o.id, o]),
    );
    const o = byid[orderId];
    requireThat(o, "Order not found.", 404);
    const ref = this.reference(day);
    const targets = [
      ...plan.routes
        .filter((r) => !r.order_ids.includes(orderId))
        .map((r) => ({ route_id: r.id, vehicle_id: r.vehicle_id })),
      ...ref.vehicles
        .filter((v) => v.depot === o.depot)
        .map((v) => ({
          route_id: `NEW-${v.vehicle_id}`,
          vehicle_id: v.vehicle_id,
        })),
    ];
    return (await Promise.all(targets
      .map(async (t) => {
        try {
          const candidate = (await this.assignment(plan, orderId, t.route_id, byid, usage));
          const r = candidate.routes.find((r) =>
            r.order_ids.includes(orderId),
          )!;
          const v = ref.vehicles.find((v) => v.vehicle_id === r.vehicle_id)!;
          return {
            ...t,
            feasible: true,
            reason: "All operating constraints pass",
            arrival: r.stops.find((s) => s.order_id === orderId)!.eta,
            added_fuel:
              candidate.routes.reduce((n, r) => n + r.fuel, 0) -
              plan.routes.reduce((n, r) => n + r.fuel, 0),
            added_distance:
              candidate.routes.reduce((n, r) => n + r.distance, 0) -
              plan.routes.reduce((n, r) => n + r.distance, 0),
            remaining_weight:
              v.weight_cap_kg -
              r.order_ids.reduce((n, id) => n + byid[id].weight, 0),
            remaining_volume:
              v.volume_cap_m3 -
              r.order_ids.reduce((n, id) => n + byid[id].volume, 0),
          };
        } catch (e) {
          if (!(e instanceof DomainError)) throw e;
          return {
            ...t,
            feasible: false,
            reason: (e as Error).message,
            added_fuel: 0,
            added_distance: 0,
          };
        }
      })))
      .sort(
        (a, b) =>
          Number(b.feasible) - Number(a.feasible) ||
          a.added_fuel - b.added_fuel ||
          a.vehicle_id.localeCompare(b.vehicle_id),
      );
  }

  async event(
    u: Commander,
    kind: string,
    oid: string,
    cmd: { client_time?: unknown },
    detail: unknown,
  ): Promise<void> {
    (await this.run(
      "INSERT INTO events(order_id,actor,kind,created,client_time,detail) VALUES($1,$2,$3,$4,$5,$6)",
      oid,
      u.id,
      kind,
      new Date().toISOString(),
      String(cmd.client_time || "").slice(0, 100),
      JSON.stringify(detail),
    ));
  }

  async command(
    u: Commander,
    cmd: unknown,
  ): Promise<{ ok: boolean; order_id?: string; day?: string; version?: number }> {
    requireThat(cmd && typeof cmd === "object", "Invalid command.");
    const raw = cmd as Record<string, unknown>;
    const key = text(raw.id, 100);
    const fingerprint = hash(JSON.stringify(cmd));
    return (await this.transaction(async () => {
      const old = (await this.get(
        "SELECT * FROM commands WHERE id=$1 AND user_id=$2",
        key,
        u.id,
      )) as unknown as StoredCommand | undefined;
      if (old) {
        requireThat(
          old.fingerprint === fingerprint,
          "Command identifier already used with different data.",
          409,
        );
        return JSON.parse(old.response);
      }
      let result: {
        ok: boolean;
        order_id?: string;
        day?: string;
        version?: number;
      };
      try {
        result = (await this.apply(u, cmd));
      } catch (e) {
        if (e instanceof DomainError) throw e;
        if (/calendar|operating/.test((e as Error).message))
          throw new DomainError((e as Error).message);
        throw e;
      }
      (await this.run(
        "INSERT INTO commands VALUES($1,$2,$3,$4)",
        key,
        u.id,
        fingerprint,
        JSON.stringify(result),
      ));
      return result;
    }));
  }

  async apply(
    u: Commander,
    cmd: unknown,
  ): Promise<{ ok: boolean; order_id?: string; day?: string; version?: number }> {
    // Runtime-validate the command envelope; per-kind fields are checked
    // below with the same user-facing messages as before.
    const parsed = CommandSchema.safeParse(cmd);
    requireThat(parsed.success, parsed.success ? "" : zodMessage(parsed.error));
    const c = parsed.data as Command & Record<string, unknown>;
    const { kind } = c as { kind: string };
    const day = (c.day as string) || "2026-02-14";

    if (["plan", "publish", "move", "defer_note"].includes(kind)) {
      requireThat(u.role === "dispatcher", "Dispatcher access required.", 403);
      const row = (await this.get("SELECT body FROM plans WHERE day=$1", day));
      let plan: Plan | null = row
        ? (JSON.parse(String(row.body)) as Plan)
        : null;
      requireThat(
        !plan?.published,
        "Published plans are locked. Resolve exceptions without rewriting the plan.",
        409,
      );
      requireThat(
        c.revision == null || Number(c.revision) === (plan?.revision || 0),
        "This draft changed. Refresh before applying your decision.",
        409,
      );
      const orders = (await this.orders()).filter((o) => o.day === day);
      const byid = Object.fromEntries(orders.map((o) => [o.id, o]));
      if (kind === "plan") {
        requireThat(orders.length, "No orders for this day.");
        const cutoff =
          new Date(day + "T00:00:00+05:30").getTime() - 8 * 3600000;
        requireThat(
          process.env.DEMO_MODE !== "0" ||
            new Date(this.now()).getTime() >= cutoff,
          "Orders are still open. Plan after 16:00.",
        );
        plan = {
          ...allocate(orders, this.reference(day), day, (await this.reservations(day))),
          revision: plan?.revision || 0,
          orders: structuredClone(orders),
        };
      } else {
        requireThat(plan, "Create a draft plan first.");
        if (kind === "defer_note") {
          const d = plan!.deferred.find(
            (d) =>
              d.order_id === (c as unknown as { order_id: string }).order_id,
          );
          requireThat(d, "Deferred order not found.", 404);
          d!.justification = text((c as unknown as { note: unknown }).note);
        }
        if (kind === "move") {
          const mv = c as unknown as { order_id: string; route_id: string };
          requireThat(byid[mv.order_id], "Order not found.", 404);
          plan = (await this.assignment(plan!, mv.order_id, mv.route_id, byid));
        }
        if (["move", "publish"].includes(kind)) {
          (await this.validatePlan(plan!, byid));
          if (kind === "publish") {
            requireThat(
              plan!.deferred.every((d) => !d.repeat || d.justification),
              "A repeated deferral needs a written justification.",
            );
            const covered = [
              ...plan!.routes.flatMap((r) => r.order_ids),
              ...plan!.deferred.map((d) => d.order_id),
            ];
            requireThat(
              new Set(covered).size === covered.length &&
                covered.length === orders.length &&
                covered.every((i) => byid[i]),
              "Orders changed since drafting. Generate a fresh plan.",
              409,
            );
            plan!.orders = structuredClone(orders);
            plan!.published = true;
            for (const r of plan!.routes)
              for (const stop of r.stops) {
                const o = byid[stop.order_id]!;
                Object.assign(o, {
                  status: "planned",
                  route_id: r.id,
                  vehicle_id: r.vehicle_id,
                  eta: stop.eta,
                  sequence: stop.sequence,
                  version: o.version + 1,
                });
                (await this.save(o));
                (await this.event(u, kind, o.id, c as { client_time?: unknown }, {
                  route: r.id,
                  eta: stop.eta,
                }));
              }
            for (const d of plan!.deferred) {
              const o = byid[d.order_id]!;
              let next = nextOperating(day, this.ref);
              while ((await this.plans()).some((p) => p.day === next && p.published))
                next = nextOperating(next, this.ref);
              d.next_date = next;
              Object.assign(o, {
                requested_day: o.requested_day || o.day,
                day: next,
                status: "deferred",
                deferral: d,
                skips: o.skips + 1,
                version: o.version + 1,
              });
              delete o.route_id;
              delete o.vehicle_id;
              delete o.eta;
              delete o.sequence;
              (await this.save(o));
              (await this.event(
                u,
                "deferred",
                o.id,
                c as { client_time?: unknown },
                d,
              ));
            }
          }
        }
      }
      plan!.revision = (plan!.revision || 0) + 1;
      const planParsed = PlanSchema.safeParse(plan);
      requireThat(planParsed.success, "Invalid plan state.");
      (await this.run(
        "INSERT INTO plans(day,body) VALUES($1,$2) ON CONFLICT(day) DO UPDATE SET body=excluded.body",
        day,
        JSON.stringify(plan),
      ));
      (await this.event(u, kind, "*", c as { client_time?: unknown }, { day }));
      return { ok: true };
    }

    if (kind === "order") {
      requireThat(u.role === "store", "Store access required.", 403);
      const outlet = this.ref.outlets.find((o) => o.outlet_id === u.scope);
      let target = eligibleDay(this.now(), this.ref);
      while ((await this.plans()).some((p) => p.day === target && p.published))
        target = nextOperating(target, this.ref);
      const oc = c as unknown as {
        temp: unknown;
        weight: unknown;
        volume: unknown;
        units: unknown;
      };
      requireThat(
        ["ambient", "chilled"].includes(oc.temp as string),
        "Invalid temperature.",
      );
      requireThat(
        oc.temp !== "chilled" || outlet?.brand === "Fresh",
        "Chilled orders are available for Fresh outlets.",
      );
      const o: Order = {
        ...outlet!,
        id: "ORD-" + randomBytes(4).toString("hex").toUpperCase(),
        day: target,
        weight: number(oc.weight, 0.01),
        volume: number(oc.volume, 0.001, 10000),
        units: number(oc.units, 1, 100000, true),
        temp: oc.temp as Order["temp"],
        status: "confirmed_order",
        version: 0,
        skips: 0,
      };
      (await this.save(parseOrThrow(OrderSchema, o)));
      (await this.event(u, "order", o.id, c as { client_time?: unknown }, {
        day: target,
      }));
      return { ok: true, order_id: o.id, day: target };
    }

    const rules: Record<string, [User["role"], string[]]> = {
      load: ["loader", ["planned"]],
      shortfall: ["loader", ["planned", "loaded"]],
      resolve: ["dispatcher", ["shortfall"]],
      depart: ["driver", ["loaded"]],
      arrive: ["driver", ["departed"]],
      deliver: ["driver", ["arrived"]],
      receive: ["store", ["delivered", "partial"]],
      dispute: ["store", ["delivered", "partial"]],
    };
    requireThat(rules[kind], "Unknown command.");
    const [role, states] = rules[kind]!;
    requireThat(u.role === role, "Your role cannot perform this action.", 403);
    const cc = c as unknown as {
      order_id?: unknown;
      version?: unknown;
      note?: unknown;
      count?: unknown;
      outcome?: unknown;
      receiver?: unknown;
      signature?: unknown;
      photo?: unknown;
    };
    const row = (await this.get(
      "SELECT body FROM orders WHERE id=$1",
      (cc.order_id as string) || "",
    ));
    requireThat(row, "Order not found.", 404);
    const o = JSON.parse(String(row.body)) as Order;
    requireThat(this.allowed(u, o), "Record is outside your assignment.", 403);
    requireThat(
      cc.version === o.version,
      "This record changed. Review the latest state before retrying.",
      409,
    );
    requireThat(
      states.includes(o.status),
      "Action is unavailable in the current state.",
      409,
    );
    let detail: Record<string, unknown> = {};
    if (kind === "shortfall") {
      detail = {
        note: text(cc.note),
        count: number(cc.count, 1, o.units, true),
      };
      o.shortfall = detail as Order["shortfall"];
    }
    if (kind === "resolve") {
      detail = { note: text(cc.note) };
      o.resolution = detail as Order["resolution"];
    }
    if (kind === "depart") {
      const same = (await this.orders()).filter(
        (x) => x.route_id === o.route_id && x.day === o.day,
      );
      requireThat(
        same.every((x) =>
          [
            "loaded",
            "departed",
            "arrived",
            "delivered",
            "partial",
            "failed",
            "confirmed",
            "disputed",
          ].includes(x.status),
        ),
        "Every stop must be loaded and all shortfalls resolved before departure.",
      );
      void NonEmptyText;
    }
    if (kind === "deliver") {
      requireThat(
        ["delivered", "partial", "failed"].includes(cc.outcome as string),
        "Select a delivery outcome.",
      );
      const count = number(cc.count, 0, o.units, true);
      requireThat(
        (cc.outcome === "delivered" && count === o.units) ||
          (cc.outcome === "partial" && count > 0 && count < o.units) ||
          (cc.outcome === "failed" && count === 0),
        "Delivered count does not match the outcome.",
      );
      detail = {
        outcome: cc.outcome,
        count,
        note: String(cc.note || "").slice(0, 500),
      };
      if (cc.outcome !== "failed")
        Object.assign(detail, {
          receiver: text(cc.receiver, 100),
          signature: imageData(cc.signature),
          photo: imageData(cc.photo),
        });
      else detail.note = text(cc.note);
      o.proof = detail as Order["proof"];
    }
    if (kind === "dispute") {
      detail = { note: text(cc.note) };
      o.dispute = detail as Order["dispute"];
    }
    o.status = {
      load: "loaded",
      shortfall: "shortfall",
      resolve: "planned",
      depart: "departed",
      arrive: "arrived",
      deliver: cc.outcome,
      receive: "confirmed",
      dispute: "disputed",
    }[kind] as Order["status"];
    o.version++;
    (await this.save(o));
    (await this.event(
      u,
      kind,
      o.id,
      c as { client_time?: unknown },
      Object.fromEntries(
        Object.entries(detail).filter(
          ([k]) => !["photo", "signature"].includes(k),
        ),
      ),
    ));

    return { ok: true, order_id: o.id, version: o.version };
  }
}

let instance: Service | undefined;

export function getService(): Service {
  if (!instance) {
    instance = new Service(new Database(process.env.DATABASE_URL || "", process.env.DATABASE_SCHEMA || "public"));
  }
  return instance;
}

export type { AppEvent, AppState, Command, Order, Plan, ReferenceData, User };
