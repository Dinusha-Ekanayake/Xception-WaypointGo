import type { BrowserContext, Page, Route } from "@playwright/test";
import type { RunSheetStopView } from "../../src/shared/domain/execution.ts";
import type { NotificationView } from "../../src/shared/domain/notification.ts";
import type { ReceiptAnswerView } from "../../src/shared/domain/receipt.ts";
import type { PostMessagePayload } from "../../src/shared/domain/messaging.ts";
import { postToThread, threadRead, type ThreadMock } from "../thread-mocks.ts";

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
    deliveredUnits: null, proofCaptured: false, storeAnswerWaived: null,
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
  /** Reading the run sheet fails, as when the signal drops right after a command. */
  dropReads: boolean;
  /** Refuse every proof file, as the server does for one that is not an image. */
  refuseUploads: boolean;
  /** The driver's inbox, newest first. */
  notifications: NotificationView[];
  /** The store manager's handover PIN by order; absent until the store answers the receipt. */
  pins: Record<string, string>;
  /** Trip threads (issue #136). */
  threads: ThreadMock[];
  /** The store's answer by order (issue #21); absent until the store answers, which reads as a 404. */
  answers: Record<string, ReceiptAnswerView>;
  /** Voice notes uploaded, in arrival order. */
  voices: Array<{ path: string; contentType: string; bytes: number }>;
  goOffline: (context: BrowserContext) => Promise<void>;
  goOnline: (context: BrowserContext) => Promise<void>;
};

const problem = (status: number, code: string, detail: string) => ({
  status,
  contentType: "application/problem+json",
  body: JSON.stringify({ type: "about:blank", title: code, status, detail, code, correlationId: "test", violations: [] }),
});

/**
 * @param options.askLocation leave the location question unanswered, so the phone asks it; otherwise
 *     the driver answered "Not now" on an earlier day and the question's sheet never covers the run
 */
export async function serve(
  page: Page,
  stops: RunSheetStopView[] = [stop(1, "OUT0101"), stop(2, "OUT0202")],
  options: { askLocation?: boolean } = {},
): Promise<Server> {
  if (!options.askLocation) {
    await page.addInitScript(() => {
      try {
        if (window.localStorage.getItem("waypoint.driver.location") === null) window.localStorage.setItem("waypoint.driver.location", "no");
      } catch {
        // No storage: the question is asked, as on a real phone.
      }
    });
  }
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
    dropReads: false,
    notifications: [],
    pins: {},
    threads: [],
    answers: {},
    voices: [],
    goOffline: async (context) => {
      server.offline = true;
      await context.setOffline(true);
    },
    goOnline: async (context) => {
      server.offline = false;
      await context.setOffline(false);
    },
  };
  const audio = new Map<string, { contentType: string; body: Buffer }>();
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  const apply = (command: SentCommand): number | null => {
    server.commands.push(command);
    if (command.kind === "message:Post") {
      postToThread(server.threads, command.payload as unknown as PostMessagePayload, { name: SESSION.displayName, role: "driver" }, new Date().toISOString());
      return null;
    }
    const target = server.stops.find((s) => s.deliveryId === command.payload.deliveryId);
    if (!target || command.kind === "delivery:ReportFault") return null;
    const now = new Date().toISOString();
    if (command.kind === "delivery:Start") target.startedAt = now;
    if (command.kind === "delivery:RecordArrival") Object.assign(target, { outcome: "ARRIVED", arrivedAt: now, waitMinutes: 0, lateMinutes: 0 });
    if (command.kind === "delivery:Record") Object.assign(target, { outcome: command.payload.outcome, completedAt: now });
    if (command.kind === "delivery:CaptureProof") target.proofCaptured = true;
    if (command.kind === "delivery:LeaveWithoutStoreAnswer") target.storeAnswerWaived = command.payload.reason as RunSheetStopView["storeAnswerWaived"];
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
      if (server.dropReads) return route.abort("internetdisconnected");
      return route.fulfill(json(server.stops.length ? [{ vehicleId: "VEH043", serviceDate: today(), stops: server.stops }] : []));
    }
    if (pathname === "/api/reference/vehicles/VEH043") {
      return route.fulfill(json({ vehicleId: "VEH043", depotCode: "KDY", refrigerated: true, van: false }));
    }
    if (pathname.startsWith("/api/execution/trips/")) return route.fulfill(json({ items: [], nextCursor: null }));
    if (pathname.startsWith("/api/reference/outlets/")) {
      const outletId = pathname.split("/").pop()!;
      // Issue #161: OUT0101 has a supplied exact point; the rest share the district centroid.
      const location = outletId === "OUT0101"
        ? { latitude: "7.291000", longitude: "80.633000", precision: "exact" }
        : { latitude: "7.290000", longitude: "80.630000", precision: "district" };
      return route.fulfill(json({ outletId, districtName: "Kandy", brandCode: "Fresh", dockType: "street_level", parkingConstraint: "none", location }));
    }
    if (pathname === "/api/notifications/stream") {
      const count = server.notifications.filter((n) => n.readAt === null).length;
      return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: unread\ndata: {"count":${count}}\n\n` });
    }
    if (pathname === "/api/notifications/unread-count") {
      return route.fulfill(json({ count: server.notifications.filter((n) => n.readAt === null).length }));
    }
    if (pathname === "/api/notifications") return route.fulfill(json({ items: server.notifications, nextCursor: null }));
    if (pathname === "/api/commands" && method === "POST") {
      const command = request.postDataJSON() as SentCommand;
      if (command.kind === "receipt:VerifyHandover") {
        server.commands.push(command);
        const orderId = String(command.payload.orderId);
        const pin = server.pins[orderId];
        if (!pin) return route.fulfill(problem(403, "FORBIDDEN", `no handover PIN for order ${orderId} is within the actor's scope`));
        const right = command.payload.pin === pin;
        const known = server.answers[orderId];
        if (known && right) Object.assign(known.handover, { status: "CONFIRMED", confirmedAt: new Date().toISOString() });
        const result = { orderId, verified: right, outcome: right ? "VERIFIED" : "WRONG", attemptsLeft: right ? 0 : 4, rowVersion: 2 };
        return route.fulfill(json({ commandId: command.commandId, kind: command.kind, replayed: false, result }));
      }
      if (server.refuse === command.kind) {
        server.refuse = null;
        return route.fulfill(problem(409, "CONSTRAINT_VIOLATED", "Arrival is recorded once for a stop"));
      }
      // A write names the version it read, as on the server: a stale one is refused, never merged.
      const target = server.stops.find((s) => s.deliveryId === command.payload.deliveryId);
      if (target && command.expectedVersion !== null && command.expectedVersion !== target.rowVersion) {
        return route.fulfill(problem(409, "VERSION_CONFLICT", `delivery is at version ${target.rowVersion}, not ${command.expectedVersion}`));
      }
      const rowVersion = apply(command);
      // As ExecutionMessages.result: the stop's new version, so the phone carries it on.
      const result = target ? { deliveryId: target.deliveryId, rowVersion, outcome: target.outcome, timingUncertain: false } : { rowVersion };
      return route.fulfill(json({ commandId: command.commandId, kind: command.kind, replayed: false, result }));
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
    const answered = pathname.match(/^\/api\/receipts\/([^/]+)\/answer$/);
    if (answered) {
      const found = server.answers[decodeURIComponent(answered[1]!)];
      return route.fulfill(found ? json(found) : problem(404, "NOT_FOUND", "The store has not answered yet"));
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
    if (method === "PUT" && /^\/api\/threads\/[^/]+\/voice\/[^/]+$/.test(pathname)) {
      const body = request.postDataBuffer() ?? Buffer.alloc(0);
      const contentType = request.headers()["content-type"] ?? "";
      server.voices.push({ path: pathname, contentType, bytes: body.length });
      audio.set(pathname, { contentType, body });
      return route.fulfill(json({ voiceNoteId: pathname.split("/").pop(), alreadyStored: false }));
    }
    if (method === "GET" && audio.has(pathname)) {
      const kept = audio.get(pathname)!;
      return route.fulfill({ status: 200, contentType: kept.contentType, body: kept.body });
    }
    const thread = method === "GET" ? threadRead(server.threads, new URL(request.url())) : undefined;
    if (thread) return route.fulfill({ status: thread.status, contentType: "application/json", body: JSON.stringify(thread.body) });
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

/** Slide across "I've arrived". A tap does not record the arrival. */
export async function arrive(page: Page): Promise<void> {
  const slider = page.getByRole("slider", { name: "I've arrived" });
  // Wait until a touch would land on it: a screen change may still be crossfading over it.
  await slider.click({ trial: true });
  const box = await slider.boundingBox();
  if (!box) throw new Error("I've arrived is not on screen");
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + 36, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, y, { steps: 18 });
  await page.mouse.up();
}

