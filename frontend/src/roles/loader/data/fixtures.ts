import type { Command, CommandAck } from "@shared/api/commands";
import { ApiError, parseProblem } from "@shared/api/problem";
import {
  LoadingCommandKind,
  type CheckStatus,
  type FlagShortfall,
  type ItemView,
  type ManifestLineView,
  type ManifestView,
  type OutletView,
  type ReadyTripView,
  type RecordCheck,
  type ReleaseTrip,
  type SessionStatus,
} from "@shared/domain/types";
import type { LoadingGateway } from "./gateway.ts";
import { isFlagged, orderStatusOf } from "./manifest.ts";

// Sample data for building the loader screens without the backend. It applies
// the same rules the Loading module does, so a screen that works here fails the
// same way there: a stale version is a conflict, one loader holds a trip, a
// flagged item is not loaded and does not block, and release is refused while an
// item is unchecked (R-LOD-07) or the checklist is incomplete (R-LOD-10).

type Phase = "not_started" | "in_progress" | "released";
type Trip = { phase: Phase; manifest: ManifestView };

const PLACES = ["Peradeniya", "Pilimathalawa", "Kadugannawa", "Katugastota", "Akurana", "Gampola", "Matale"];
const ME = { userId: "sample-loader", name: "Sample Loader", employeeCode: "LDR-00038" };

function outlet(index: number): OutletView {
  return {
    outletId: `OUT-${String(101 + index).padStart(4, "0")}`,
    brandCode: ["FRESH", "STYLE", "TECH"][index % 3]!,
    districtName: PLACES[index % PLACES.length]!,
    depotCode: "KDY",
    dockType: "street",
    parkingConstraint: "none",
    windowOpen: "06:00:00",
    windowClose: "18:00:00",
    effectiveWindowOpen: null,
    effectiveWindowClose: null,
    vanOnly: false,
  };
}

const OUTLETS = PLACES.map((_, i) => outlet(i));

function items(order: number, seed: number, loaded: boolean): ItemView[] {
  const count = 2 + ((seed + order) % 4);
  return Array.from({ length: count }, (_, i) => {
    const units = 1 + ((seed + order + i) % 3);
    return {
      lineNo: i + 1,
      productId: `P-${100 + ((seed * 7 + order * 3 + i) % 280)}`,
      units,
      status: loaded ? "LOADED" : "PENDING",
      loadedUnits: loaded ? units : 0,
      attempt: loaded ? 1 : 0,
      checkedAt: loaded ? new Date(Date.now() - 20 * 60_000).toISOString() : null,
      checkedBy: loaded ? ME.userId : null,
    };
  });
}

function lines(stops: number, temperature: "chilled" | "ambient", seed: number, loaded: number): ManifestLineView[] {
  const out: ManifestLineView[] = [];
  let order = 0;
  for (let stop = 1; stop <= stops; stop++) {
    for (let k = 0; k < ((stop + seed) % 3 === 0 ? 2 : 1); k++) {
      order++;
      const place = OUTLETS[(stop + seed) % OUTLETS.length]!;
      const its = items(order, seed, false);
      out.push({
        loadSequence: 0,
        stopSequence: stop,
        orderId: `ord-${seed}-${order}`,
        orderRef: `ORD00923${String(seed * 10 + order).padStart(2, "0")}`,
        outletId: place.outletId,
        districtName: place.districtName,
        windowOpen: "05:00:00",
        windowClose: "08:00:00",
        plannedArrival: null,
        temperature,
        itemCount: its.reduce((n, i) => n + i.units, 0),
        weightKg: String(120 + ((order * 37 + seed) % 180)),
        volumeM3: (0.8 + ((order * 13 + seed) % 12) / 10).toFixed(2),
        status: "PENDING",
        loadedUnits: 0,
        attempt: 0,
        items: its,
      });
    }
  }
  // Last stop loads first (D-L).
  out.sort((a, b) => b.stopSequence - a.stopSequence);
  out.forEach((line, i) => {
    line.loadSequence = i + 1;
    if (i < loaded) {
      const at = new Date(Date.now() - 20 * 60_000).toISOString();
      line.items = line.items.map((it) => ({ ...it, status: "LOADED", loadedUnits: it.units, attempt: 1, checkedAt: at, checkedBy: ME.userId }));
    }
  });
  return out.map(refresh);
}

