import { expect, test, type Page } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { LIVE_NOW, liveDay, serve, type Desk } from "./mocks.ts";
import { storeClosed } from "../playbook-fixture.ts";

// R-EXE-31: on a trip on the road the dispatcher skips a store (next day or
// cancel, with a reason) or ends the trip. Each sends the stop's version, says
// who is told, and the stop reads "Off the trip" once Execution took it off.

async function openTrip(page: Page): Promise<Desk> {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const desk = await serve(page, liveDay());
  await page.goto("/#/live");
  const needs = page.getByRole("region", { name: "Needs you" });
  await needs.getByRole("listitem").filter({ hasText: "OUT010" }).getByRole("button", { name: "Open trip" }).click();
  await expect(page.getByRole("heading", { name: /VEH030/ })).toBeVisible();
  return desk;
}

test("the dispatcher skips a store and cancels its order, with a reason", async ({ page }) => {
  const desk = await openTrip(page);
  const row = page.getByRole("row", { name: /OUT012/ });
  // A failed stop is done: nothing to skip there.
  await expect(page.getByRole("row", { name: /OUT010/ }).getByRole("button", { name: "Skip store" })).toHaveCount(0);

  await row.getByRole("button", { name: "Skip store" }).click();
  const sheet = page.getByRole("dialog", { name: "Skip OUT012" });
  await expect(sheet.getByRole("button", { name: "Skip to next day" })).toBeDisabled();
  await sheet.getByRole("radio", { name: "Cancel" }).click();
  await expect(sheet).toContainText("the warehouse is not called");
  await sheet.getByRole("textbox", { name: "Why" }).fill("Store refused the delivery");
  await sheet.getByRole("button", { name: "Skip and cancel" }).click();

  await expect(sheet).toBeHidden();
  const sent = desk.commands.find((c) => c.kind === "delivery:SkipStop");
  expect(sent?.payload).toEqual({ deliveryId: "d-VEH030-2", then: "CANCEL", reason: "Store refused the delivery" });
  expect(sent?.expectedVersion).toBe(1);
  // A driver with no signal may still drive there: the dispatcher sees it is not seen yet, and phones.
  await expect(row).toContainText("Off the trip · cancelled · not seen by the driver yet");
  await expect(row.getByRole("button", { name: "Skip store" })).toHaveCount(0);
});

test("ending the trip says how many stops go to the next day and sends one command", async ({ page }) => {
  const desk = await openTrip(page);
  await page.getByRole("button", { name: "End trip" }).click();
  const sheet = page.getByRole("dialog", { name: "End trip" });
  await expect(sheet).toContainText("1 stop not yet done goes to the next delivery day");
  await expect(sheet).toContainText("OUT012");
  await sheet.getByRole("textbox", { name: "Why" }).fill("Vehicle broke down");
  await sheet.getByRole("button", { name: "End trip · 1 stop" }).click();

  await expect(sheet).toBeHidden();
  expect(desk.commands.filter((c) => c.kind === "delivery:EndTrip").map((c) => c.payload)).toEqual([{ tripId: "t-VEH030", reason: "Vehicle broke down" }]);
  await expect(page.getByRole("row", { name: /OUT012/ })).toContainText("Off the trip · next day · not seen by the driver yet");
  await expect(page.getByRole("button", { name: "End trip" })).toHaveCount(0);
});

test("the dispatcher decides from the trip's chat: Skip beside the report, End trip in the header", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", {
      kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch",
      body: "OUT063 shutter is down, nobody answers", aboutOutletId: "OUT063",
    }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const chat = page.getByRole("dialog", { name: "Trip messages" });
  await expect(chat.getByRole("button", { name: "End trip" })).toBeVisible();
  const report = chat.locator("#message-r1");
  await report.getByRole("button", { name: "Skip OUT063" }).click();
  const form = report.getByRole("group", { name: "Skip OUT063" });
  await form.getByRole("textbox", { name: "Why" }).fill("Store closed, nobody answers");
  await form.getByRole("button", { name: "Skip to next day" }).click();

  await expect.poll(() => desk.commands.find((c) => c.kind === "delivery:SkipStop")?.payload).toEqual({
    deliveryId: "d-VEH020-2", then: "NEXT_DAY", reason: "Store closed, nobody answers",
  });
  // The stop is off the trip: no skip left beside the report, and nothing left for End trip.
  await expect(report.getByRole("button", { name: "Skip OUT063" })).toHaveCount(0);
  await expect(chat.getByRole("button", { name: "End trip" })).toHaveCount(0);
});