/** Slide across "Hand over" at the stop: the goods are with the store (issue #21). */
export async function handOver(page: Page): Promise<void> {
  const slider = page.getByRole("slider", { name: "Hand over" });
  // Wait until a touch would land on it: a screen change may still be crossfading over it.
  await slider.click({ trial: true });
  const box = await slider.boundingBox();
  if (!box) throw new Error("Hand over is not on screen");
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + 36, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, y, { steps: 18 });
  await page.mouse.up();
}

/** Slide a reason across in "Why are you moving on?": a tap does not choose it. */
export async function slideReason(page: Page, label: string): Promise<void> {
  const row = page.getByRole("slider", { name: label });
  await row.click({ trial: true });
  const box = await row.boundingBox();
  if (!box) throw new Error(`${label} is not on screen`);
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + 32, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, y, { steps: 18 });
  await page.mouse.up();
}

/** The store's answer for a stop: every product received unless `short` units are missing from the first. */
export function storeAnswer(at: RunSheetStopView, short = 0): ReceiptAnswerView {
  return {
    orderId: at.orderId,
    status: short > 0 ? "PARTIAL" : "CONFIRMED",
    lines: [{ productId: "P-1", expectedQuantity: at.itemCount, receivedQuantity: at.itemCount - short }],
    note: short > 0 ? "One crate crushed" : null,
    answeredAt: new Date().toISOString(),
    handover: { orderId: at.orderId, status: "AWAITING", expiresAt: new Date(Date.now() + 3_600_000).toISOString(), attemptsLeft: 5, confirmedAt: null, rowVersion: 1 },
  };
}

/** Starts or continues the trip from Home. */
export async function startTrip(page: Page): Promise<void> {
  await page.getByRole("button", { name: /^(Start|Continue) (trip|run)$/ }).click();
}

/** At the stop: from the Figma report to the delivery form, where counts and proof are entered. */
export async function openForm(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Open delivery report" }).click();
  await page.getByRole("button", { name: "Record delivery" }).click();
}
