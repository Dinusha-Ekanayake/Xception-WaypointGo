import type { Decimal, ForecastOverviewView } from "@shared/domain/types";

// The Forecast screen's arithmetic over /api/ml/forecast/overview (#16): the
// depots in view combined, a brand picked out, and what the screen shows on top.
// Pure, so tests/dispatcher-forecast.test.ts covers it without a browser; the
// read itself is useForecast.ts.

export const WEEKS = 10;

export type BrandFilter = "all" | string;

export type ForecastWeek = {
  key: number;
  /** 1 for next week. */
  index: number;
  isoYear: number;
  isoWeek: number;
  start: string;
  operatingDays: number;
  holidayDays: number;
  paydays: number;
  festival: string | null;
  generatedDays: number;
  brands: Array<{ code: string; total: number; chilled: number }>;
  total: number;
  chilled: number;
  fleetM3: number;
  refrigeratedM3: number;
  vehicles: number;
  refrigeratedVehicles: number;
};

export type Forecast = {
  status: "READY" | "NONE";
  /** "name@version", "deterministic", "mixed", or null with no run. */
  modelLabel: string | null;
  degraded: boolean;
  generatedAt: string | null;
  /** The earliest next run across the depots in view; one run serves them all. */
  nextRunAt: string | null;
  weeks: ForecastWeek[];
  brandCodes: string[];
};

export function num(value: Decimal | number | null | undefined): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
}

/** Sums the depots in view week by week: demand and fleet capacity both ("Both" shows combined totals). */
export function combine(overviews: ForecastOverviewView[]): Forecast {
  const byKey = new Map<number, ForecastWeek>();
  const brandCodes = new Set<string>();
  for (const o of overviews) {
    for (const w of o.weeks) {
      const key = w.isoYear * 100 + w.isoWeek;
      const into = byKey.get(key) ?? {
        key,
        index: 0,
        isoYear: w.isoYear,
        isoWeek: w.isoWeek,
        start: w.weekStart,
        operatingDays: w.operatingDays,
        holidayDays: w.holidayDays,
        paydays: w.paydays,
        festival: w.festival,
        generatedDays: w.generatedDays,
        brands: [],
        total: 0,
        chilled: 0,
        fleetM3: 0,
        refrigeratedM3: 0,
        vehicles: 0,
        refrigeratedVehicles: 0,
      };
      for (const b of w.brands) {
        brandCodes.add(b.brandCode);
        const existing = into.brands.find((x) => x.code === b.brandCode);
        if (existing) {
          existing.total += num(b.totalM3);
          existing.chilled += num(b.chilledM3);
        } else {
          into.brands.push({ code: b.brandCode, total: num(b.totalM3), chilled: num(b.chilledM3) });
        }
      }
      into.total += num(w.totalM3);
      into.chilled += num(w.chilledM3);
      into.fleetM3 += num(w.capacity.fleetM3);
      into.refrigeratedM3 += num(w.capacity.refrigeratedM3);
      into.vehicles += w.capacity.vehicles;
      into.refrigeratedVehicles += w.capacity.refrigeratedVehicles;
      into.festival = into.festival ?? w.festival;
      byKey.set(key, into);
    }
  }
  const weeks = [...byKey.values()].sort((a, b) => a.key - b.key).map((w, i) => ({ ...w, index: i + 1 }));
  const labels = [...new Set(overviews.map((o) => o.modelLabel).filter((l): l is string => !!l))];
  const generated = overviews.map((o) => o.generatedAt).filter((g): g is string => !!g).sort();
  const next = overviews.map((o) => o.nextRunAt).filter((n): n is string => !!n).sort();
  return {
    status: overviews.some((o) => o.status === "READY") ? "READY" : "NONE",
    modelLabel: labels.length === 0 ? null : labels.length === 1 ? labels[0] : "mixed",
    degraded: overviews.some((o) => o.degraded),
    generatedAt: generated.length ? generated[generated.length - 1] : null,
    nextRunAt: next.length ? next[0] : null,
    weeks,
    brandCodes: [...brandCodes].sort(),
  };
}

/** One brand's demand, or everything; capacity is the fleet's either way. */
export function forBrand(weeks: ForecastWeek[], brand: BrandFilter): ForecastWeek[] {
  if (brand === "all") return weeks;
  return weeks.map((w) => {
    const brands = w.brands.filter((b) => b.code === brand);
    return {
      ...w,
      brands,
      total: brands.reduce((s, b) => s + b.total, 0),
      chilled: brands.reduce((s, b) => s + b.chilled, 0),
    };
  });
}

export type Segment = { id: string; label: string; m3: number };