test("the chat suggests the likely decisions beside a report, and keeps which one was taken (#269)", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", {
      kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch",
      body: "OUT063 shutter is down, nobody answers", aboutOutletId: "OUT063",
    }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const report = page.getByRole("dialog", { name: "Trip messages" }).locator("#message-r1");
  const chips = report.getByRole("group", { name: "Suggested decisions" });
  await expect(chips).toContainText("Suggested by the rules");
  await expect(chips.getByRole("button")).toHaveText(["Call the store", "Skip to next day", "Wait a little"]);
  await expect(chips).toContainText("skipped twice in a row");

  await chips.getByRole("button", { name: "Skip to next day" }).click();
  const form = report.getByRole("group", { name: "Skip OUT063" });
  await expect(form.getByRole("radio", { name: "Next day" })).toHaveAttribute("aria-checked", "true");
  await form.getByRole("textbox", { name: "Why" }).fill("Nobody came to the door");
  await form.getByRole("button", { name: "Skip to next day" }).click();

  // First what was on screen (so a decision taken another way is logged too), then what was taken.
  await expect.poll(() => desk.commands.filter((c) => c.kind === "ml:RecordSuggestion").map((c) => c.payload)).toEqual([
    { deliveryId: "d-VEH020-2", situation: "STORE_CLOSED", shown: ["CALL_STORE", "SKIP_NEXT_DAY", "WAIT"], shownOnly: true },
    { deliveryId: "d-VEH020-2", situation: "STORE_CLOSED", shown: ["CALL_STORE", "SKIP_NEXT_DAY", "WAIT"], chosen: "SKIP_NEXT_DAY" },
  ]);
  expect(desk.commands.find((c) => c.kind === "delivery:SkipStop")?.payload).toMatchObject({ then: "NEXT_DAY" });
});

test("once learned, a chip says how often dispatchers chose it here", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", { kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Shutter down", aboutOutletId: "OUT063" }),
  ]);
  await serve(page, { ...liveDay(), threads: [thread] });
  await page.route("**/api/ml/decision-suggestions**", (route) => {
    const learned = { ...storeClosed("d-VEH020-2"), basis: "learned" };
    learned.suggestions = learned.suggestions.map((x, i) => ({ ...x, chosenTimes: [21, 3, 1][i]!, shownTimes: 30 }));
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(learned) });
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();
  const chips = page.getByRole("dialog", { name: "Trip messages" }).locator("#message-r1").getByRole("group", { name: "Suggested decisions" });
  await expect(chips).toContainText("Suggested from what dispatchers chose here");
  await expect(chips.getByRole("button").first()).toHaveText("Call the store · chosen 21 of 30 times");
});

test("the dispatcher tells each store waiting its own time, one message per store (decision suggestions part 4)", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const day = liveDay();
  const veh020 = day.sheets!.find((s) => s.vehicleId === "VEH020")!;
  const second = veh020.stops[1]!;
  veh020.stops.push({ ...second, deliveryId: "d-VEH020-3", sequence: 3, outletId: "OUT064", expectedArrival: "2027-03-01T11:50:00Z" });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063", "OUT064"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", { kind: "report", reportType: "late", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Traffic at Peradeniya", aboutOutletId: "OUT063" }),
  ]);
  const desk = await serve(page, { ...day, threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const chat = page.getByRole("dialog", { name: "Trip messages" });
  await chat.getByRole("button", { name: "Tell each store waiting its time (2)" }).click();
  const preview = chat.getByRole("group", { name: "Tell each store its time" });
  await expect(preview).toContainText("OUT063");
  await expect(preview).toContainText("OUT064");
  await preview.getByRole("button", { name: "Send 2" }).click();
  await expect.poll(() => desk.commands.filter((c) => c.kind === "message:Post").map((c) => c.payload.outletId)).toEqual(["OUT063", "OUT064"]);
  const posts = desk.commands.filter((c) => c.kind === "message:Post");
  expect(posts.every((c) => c.payload.to === "outlet")).toBe(true);
  expect(posts[0]!.payload.body).not.toEqual(posts[1]!.payload.body);
});