/** An order's own fields from its items, as the server derives them. */
function refresh(line: ManifestLineView): ManifestLineView {
  return {
    ...line,
    status: orderStatusOf(line.items),
    loadedUnits: line.items.reduce((n, i) => n + i.loadedUnits, 0),
    attempt: Math.max(0, ...line.items.map((i) => i.attempt)),
  };
}

function statusOf(t: Trip): SessionStatus {
  if (t.phase === "released") return "COMPLETED";
  if (t.phase === "not_started") return "NOT_STARTED";
  const all = t.manifest.lines.flatMap((l) => l.items);
  if (!all.some((i) => i.status === "PENDING")) return "READY";
  return all.some((i) => isFlagged(i.status)) ? "BLOCKED" : "IN_PROGRESS";
}

function trip(n: number, vehicle: string, departs: string, phase: Phase, stops: number, temp: "chilled" | "ambient", loaded = 0): Trip {
  const tripId = `trip-${n}`;
  return {
    phase,
    manifest: {
      tripId,
      planId: "plan-sample",
      planVersion: 3,
      depotCode: "KDY",
      serviceDate: new Date().toISOString().slice(0, 10),
      vehicleId: vehicle,
      tripNumber: 1,
      tripsForVehicle: 1,
      brandCode: "Fresh",
      districtName: "Kandy",
      temperature: temp,
      plannedDeparture: departs,
      dockCode: `Dock ${(n % 4) + 1}`,
      weightCapKg: temp === "chilled" ? "5510" : "7200",
      volumeCapM3: temp === "chilled" ? "26.4" : "32.0",
      status: "NOT_STARTED",
      holder: phase === "in_progress" ? { ...ME, since: new Date(Date.now() - 35 * 60_000).toISOString() } : null,
      releasedAt: phase === "released" ? new Date().toISOString() : null,
      lines: lines(stops, temp, n, phase === "released" ? 99 : loaded),
      rowVersion: 1,
    },
  };
}

function board(t: Trip): ReadyTripView {
  const m = t.manifest;
  const sum = (key: "weightKg" | "volumeM3") => m.lines.reduce((n, l) => n + Number(l[key]), 0).toFixed(2);
  return {
    tripId: m.tripId,
    vehicleId: m.vehicleId,
    tripNumber: m.tripNumber,
    tripsForVehicle: m.tripsForVehicle,
    plannedDeparture: m.plannedDeparture,
    status: statusOf(t),
    brandCode: m.brandCode,
    districtName: m.districtName,
    temperature: m.temperature,
    dockCode: m.dockCode,
    stopCount: new Set(m.lines.map((l) => l.stopSequence)).size,
    orderCount: m.lines.length,
    weightKg: sum("weightKg"),
    volumeM3: sum("volumeM3"),
    holder: m.holder,
    releasedAt: m.releasedAt,
    rowVersion: m.rowVersion,
  };
}

function problem(status: number, title: string, detail: string, violations: string[] = []): ApiError {
  // Parsed like a real body, so a sample failure has the same shape as a live one.
  return new ApiError(parseProblem(status, { code: title, title, status, detail, violations }));
}

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const pause = () => new Promise((resolve) => setTimeout(resolve, 120));

