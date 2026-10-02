import type { BrowserContext, Page, Route } from "@playwright/test";
import type { RunSheetStopView } from "../../src/shared/domain/execution.ts";

// A small stand-in for Execution, in the shapes ExecutionViews.java serves and
// with the server's own bookkeeping: one version per command, outcome by
// command. The tests assert what the phone sends; this only answers.

export const SESSION = { userId: "driver-user", displayName: "Nimal Perera", roles: ["driver"], scope: ["depot:KDY"] };

export type SentCommand = {
  commandId: string;
  kind: string;
  expectedVersion: number | null;
  clientRecordedAt: string;
  payload: Record<string, unknown>;
};

/** Today at the depots, as the phone asks for it. */
export function today(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

function stop(sequence: number, outletId: string, over: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `00000000-0000-7000-8000-00000000000${sequence}`,
    tripId: "00000000-0000-7000-8000-0000000000aa",
    sequence,
    orderId: `00000000-0000-7000-8000-0000000000b${sequence}`,
    outletId,
    itemCount: 12,
    mallOutlet: false,
    plannedArrival: "09:30",
    // Open all day, so no test depends on the hour it runs at.
    windowOpen: "00:00",
    windowClose: "23:59",
    expectedArrival: null,
    startedAt: null,
    arrivedAt: null,
    completedAt: null,
    waitMinutes: null,
    lateMinutes: null,
    outcome: "PENDING",
    proofCaptured: false,
    rowVersion: 1,
    lines: [],
    ...over,
  };
}

export type Server = {
  stops: RunSheetStopView[];
  /** Commands the server took, sent at once or in a sync batch, in arrival order. */
  commands: SentCommand[];
  batches: number;
  uploads: Array<{ path: string; contentType: string; bytes: number }>;
  calls: string[];
  /** No signal: every request fails as the network would. */
  offline: boolean;
  /** The session has ended: every request is answered 401. */
  expired: boolean;
  /** Refuse the next command of this kind with a rule violation. */
  refuse: string | null;
  /** Refuse every proof file, as the server does for one that is not an image. */
  refuseUploads: boolean;
  goOffline: (context: BrowserContext) => Promise<void>;
  goOnline: (context: BrowserContext) => Promise<void>;
};

const problem = (status: number, code: string, detail: string) => ({
  status,
  contentType: "application/problem+json",
  body: JSON.stringify({ type: "about:blank", title: code, status, detail, code, correlationId: "test", violations: [] }),
});

export async function serve(page: Page, stops: RunSheetStopView[] = [stop(1, "OUT0101"), stop(2, "OUT0202")]): Promise<Server> {
  const server: Server = {
    stops,
    commands: [],
    batches: 0,
    uploads: [],
    calls: [],
    offline: false,
    expired: false,
    refuse: null,
    refuseUploads: false,
    goOffline: async (context) => {
      server.offline = true;
      await context.setOffline(true);
    },
    goOnline: async (context) => {
      server.offline = false;
      await context.setOffline(false);
    },
  };
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  const apply = (command: SentCommand): number | null => {
    server.commands.push(command);
    const target = server.stops.find((s) => s.deliveryId === command.payload.deliveryId);
    if (!target || command.kind === "delivery:ReportFault") return null;
    const now = new Date().toISOString();
    if (command.kind === "delivery:Start") target.startedAt = now;
    if (command.kind === "delivery:RecordArrival") Object.assign(target, { outcome: "ARRIVED", arrivedAt: now, waitMinutes: 0, lateMinutes: 0 });
    if (command.kind === "delivery:Record") Object.assign(target, { outcome: command.payload.outcome, completedAt: now });
    if (command.kind === "delivery:CaptureProof") target.proofCaptured = true;
    target.rowVersion += 1;
    return target.rowVersion;
  };

  await page.route("**/api/**", async (route: Route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    if (server.offline) return route.abort("internetdisconnected");
    server.calls.push(`${method} ${pathname}`);
    if (server.expired) return route.fulfill(problem(401, "UNAUTHENTICATED", "Sign in to continue"));

    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/execution/vehicles") return route.fulfill(json(["VEH043"]));
    if (pathname === "/api/execution/run-sheets") {
      return route.fulfill(json(server.stops.length ? [{ vehicleId: "VEH043", serviceDate: today(), stops: server.stops }] : []));
    }
    if (pathname === "/api/reference/vehicles/VEH043") {
      return route.fulfill(json({ vehicleId: "VEH043", depotCode: "KDY", refrigerated: true, van: false }));
    }
    if (pathname.startsWith("/api/reference/outlets/")) {
      const outletId = pathname.split("/").pop()!;
      return route.fulfill(json({ outletId, districtName: "Kandy", brandCode: "Fresh", dockType: "street_level", parkingConstraint: "none" }));
    }
    if (pathname === "/api/commands" && method === "POST") {
      const command = request.postDataJSON() as SentCommand;
      if (server.refuse === command.kind) {
        server.refuse = null;
        return route.fulfill(problem(409, "CONSTRAINT_VIOLATED", "Arrival is recorded once for a stop"));
      }
      const rowVersion = apply(command);
      return route.fulfill(json({ commandId: command.commandId, kind: command.kind, replayed: false, result: { rowVersion } }));
    }
    if (pathname === "/api/sync" && method === "POST") {
      const batch = request.postDataJSON() as { operations: Array<{ sequence: number; command: SentCommand }> };
      server.batches += 1;
      return route.fulfill(
        json({
          results: batch.operations.map((operation) => {
            const target = server.stops.find((s) => s.deliveryId === operation.command.payload.deliveryId);
            if (target && (operation.command.expectedVersion !== target.rowVersion || target.outcome === "SKIPPED")) {
              return {
                operationId: operation.command.commandId,
                sequence: operation.sequence,
                status: "CONFLICT",
                problemCode: "VERSION_CONFLICT",
                detail: "This stop changed on another device or in the plan. Reload the run sheet.",
                replayed: false,
              };
            }
            apply(operation.command);
            return { operationId: operation.command.commandId, sequence: operation.sequence, status: "APPLIED", problemCode: null, detail: null, replayed: false };
          }),
        }),
      );
    }
    if (method === "PUT" && /^\/api\/execution\/deliveries\/[^/]+\/attachments\/[^/]+$/.test(pathname)) {
      if (server.refuseUploads) return route.fulfill(problem(415, "VALIDATION_FAILED", "The file is not a JPEG, PNG or WebP image"));
      server.uploads.push({
        path: pathname + new URL(request.url()).search,
        contentType: request.headers()["content-type"] ?? "",
        bytes: request.postDataBuffer()?.length ?? 0,
      });
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ attachmentId: pathname.split("/").pop(), stored: true }) });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return server;
}

export { stop };

/** Signs on the pad with a finger-sized stroke. */
export async function sign(page: Page): Promise<void> {
  const pad = page.getByRole("img", { name: "Signature of the person receiving the goods" });
  await pad.scrollIntoViewIfNeeded();
  const box = (await pad.boundingBox())!;
  await page.mouse.move(box.x + 30, box.y + 40);
  await page.mouse.down();
  await page.mouse.move(box.x + 120, box.y + 90, { steps: 6 });
  await page.mouse.move(box.x + 220, box.y + 50, { steps: 6 });
  await page.mouse.up();
}
