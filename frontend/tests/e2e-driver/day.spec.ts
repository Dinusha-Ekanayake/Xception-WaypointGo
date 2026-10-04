import { expect, test } from "@playwright/test";
import { arrive, handOver, serve, slideReason, startTrip, stop, storeAnswer } from "./mocks.ts";

test("a stop worked with no signal survives a reload and is sent once, in order, when the signal returns", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  // The shell must be on the phone before the signal goes, or a reload has nothing to show.
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));

  await server.goOffline(context);
  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await arrive(page);
  await handOver(page);
  // Issue #21: with no signal the store's report cannot be read, and the screen says so.
  await expect(page.getByText("Waiting for store confirmation")).toBeVisible();
  await expect(page.getByText(/cannot reach Waypoint/)).toBeVisible();
  await page.getByRole("button", { name: "Continue to next stop" }).click();
  await slideReason(page, "No signal to see the store's report");
  await expect(page.getByRole("heading", { name: "OUT0202" })).toBeVisible();
  expect(server.commands).toHaveLength(0);

  // The phone restarts with still no signal: the day, and the work, are on it.
  await page.reload();
  await expect(page.getByText("Showing the run saved on this phone")).toBeVisible();
  await expect(page.getByRole("button", { name: "5 saved on this device" })).toBeVisible();
  await startTrip(page);
  await expect(page.getByText("Offline", { exact: true })).toBeVisible();

  await server.goOnline(context);
  // Stop 01's work goes first, in order, then the start of stop 02.
  await expect.poll(() => server.commands.length).toBe(5);
  expect(server.commands.map((c) => [c.kind, c.expectedVersion, c.payload.deliveryId])).toEqual([
    ["delivery:Start", 1, server.stops[0]!.deliveryId],
    ["delivery:RecordArrival", 2, server.stops[0]!.deliveryId],
    ["delivery:Record", 3, server.stops[0]!.deliveryId],
    ["delivery:LeaveWithoutStoreAnswer", 4, server.stops[0]!.deliveryId],
    ["delivery:Start", 1, server.stops[1]!.deliveryId],
  ]);
  expect(server.commands[2]!.payload).toMatchObject({ outcome: "DELIVERED", deliveredUnits: null });
  expect(server.commands[3]!.payload).toMatchObject({ reason: "no_signal" });

  await expect(page.getByText(/^Synced \d\d:\d\d$/)).toBeVisible();
  // Sent once: a second pass finds nothing to send.
  expect(server.commands).toHaveLength(5);
});

test("with a signal, the store's report reaches the driver, who accepts it with the PIN, and the run completes", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  const order = server.stops[0]!.orderId;
  server.pins[order] = "4821";
  // The store has already counted: the phone's first look finds the answer.
  server.answers[order] = storeAnswer(server.stops[0]!, 1);
  await page.goto("/");
  await startTrip(page);
  await arrive(page);
  await handOver(page);

  await expect(page.getByText("Part received")).toBeVisible();
  await expect(page.getByText("11 of 12 units")).toBeVisible();
  await expect(page.getByText("One crate crushed")).toBeVisible();
  await page.getByRole("button", { name: "Enter PIN to accept" }).click();
  const pin = page.getByRole("dialog", { name: "Store manager PIN" });
  await pin.getByLabel("Store manager PIN").fill("4821");
  await pin.getByRole("button", { name: "Confirm" }).click();
  await expect(pin).toContainText("Handover confirmed");
  expect(server.batches).toBe(0);
  expect(server.commands.map((c) => c.kind)).toEqual(["delivery:Start", "delivery:RecordArrival", "delivery:Record", "receipt:VerifyHandover"]);
  expect(server.commands[2]!.payload).toMatchObject({ outcome: "DELIVERED", deliveredUnits: null });

  // A correct PIN moves the run on by itself, behind the confirmation: no Next stop to tap.
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();
  await expect(pin).toHaveCount(0);
  await expect(page.getByText("1 of 1")).toBeVisible();
});

test("scrolling the voices folds the driver card so the list has the room", async ({ page }) => {
  const server = await serve(page);
  server.notifications = Array.from({ length: 8 }, (_, i) => ({
    notificationId: `00000000-0000-7000-8000-000000000c0${i}`,
    eventType: "message.posted",
    title: `Voice ${i + 1}`,
    body: "Voice message about today's trip",
    subjectType: "trip",
    subjectId: "00000000-0000-7000-8000-0000000000aa",
    createdAt: new Date().toISOString(),
    readAt: null,
  }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Fuel QR" })).toBeVisible();
  const feed = page.locator("[aria-label='Notifications']");
  // The vehicle tile, Fuel QR and the big button fold away; the driver's name stays.
  const details = () => page.evaluate(() => {
    const fuel = [...document.querySelectorAll("button")].find((el) => el.textContent?.includes("Fuel QR"));
    let wrap: HTMLElement | null = fuel?.parentElement ?? null;
    while (wrap && !wrap.className.includes("max-h-")) wrap = wrap.parentElement;
    return wrap?.clientHeight ?? -1;
  });
  expect(await details()).toBeGreaterThan(0);
  await feed.evaluate((el) => {
    el.style.scrollBehavior = "auto";
    el.scrollTop = 240;
  });
  await expect.poll(details).toBe(0);
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await feed.evaluate((el) => {
    el.scrollTop = 0;
  });
  await expect.poll(details).toBeGreaterThan(0);
});

test("a driver who disagrees with the store moves on, and dispatch gets an issue about the order", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101", { outcome: "ARRIVED", arrivedAt: new Date().toISOString(), startedAt: new Date().toISOString(), waitMinutes: 0, lateMinutes: 0, rowVersion: 3 })]);
  server.answers[server.stops[0]!.orderId] = storeAnswer(server.stops[0]!, 4);
  await page.goto("/");
  await startTrip(page);
  await handOver(page);
  await expect(page.getByText("8 of 12 units")).toBeVisible();

  await page.getByRole("button", { name: "I disagree" }).click();
  const sheet = page.getByRole("dialog", { name: "Disagree with the store" });
  await sheet.getByLabel("What is wrong? (optional)").fill("All twelve were counted off the van");
  await sheet.getByRole("button", { name: "Raise issue and move on" }).click();
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();

  const stopId = server.stops[0]!.deliveryId;
  expect(server.commands.map((c) => c.kind)).toEqual(["delivery:Record", "delivery:LeaveWithoutStoreAnswer", "issue:Raise"]);
  expect(server.commands[1]!.payload).toEqual({ deliveryId: stopId, reason: "disagree" });
  expect(server.commands[2]!.payload).toMatchObject({
    type: "OTHER",
    outletId: "OUT0101",
    subjects: [{ type: "order", id: server.stops[0]!.orderId }, { type: "delivery", id: stopId }],
  });
  expect(String(server.commands[2]!.payload.description)).toContain("All twelve were counted off the van");
});
