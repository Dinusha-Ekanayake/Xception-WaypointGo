import { test, expect, type Page } from "@playwright/test";
import { Database } from "../../lib/database.ts";
import { Service } from "../../lib/service.ts";

async function login(page: Page, role: string) {
  await page.goto("/");
  await page.locator('input[name="email"]').fill(`${role}@waypoint.local`);
  await page.locator('input[name="password"]').fill("Waypoint2026!");
  await page.getByRole("button", { name: "Sign in →", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
}

test("empty draft has no invented delivery results or risk forecasts", async ({
  page,
}) => {
  await login(page, "dispatcher");
  await expect(page.getByText("118 served", { exact: false })).toHaveCount(0);
  await expect(page.getByText("38% risk", { exact: false })).toHaveCount(0);
  await page.getByRole("button", { name: "Live runs", exact: true }).click();
  await expect(
    page.getByText("No published plan", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("Fresh Kotahena", { exact: false })).toHaveCount(
    0,
  );
});

for (const role of ["driver", "loader", "store"])
  test(`${role} can sign in again after expiry without clearing device data`, async ({
    page,
    context,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page, role);
    await expect.poll(() => page.evaluate(() => scrollY)).toBe(0);
    await context.clearCookies();
    await page.getByRole("button", { name: "Sync queue", exact: true }).click();
    await page.getByRole("button", { name: "Sync now ↻", exact: true }).click();
    await page
      .getByRole("button", { name: "Sign in again", exact: true })
      .click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await page
      .getByRole("dialog")
      .locator('input[name="password"]')
      .fill("Waypoint2026!");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Sign in", exact: true })
      .click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(
      page.getByText("Session expired.", { exact: false }),
    ).toHaveCount(0);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });

test("store never labels an unplanned order as on the way or invents a deferral", async ({
  page,
}) => {
  await login(page, "store");
  const state = await (await page.request.get("/api/state")).json();
  for (const o of state.orders.filter(
    (o: any) => o.status === "confirmed_order",
  )) {
    const card = page
      .locator("article")
      .filter({ has: page.getByText(o.id, { exact: true }) });
    await expect(card).toContainText("Awaiting dispatch");
    await expect(card.getByText("On the way", { exact: true })).toHaveCount(0);
  }
  await expect(page.getByText(/^Deferred to /)).toHaveCount(
    state.orders.filter((o: any) => o.status === "deferred").length,
  );
});

test("loader can select a trip beyond the first four vehicles and see its manifest", async ({
  page,
}) => {
  await login(page, "loader");
  const state = await (await page.request.get("/api/state")).json();
  const routes = state.plans.flatMap((p: any) =>
    p.routes.map((r: any) => ({ ...r, day: p.day })),
  );
  const route = routes.at(-1);
  expect(route).toBeTruthy();
  await page.getByLabel("Show trip").selectOption(`${route.id}|${route.day}`);
  await expect(
    page.getByText(route.order_ids[0], { exact: true }).first(),
  ).toBeVisible();
});

test("four roles complete shortfall recovery, offline proof, session recovery and receipt", async ({
  browser,
}, testInfo) => {
  test.setTimeout(120000);
  const baseURL = "http://127.0.0.1:43219";
  const contexts = await Promise.all(
    ["dispatcher", "loader", "driver", "store"].map(() =>
      browser.newContext({ baseURL, viewport: { width: 390, height: 844 } }),
    ),
  );
  const [dispatch, loader, driver, store] = await Promise.all(
    contexts.map((c) => c.newPage()),
  );
  try {
    await login(store, "store");
    await store
      .getByRole("button", { name: "＋ Place an order", exact: true })
      .click();
    await store.locator('input[name="units"]').fill("5");
    await store.locator('input[name="weight"]').fill("20");
    await store.locator('input[name="volume"]').fill("0.1");
    await store
      .getByRole("button", { name: "Submit order", exact: true })
      .click();
    await expect(store.getByText(/confirmed for 2026-02-14/)).toBeVisible();
    await dispatch.setViewportSize({ width: 1440, height: 1000 });
    await login(dispatch, "dispatcher");
    await dispatch
      .getByRole("button", { name: "Generate draft", exact: true })
      .click();
    await expect(
      dispatch.getByText("Changes saved.", { exact: true }),
    ).toBeVisible();
    let state = await (await dispatch.request.get("/api/state")).json();
    let plan = state.plans.find((p: any) => p.day === "2026-02-14");
    for (const d of plan.deferred.filter((d: any) => d.repeat)) {
      await dispatch
        .getByRole("button", {
          name: `Explain deferral ${d.order_id}`,
          exact: true,
        })
        .click();
      await dispatch
        .getByRole("dialog")
        .locator("textarea")
        .fill(
          "Review smaller split loads and refrigerated van capacity before the next run.",
        );
      await dispatch
        .getByRole("dialog")
        .getByRole("button", { name: "Save", exact: true })
        .click();
      await expect(dispatch.getByRole("dialog")).toHaveCount(0);
    }
    await dispatch
      .getByRole("button", { name: "Publish plan", exact: true })
      .click();
    const review = dispatch.getByRole("dialog");
    await expect(review.getByRole("heading", { name: "Review publication" })).toBeVisible();
    await review.getByRole("button", { name: "Back to draft", exact: true }).click();
    state = await (await dispatch.request.get("/api/state")).json();
    plan = state.plans.find((p: any) => p.day === "2026-02-14");
    expect(plan.published).toBe(false);
    await dispatch.getByLabel("Depot", { exact: true }).selectOption("Kandy");
    await dispatch.getByRole("button", { name: "Publish plan", exact: true }).click();
    await expect(review).toContainText("All depots");
    for (const depot of ["Peliyagoda", "Kandy"]) {
      const assignedIds = plan.routes.flatMap((r: any) => r.order_ids);
      const expected = state.orders.filter((o: any) => o.depot === depot && assignedIds.includes(o.id)).length;
      await expect(review.getByRole("row", { name: new RegExp(depot) }).getByRole("cell").first()).toHaveText(String(expected));
    }
    await review.getByRole("button", { name: "Confirm publication", exact: true }).click();
    await expect(
      dispatch.getByText("Published · locked", { exact: true }),
    ).toBeVisible();
    await login(driver, "driver");
    state = await (await driver.request.get("/api/state")).json();
    const first = state.orders
      .filter((o: any) => o.day === "2026-02-14")
      .sort(
        (a: any, b: any) =>
          a.route_id.localeCompare(b.route_id) || a.sequence - b.sequence,
      )[0];
    expect(first.outlet_id).toBe("OUT001");
    plan = state.plans.find((p: any) => p.day === "2026-02-14");
    const route = plan.routes.find((r: any) => r.id === first.route_id);
    await login(loader, "loader");
    await loader.getByLabel("Show trip").selectOption(`${route.id}|2026-02-14`);
    await loader
      .getByRole("button", { name: `Flag shortfall ${first.id}`, exact: true })
      .click();
    await loader
      .getByRole("dialog")
      .locator("textarea")
      .fill("One carton damaged at the dock");
    await loader.getByRole("dialog").locator('input[name="count"]').fill("1");
    await loader
      .getByRole("dialog")
      .getByRole("button", { name: "Save", exact: true })
      .click();
    await expect(loader.getByTestId(`load-${first.id}`)).toContainText(
      "Departure blocked",
    );
    await dispatch.reload();
    await dispatch
      .getByRole("button", { name: "Live runs", exact: true })
      .click();
    await dispatch
      .getByRole("button", { name: "Record resolution", exact: true })
      .click();
    await dispatch
      .getByRole("dialog")
      .locator("textarea")
      .fill("Replacement carton supplied. Loader must verify all cases.");
    await dispatch
      .getByRole("dialog")
      .getByRole("button", { name: "Save", exact: true })
      .click();
    await loader.reload();
    await loader.getByLabel("Show trip").selectOption(`${route.id}|2026-02-14`);
    for (const id of [...route.order_ids].reverse()) {
      await loader
        .getByRole("button", { name: `Mark loaded ${id}`, exact: true })
        .click();
      await expect(
        loader.getByRole("button", { name: `Mark loaded ${id}`, exact: true }),
      ).toHaveCount(0);
    }
    await expect(
      loader.getByText("Ready for departure", { exact: true }),
    ).toBeVisible();
    await driver.reload();
    await driver.evaluate(async () => {
      await navigator.serviceWorker.ready;
    });
    await expect
      .poll(() => driver.evaluate(() => !!navigator.serviceWorker.controller))
      .toBe(true);
    await contexts[2].setOffline(true);
    await driver
      .getByRole("button", { name: "Start this stop →", exact: true })
      .first()
      .click();
    await expect(
      driver.getByText("Saved on this device. Waiting for connection.", {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      driver.getByText("Waiting to send", { exact: true }),
    ).toBeVisible();
    await driver.reload();
    await driver
      .getByRole("button", { name: "I’ve arrived", exact: true })
      .first()
      .click();
    await driver
      .getByRole("button", { name: "Record delivery", exact: true })
      .first()
      .click();
    const dialog = driver.getByRole("dialog");
    await dialog.locator('input[name="receiver"]').fill("Outlet receiver");
    await dialog.locator('textarea[name="note"]').fill("Received at the rear dock");
    await dialog
      .locator('input[type="file"]')
      .setInputFiles("public/assets/proof-sample.png");
    const canvas = dialog.locator("canvas");
    await canvas.scrollIntoViewIfNeeded();
    const box = (await canvas.boundingBox())!;
    await driver.mouse.move(box.x + 20, box.y + 20);
    await driver.mouse.down();
    await driver.mouse.move(box.x + 80, box.y + 40, { steps: 8 });
    await driver.mouse.move(box.x + 130, box.y + 20, { steps: 8 });
    await driver.mouse.up();
    await expect(dialog.getByRole("status")).toHaveText("Draft saved on this device · not submitted");
    await dialog.getByRole("button", { name: "Close dialog" }).click();
    await driver.getByRole("button", { name: "Day", exact: true }).click();
    await expect(driver.locator(".driver-workspace")).toHaveAttribute("data-theme", "light");
    await driver.screenshot({ path: testInfo.outputPath("driver-day.png") });
    await driver.getByRole("button", { name: "Night", exact: true }).click();
    await driver.screenshot({ path: testInfo.outputPath("driver-night.png") });
    await expect(driver.locator(".driver-workspace")).toHaveAttribute("data-theme", "dark");
    await driver.reload();
    await expect(driver.getByRole("button", { name: "Night", exact: true })).toHaveAttribute("aria-pressed", "true");
    await driver.getByRole("button", { name: "Record delivery", exact: true }).first().click();
    await expect(dialog.getByRole("status")).toHaveText("Saved draft restored. Review before submitting.");
    await expect(dialog.locator('input[name="receiver"]')).toHaveValue("Outlet receiver");
    await expect(dialog.locator('textarea[name="note"]')).toHaveValue("Received at the rear dock");
    await expect(dialog.getByAltText("Selected delivery photo")).toBeVisible();
    await expect(dialog).toHaveCSS("background-color", "rgb(20, 39, 30)");
    await expect(dialog.getByRole("heading", { name: "Record delivery" })).toHaveCSS("color", "rgb(237, 245, 240)");
    await expect.poll(() => dialog.locator("canvas").evaluate((canvas) =>
      [...(canvas as HTMLCanvasElement).getContext("2d")!.getImageData(0, 0, 600, 180).data]
        .some((value, index) => index % 4 === 3 && value > 0),
    )).toBe(true);
    await driver.screenshot({ path: testInfo.outputPath("driver-night-proof.png") });
    await dialog
      .getByRole("button", { name: "Save delivery record", exact: true })
      .click();
    await expect(driver.getByRole("dialog")).toHaveCount(0);
    await driver.reload();
    await driver
      .getByRole("button", { name: "Sync queue", exact: true })
      .click();
    await expect(
      driver.getByText("Waiting to send", { exact: true }),
    ).toHaveCount(3);
    await contexts[2].clearCookies();
    await contexts[2].setOffline(false);
    await driver
      .getByRole("button", { name: "Sync now ↻", exact: true })
      .click();
    await driver
      .getByRole("button", { name: "Sign in again", exact: true })
      .click();
    await driver
      .getByRole("dialog")
      .locator('input[name="password"]')
      .fill("Waypoint2026!");
    await driver
      .getByRole("dialog")
      .getByRole("button", { name: "Sign in", exact: true })
      .click();
    await expect(
      driver.getByText("Everything is synced", { exact: true }),
    ).toBeVisible();
    await expect(driver.locator(".wp-card").first()).toHaveCSS("background-color", "rgb(20, 39, 30)");
    await store.reload();
    const card = store
      .locator("article")
      .filter({ has: store.getByText(first.id, { exact: true }) });
    await card
      .getByRole("button", { name: "Confirm receipt", exact: true })
      .click();
    await expect(card).toContainText("confirmation received");
    const final = await (await dispatch.request.get("/api/state")).json();
    expect(final.orders.find((o: any) => o.id === first.id).status).toBe(
      "confirmed",
    );
    expect(
      final.events.filter(
        (e: any) => e.kind === "deliver" && e.order_id === first.id,
      ),
    ).toHaveLength(1);
    for (const [role, page] of [["dispatcher", dispatch], ["loader", loader], ["driver", driver], ["store", store]] as const) {
      for (const width of [320, 390, 1440]) {
        await page.setViewportSize({ width, height: 1000 });
        await page.evaluate(() => window.scrollTo(0, 0));
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${role} fits ${width}px`).toBe(true);
        await expect(page.locator(".text-copy").first()).toHaveCSS("font-size", width >= 1024 ? "18px" : "17px");
        await page.screenshot({ path: testInfo.outputPath(`${role}-${width}.png`) });
      }
    }
  } finally {
    await Promise.allSettled(contexts.map((c) => c.close()));
  }
});

test("rejected offline action remains reviewable and later actions are retained", async ({
  browser,
}) => {
  const ctx = await browser.newContext({
    baseURL: "http://127.0.0.1:43219",
    viewport: { width: 390, height: 844 },
  });
  const page = await ctx.newPage();
  try {
    await login(page, "driver");
    const state = await (await page.request.get("/api/state")).json();
    const target = state.orders.find((o: any) => o.status === "loaded");
    expect(target).toBeTruthy();
    const stale = {
      id: "conflict-repro",
      kind: "depart",
      order_id: target.id,
      version: target.version - 1,
      client_time: new Date().toISOString(),
    };
    const later = {
      id: "after-conflict",
      kind: "arrive",
      order_id: target.id,
      version: target.version,
      client_time: new Date().toISOString(),
    };
    await page.evaluate(
      async ({ user, commands }) => {
        const db = await new Promise<IDBDatabase>((resolve, reject) => {
          const r = indexedDB.open("waypoint-v1", 1);
          r.onsuccess = () => resolve(r.result);
          r.onerror = () => reject(r.error);
        });
        await new Promise<void>((resolve, reject) => {
          const t = db.transaction("records", "readwrite");
          t.objectStore("records").put(
            commands.map((command) => ({
              command,
              created: command.client_time,
            })),
            "queue:" + user,
          );
          t.oncomplete = () => resolve();
          t.onerror = () => reject(t.error);
        });
        db.close();
      },
      { user: state.user.id, commands: [stale, later] },
    );
    await page.reload();
    await page.getByRole("button", { name: "Sync queue", exact: true }).click();
    await expect(page.getByText("Needs review", { exact: true })).toBeVisible();
    await expect(
      page.getByText("Waiting to send", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(
      page.getByText(
        "Synchronize or review saved records before signing out of this device.",
        { exact: true },
      ),
    ).toBeVisible();
    const after = await (await page.request.get("/api/state")).json();
    expect(after.orders.find((o: any) => o.id === target.id).version).toBe(
      target.version,
    );
  } finally {
    await ctx.close();
  }
});

test("publication review rejects a draft changed by another dispatcher", async ({ page }) => {
  await login(page, "dispatcher");
  const day = "2026-02-13";
  const command = async (kind: string, data: Record<string, unknown> = {}) => {
    const response = await page.request.post("/api/command", {
      data: { id: crypto.randomUUID(), kind, day, ...data },
    });
    expect(response.ok()).toBe(true);
  };
  await command("plan");
  let state = await (await page.request.get("/api/state")).json();
  const plan = state.plans.find((p: any) => p.day === day);
  for (const d of plan.deferred.filter((d: any) => d.repeat))
    await command("defer_note", { order_id: d.order_id, note: "Arrange capacity before the next run." });
  await page.reload();
  await page.getByLabel("Run day", { exact: true }).selectOption(day);
  await page.getByRole("button", { name: "Publish plan", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await command("plan");
  await page.getByRole("dialog").getByRole("button", { name: "Confirm publication", exact: true }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("Publication was not completed");
  state = await (await page.request.get("/api/state")).json();
  expect(state.plans.find((p: any) => p.day === day).published).toBe(false);
  await page.getByRole("button", { name: "Back to draft", exact: true }).click();
  await expect(page.getByText("This draft changed. Refresh before applying your decision.", { exact: true })).toBeVisible();
});

test("store proof images download separately and remain available after offline reload", async ({ page, context }) => {
  const db = new Database(process.env.DATABASE_URL!, process.env.DATABASE_SCHEMA!);
  try {
    const service = new Service(db);
    const base = (await service.orders()).find((o) => o.outlet_id === "OUT001")!;
    const image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0XcAAAAASUVORK5CYII=";
    await service.save({ ...base, id: "PROOF-OFFLINE-TEST", day: "2026-02-14", status: "delivered", proof: { outcome: "delivered", count: base.units, note: "", photo: image, signature: image } });
  } finally { await db.close(); }
  await login(page, "store");
  const raw = await (await page.request.get("/api/state")).text();
  expect(raw).not.toContain("data:image/");
  await page.locator("article").filter({ has: page.getByText("PROOF-OFFLINE-TEST", { exact: true }) }).getByRole("button", { name: "View receipt →", exact: true }).click();
  const photo = page.getByAltText("Delivery proof", { exact: true });
  await expect(photo).toBeVisible();
  await expect.poll(() => photo.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await expect(page.getByText("Proof saved on this device.")).toBeVisible();
  await context.setOffline(true);
  await page.reload();
  await page.locator("article").filter({ has: page.getByText("PROOF-OFFLINE-TEST", { exact: true }) }).getByRole("button", { name: "View receipt →", exact: true }).click();
  await expect(photo).toBeVisible();
  await expect.poll(() => photo.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await context.setOffline(false);
});

test("database connection outage retains a queued command and retries the same ID once", async ({ page }) => {
  test.skip(!process.env.TEST_DB_PROXY_CONTROL, "Network outage injection requires local plaintext PostgreSQL.");
  const control = async (action: string) => {
    const response = await fetch(`${process.env.TEST_DB_PROXY_CONTROL}/${action}`, { method: "POST", headers: { Authorization: `Bearer ${process.env.TEST_DB_PROXY_TOKEN}` } });
    expect(response.ok).toBe(true);
  };
  await login(page, "store");
  const commands: Record<string, unknown>[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/command") commands.push(request.postDataJSON());
  });
  try {
    await control("pause");
    await page.getByRole("button", { name: "＋ Place an order", exact: true }).click();
    await page.locator('input[name="units"]').fill("3");
    await page.locator('input[name="weight"]').fill("15");
    await page.locator('input[name="volume"]').fill("0.1");
    const failure = page.waitForResponse((r) => new URL(r.url()).pathname === "/api/command" && r.status() === 500);
    await page.getByRole("button", { name: "Submit order", exact: true }).click();
    await failure;
    await page.getByRole("button", { name: "Sync queue", exact: true }).click();
    await expect(page.getByText("Waiting to send", { exact: true })).toHaveCount(1);
    const saved = await page.evaluate(async () => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const open = indexedDB.open("waypoint-v1", 1);
        open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error);
      });
      const queue = await new Promise<any[]>((resolve, reject) => {
        const request = db.transaction("records").objectStore("records").get("queue:store@waypoint.local");
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      db.close(); return queue;
    });
    expect(saved).toHaveLength(1);
    expect(saved[0].command.id).toBe(commands[0].id);
    await control("resume");
    await page.getByRole("button", { name: "Sync now ↻", exact: true }).click();
    await expect(page.getByText("Everything is synced", { exact: true })).toBeVisible();
    expect(commands.length).toBeGreaterThanOrEqual(2);
    expect(new Set(commands.map((c) => c.id)).size).toBe(1);
    const replay = await page.request.post("/api/command", { data: saved[0].command });
    expect(replay.ok()).toBe(true);
    const result = await replay.json();
    const state = await (await page.request.get("/api/state")).json();
    expect(state.orders.filter((o: any) => o.id === result.order_id)).toHaveLength(1);
    expect(state.events.filter((e: any) => e.order_id === result.order_id && e.kind === "order")).toHaveLength(1);
  } finally { await control("resume"); }
});

test("dispatcher resolves a failed delivery with a linked replacement", async ({ page }, testInfo) => {
  await login(page, "dispatcher");
  await page.getByLabel("Run day", { exact: true }).selectOption("2026-02-09");
  await page.getByRole("button", { name: "Live runs", exact: true }).click();
  await page.getByRole("button", { name: "Resolve delivery exception", exact: true }).first().click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Replacement weight (kg)").fill("1");
  await dialog.getByLabel("Replacement volume (m³)").fill("0.1");
  await dialog.getByLabel("Agreement and follow-up details").fill("Store agreed to replacement on the next eligible run.");
  await page.screenshot({ path: testInfo.outputPath("exception-resolution.png") });
  await dialog.getByRole("button", { name: "Confirm resolution", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  const state = await (await page.request.get("/api/state")).json();
  const resolved = state.orders.find((o: any) => o.exception_resolution?.decision === "redeliver");
  expect(resolved).toBeTruthy();
  expect(resolved.proof).toBeTruthy();
  expect(state.orders.filter((o: any) => o.parent_order_id === resolved.id)).toHaveLength(1);
});
