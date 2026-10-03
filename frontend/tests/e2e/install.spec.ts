import { expect, test, type Page } from "@playwright/test";

// Issue #201: each field role address installs as its own app, and what an
// installed app shows besides data (icons, fonts, images) stays available with
// no network. Chromium resolves every *.localhost name to this machine, so
// driver.waypoint.localhost reaches the test server as a driver address would.

const roleAddress = (label: string, baseURL: string | undefined) => `http://${label}.waypoint.localhost:${new URL(baseURL!).port}/`;

async function signedOut(page: Page) {
  await page.route("**/api/**", (route) => route.fulfill({ status: 401, contentType: "application/problem+json", json: { status: 401, code: "unauthenticated" } }));
}

async function withWorker(page: Page) {
  await page.evaluate(() => navigator.serviceWorker.register("/sw.js").then(() => navigator.serviceWorker.ready).then(() => undefined));
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
}

for (const [label, name] of [["driver", "Waypoint Driver"], ["loader", "Waypoint Loader"], ["store", "Waypoint Store"]] as const) {
  test(`${label}'s address installs as ${name}`, async ({ page, baseURL }) => {
    await signedOut(page);
    await page.goto(roleAddress(label, baseURL));
    const href = await page.locator('link[rel="manifest"]').getAttribute("href");
    const manifest = await page.evaluate((h) => fetch(h!).then((r) => r.json()), href);
    expect(manifest.name).toBe(name);
    expect(manifest.display).toBe("standalone");
    for (const icon of manifest.icons as Array<{ src: string }>) {
      expect(await page.evaluate((src) => fetch(src).then((r) => r.status), icon.src), icon.src).toBe(200);
    }

    await withWorker(page);
    const cdp = await page.context().newCDPSession(page);
    const { installabilityErrors } = await cdp.send("Page.getInstallabilityErrors");
    expect(installabilityErrors).toEqual([]);
  });
}

test("icons stay available with no network; API reads never come from the worker", async ({ context, page, baseURL }) => {
  await signedOut(page);
  await page.goto(roleAddress("driver", baseURL));
  await withWorker(page);
  const status = (path: string) => page.evaluate((p) => fetch(p).then((r) => r.status, () => "failed"), path);
  expect(await status("/icons/go/truck.svg")).toBe(200);
  expect(await status("/manifest.webmanifest")).toBe(200);

  await context.setOffline(true);
  expect(await status("/icons/go/truck.svg")).toBe(200);
  expect(await status("/manifest.webmanifest")).toBe(200);
  // The page route answers /api for the page; offline, a read the worker kept would answer too. None is kept.
  await page.unroute("**/api/**");
  expect(await status("/api/session")).toBe("failed");
  await context.setOffline(false);
});
