import { z } from "zod";

/* =====================================================================
 * Waypoint Dispatch - central domain types + Zod runtime validation.
 *
 * Single source of truth for every entity crossing a trust boundary:
 * CSV imports, PostgreSQL rows, API bodies, commands, and UI forms.
 * `Schema.parse` / `safeParse` is used at each boundary; the inferred
 * `*T` types flow through lib/, app/ and components/.
 * ===================================================================== */

// ---------- primitives ----------

export const BrandSchema = z.enum(["Fresh", "Style", "Tech"]);
export type Brand = z.infer<typeof BrandSchema>;

export const TempSchema = z.enum(["ambient", "chilled"]);
export type Temp = z.infer<typeof TempSchema>;

export const VehicleTypeSchema = z.enum(["van", "truck"]);
export type VehicleType = z.infer<typeof VehicleTypeSchema>;

export const VehicleTempSchema = z.enum(["ambient", "reefer"]);
export type VehicleTemp = z.infer<typeof VehicleTempSchema>;

export const DepotSchema = z.string().min(1).max(100);
export type Depot = z.infer<typeof DepotSchema>;

export const RoleSchema = z.enum(["dispatcher", "loader", "driver", "store"]);
export type Role = z.infer<typeof RoleSchema>;

export const OrderStatusSchema = z.enum([
  "confirmed_order",
  "planned",
  "loaded",
  "shortfall",
  "departed",
  "arrived",
  "delivered",
  "partial",
  "failed",
  "confirmed",
  "disputed",
  "deferred",
  "resolved",
]);
export type OrderStatus = z.infer<typeof OrderStatusSchema>;

export const RouteErrorCodeSchema = z.enum([
  "weight",
  "volume",
  "temperature",
  "access",
  "depot",
  "window",
  "fuel",
  "trip_limit",
  "group",
  "empty",
  "unavailable",
]);
export type RouteErrorCode = z.infer<typeof RouteErrorCodeSchema>;

export const DaySchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Expected YYYY-MM-DD.");
export type Day = z.infer<typeof DaySchema>;

export const ClockSchema = z.string().regex(/^\d{2}:\d{2}$/, "Expected HH:MM.");
export type Clock = z.infer<typeof ClockSchema>;

// ---------- reference data (General Data CSVs) ----------

export const OutletSchema = z.object({
  outlet_id: z.string().min(1),
  brand: BrandSchema,
  district: z.string().min(1),
  depot: z.string().min(1),
  dock_type: z.string().min(1),
  parking_constraint: z.string().min(1),
  mall_window: z.string().max(50),
  window_open_time: ClockSchema,
  window_close_time: ClockSchema,
});
export type Outlet = z.infer<typeof OutletSchema>;

export const VehicleSchema = z.object({
  vehicle_id: z.string().min(1),
  type: VehicleTypeSchema,
  temp: VehicleTempSchema,
  weight_cap_kg: z.coerce.number().finite().positive(),
  volume_cap_m3: z.coerce.number().finite().positive(),
  fuel_type: z.string().min(1).default("diesel"),
  km_per_l: z.coerce.number().finite().positive(),
  weekly_fuel_quota_l: z.coerce.number().finite().positive(),
  depot: z.string().min(1),
  status: z.string().optional(),
});
export type Vehicle = z.infer<typeof VehicleSchema>;

export const CalendarRowSchema = z.object({
  date: DaySchema,
  is_operating: z.string().min(1),
});
export type CalendarRow = z.infer<typeof CalendarRowSchema>;

/** Raw calendar CSV row (extra columns preserved, only date/is_operating validated). */
export const CalendarCsvRowSchema = z
  .object({ date: DaySchema, is_operating: z.string().min(1) })
  .catchall(z.string());
export type CalendarCsvRow = z.infer<typeof CalendarCsvRowSchema>;

