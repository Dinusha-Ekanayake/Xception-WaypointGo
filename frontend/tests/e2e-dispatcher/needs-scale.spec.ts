import { expect, test } from "@playwright/test";
import type { ReadyTripView, RunSheetView } from "../../src/shared/domain/types.ts";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// At 80 to 1,280 trips "Needs you" lists trips, not lines: a trip's cards fold
// under its most urgent one, and only the first twelve are shown until asked.

test("needs you shows twelve trips, folds a trip's cards together and shows the rest on request", async ({ page }) => {
  const day = liveDay();
  const base = day.sheets![0]!; // VEH020: OUT063 may miss its window
  const baseDock = day.dock!.find((t) => t.vehicleId === base.vehicleId)!;
  const extra: RunSheetView[] = [];
  const extraDock: ReadyTripView[] = [];
  for (let i = 0; i < 15; i++) {
    const vehicleId = `VEH${String(100 + i)}`;
    extra.push({
      ...base,
      vehicleId,
      stops: base.stops.map((s, n) => ({
        ...s,
        deliveryId: `${s.deliveryId}-${i}`,
        tripId: `t-${vehicleId}`,
        outletId: `${s.outletId}${i}`,
        // VEH100 left its first stop before the store answered, and its second may miss its window: two cards, one trip.
        ...(i === 0 && n === 0 ? { storeAnswerWaived: "store_absent" as const } : {}),
      })),
    });
    extraDock.push({ ...baseDock, vehicleId, tripId: `t-${vehicleId}` });
  }
  await page.clock.install({ time: new Date(LIVE_NOW) });
  await serve(page, { ...day, sheets: [...day.sheets!, ...extra], dock: [...day.dock!, ...extraDock] });
  await page.goto("/#/live");

  const needs = page.getByRole("region", { name: "Needs you" });
  await expect(needs.getByRole("listitem")).toHaveCount(12);
  const more = needs.getByRole("button", { name: /^Show \d+ more trips?$/ });
  await expect(more).toBeVisible();
  const total = Number((await needs.getByRole("heading").innerText()).match(/(\d+) items/)?.[1]);
  await more.click();
  await expect(needs.getByRole("listitem")).toHaveCount(total);
  await expect(more).toHaveCount(0);

  // VEH100: the window card leads; the stop left before the store answered is folded under it.
  const veh100 = needs.getByRole("listitem").filter({ hasText: "OUT0630 · may miss its window" });
  await expect(veh100).not.toContainText("OUT0610 · left before the store answered");
  await veh100.getByRole("button", { name: "+1 more on this trip" }).click();
  await expect(veh100).toContainText("OUT0610 · left before the store answered");
  await expect(veh100.getByRole("button", { name: "Show less" })).toHaveAttribute("aria-expanded", "true");
});
