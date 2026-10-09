import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";
import { translate } from "../../src/roles/store/data/strings.ts";

// Roadmap FF-00: a store manager who reads Sinhala or Tamil receives a delivery in
// that language, from Home through the count to the confirmation. The choice is
// the one made in Settings, kept on the device; what is sent does not change.

for (const lang of ["si", "ta"] as const) {
  const t = (english: string, vars?: Record<string, string | number>) => translate(lang, english, vars);

  test(`a delivery received in ${lang === "si" ? "Sinhala" : "Tamil"}`, async ({ page }, testInfo) => {
    await page.addInitScript((value) => window.localStorage.setItem("waypoint.lang", value), lang);
    const store = await mockStore(page);
    await page.goto("/");

    await expect(page.getByRole("button", { name: t("Receive delivery") }).first()).toBeVisible();
    await page.waitForTimeout(600);
    await page.screenshot({ path: testInfo.outputPath(`store-home-${lang}.png`), fullPage: true });

    await page.getByRole("button", { name: t("Receive delivery") }).first().click();
    await expect(page.getByRole("heading", { name: t("Receive delivery") })).toBeVisible();
    await expect(page.getByText(t("Report any issues, then submit count"))).toBeVisible();
    await expect(page.getByRole("heading", { name: t("Receipt summary") })).toBeVisible();
    await page.waitForTimeout(600);
    await page.screenshot({ path: testInfo.outputPath(`store-receive-${lang}.png`), fullPage: true });

    await page.getByRole("button", { name: t("Submit count") }).click();
    await expect(page.getByRole("dialog", { name: t("Enter this PIN on the driver's phone") })).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: testInfo.outputPath(`store-pin-${lang}.png`) });
    store.handover.current = { ...store.handover.current!, status: "CONFIRMED", confirmedAt: new Date().toISOString() };
    const confirmed = page.getByRole("dialog", { name: t("Delivery confirmed") });
    await expect(confirmed).toBeVisible({ timeout: 10_000 });
    // "5 units received · recorded at 05:42", in the store's language: the words before the time.
    await expect(confirmed).toContainText(t("{n} units received · recorded at {time}", { n: 5 }).split("{time}")[0]!.trim());
    await page.screenshot({ path: testInfo.outputPath(`store-confirmed-${lang}.png`) });

    // The words changed; what is sent did not.
    expect(store.sent.map((s) => s.kind)).toEqual(["receipt:Confirm"]);
  });
}