export const DistrictTravelSchema = z.object({
  district: z.string().min(1),
  depot_to_district_freeflow_min: z.coerce.number().finite().nonnegative(),
  inter_stop_freeflow_min: z.coerce.number().finite().nonnegative(),
  depot_to_district_km: z.coerce.number().finite().nonnegative(),
  inter_stop_km: z.coerce.number().finite().nonnegative(),
});
export type DistrictTravel = z.infer<typeof DistrictTravelSchema>;

export const ServiceAllowanceSchema = z.object({
  brand: BrandSchema,
  dock_type: z.string().min(1),
  service_allowance_min: z.coerce.number().finite().nonnegative(),
});
export type ServiceAllowance = z.infer<typeof ServiceAllowanceSchema>;

export const ReferenceDataSchema = z.object({
  outlets: z.array(OutletSchema),
  vehicles: z.array(VehicleSchema),
  calendar: z.array(CalendarRowSchema),
  district_travel: z.array(DistrictTravelSchema),
  service_allowance: z.array(ServiceAllowanceSchema),
});
export type ReferenceData = z.infer<typeof ReferenceDataSchema>;

// ---------- orders ----------

export const OrderSourceSchema = z.object({
  file: z.string().max(300),
  order_ref: z.string().max(100),
  kind: z.enum(["historical", "simulated", "synthetic", "source scenario"]),
});
export type OrderSource = z.infer<typeof OrderSourceSchema>;

export const DeferralSchema = z.object({
  reason: z.string().max(1000),
  next_date: DaySchema,
  repeat: z.boolean().default(false),
  justification: z.string().max(1000).default(""),
});
export type Deferral = z.infer<typeof DeferralSchema>;

export const ShortfallSchema = z.object({
  note: z.string().min(1).max(500),
  count: z.number().int().positive(),
});
export type Shortfall = z.infer<typeof ShortfallSchema>;

export const ResolutionSchema = z.object({
  note: z.string().min(1).max(500),
});
export type Resolution = z.infer<typeof ResolutionSchema>;

const ImageDataUrlSchema = z
  .string()
  .max(1500000, "Use a PNG/JPEG image smaller than 1 MB.")
  .regex(
    /^data:image\/(png|jpeg);base64,[A-Za-z0-9+/]+=*$/,
    "Use a PNG/JPEG image smaller than 1 MB.",
  );

export const ProofSchema = z.object({
  outcome: z.enum(["delivered", "partial", "failed"]),
  count: z.number().int().nonnegative(),
  note: z.string().max(500).default(""),
  receiver: z.string().max(100).optional(),
  signature_id: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  photo_id: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  signature: ImageDataUrlSchema.optional(),
  photo: ImageDataUrlSchema.optional(),
  demo: z.boolean().optional(),
});
export type Proof = z.infer<typeof ProofSchema>;

export const DisputeSchema = z.object({
  note: z.string().min(1).max(500),
});
export type Dispute = z.infer<typeof DisputeSchema>;

export const OrderSchema = z.object({
  id: z.string().min(1).max(100),
  outlet_id: z.string().min(1),
  brand: BrandSchema,
  district: z.string().min(1),
  depot: z.string().min(1),
  dock_type: z.string().min(1),
  parking_constraint: z.string().default("normal"),
  mall_window: z.string().max(50).default(""),
  window_open_time: ClockSchema,
  window_close_time: ClockSchema,
  day: DaySchema,
  requested_day: DaySchema.optional(),
  weight: z.number().finite().nonnegative(),
  volume: z.number().finite().nonnegative(),
  units: z.number().int().nonnegative(),
  temp: TempSchema,
  status: OrderStatusSchema.default("confirmed_order"),
  version: z.number().int().nonnegative().default(0),
  skips: z.number().int().nonnegative().default(0),
  // Enrichment / workflow fields
  route_id: z.string().max(100).optional(),
  vehicle_id: z.string().max(100).optional(),
  eta: z.string().max(10).optional(),
  sequence: z.number().int().positive().optional(),
  service_minutes: z.number().finite().nonnegative().optional(),
  days_since_last_served: z.number().int().nonnegative().optional(),
  deferral: DeferralSchema.optional(),
  shortfall: ShortfallSchema.optional(),
  resolution: ResolutionSchema.optional(),
  proof: ProofSchema.optional(),
  dispute: DisputeSchema.optional(),
  source: OrderSourceSchema.optional(),
  parent_order_id: z.string().optional(),
  exception_resolution: z.object({
    decision: z.enum(["redeliver", "returned", "close"]), note: z.string(), actor: z.string(),
    created: z.string(), previous_status: z.string(), replacement_order_id: z.string().optional(),
    count: z.number().optional(),
  }).optional(),
});
export type Order = z.infer<typeof OrderSchema>;

