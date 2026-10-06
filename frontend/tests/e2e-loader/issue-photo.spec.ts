import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Roadmap FF-02: a loader's photo of a damaged or short line goes to the order's
// issue photos, and the shortfall names it, so the issue shows the evidence.

// A real 2x2 PNG, so the tablet can read and redraw it.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==", "base64");

test("a reported item carries the loader's photo to the issue", async ({ page }) => {
  const open = manifest("trip-test", false, 4);
  const commands: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  const uploads: string[] = [];

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (url.pathname === "/api/session") return json(SESSION);
    if (url.pathname === "/api/loading/trips") return json(board(open));
    if (url.pathname === "/api/reference/outlets") return json([]);
    if (url.pathname === "/api/loading/trips/trip-test/manifest") return json(open);
    if (request.method() === "PUT" && url.pathname.startsWith("/api/issues/attachments/")) {
      uploads.push(`${url.pathname}?${url.searchParams.toString()}`);
      return json({ attachmentId: url.pathname.split("/").pop(), stored: true });
    }
    if (url.pathname === "/api/commands" && request.method() === "POST") {
      const body = request.postDataJSON() as { kind: string; payload: Record<string, unknown> };
      commands.push(body);
      return json({ commandId: "c", kind: body.kind, replayed: false, result: { rowVersion: 5 } });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Report", exact: true }).first().click();
  const sheet = page.getByRole("dialog", { name: "Report an issue" });
  await sheet.getByRole("textbox").fill("Carton crushed on the pallet");
  await sheet.getByLabel("Take a photo of the item").setInputFiles({ name: "item.png", mimeType: "image/png", buffer: PNG });
  await expect(sheet.getByText("Photo added")).toBeVisible();
  await sheet.getByRole("button", { name: "Send to dispatcher" }).click();

  await expect.poll(() => commands.filter((c) => c.kind === "loading:Shortfall").length).toBe(1);
  const shortfall = commands.find((c) => c.kind === "loading:Shortfall")!;
  const photoId = shortfall.payload.photoAttachmentId as string;
  expect(photoId).toMatch(/^[0-9a-f-]{36}$/);
  await expect.poll(() => uploads.length).toBe(1);
  expect(uploads[0]).toBe(`/api/issues/attachments/${photoId}?order=${shortfall.payload.orderId}`);
});
