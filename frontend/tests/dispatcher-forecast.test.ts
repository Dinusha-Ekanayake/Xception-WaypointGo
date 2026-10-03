import assert from "node:assert/strict";
import { test } from "node:test";
import type { ForecastOverviewView, ForecastWeekView } from "../src/shared/domain/intelligence.ts";
import {
  actions,
  combine,
  countdown,
  dayNeeds,
  forBrand,
  kpis,
  segments,
  weekDate,
  weekLabel,
} from "../src/roles/dispatcher/data/forecast.ts";

function week(isoWeek: number, over: Partial<ForecastWeekView> & { fresh?: [number, number]; style?: number } = {}): ForecastWeekView {
  const [freshTotal, freshChilled] = over.fresh ?? [100, 30];
  const style = over.style ?? 50;
  const start = `2026-10-${String(5 + 7 * (isoWeek - 41)).padStart(2, "0")}`;
  return {
    isoYear: 2026,
    isoWeek,
    weekStart: start,
    operatingDays: 6,
    holidayDays: 0,
    paydays: 0,
    festival: null,
    generatedDays: 0,
    brands: [
      { brandCode: "Fresh", totalM3: String(freshTotal), chilledM3: String(freshChilled) },
      { brandCode: "Style", totalM3: String(style), chilledM3: "0" },
    ],
    totalM3: String(freshTotal + style),
    chilledM3: String(freshChilled),
    capacity: { vehicles: 10, refrigeratedVehicles: 2, fleetM3: "600", refrigeratedM3: "120" },
    ...over,
  };
}

function overview(depot: string, weeks: ForecastWeekView[], over: Partial<ForecastOverviewView> = {}): ForecastOverviewView {
  return {
    depotCode: depot,
    status: "READY",
    modelLabel: "datathon-task2a@2026.1",
    degraded: false,
    generatedAt: "2026-10-05T04:00:00Z",
    nextRunAt: "2026-10-11T18:30:00Z",
    weeks,
    ...over,
  };
}

test("both depots are summed week by week, demand and capacity alike", () => {
  const f = combine([overview("Kandy", [week(41)]), overview("Peliyagoda", [week(41)])]);
  assert.equal(f.weeks.length, 1);
  const w = f.weeks[0]!;
  assert.equal(w.total, 300);
  assert.equal(w.chilled, 60);
  assert.equal(w.fleetM3, 1200);
  assert.equal(w.vehicles, 20);
  assert.deepEqual(w.brands.find((b) => b.code === "Fresh"), { code: "Fresh", total: 200, chilled: 60 });
  assert.deepEqual(f.brandCodes, ["Fresh", "Style"]);
  assert.equal(weekLabel(w), "Wk 1");
});

test("depots answered by different models read as mixed, and any fallback marks the whole view degraded", () => {
  const f = combine([
    overview("Kandy", [week(41)]),
    overview("Peliyagoda", [week(41)], { modelLabel: "deterministic", degraded: true }),
  ]);
  assert.equal(f.modelLabel, "mixed");
  assert.equal(f.degraded, true);
});

test("no run anywhere is NONE, so the screen says there is no forecast yet", () => {
  const f = combine([overview("Kandy", [week(41)], { status: "NONE", modelLabel: null, generatedAt: null })]);
  assert.equal(f.status, "NONE");
  assert.equal(f.modelLabel, null);
});

test("a brand filter keeps the fleet's capacity but only that brand's demand", () => {
  const style = forBrand(combine([overview("Kandy", [week(41)])]).weeks, "Style")[0]!;
  assert.equal(style.total, 50);
  assert.equal(style.chilled, 0);
  assert.equal(style.fleetM3, 600);
});

test("a bar stacks chilled first, then each brand's ambient", () => {
  const w = combine([overview("Kandy", [week(41)])]).weeks[0]!;
  assert.deepEqual(
    segments(w).map((s) => [s.label, s.m3]),
    [["Fresh chilled", 30], ["Fresh ambient", 70], ["Style", 50]],
  );
});

test("the cards pick next week, the peak week, the busiest operating day and chilled pressure", () => {
  const weeks = combine([
    overview("Kandy", [
      week(41),
      week(42, { fresh: [300, 115], festival: "Deepavali" }),
      week(43, { operatingDays: 3, holidayDays: 3 }),
    ]),
  ]).weeks;
  const k = kpis(weeks);
  assert.equal(k.next!.week.isoWeek, 41);
  assert.equal(k.next!.topBrand!.code, "Fresh");
  assert.equal(k.peak!.week.isoWeek, 42);
  assert.equal(k.busiestDay!.week.isoWeek, 42, "350 over 6 days beats 150 over 3");
  assert.equal(k.chilled!.week.isoWeek, 42);
  assert.ok(Math.abs(k.chilled!.share - 115 / 120) < 1e-9);
});

test("an average operating day needs vehicles at two trips a day of the mean vehicle", () => {
  const need = dayNeeds(combine([overview("Kandy", [week(41, { fresh: [300, 60], style: 0 })])]).weeks)[0]!;
  // 300 m3 over 6 days = 50 a day; a vehicle carries 600 / 6 / 10 = 10 a day.
  assert.equal(need.m3, 50);
  assert.equal(need.vehiclesNeeded, 5);
  // 60 chilled over 6 days = 10; a reefer carries 120 / 6 / 2 = 10 a day.
  assert.equal(need.refrigeratedNeeded, 1);
  assert.equal(need.drivers, 5);
});

test("suggested actions put a full refrigerated fleet first, then overload, then short weeks", () => {
  const list = actions(combine([
    overview("Kandy", [
      week(41),
      week(42, { fresh: [700, 118] }),
      week(43, { operatingDays: 4 }),
    ]),
  ]).weeks);
  assert.equal(list[0]!.tone, "danger");
  assert.match(list[0]!.title, /Wk 2: keep all refrigerated vehicles out/);
  assert.equal(list[1]!.tone, "warning");
  assert.match(list[1]!.title, /over weekly capacity/);
  assert.equal(list[2]!.tone, "success");
  assert.match(list[2]!.detail, /\+50% load per day/);
});

test("a week reads as its Monday", () => {
  const w = combine([overview("Kandy", [week(42)])]).weeks[0]!;
  assert.equal(weekDate(w), "12 Oct");
});

test("the countdown reads in days, hours, then minutes and seconds, and ends at the run", () => {
  assert.equal(countdown(2 * 86_400_000 + 9 * 3_600_000 + 5_000), "2 d 9 h");
  assert.equal(countdown(9 * 3_600_000 + 5 * 60_000), "9 h 05 min");
  assert.equal(countdown(23 * 60_000 + 41_000), "23:41");
  assert.equal(countdown(400), "00:01", "a part second still counts");
  assert.equal(countdown(0), null);
});

test("the next run is the earliest across the depots in view", () => {
  const f = combine([
    overview("Kandy", [week(41)], { nextRunAt: "2026-10-11T18:30:00Z" }),
    overview("Peliyagoda", [week(41)], { nextRunAt: "2026-10-05T08:30:00Z" }),
  ]);
  assert.equal(f.nextRunAt, "2026-10-05T08:30:00Z");
});