/** Parse a DB/CSV order row at runtime; throws a readable Error on mismatch. */
export function parseOrder(data: unknown): Order {
  return OrderSchema.parse(data);
}

// ---------- planning output ----------

export const RouteStopSchema = z.object({
  order_id: z.string().min(1),
  arrival: z.number().finite().nonnegative(),
  eta: ClockSchema,
  service: z.number().finite().nonnegative(),
  sequence: z.number().int().positive(),
});
export type RouteStop = z.infer<typeof RouteStopSchema>;

export const PlannedRouteSchema = z.object({
  id: z.string().min(1),
  vehicle_id: z.string().min(1),
  order_ids: z.array(z.string().min(1)),
  stops: z.array(RouteStopSchema),
  fuel: z.number().finite().nonnegative(),
  distance: z.number().finite().nonnegative(),
  start: z.number().finite().nonnegative(),
  end: z.number().finite().nonnegative(),
  errors: z.array(RouteErrorCodeSchema).default([]),
});
export type PlannedRoute = z.infer<typeof PlannedRouteSchema>;

export const DeferredEntrySchema = z.object({
  order_id: z.string().min(1),
  reason: z.string().max(2000),
  next_date: DaySchema,
  repeat: z.boolean().default(false),
  justification: z.string().max(1000).default(""),
});
export type DeferredEntry = z.infer<typeof DeferredEntrySchema>;

export const PlanSchema = z.object({
  day: DaySchema,
  revision: z.number().int().nonnegative().default(0),
  orders: z.array(OrderSchema).optional(),
  routes: z.array(PlannedRouteSchema),
  deferred: z.array(DeferredEntrySchema),
  published: z.boolean().default(false),
});
export type Plan = z.infer<typeof PlanSchema>;

export const ValidateRouteResultSchema = z.object({
  errors: z.array(RouteErrorCodeSchema),
  stops: z.array(RouteStopSchema),
  fuel: z.number().finite().nonnegative(),
  distance: z.number().finite().nonnegative(),
  start: z.number().finite().nonnegative(),
  end: z.number().finite().nonnegative(),
});
export type ValidateRouteResult = z.infer<typeof ValidateRouteResultSchema>;

export const VehicleReservationSchema = z.object({
  fuel: z.number().finite().nonnegative().default(0),
  trips: z.number().int().nonnegative().default(0),
  end: z.number().finite().nonnegative().default(210),
});
export type VehicleReservation = z.infer<typeof VehicleReservationSchema>;

export const ReservationsSchema = z.record(
  z.string(),
  VehicleReservationSchema,
);
export type Reservations = z.infer<typeof ReservationsSchema>;

// ---------- users / state / events / queue ----------

export const UserSchema = z.object({
  id: z.string().min(1).max(200),
  role: RoleSchema,
  scope: z.string().min(1).max(200),
});
export type User = z.infer<typeof UserSchema>;

export const AppEventSchema = z.object({
  id: z.number().int().nonnegative(),
  order_id: z.string().max(100),
  actor: z.string().max(200),
  kind: z.string().min(1).max(100),
  created: z.string().max(100),
  client_time: z.string().max(100).nullable().optional(),
  detail: z.string().max(20000),
});
export type AppEvent = z.infer<typeof AppEventSchema>;

