import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Issue #136: the driver's side of the trip's thread. The driver reads what the
// dispatcher wrote to them or to everyone, replies to the dispatcher or a store
// on the trip, and reports a problem, which only the dispatcher reads. Typed
// messages keep on the phone with no signal.

const TRIP = "00000000-0000-7000-8000-0000000000aa";

async function open(page: import("@playwright/test").Page) {
  const server = await serve(page);
  server.threads = [
    tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver", [
      said("m1", new Date(Date.now() - 20 * 60_000).toISOString(), { body: "Take the bypass at Akuressa", audience: "driver" }),
    ]),
  ];
  server.notifications = [
    {
      notificationId: "n1", eventType: "message.posted", title: "Dinusha Bawantha · VEH043", body: "Take the bypass at Akuressa",
      subjectType: "thread", subjectId: `thread-${TRIP}`, createdAt: new Date().toISOString(), readAt: null, facts: null,
    } as never,
  ];
  await page.goto("/");
  return server;
}

test("the driver reads the dispatcher's message, replies, and reports a problem to the dispatcher", async ({ page }) => {
  const server = await open(page);
  await page.getByRole("button", { name: "Messages, 1 new" }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByText("Take the bypass at Akuressa")).toBeVisible();
  await expect(screen.getByText("To the driver")).toBeVisible();

  await screen.getByRole("textbox", { name: "Message" }).fill("On it, about 20 minutes more");
  await screen.getByRole("button", { name: "Send", exact: true }).click();
  await expect(screen.getByText("On it, about 20 minutes more")).toBeVisible();

  await screen.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await screen.getByRole("combobox", { name: "Problem" }).selectOption("road_disruption");
  await screen.getByRole("textbox", { name: "Message" }).fill("Road closed after the bridge");
  await screen.getByRole("button", { name: "Report", exact: true }).click();
  await expect(screen.getByText("Report · Road disruption")).toBeVisible();

  const posts = server.commands.filter((c) => c.kind === "message:Post").map((c) => c.payload);
  expect(posts[0]).toMatchObject({ threadId: `thread-${TRIP}`, to: "dispatch", body: "On it, about 20 minutes more" });
  expect(posts[1]).toMatchObject({ to: "dispatch", report: "road_disruption", body: "Road closed after the bridge" });
  // Opening the thread reads its notification.
  expect(server.commands.some((c) => c.kind === "notification:MarkRead")).toBe(true);
});

test("with no signal a typed message is kept on the phone and sent when the signal returns", async ({ page, context }) => {
  const server = await open(page);
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByText("Take the bypass at Akuressa")).toBeVisible();

  await server.goOffline(context);
  await expect(screen.getByText(/No signal\. What you write or record is kept on this phone/)).toBeVisible();
  // A voice note is kept with no signal too (voice.spec.ts).
  await expect(screen.getByRole("button", { name: "Voice" })).toBeEnabled();
  await screen.getByRole("textbox", { name: "Message" }).fill("Signal gone near Deniyaya");
  await screen.getByRole("button", { name: "Send", exact: true }).click();
  await expect(screen.getByText("Saved on this device. It sends when the connection returns.")).toBeVisible();
  await expect(screen.getByTestId("waiting-message")).toContainText("Signal gone near Deniyaya");
  expect(server.commands.some((c) => c.kind === "message:Post")).toBe(false);

  await server.goOnline(context);
  await expect.poll(() => server.commands.find((c) => c.kind === "message:Post")?.payload.body).toBe("Signal gone near Deniyaya");
});