/** The stack for one bar, bottom up: chilled first (it rides only in reefers), then each brand's ambient. */
export function segments(week: ForecastWeek): Segment[] {
  const chilledBy = week.brands.filter((b) => b.chilled > 0);
  const out: Segment[] = chilledBy.map((b) => ({ id: `${b.code}-chilled`, label: `${b.code} chilled`, m3: b.chilled }));
  for (const b of week.brands) {
    const ambient = b.total - b.chilled;
    if (ambient > 0) {
      out.push({ id: `${b.code}-ambient`, label: b.chilled > 0 ? `${b.code} ambient` : b.code, m3: ambient });
    }
  }
  return out;
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

export function perOperatingDay(m3: number, days: number): number {
  return days > 0 ? m3 / days : 0;
}

export type Kpis = {
  next: { week: ForecastWeek; chilledShare: number; topBrand: { code: string; share: number } | null } | null;
  peak: { week: ForecastWeek; overMedian: number } | null;
  busiestDay: { week: ForecastWeek; m3: number } | null;
  chilled: { week: ForecastWeek; share: number } | null;
};

export function kpis(weeks: ForecastWeek[]): Kpis {
  if (weeks.length === 0) return { next: null, peak: null, busiestDay: null, chilled: null };
  const next = weeks[0];
  const top = [...next.brands].sort((a, b) => b.total - a.total)[0];
  const peak = weeks.reduce((a, b) => (b.total > a.total ? b : a));
  const typical = median(weeks.map((w) => w.total));
  const busiest = weeks.reduce((a, b) =>
    perOperatingDay(b.total, b.operatingDays) > perOperatingDay(a.total, a.operatingDays) ? b : a,
  );
  const pressure = weeks.reduce((a, b) => (share(b.chilled, b.refrigeratedM3) > share(a.chilled, a.refrigeratedM3) ? b : a));
  return {
    next: {
      week: next,
      chilledShare: share(next.chilled, next.total),
      topBrand: top && next.total > 0 ? { code: top.code, share: top.total / next.total } : null,
    },
    peak: { week: peak, overMedian: typical > 0 ? peak.total / typical - 1 : 0 },
    busiestDay: { week: busiest, m3: perOperatingDay(busiest.total, busiest.operatingDays) },
    chilled: { week: pressure, share: share(pressure.chilled, pressure.refrigeratedM3) },
  };
}

export function share(part: number, whole: number): number {
  return whole > 0 ? part / whole : 0;
}

export type DayNeed = {
  week: ForecastWeek;
  m3: number;
  vehiclesNeeded: number;
  vehicles: number;
  refrigeratedNeeded: number;
  refrigeratedVehicles: number;
  drivers: number;
};

/**
 * An average operating day of the heaviest weeks, against the fleet. A vehicle
 * carries its mean volume twice a day (R-PLN-07), the same assumption as the
 * capacity line (A-40); estimates, not a plan.
 */
export function dayNeeds(weeks: ForecastWeek[], limit = 4): DayNeed[] {
  return weeks
    .filter((w) => w.operatingDays > 0)
    .map((w) => {
      const m3 = perOperatingDay(w.total, w.operatingDays);
      const perVehicle = w.vehicles > 0 ? w.fleetM3 / w.operatingDays / w.vehicles : 0;
      const vehiclesNeeded = perVehicle > 0 ? Math.ceil(m3 / perVehicle) : 0;
      return {
        week: w,
        m3,
        vehiclesNeeded,
        vehicles: w.vehicles,
        refrigeratedNeeded: refrigeratedNeeded(w),
        refrigeratedVehicles: w.refrigeratedVehicles,
        drivers: vehiclesNeeded,
      };
    })
    .sort((a, b) => b.m3 - a.m3)
    .slice(0, limit)
    .sort((a, b) => a.week.key - b.week.key);
}

/**
 * Refrigerated vehicles an average operating day of the week needs: its chilled
 * demand per day over what one refrigerated vehicle carries a day (two trips of
 * the mean refrigerated volume, A-40).
 */
export function refrigeratedNeeded(w: ForecastWeek): number {
  if (w.operatingDays <= 0 || w.refrigeratedVehicles <= 0 || w.refrigeratedM3 <= 0) return 0;
  const perVehicleDay = w.refrigeratedM3 / w.operatingDays / w.refrigeratedVehicles;
  return Math.ceil(perOperatingDay(w.chilled, w.operatingDays) / perVehicleDay);
}

/** Capacity this close to the busiest week is drawn on the chart; further above, it would flatten every bar. */
export const CAPACITY_IN_VIEW = 1.6;

export type Scale = { top: number; ticks: number[]; capacityOnScale: boolean };

/**
 * The value axis for a demand chart with capacity beside it. Capacity is drawn
 * on the scale only when it is within CAPACITY_IN_VIEW of the busiest week, as
 * in the design; otherwise the scale follows demand, so the weeks can be told
 * apart, and the chart states capacity instead of drawing it. The top is a
 * round number with round ticks ("0, 600, 1200, 1800").
 */
export function focusScale(demand: number[], capacity: number[], steps = 3): Scale {
  const peak = Math.max(0, ...demand);
  const cap = Math.max(0, ...capacity);
  const capacityOnScale = cap > 0 && cap <= Math.max(peak, 1) * CAPACITY_IN_VIEW;
  const raw = Math.max(1, capacityOnScale ? Math.max(peak, cap) : peak) * 1.1;
  const step = niceStep(raw / steps);
  const top = Math.ceil(raw / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 100) / 100);
  return { top, ticks, capacityOnScale };
}