export const AppStateSchema = z.object({
  user: UserSchema,
  orders: z.array(OrderSchema),
  plans: z.array(PlanSchema),
  events: z.array(AppEventSchema),
  vehicles: z.array(VehicleSchema),
  outlets: z.array(OutletSchema),
  now: z.string().min(1),
  demo: z.boolean(),
  updated: z.string().min(1),
  scenarios: z
    .array(
      z.object({
        day: DaySchema,
        name: z.string(),
        description: z.string(),
        kind: z.string(),
      }),
    )
    .optional(),
  planning: z
    .record(
      z.string(),
      z.object({
        vehicles: z.array(VehicleSchema),
        reservations: ReservationsSchema,
      }),
    )
    .optional(),
});
export type AppState = z.infer<typeof AppStateSchema>;

export const QueuedCommandSchema = z.object({
  command: z.record(z.string(), z.unknown()),
  created: z.string().min(1),
  error: z.string().max(2000).optional(),
});
export type QueuedCommand = z.infer<typeof QueuedCommandSchema>;

// ---------- commands (client -> server) ----------

const BaseCommandFields = {
  id: z.string().min(1).max(100),
  client_time: z.string().max(100).optional(),
  day: DaySchema.optional(),
  revision: z.number().int().nonnegative().optional(),
} as const;

export const PlanCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("plan"),
  day: DaySchema,
});
export const PublishCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("publish"),
  day: DaySchema,
});
export const MoveCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("move"),
  day: DaySchema,
  order_id: z.string().min(1).max(100),
  route_id: z.string().min(1).max(100),
});
export const DeferNoteCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("defer_note"),
  day: DaySchema,
  order_id: z.string().min(1).max(100),
  note: z.string().min(1).max(500),
});
export const OrderCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("order"),
  temp: TempSchema,
  weight: z.number().finite().min(0.01).max(1000000),
  volume: z.number().finite().min(0.001).max(10000),
  units: z.number().int().min(1).max(100000),
});
export const LoadCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("load"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
});
export const ShortfallCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("shortfall"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
  note: z.string().min(1).max(500),
  count: z.number().int().min(1),
});
export const ResolveCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("resolve"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
  note: z.string().min(1).max(500),
});
export const DepartCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("depart"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
});
export const ArriveCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("arrive"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
});
export const DeliverCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("deliver"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
  outcome: z.enum(["delivered", "partial", "failed"]),
  count: z.number().int().nonnegative(),
  note: z.string().max(500).default(""),
  receiver: z.string().max(100).default(""),
  signature: z.string().max(1500000).default(""),
  photo: z.string().max(1500000).default(""),
});
export const ReceiveCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("receive"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
});
export const DisputeCommandSchema = z.object({
  ...BaseCommandFields,
  kind: z.literal("dispute"),
  order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(),
  note: z.string().min(1).max(500),
});

export const ResolveExceptionCommandSchema = z.object({
  ...BaseCommandFields, kind: z.literal("resolve_exception"), order_id: z.string().min(1).max(100),
  version: z.number().int().nonnegative(), decision: z.enum(["redeliver", "returned", "close"]),
  note: z.string().min(1).max(500), count: z.number().int().positive().optional(),
  weight: z.number().positive().optional(), volume: z.number().positive().optional(),
});
export const CommandSchema = z.discriminatedUnion("kind", [
  PlanCommandSchema,
  PublishCommandSchema,
  MoveCommandSchema,
  DeferNoteCommandSchema,
  OrderCommandSchema,
  LoadCommandSchema,
  ShortfallCommandSchema,
  ResolveCommandSchema,
  ResolveExceptionCommandSchema,
  DepartCommandSchema,
  ArriveCommandSchema,
  DeliverCommandSchema,
  ReceiveCommandSchema,
  DisputeCommandSchema,
]);
export type Command = z.infer<typeof CommandSchema>;

export const LoginSchema = z.object({
  email: z.string().min(1).max(200),
  password: z.string().min(1).max(200),
});
export type Login = z.infer<typeof LoginSchema>;

