import { expect, test } from "@playwright/test";
import type { PostMessagePayload } from "../../src/shared/domain/messaging.ts";
import { postToThread, said, threadRead, tripThread } from "../thread-mocks.ts";
import { board, manifest, SESSION } from "./mocks.ts";

// Issue #136: the loader's side of a trip's thread, from the load sheet. The
// loader reads what the dispatcher wrote to the loaders, and writes to the
// dispatcher alone, a report of missing items included.

test("the load sheet opens the trip's messages, and a missing-items report goes to the dispatcher", async ({ page }) => {
  const posts: PostMessagePayload[] = [];
  const loaded = manifest("trip-test", true, 4);
  const threads = [tripThread("trip-test", loaded.vehicleId, ["OUT0101"], "loader", [said("m1", new Date().toISOString(), { body: "Load the chilled crates last", audience: "loader" })])];

  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (url.pathname === "/api/session") return json(SESSION);
    if (url.pathname === "/api/loading/trips") return json(board(loaded));
    if (url.pathname === "/api/reference/outlets") return json([]);
    if (url.pathname === "/api/loading/trips/trip-test/manifest") return json(loaded);
    if (url.pathname === "/api/commands" && route.request().method() === "POST") {
      const command = route.request().postDataJSON() as { commandId: string; kind: string; payload: PostMessagePayload };
      if (command.kind !== "message:Post") return json({ commandId: command.commandId, kind: command.kind, replayed: false, result: {} });
      posts.push(command.payload);
      const result = postToThread(threads, command.payload, { name: "Isuru", role: "loader" }, new Date().toISOString());
      return json({ commandId: command.commandId, kind: command.kind, replayed: false, result });
    }
    const thread = route.request().method() === "GET" ? threadRead(threads, url) : undefined;
    if (thread) return json(thread.body, thread.status);
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Messages" }).click();

  const sheet = page.getByRole("dialog", { name: `Messages for ${loaded.vehicleId}` });
  await expect(sheet.getByText("Load the chilled crates last")).toBeVisible();
  await expect(sheet.getByText("To the loaders")).toBeVisible();
  await sheet.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await expect(sheet.getByRole("combobox", { name: "Problem" })).toHaveValue("loading_shortfall");
  await sheet.getByRole("textbox", { name: "Message" }).fill("Two crates of yoghurt not at the dock");
  await sheet.getByRole("button", { name: "Report", exact: true }).click();
  await expect(sheet.getByText("Report · Loading shortfall")).toBeVisible();
  expect(posts).toEqual([expect.objectContaining({ threadId: "thread-trip-test", to: "dispatch", report: "loading_shortfall", body: "Two crates of yoghurt not at the dock" })]);
});
