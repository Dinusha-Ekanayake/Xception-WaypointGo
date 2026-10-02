import type { ManifestView, ReadyTripView } from "../../src/shared/domain/loading.ts";

// Mock responses in the shape the Loading module serves (LoadingViews.java),
// so the mocked browser tests exercise the same contract as the live one.

export const OPERATOR = { userId: "loader-user", displayName: "Isuru", employeeCode: "LDR-00038", since: "2026-10-01T08:00:00Z" };

export const SESSION = {
  userId: "device-user",
  displayName: "Depot supervisor",
  roles: ["loader"],
  scope: ["depot:KDY"],
  operator: OPERATOR,
};

const HOLDER = { userId: OPERATOR.userId, name: OPERATOR.displayName, employeeCode: OPERATOR.employeeCode, since: OPERATOR.since, lastActiveAt: new Date().toISOString() };

/** One trip with one order of two items, held by the signed-in operator. */
export function manifest(tripId: string, loaded: boolean, rowVersion: number): ManifestView {
  const item = (lineNo: number, units: number) => ({
    lineNo,
    productId: `P-${lineNo}`,
    units,
    status: loaded ? ("LOADED" as const) : ("PENDING" as const),
    loadedUnits: loaded ? units : 0,
    attempt: loaded ? 1 : 0,
    checkedAt: loaded ? "2026-10-01T08:10:00Z" : null,
    checkedBy: loaded ? OPERATOR.userId : null,
  });
  return {
    tripId,
    planId: "plan-test",
    planVersion: 1,
    depotCode: "KDY",
    serviceDate: "2026-10-01",
    vehicleId: "VEH043",
    tripNumber: 1,
    tripsForVehicle: 1,
    brandCode: "Fresh",
    districtName: "Kandy",
    temperature: "chilled",
    plannedDeparture: "16:30:00",
    dockCode: "Dock 2",
    weightCapKg: "5510",
    volumeCapM3: "26.4",
    status: loaded ? "READY" : "IN_PROGRESS",
    holder: HOLDER,
    releasedAt: null,
    rowVersion,
    lines: [
      {
        loadSequence: 1,
        stopSequence: 1,
        orderId: "order-test",
        orderRef: "ORD0092336",
        outletId: "OUT001",
        districtName: "Peradeniya",
        windowOpen: "05:00:00",
        windowClose: "08:00:00",
        plannedArrival: "05:30:00",
        temperature: "chilled",
        itemCount: 3,
        weightKg: "20",
        volumeM3: "0.2",
        status: loaded ? "LOADED" : "PENDING",
        loadedUnits: loaded ? 3 : 0,
        attempt: loaded ? 1 : 0,
        items: [item(1, 2), item(2, 1)],
      },
    ],
  };
}

export function board(m: ManifestView): ReadyTripView[] {
  return [
    {
      tripId: m.tripId,
      vehicleId: m.vehicleId,
      tripNumber: m.tripNumber,
      tripsForVehicle: m.tripsForVehicle,
      plannedDeparture: m.plannedDeparture,
      status: m.status,
      brandCode: m.brandCode,
      districtName: m.districtName,
      temperature: m.temperature,
      dockCode: m.dockCode,
      stopCount: 1,
      orderCount: m.lines.length,
      weightKg: "20",
      volumeM3: "0.2",
      weightCapKg: m.weightCapKg,
      volumeCapM3: m.volumeCapM3,
      holder: m.holder,
      releasedAt: m.releasedAt,
      rowVersion: m.rowVersion,
    },
  ];
}
