import { expect, test, type Page } from "@playwright/test";
import { serve, startTrip, stop, takeVehicle } from "./mocks.ts";

// Issue #114: the Figma driver screens run on the live run sheet. The phone
// takes its vehicle, works each stop and keeps every write on the device until
// it is sent; the server applies each once, in the order the driver made them.

const HANDOVER = "Handed over in person and confirmed on the driver's phone; no photo or signature taken.";


/** Arrive, then confirm the report and the store's PIN step. */
async function deliver(page: Page) {
  await page.getByRole("button", { name: "I’ve arrived" }).click();
  await expect(page.getByRole("region", { name: "Units handed over" })).toBeVisible();
  await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
  await expect(page.getByText("Enter Store Manager PIN")).toBeVisible();
  await page.getByRole("button", { name: "Confirm", exact: true }).last().click();
}

test("a stop worked with no signal survives a reload and is sent once, in order, when the signal returns", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await takeVehicle(page);
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  // The shell must be on the phone before the signal goes, or a reload has nothing to show.
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));

  await server.goOffline(context);
  await startTrip(page);
  await expect(page.getByText("Next • stop 01 of 02")).toBeVisible();
  await deliver(page);
  await expect(page.getByText("Next • stop 02 of 02")).toBeVisible();
  await page.waitForTimeout(1500); console.log("STATUS:", JSON.stringify(await page.getByRole("status").allTextContents()));
  await expect(page.getByRole("status").filter({ hasText: "Offline · 5 records saved on this phone" })).toBeVisible();
  expect(server.commands).toHaveLength(0);

  // The phone restarts with still no signal: the run, and the work, are on it.
  await page.reload();
  await expect(page.getByRole("status").filter({ hasText: "Offline · 5 records saved on this phone" })).toBeVisible();

  await server.goOnline(context);
  // Stop 01 in full, then "heading to stop 02", each on the version the one before it left.
  await expect.poll(() => server.commands.length).toBe(5);
  expect(server.commands.map((c) => [c.kind, c.expectedVersion])).toEqual([
    ["delivery:Start", 1],
    ["delivery:RecordArrival", 2],
    ["delivery:Record", 3],
    ["delivery:CaptureProof", 4],
    ["delivery:Start", 1],
  ]);
  expect(server.commands[2]!.payload).toMatchObject({ outcome: "DELIVERED", deliveredUnits: 12 });
  expect(server.commands[3]!.payload).toMatchObject({ photoAttachmentId: null, signatureAttachmentId: null, fallbackReason: HANDOVER });
  await expect(page.getByRole("status").filter({ hasText: "records saved on this phone" })).toHaveCount(0);
  expect(server.commands).toHaveLength(5);
});

test("with a signal, each delivery is sent at once and the run completes with its figures", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  await page.goto("/");
  await takeVehicle(page);
  await startTrip(page);
  await expect(page.getByText("Next • stop 01 of 01")).toBeVisible();
  await deliver(page);
  await expect(page.getByText("1 of 1 stop delivered")).toBeVisible();
  expect(server.commands.map((c) => c.kind)).toEqual(["delivery:Start", "delivery:RecordArrival", "delivery:Record", "delivery:CaptureProof"]);
  expect(server.batches).toBe(0);
});

test("a partial delivery says why and what happened to the rest before it can be confirmed", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  await page.goto("/");
  await takeVehicle(page);
  await startTrip(page);
  await page.getByRole("button", { name: "I’ve arrived" }).click();

  await page.getByRole("button", { name: "One unit fewer" }).click();
  await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
  await expect(page.getByText("Say why the delivery was partial.")).toBeVisible();
  await expect(page.getByText("Enter Store Manager PIN")).toBeHidden();

  await page.getByLabel("Why were fewer units handed over?").fill("One unit short at loading");
  await page.getByLabel("What happened to the units not handed over?").fill("Never left the depot");
  await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
  await page.getByRole("button", { name: "Confirm", exact: true }).last().click();

  await expect.poll(() => server.commands.find((c) => c.kind === "delivery:Record")?.payload).toMatchObject({
    outcome: "PARTIAL",
    deliveredUnits: 11,
    reason: "One unit short at loading",
    dispositionNote: "Never left the depot",
  });
});

test("a typed vehicle that is not on the run is refused, naming the driver's vehicle", async ({ page }) => {
  await serve(page);
  await page.goto("/");
  await takeVehicle(page, "VEH999");
  await expect(page.getByText("VEH999 is not on your run. Your vehicle is VEH043.")).toBeVisible();
});

test("with no released trip the phone says so instead of drawing sample stops", async ({ page }) => {
  await serve(page, []);
  await page.goto("/");
  await takeVehicle(page);
  await expect(page.getByText("No released trip for you yet. It appears once the loader releases your vehicle.")).toBeVisible();
  await expect(page.getByText("Peradeniya")).toHaveCount(0);
});
