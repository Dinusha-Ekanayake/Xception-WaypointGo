import type { CheckStatus } from "./loading.ts";
import type { DeliveryOutcome } from "./execution.ts";
import type { IssueSeverity, IssueType, SubjectRef } from "./issues.ts";
import type { OrderStatus } from "./ordering.ts";
import type { Decimal, IsoDate, IsoInstant, IsoTime, Temperature, Uuid } from "./common.ts";

// The event catalogue, mirrored from each module's *Events class. The backend
// EventCatalogueTest holds the Java side to the same list; a type added there
// is added here.

type PlannedStop = { sequence: number; orderId: Uuid; outletId: string; plannedArrival: IsoTime };
type PlannedTrip = {
  tripId: Uuid;
  vehicleId: string;
  tripNumber: 1 | 2;
  brandCode: string;
  districtName: string;
  temperature: Temperature;
  plannedDeparture: IsoTime;
  stops: PlannedStop[];
};

export type EventPayloads = {
  "order.placed": {
    orderId: Uuid;
    orderRef: string;
    outletId: string;
    depotCode: string;
    brandCode: string;
    districtName: string;
    deliveryDate: IsoDate;
    // Null while STOCK_UNKNOWN: the warehouse has not answered, and totals are never guessed.
    temperature: Temperature | null;
    weightKg: Decimal | null;
    volumeM3: Decimal | null;
    itemCount: number;
    status: OrderStatus;
  };
  "order.amended": {
    orderId: Uuid;
    outletId: string;
    depotCode: string;
    deliveryDate: IsoDate;
    temperature: Temperature | null;
    weightKg: Decimal | null;
    volumeM3: Decimal | null;
    itemCount: number;
  };
  "order.cancelled": {
    orderId: Uuid;
    outletId: string;
    depotCode: string;
    warehouseOrderRef: string | null;
    reason: string;
  };
  "order.auto_deferred": {
    orderId: Uuid;
    outletId: string;
    depotCode: string;
    fromDate: IsoDate;
    toDate: IsoDate;
    reason: "stock_unresolved";
  };
  "orders.closed": { depotCode: string; serviceDate: IsoDate; orderIds: Uuid[] };
  "plan.published": {
    planId: Uuid;
    depotCode: string;
    serviceDate: IsoDate;
    planVersion: number;
    supersedes: Uuid | null;
    trips: PlannedTrip[];
  };
  "plan.revised": {
    planId: Uuid;
    depotCode: string;
    serviceDate: IsoDate;
    planVersion: number;
    supersedes: Uuid;
    reason: string;
    trips: PlannedTrip[];
  };
  "order.deferred": {
    orderId: Uuid;
    planId: Uuid;
    outletId: string;
    serviceDate: IsoDate;
    ruleId: string;
    reason: string;
    skipCount: number;
  };
  "order.unservable": { orderId: Uuid; planId: Uuid; outletId: string; ruleId: string; reason: string };
  "loading.started": { tripId: Uuid; planId: Uuid; vehicleId: string };
  "loading.shortfall": {
    shortfallId: Uuid;
    tripId: Uuid;
    orderId: Uuid;
    depotCode: string;
    kind: CheckStatus;
    missingUnits: number;
    reason: string;
  };
  "loading.interchange_requested": {
    tripId: Uuid;
    planId: Uuid;
    currentVehicleId: string;
    replacementVehicleId: string;
    reason: string;
  };
  "trip.released": {
    tripId: Uuid;
    planId: Uuid;
    planVersion: number;
    vehicleId: string;
    depotCode: string;
    serviceDate: IsoDate;
    stops: PlannedStop[];
  };
  "delivery.started": { deliveryId: Uuid; orderId: Uuid; tripId: Uuid; outletId: string };
  "delivery.completed": {
    deliveryId: Uuid;
    orderId: Uuid;
    tripId: Uuid;
    outletId: string;
    outcome: Extract<DeliveryOutcome, "DELIVERED" | "PARTIAL">;
    deliveredUnits: number | null;
    completedAt: IsoInstant;
    lateMinutes: number | null;
  };
  "delivery.failed": {
    deliveryId: Uuid;
    orderId: Uuid;
    tripId: Uuid;
    outletId: string;
    depotCode: string;
    reason: string;
    failedAt: IsoInstant;
  };
  "eta.changed": {
    deliveryId: Uuid;
    orderId: Uuid;
    outletId: string;
    expectedArrival: IsoInstant;
    delayMinutes: number;
  };
  "vehicle.fault_reported": {
    vehicleId: string;
    depotCode: string;
    serviceDate: IsoDate;
    description: string;
    at: IsoInstant;
  };
  "road.disruption_reported": {
    vehicleId: string;
    depotCode: string;
    districtName: string | null;
    description: string;
    at: IsoInstant;
  };
  "receipt.confirmed": {
    receiptId: Uuid;
    orderId: Uuid;
    outletId: string;
    partial: boolean;
    confirmedAt: IsoInstant;
  };
  "receipt.disputed": {
    receiptId: Uuid;
    orderId: Uuid;
    outletId: string;
    depotCode: string;
    reason: string;
    at: IsoInstant;
  };
  "receipt.auto_closed": { receiptId: Uuid; orderId: Uuid; outletId: string; at: IsoInstant };
  "issue.raised": {
    issueId: Uuid;
    issueType: IssueType;
    severity: IssueSeverity;
    depotCode: string;
    outletId: string | null;
    subjects: SubjectRef[];
  };
  "issue.resolved": {
    issueId: Uuid;
    depotCode: string;
    outletId: string | null;
    action: string;
    at: IsoInstant;
  };
  "shortfall.resolved": { issueId: Uuid; tripId: Uuid; orderId: Uuid; resolution: string };
  "redelivery.requested": {
    issueId: Uuid;
    originalOrderId: Uuid;
    requestedDate: IsoDate;
    reason: string;
  };
  "warehouse.order_status_changed": {
    orderId: Uuid;
    warehouseOrderRef: string | null;
    status: "pending" | "shipped" | "delivered" | "cancelled" | "expired" | "insufficient";
    reservation: {
      warehouseOrderRef: string;
      weightKg: Decimal;
      volumeM3: Decimal;
      temperature: Temperature;
      itemCount: number;
    } | null;
  };
  "warehouse.discrepancy_found": {
    orderId: Uuid;
    depotCode: string;
    waypointStatus: string;
    warehouseStatus: string;
    detail: string;
  };
  "catalogue.synced": { catalogueVersion: string; productCount: number; syncedAt: IsoInstant };
  "reference.version_published": { versionId: Uuid; contentHash: string };
  "vehicle.status_changed": {
    vehicleId: string;
    serviceDate: IsoDate;
    status: "available" | "in_workshop" | "unavailable";
    reason: string | null;
  };
  "calendar.overridden": { date: IsoDate; operating: boolean; reason: string };
};

export type EventType = keyof EventPayloads;
