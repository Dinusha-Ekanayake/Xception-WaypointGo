import { expect, test } from "@playwright/test";
import { arrive, openForm, serve, sign, startTrip, stop } from "./mocks.ts";

// Written for the earlier driver screens (#114 moved the run onto the Figma flow): the Figma driver flow has no "not delivered" path yet (failure reason and what happened to the goods); issue #21.
test.fixme("no one at the store: the stop is recorded as not delivered, with what happened to the goods", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Report problem" }).first().click();

  const sheet = page.getByRole("dialog", { name: "Report a problem" });
  await sheet.getByRole("radio", { name: /No one at the outlet/ }).click();
  await sheet.getByRole("button", { name: "Record this stop" }).click();

  await expect(page.getByLabel("Why could it not be delivered?")).toHaveValue("outlet_closed");
  await page.getByRole("button", { name: "Record as not delivered" }).click();
  await expect(page.getByText("Say what happened to the goods.")).toBeVisible();
  await page.getByLabel("What happened to the goods not delivered?").fill("Returning to the depot");
  await page.getByRole("button", { name: "Record as not delivered" }).click();

  const saved = page.getByRole("dialog", { name: "Recorded as not delivered" });
  await expect(saved).toBeVisible();
  const record = server.commands.at(-1)!;
  expect(record).toMatchObject({
    kind: "delivery:Record",
    expectedVersion: 2,
    payload: { outcome: "FAILED", deliveredUnits: null, reason: "outlet_closed", dispositionNote: "Returning to the depot" },
  });
  // The vehicle never reached the stop, so there is no proof to capture.
  expect(server.commands.some((c) => c.kind === "delivery:CaptureProof")).toBe(false);

  await saved.getByRole("button", { name: "Next stop" }).click();
  await expect(page.getByRole("heading", { name: "OUT0202" })).toBeVisible();
});

// Written for the earlier driver screens (#114 moved the run onto the Figma flow): the Figma report sheet sends a fault from the delivery report, not from the route; rewrite against it; issue #21.
test.fixme("a road report reaches dispatch with the stop it is about", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Report problem" }).first().click();
  const sheet = page.getByRole("dialog", { name: "Report a problem" });
  await sheet.getByRole("radio", { name: /Road closed or blocked/ }).click();
  await expect(sheet.getByRole("button", { name: "Send to dispatch" })).toBeDisabled();
  await sheet.getByLabel("What happened?").fill("Landslide at Kadugannawa");
  await sheet.getByRole("button", { name: "Send to dispatch" }).click();

  await expect(page.getByRole("dialog", { name: "Report sent" })).toBeVisible();
  expect(server.commands.at(-1)).toMatchObject({
    kind: "delivery:ReportFault",
    expectedVersion: null,
    payload: { vehicleId: "VEH043", deliveryId: server.stops[0]!.deliveryId, kind: "road", description: "Landslide at Kadugannawa" },
  });
});

// Written for the earlier driver screens (#114 moved the run onto the Figma flow): the Figma screens show a refused write only as a short message; rewrite against LiveStatus; issue #21.
test.fixme("a write the server refuses on a rule is shown and is not kept for later", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  server.refuse = "delivery:RecordArrival";
  await arrive(page);
  await expect(page.getByText("Arrival is recorded once for a stop")).toBeVisible();
  await expect(page.getByRole("button", { name: /to send|saved on this device|to review/ })).toHaveCount(0);
  expect(server.batches).toBe(0);
});

// Written for the earlier driver screens (#114 moved the run onto the Figma flow): the Figma flow takes no photo or signature (the store PIN step stands in), so there is no proof file to refuse; issue #21.
test.fixme("a proof file the server refuses stays on the phone until the driver removes it", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  server.refuseUploads = true;
  await page.goto("/");
  await startTrip(page);
  await arrive(page);
  await openForm(page);
  await sign(page);
  await expect(page.getByText("Signed", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("dialog", { name: "Delivery confirmed" }).getByRole("button", { name: "Finish run" }).click();
  await page.getByRole("button", { name: "Back to home" }).click();

  // The delivery stands; the file is shown with the server's reason, not dropped and not resent.
  const refused = page.getByRole("region", { name: "Proof files the server refused" });
  await expect(refused).toContainText("Signature · stop 01 OUT0101");
  await expect(refused).toContainText("The file is not a JPEG, PNG or WebP image");
  expect(server.commands.map((c) => c.kind)).toContain("delivery:CaptureProof");

  await page.getByRole("button", { name: /Settings:/ }).click();
  await page.getByRole("dialog", { name: "Settings" }).getByRole("button", { name: "Sign out" }).click();
  const sheet = page.getByRole("dialog", { name: "Sign out" });
  await expect(sheet).toContainText("1 record is still only on this phone");
  await sheet.getByRole("button", { name: "Stay signed in" }).click();

  await refused.getByRole("button", { name: "Remove" }).click();
  await expect(refused).toHaveCount(0);
  await page.getByRole("button", { name: /Settings:/ }).click();
  await page.getByRole("dialog", { name: "Settings" }).getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("dialog", { name: "Sign out" }).getByRole("button", { name: "Sign out" })).toBeVisible();
});
