import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";
import { translate } from "../../src/roles/driver/data/strings.ts";

// Roadmap FF-00: a driver who reads Sinhala or Tamil works the run in that
// language. The choice is the one made in Settings, kept on the phone; the
// commands sent are the same in every language.

for (const lang of ["si", "ta"] as const) {
  const t = (english: string, vars?: Record<string, string | number>) => translate(lang, english, vars);

  test(`a stop worked in ${lang === "si" ? "Sinhala" : "Tamil"}: Home, the run sheet, arriving and the handover`, async ({ page }, testInfo) => {
    await page.addInitScript((value) => window.localStorage.setItem("waypoint.lang", value), lang);
    const server = await serve(page);
    await page.goto("/");

    const start = page.getByRole("button", { name: t("Start trip") });
    await expect(start).toBeVisible();
    await expect(page.getByRole("heading", { name: t("Notifications") })).toBeVisible();
    await expect(page.getByText(t("Vehicle"), { exact: true })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`driver-home-${lang}.png`) });

    await start.click();
    await expect(page.getByText(t("Expected"), { exact: true })).toBeVisible();
    await expect(page.getByText(t("Window"), { exact: true })).toBeVisible();
    await expect(page.getByText(t("Next · stop {n} of {total}", { n: "01", total: "02" }))).toBeVisible();
    await expect(page.getByText(t("{n} units", { n: 12 }), { exact: true })).toBeVisible();
    const arrived = page.getByRole("slider", { name: t("I've arrived") });
    await expect(arrived).toBeVisible();
    // The screens crossfade; the picture is taken once the run sheet has settled.
    await page.waitForTimeout(600);
    await page.screenshot({ path: testInfo.outputPath(`driver-run-${lang}.png`) });

    await arrived.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByText(t("Unload the goods, then slide Hand over. The store checks what arrived and their report appears here."))).toBeVisible();
    await expect(page.getByRole("slider", { name: t("Hand over") })).toBeVisible();
    await page.waitForTimeout(600);
    await page.screenshot({ path: testInfo.outputPath(`driver-handover-${lang}.png`) });

    // The words changed; what is sent did not.
    await expect.poll(() => server.commands.map((c) => c.kind)).toEqual(["delivery:Start", "delivery:RecordArrival"]);
  });
}