test("a report about the vehicle gets its chips too, asked about the next stop, and its word goes to everyone on the trip", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", { kind: "report", reportType: "vehicle_fault", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Engine light, pulled over" }),
  ]);
  await serve(page, { ...liveDay(), threads: [thread] });
  const asked: string[] = [];
  await page.route("**/api/ml/decision-suggestions**", (route) => {
    const url = new URL(route.request().url());
    asked.push(`${url.searchParams.get("deliveryId")}|${url.searchParams.get("situation")}`);
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        situation: "BREAKDOWN", deliveryId: url.searchParams.get("deliveryId"), basis: "rules", version: 1,
        suggestions: [
          { action: "END_TRIP", chance: 0.45, warning: null },
          { action: "WAIT", chance: 0.35, warning: null },
          { action: "TELL_STORE", chance: 0.2, warning: null },
        ],
        playbook: {
          situation: "BREAKDOWN",
          steps: ["Find out from the driver how long the repair takes."],
          message: { audience: "outlet", template: "{vehicle} has broken down on the way to {store}. We will tell you as soon as we know when it can come." },
        },
      }),
    });
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const chat = page.getByRole("dialog", { name: "Trip messages" });
  const chips = chat.locator("#message-r1").getByRole("group", { name: "Suggested decisions" });
  await expect(chips.getByRole("button")).toHaveText(["End trip", "Wait a little", "Tell the store"]);
  await expect.poll(() => asked[0]).toMatch(/^d-VEH020-\d+\|vehicle_fault$/);

  await chips.getByRole("button", { name: "Tell the store" }).click();
  await expect(chat.getByRole("textbox").last()).toHaveValue("VEH020 has broken down on the way to your store. We will tell you as soon as we know when it can come.");
  await expect(chat.getByRole("radio", { name: "Everyone on the trip" })).toHaveAttribute("aria-checked", "true");
});

/** A trip outlook whose problem room is the one given (R-ML-36). */
function withRoom(room: unknown, signals: unknown[] = [{ messageId: "r1", situation: "BREAKDOWN", source: "report", outletId: null, authorRole: null }]) {
  return { tripId: "t-VEH020", version: 1, situation: "BREAKDOWN", trip: [], signals, stops: [], messagesRead: true, unreadMessages: 0, room, toReview: [] };
}

const ROWS = [
  { deliveryId: "d-VEH020-1", outletId: "OUT061", sequence: 1, state: "DELIVERED", at: null, because: null },
  { deliveryId: "d-VEH020-2", outletId: "OUT063", sequence: 2, state: "NOT_ASKED", at: null, because: null },
];

async function openRoom(page: Page, room: unknown, extra: Partial<Desk> = {}, signals?: unknown[]): Promise<{ desk: Desk; asked: string[] }> {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", { kind: "report", reportType: "vehicle_fault", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Engine light" }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread], ...extra });
  const asked: string[] = [];
  await page.route("**/api/ml/trip-outlook**", (route) => {
    asked.push(new URL(route.request().url()).search);
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(withRoom(room, signals)) });
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();
  return { desk, asked };
}