export function sampleGateway(): LoadingGateway {
  const trips = [
    trip(1, "VEH043", "04:30:00", "in_progress", 4, "chilled", 3),
    trip(2, "VEH039", "03:50:00", "not_started", 3, "chilled"),
    trip(3, "VEH040", "04:00:00", "released", 2, "chilled"),
    trip(4, "VEH044", "04:10:00", "not_started", 5, "ambient"),
    trip(5, "VEH045", "04:15:00", "not_started", 4, "ambient"),
  ];

  const find = (tripId: string): Trip => {
    const found = trips.find((t) => t.manifest.tripId === tripId);
    if (!found) throw problem(404, "NOT_FOUND", "No such trip for this depot and day.");
    return found;
  };

  const change = (m: ManifestView, orderId: string, lineNo: number | null, next: (i: ItemView) => ItemView | null) => {
    m.lines = m.lines.map((l) => {
      if (l.orderId !== orderId) return l;
      return refresh({ ...l, items: l.items.map((i) => (lineNo === null || i.lineNo === lineNo ? (next(i) ?? i) : i)) });
    });
  };

  const apply = (command: Command): unknown => {
    const t = find((command.payload as { tripId: string }).tripId);
    const m = t.manifest;
    if (command.expectedVersion !== null && command.expectedVersion !== m.rowVersion) {
      throw problem(409, "VERSION_CONFLICT", "The load sheet changed on another device. Reload it and check again.");
    }
    if (t.phase === "released") throw problem(409, "CONFLICT", "This trip has already been released.");
    const now = new Date().toISOString();
    const attempt = (i: ItemView, status: CheckStatus, loadedUnits: number): ItemView =>
      ({ ...i, status, loadedUnits, attempt: i.attempt + 1, checkedAt: now, checkedBy: ME.userId });
    switch (command.kind) {
      case LoadingCommandKind.start:
        t.phase = "in_progress";
        m.holder = m.holder ?? { ...ME, since: now };
        break;
      case LoadingCommandKind.check: {
        const p = command.payload as RecordCheck;
        change(m, p.orderId, p.lineNo, (i) =>
          p.status === "LOADED"
            ? i.status === "LOADED" || (p.lineNo === null && isFlagged(i.status)) ? null : attempt(i, "LOADED", i.units)
            : i.status === "LOADED" ? attempt(i, "PENDING", 0) : null,
        );
        break;
      }
      case LoadingCommandKind.shortfall: {
        const p = command.payload as FlagShortfall;
        change(m, p.orderId, p.lineNo, (i) =>
          p.lineNo === null ? (isFlagged(i.status) ? null : attempt(i, p.kind, 0)) : attempt(i, p.kind, Math.max(0, i.units - p.missingUnits)),
        );
        break;
      }
      case LoadingCommandKind.release: {
        const checklist = command.payload as ReleaseTrip;
        const left = m.lines.flatMap((l) => l.items).filter((i) => i.status === "PENDING").length;
        if (left > 0) {
          throw problem(409, "CONFLICT", `Can't release yet: ${left} ${left === 1 ? "item" : "items"} still to load, or report what's missing.`, ["R-LOD-07"]);
        }
        if (!checklist.doorsSealed || !checklist.ordersSecured || !checklist.driverPresent) {
          throw problem(422, "VALIDATION_FAILED", "Confirm doors sealed, orders secured, and driver present.", ["R-LOD-10"]);
        }
        t.phase = "released";
        m.holder = null;
        m.releasedAt = now;
        break;
      }
      case LoadingCommandKind.handBack:
        m.holder = null;
        break;
      default:
        throw problem(400, "UNKNOWN_COMMAND", `The sample data does not handle ${command.kind}.`);
    }
    m.rowVersion++;
    m.status = statusOf(t);
    return { tripId: m.tripId, rowVersion: m.rowVersion, status: m.status };
  };

  return {
    sample: true,
    readyTrips: async () => {
      await pause();
      return trips.map((t) => clone(board(t)));
    },
    manifest: async (tripId) => {
      await pause();
      const t = find(tripId);
      return clone({ ...t.manifest, status: statusOf(t) });
    },
    outlets: async () => clone(OUTLETS),
    send: async (command): Promise<CommandAck> => {
      await pause();
      return { commandId: command.commandId, kind: command.kind, replayed: false, result: apply(command) };
    },
    queue: async () => ({ durable: false, reason: "Sample data cannot save offline changes." }),
    revisePlan: (tripId) => {
      const m = find(tripId).manifest;
      m.planVersion++;
      m.rowVersion++;
      // The revision moved two loaded orders; their earlier ticks stop counting.
      const moved = new Set(m.lines.filter((l) => l.status !== "PENDING").slice(0, 2).map((l) => l.orderId));
      m.lines = m.lines.map((l) =>
        moved.has(l.orderId)
          ? refresh({ ...l, items: l.items.map((i) => ({ ...i, status: "PENDING", loadedUnits: 0, attempt: i.attempt + 1, checkedAt: null, checkedBy: null })) })
          : l,
      );
    },
  };
}