function niceStep(x: number): number {
  const p = 10 ** Math.floor(Math.log10(x));
  const f = x / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
}

export type Action = { tone: "danger" | "warning" | "success"; title: string; detail: string };

/** What the numbers suggest doing, most urgent first. Rules, not a model; each names its week. */
export function actions(weeks: ForecastWeek[], limit = 4): Action[] {
  const out: Action[] = [];
  const typicalDays = median(weeks.map((w) => w.operatingDays).filter((d) => d > 0));
  for (const w of weeks) {
    const chilledLoad = share(w.chilled, w.refrigeratedM3);
    if (w.refrigeratedM3 > 0 && chilledLoad >= 0.95) {
      const need = dayNeeds([w], 1)[0];
      out.push({
        tone: "danger",
        title: `${weekLabel(w)}: keep all refrigerated vehicles out`,
        detail: `Chilled is ${pct(chilledLoad)} of refrigerated capacity${need ? `, about ${need.refrigeratedNeeded} of ${need.refrigeratedVehicles} on an average day` : ""}`,
      });
    }
    if (w.fleetM3 > 0 && w.total > w.fleetM3) {
      out.push({
        tone: "warning",
        title: `${weekLabel(w)} is about ${m3(w.total - w.fleetM3)} over weekly capacity`,
        detail: "Pre-deliver ambient brands the week before",
      });
    }
    if (w.operatingDays > 0 && typicalDays > 0 && w.operatingDays < typicalDays) {
      out.push({
        tone: "success",
        title: `${weekLabel(w)} has only ${w.operatingDays} operating days`,
        detail: `+${Math.round((typicalDays / w.operatingDays - 1) * 100)}% load per day`,
      });
    }
  }
  const order = { danger: 0, warning: 1, success: 2 } as const;
  return out.sort((a, b) => order[a.tone] - order[b.tone]).slice(0, limit);
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function weekLabel(w: ForecastWeek): string {
  return `Wk ${w.index}`;
}

/** "12 Oct", the week's Monday. */
export function weekDate(w: ForecastWeek): string {
  const [, month, day] = w.start.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]}`;
}

export function m3(value: number): string {
  return `${Math.round(value).toLocaleString("en-GB")} m³`;
}

export function pct(value: number): string {
  return `${Math.round(value * 100)}%`;
}

/**
 * Time left until a run, for a countdown that ticks every second: "2 d 9 h",
 * "9 h 05 min", then "23:41" in the last hour. Null once the time has come.
 */
export function countdown(ms: number): string | null {
  if (ms <= 0) return null;
  const s = Math.ceil(ms / 1000);
  const days = Math.floor(s / 86_400);
  const hours = Math.floor((s % 86_400) / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = s % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  if (days > 0) return `${days} d ${hours} h`;
  if (hours > 0) return `${hours} h ${two(minutes)} min`;
  return `${two(minutes)}:${two(seconds)}`;
}

/** The model registry's view as the error chip needs it (GET /api/ml/models). */
export type ModelMetrics = { name: string; version: string; kind: string; status: string; metrics: Record<string, string | number> };

const plusMinus = (value: string | number | undefined): string | null => {
  const n = Number(value);
  return value === undefined || !Number.isFinite(n) ? null : `±${Math.round(n * 100)}%`;
};

/**
 * The Figma "Forecast error" chip, from what the demand model measured when it
 * was validated (issue #119). The registry holds the error over all volume and
 * over chilled volume (WAPE), not per brand, so the chip says those two and no
 * more. A fallback run has no measured error and says so.
 */
export function forecastError(models: ModelMetrics[], modelLabel: string | null, degraded: boolean): string {
  if (degraded || !modelLabel) return "Recent averages · forecast error not measured";
  const model =
    models.find((m) => `${m.name}@${m.version}` === modelLabel) ??
    models.find((m) => m.kind === "demand_forecast" && m.status === "ACTIVE");
  const total = plusMinus(model?.metrics.total_wape);
  const chilled = plusMinus(model?.metrics.chilled_wape);
  if (!total && !chilled) return "Forecast error not reported by the model";
  return ["Forecast error", total && `${total} total`, chilled && `${chilled} chilled`].filter(Boolean).join(" · ");
}