// ---------- UI forms (client-side validation before enqueue) ----------

export const OrderFormSchema = z.object({
  temp: TempSchema,
  units: z.coerce.number().int().min(1).max(100000),
  weight: z.coerce.number().finite().min(0.01).max(1000000),
  volume: z.coerce.number().finite().min(0.001).max(10000),
});
export type OrderForm = z.infer<typeof OrderFormSchema>;

export const NoteFormSchema = z.object({
  note: z
    .string()
    .trim()
    .min(1, "A non-empty explanation is required.")
    .max(500),
  count: z.coerce.number().int().min(1).optional(),
});
export type NoteForm = z.infer<typeof NoteFormSchema>;

export const ProofFormSchema = z
  .object({
    outcome: z.enum(["delivered", "partial", "failed"]),
    count: z.coerce.number().int().min(0),
    note: z.string().max(500).default(""),
    receiver: z.string().max(100).default(""),
    signature: z.string().max(1500000).default(""),
    photo: z.string().max(1500000).default(""),
  })
  .superRefine((v, ctx) => {
    if (v.outcome !== "failed" && (!v.signature || !v.photo)) {
      ctx.addIssue({
        code: "custom",
        message: "Add a photo and receiver signature.",
        path: ["signature"],
      });
    }
  });
export type ProofForm = z.infer<typeof ProofFormSchema>;

export const FlagFormSchema = z.object({
  note: z.string().trim().min(1).max(500),
  count: z.coerce.number().int().min(1),
});
export type FlagForm = z.infer<typeof FlagFormSchema>;

// ---------- shared component prop types ----------

export interface ActFn {
  (kind: Command["kind"], data?: Record<string, unknown>): Promise<boolean>;
}

export interface WorkspaceBundle {
  state: AppState;
  act: ActFn;
  busy: boolean;
  onDetail: (order: Order) => void;
  queue: QueuedCommand[];
}

export interface DispatcherProps extends WorkspaceBundle {
  tab: string;
}

export interface FieldWorkProps extends WorkspaceBundle {
  tab?: string;
}

export interface DriverLoaderShared extends WorkspaceBundle {
  orders: Order[];
  routes: (PlannedRoute & { day: string })[];
  doAction: (
    kind: Command["kind"],
    order: Order,
    data?: Record<string, unknown>,
  ) => Promise<boolean>;
}

export interface OrderDetailProps {
  order: Order;
  state: AppState;
  onClose: () => void;
}

// ---------- helpers ----------

/** Throw a DomainError-compatible Error with the first Zod issue message. */
export function parseOrThrow<T>(schema: z.ZodType<T>, data: unknown): T {
  const parsed = schema.safeParse(data);
  if (parsed.success) return parsed.data;
  const first = parsed.error.issues[0];
  throw new Error(
    first
      ? `${first.path.join(".") || "value"}: ${first.message}`
      : "Invalid value.",
  );
}

/** First-issue message for surfacing Zod failures in the UI/API. */
export function zodMessage(error: z.ZodError): string {
  const first = error.issues[0];
  return first
    ? `${first.path.join(".") || "value"}: ${first.message}`
    : "Invalid value.";
}

export const REASONS: Record<RouteErrorCode, string> = {
  weight: "Weight capacity exceeded",
  volume: "Volume capacity exceeded",
  temperature: "Refrigerated vehicle required",
  access: "Van-only outlet access",
  depot: "Vehicle belongs to another depot",
  window: "Delivery window cannot be met",
  fuel: "Weekly fuel quota exhausted",
  trip_limit: "Two daily trips already reserved",
  group: "Trip must serve one brand and district",
  empty: "Trip is empty",
  unavailable: "Vehicle is unavailable for this run",
};

export interface AssignmentOption {
  route_id: string;
  vehicle_id: string;
  feasible: boolean;
  reason: string;
  added_fuel: number;
  added_distance: number;
  arrival?: string;
  remaining_weight?: number;
  remaining_volume?: number;
}