test("a breakdown's room asks the stores whose window it misses, in one tap, and keeps ending the trip as the way out (R-ML-36)", async ({ page }) => {
  const { desk, asked } = await openRoom(page, {
    situation: "BREAKDOWN",
    rows: ROWS,
    next: {
      kind: "ASK", why: "Nobody can choose a store's delivery before it says when it can receive.",
      stops: [{ deliveryId: "d-VEH020-2", outletId: "OUT063", receiveAt: "2027-03-01T12:40:00Z", rowVersion: 1 }],
      about: null, extraMinutes: 60, extras: [30, 60, 120], endTripToo: true,
    },
  });
  const chat = page.getByRole("dialog", { name: "Trip messages" });
  const room = chat.getByRole("region", { name: "Problem room" });
  await expect(room).toContainText("Problem · VEH020 broke down");
  const rows = room.getByRole("list", { name: "Stores on the trip" });
  await expect(rows).toContainText("OUT061");
  await expect(rows).toContainText("Done");
  await expect(rows).toContainText("Not asked yet");
  // One place: no old chips, no End trip in the header, no ready replies, no percentages.
  await expect(chat.locator("#message-r1").getByRole("group", { name: "Suggested decisions" })).toHaveCount(0);
  await expect(chat.getByRole("button", { name: "End trip", exact: true })).toHaveCount(0);
  await expect(chat.getByRole("group", { name: "Suggested replies" })).toHaveCount(0);
  await expect(room).not.toContainText("%");
  if (process.env.SHOTS_DIR) {
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${process.env.SHOTS_DIR}/25-problem-room-ask.png` });
  }

  await room.getByRole("radio", { name: "+2 h" }).click();
  await expect.poll(() => asked.some((q) => q.includes("extraMinutes=120"))).toBe(true);

  await room.getByRole("button", { name: /^Ask OUT063: receive at about/ }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AskDecision")?.payload).toEqual({
    kind: "receive_late", deliveryId: "d-VEH020-2", receiveAt: "2027-03-01T12:40:00Z",
  });

  await room.getByRole("button", { name: "The driver can't go on: end the trip" }).click();
  await expect(chat.getByRole("button", { name: /^End trip/ }).last()).toBeVisible();
});

test("a store that said No is the next to skip, the form filled in with why (R-ML-36)", async ({ page }) => {
  const { desk } = await openRoom(page, {
    situation: "BREAKDOWN",
    rows: [ROWS[0], { ...ROWS[1], state: "NO" }],
    next: {
      kind: "SKIP", why: "OUT063 can't receive at 18:10.",
      stops: [{ deliveryId: "d-VEH020-2", outletId: "OUT063", receiveAt: null, rowVersion: 1 }],
      about: null, extraMinutes: null, extras: [], endTripToo: true,
    },
  });
  const chat = page.getByRole("dialog", { name: "Trip messages" });
  const room = chat.getByRole("region", { name: "Problem room" });
  await expect(room).toContainText("No · can't receive");
  await room.getByRole("button", { name: "Skip OUT063 to next day" }).click();
  const form = chat.getByRole("group", { name: "Skip OUT063" });
  await expect(form.getByRole("textbox", { name: "Why" })).toHaveValue("OUT063 can't receive at 18:10.");
  await form.getByRole("button", { name: "Skip to next day" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "delivery:SkipStop")?.payload).toMatchObject({
    deliveryId: "d-VEH020-2", then: "NEXT_DAY", reason: "OUT063 can't receive at 18:10.",
  });
});

test("a store with no answer by the deadline is called, and its answer recorded by phone (R-ML-36)", async ({ page }) => {
  const asked = {
    requestId: "q-1", kind: "receive_late", depotCode: "KDY", serviceDate: "2027-03-01", tripId: "t-VEH020", vehicleId: "VEH020",
    deliveryId: "d-VEH020-2", outletId: "OUT063", aboutDeliveryId: null, aboutOutletId: null, aboutUnits: null,
    receiveAt: "2027-03-01T12:40:00Z", arrivalAt: null, deadline: "2027-03-01T10:20:00Z", state: "expired", answer: null,
    answeredVia: null, answeredAt: null, answeredLate: false, reason: null, voiceNoteId: null, askedAt: "2027-03-01T10:06:00Z",
    rowVersion: 3, askedBySystem: false,
  };
  const { desk } = await openRoom(page, {
    situation: "BREAKDOWN",
    rows: [ROWS[0], { ...ROWS[1], state: "NO_ANSWER" }],
    next: {
      kind: "CALL", why: "OUT063 did not answer by the deadline.",
      stops: [{ deliveryId: "d-VEH020-2", outletId: "OUT063", receiveAt: null, rowVersion: 1 }],
      about: null, extraMinutes: null, extras: [], endTripToo: true,
    },
  }, { decisions: [asked] as never });
  const room = page.getByRole("dialog", { name: "Trip messages" }).getByRole("region", { name: "Problem room" });
  await expect(room.getByRole("link", { name: /^Call OUT063/ })).toBeVisible();
  await room.getByRole("button", { name: "They said No" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AnswerDecision")).toMatchObject({
    payload: { requestId: "q-1", answer: "no", byPhone: true }, expectedVersion: 3,
  });
});

test("a skipped store's goods in the way ask the next store's dock, and a refusal tells the driver (R-ML-36)", async ({ page }) => {
  const dock = {
    situation: "BREAKDOWN",
    rows: [{ ...ROWS[0], state: "SKIPPED" }, ROWS[1]],
    next: {
      kind: "ASK_DOCK", why: "OUT061's goods were loaded in front of OUT063's.",
      stops: [{ deliveryId: "d-VEH020-2", outletId: "OUT063", receiveAt: null, rowVersion: 1 }],
      about: { deliveryId: "d-VEH020-1", outletId: "OUT061", receiveAt: null, rowVersion: 1 },
      extraMinutes: null, extras: [], endTripToo: false,
    },
  };
  const { desk } = await openRoom(page, dock);
  const room = page.getByRole("dialog", { name: "Trip messages" }).getByRole("region", { name: "Problem room" });
  await room.getByRole("button", { name: "Ask OUT063: may the crew unload OUT061's goods at its dock?" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AskDecision")?.payload).toEqual({
    kind: "dock_help", deliveryId: "d-VEH020-2", aboutDeliveryId: "d-VEH020-1",
  });
});

test("a driver's message read as a breakdown says so plainly under the room, and the dispatcher can take it off with a reason (R-ML-35)", async ({ page }) => {
  const { desk } = await openRoom(page, {
    situation: "BREAKDOWN",
    rows: ROWS,
    next: { kind: "WAIT", why: "1 answer due by 15:40.", stops: [], about: null, extraMinutes: null, extras: [], endTripToo: true },
  }, {}, [{ messageId: "m1", situation: "BREAKDOWN", source: "message", outletId: null, authorRole: "driver" }]);
  const chat = page.getByRole("dialog", { name: "Trip messages" });
  await expect(chat).toContainText("Breakdown: from the driver's message, read automatically");
  await expect(chat).not.toContainText("Jev");
  await chat.getByRole("button", { name: "Not a problem" }).click();
  const off = chat.getByRole("group", { name: "Take it off the card" });
  await expect(off.getByRole("button", { name: "Take it off" })).toBeDisabled();
  await off.getByRole("textbox", { name: "Why" }).fill("Only a warning light, driving on");
  await off.getByRole("button", { name: "Take it off" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "ml:DismissSignal")?.payload).toEqual({
    tripId: "t-VEH020", messageId: "m1", reason: "Only a warning light, driving on",
  });
});

test("messages that could not be read automatically are said, even with nothing else open (rule 9)", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("m1", "2027-03-01T10:05:00Z", { authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Something is wrong" }),
  ]);
  await serve(page, { ...liveDay(), threads: [thread] });
  await page.route("**/api/ml/trip-outlook**", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ tripId: "t-VEH020", version: 1, situation: null, trip: [], signals: [], stops: [], messagesRead: true, unreadMessages: 2 }),
  }));
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByRole("button", { name: /^VEH020/ }).first().click();
  await page.getByRole("button", { name: /Messages|All messages/ }).first().click();
  const chat = page.getByRole("dialog", { name: "Trip messages" });
  await expect(chat.getByRole("status").filter({ hasText: "2 messages could not be read automatically. Read them yourself." })).toBeVisible();
  await expect(chat.getByRole("region", { name: "Next step" })).toHaveCount(0);
});

test("a voice note heard over noise is put to the dispatcher to confirm in one tap, before it counts (R-ML-37)", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("m1", "2027-03-01T10:05:00Z", { authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Voice note" }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.route("**/api/ml/trip-outlook**", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      tripId: "t-VEH020", version: 1, situation: null, trip: [], signals: [], stops: [], messagesRead: true, unreadMessages: 0, room: null,
      toReview: [{ messageId: "m1", guess: "BREAKDOWN", unclear: true, source: "voice", authorRole: "driver", outletId: null }],
    }),
  }));
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByRole("button", { name: /^VEH020/ }).first().click();
  await page.getByRole("button", { name: /Messages|All messages/ }).first().click();

  const check = page.getByRole("dialog", { name: "Trip messages" }).getByRole("group", { name: "Check this message" });
  await expect(check).toContainText("Check this: the driver's voice note was hard to hear. Looks like: breakdown.");
  await expect(check.getByRole("button", { name: "Store closed" })).toHaveCount(0);
  await check.getByRole("button", { name: "Something else" }).click();
  await expect(check.getByRole("button", { name: "Store closed" })).toBeVisible();
  if (process.env.SHOTS_DIR) await page.screenshot({ path: `${process.env.SHOTS_DIR}/26-check-this.png` });
  await check.getByRole("button", { name: "Yes, breakdown" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "ml:ConfirmSignal")?.payload).toEqual({
    tripId: "t-VEH020", messageId: "m1", situation: "BREAKDOWN",
  });
});
