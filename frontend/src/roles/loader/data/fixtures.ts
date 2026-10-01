import type { Command, CommandAck } from "@shared/api/commands";
import { ApiError, parseProblem } from "@shared/api/problem";
import {
  LoadingCommandKind,
  type CheckStatus,
  type FlagShortfall,
  type ManifestLineView,
  type ManifestView,
  type OutletView,
  type ReadyTripView,
  type RecordCheck,
  type SessionStatus,
} from "@shared/domain/types";
import type { LoadingGateway } from "./gateway.ts";

// Sample data for building the loader screens before the Loading module (#10)
// serves them. It enforces the same rules the server will, so a screen that
// works here fails the same way there: a stale version is a conflict, and a
// release with an unchecked order is refused with R-LOD-07.

type Trip = ReadyTripView & { manifest: ManifestView };

const PLACES = ["Peradeniya", "Pilimathalawa", "Kadugannawa", "Katugastota", "Akurana", "Gampola", "Matale"];

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

function lines(stops: number, temperature: "chilled" | "ambient", seed: number, loaded: number): ManifestLineView[] {
  const out: ManifestLineView[] = [];
  let order = 0;
  for (let stop = 1; stop <= stops; stop++) {
    const orders = (stop + seed) % 3 === 0 ? 2 : 1;
    for (let k = 0; k < orders; k++) {
      order++;
      const items = 4 + ((seed + order * 3) % 7);
      out.push({
        loadSequence: 0,
        stopSequence: stop,
        orderId: `ord-${seed}-${order}`,
        outletId: OUTLETS[(stop + seed) % OUTLETS.length]!.outletId,
        temperature: k === 1 && temperature === "chilled" ? "ambient" : temperature,
        itemCount: items,
        weightKg: String(120 + ((order * 37 + seed) % 180)),
        volumeM3: (0.8 + ((order * 13 + seed) % 12) / 10).toFixed(2),
        status: "PENDING",
        loadedUnits: 0,
        attempt: 1,
      });
    }
  }
  // Last stop loads first (D-L).
  out.sort((a, b) => b.stopSequence - a.stopSequence);
  out.forEach((line, i) => {
    line.loadSequence = i + 1;
    if (i < loaded) Object.assign(line, { status: "LOADED", loadedUnits: line.itemCount });
  });
  return out;
}

function trip(n: number, vehicleId: string, departs: string, status: SessionStatus, stops: number, temp: "chilled" | "ambient", loaded = 0): Trip {
  const tripId = `trip-${n}`;
  return {
    tripId,
    vehicleId,
    tripNumber: 1,
    plannedDeparture: departs,
    status,
    manifest: {
      tripId,
      planId: "plan-sample",
      planVersion: 3,
      vehicleId,
      tripNumber: 1,
      status,
      lines: lines(stops, temp, n, status === "COMPLETED" ? 99 : loaded),
      rowVersion: 1,
    },
  };
}

function seed(): Trip[] {
  return [
    trip(1, "VEH043", "04:30:00", "IN_PROGRESS", 4, "chilled", 3),
    trip(2, "VEH039", "03:50:00", "BLOCKED", 3, "chilled", 1),
    trip(3, "VEH040", "04:00:00", "COMPLETED", 2, "chilled"),
    trip(4, "VEH044", "04:10:00", "NOT_STARTED", 5, "ambient"),
    trip(5, "VEH045", "04:15:00", "NOT_STARTED", 4, "ambient"),
    trip(6, "VEH047", "07:00:00", "NOT_STARTED", 3, "ambient"),
  ];
}

function problem(status: number, title: string, detail: string, violations: string[] = []): ApiError {
  // Parsed like a real body, so a sample failure has the same shape as a live one.
  return new ApiError(parseProblem(status, { code: title, title, status, detail, violations }));
}

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const pause = () => new Promise((resolve) => setTimeout(resolve, 120));

export function sampleGateway(): LoadingGateway {
  const trips = seed();
  let held: Command[] = [];

  const find = (tripId: string): Trip => {
    const found = trips.find((t) => t.tripId === tripId);
    if (!found) throw problem(404, "NOT_FOUND", "No such trip for this depot and day.");
    return found;
  };

  const setStatus = (t: Trip, status: SessionStatus) => {
    t.status = status;
    t.manifest.status = status;
  };

  const apply = (command: Command): unknown => {
    const payload = command.payload as { tripId: string };
    const t = find(payload.tripId);
    const m = t.manifest;
    if (command.expectedVersion !== null && command.expectedVersion !== m.rowVersion) {
      throw problem(409, "VERSION_CONFLICT", "The load sheet changed on another device. Reload it and check again.");
    }
    const line = (orderId: string) => m.lines.find((l) => l.orderId === orderId)!;
    switch (command.kind) {
      case LoadingCommandKind.start:
        setStatus(t, "IN_PROGRESS");
        break;
      case LoadingCommandKind.check: {
        const p = command.payload as RecordCheck;
        Object.assign(line(p.orderId), { status: p.status, loadedUnits: p.loadedUnits });
        break;
      }
      case LoadingCommandKind.shortfall: {
        const p = command.payload as FlagShortfall;
        const l = line(p.orderId);
        Object.assign(l, { status: p.kind satisfies CheckStatus, loadedUnits: Math.max(0, l.itemCount - p.missingUnits) });
        break;
      }
      case LoadingCommandKind.release: {
        const left = m.lines.filter((l) => l.status === "PENDING").length;
        if (left > 0) {
          throw problem(422, "RELEASE_BLOCKED", `${left} ${left === 1 ? "order is" : "orders are"} still to load.`, ["R-LOD-07"]);
        }
        setStatus(t, "COMPLETED");
        break;
      }
      case LoadingCommandKind.handover:
        setStatus(t, "NOT_STARTED");
        break;
      default:
        throw problem(400, "UNKNOWN_COMMAND", `The sample data does not handle ${command.kind}.`);
    }
    m.rowVersion++;
    return { rowVersion: m.rowVersion };
  };

  return {
    sample: true,
    readyTrips: async () => {
      await pause();
      return trips.map(({ manifest: _, ...view }) => clone(view));
    },
    manifest: async (tripId) => {
      await pause();
      return clone(find(tripId).manifest);
    },
    outlets: async () => clone(OUTLETS),
    send: async (command): Promise<CommandAck> => {
      await pause();
      return { commandId: command.commandId, kind: command.kind, replayed: false, result: apply(command) };
    },
    queue: async (command) => {
      held = [...held, command];
      return { durable: true };
    },
    flush: async () => {
      let sent = 0;
      let heldForReview = 0;
      for (const command of held) {
        try {
          apply(command);
          sent++;
        } catch {
          heldForReview++;
        }
      }
      held = [];
      return { sent, remaining: 0, heldForReview };
    },
    revisePlan: (tripId) => {
      const m = find(tripId).manifest;
      m.planVersion++;
      m.rowVersion++;
      // The revision moved two loaded orders; their earlier checks stop counting.
      m.lines
        .filter((l) => l.status !== "PENDING")
        .slice(0, 2)
        .forEach((l) => Object.assign(l, { status: "PENDING", loadedUnits: 0, attempt: l.attempt + 1 }));
    },
  };
}
