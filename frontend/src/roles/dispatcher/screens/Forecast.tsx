"use client";

import { useMemo, useState } from "react";
import { FilterTabs, KpiCard, Notice } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import Refusal from "./Refusal.tsx";
import ForecastChart, { seriesColour } from "./ForecastChart.tsx";
import { DayNeeds, ForecastActions } from "./ForecastSide.tsx";
import {
  WEEKS,
  actions,
  combine,
  dayNeeds,
  forBrand,
  kpis,
  m3,
  pct,
  segments,
  weekLabel,
  type BrandFilter,
} from "../data/forecast.ts";
import { formatDay } from "../data/scope.ts";
import { useForecast } from "../data/useForecast.ts";

// Figma "Forecast": the next ten weeks of demand for the depots in view, against
// what the fleet can carry. Read from /api/ml/forecast/overview (#16). Container
// only; the chart and the side cards are views.

export default function Forecast({
  depots,
  scopeLabel,
  online,
}: {
  depots: string[];
  scopeLabel: string;
  online: boolean;
}): React.JSX.Element {
  const resource = useForecast(depots);
  const [brand, setBrand] = useState<BrandFilter>("all");
  const forecast = useMemo(() => (resource.data ? combine(resource.data) : null), [resource.data]);
  const weeks = useMemo(() => (forecast ? forBrand(forecast.weeks, brand) : []), [forecast, brand]);
  const k = kpis(weeks);
  const ready = forecast?.status === "READY";
  const loading = !forecast;

  const brandOptions = [
    { value: "all", label: "All brands" },
    ...(forecast?.brandCodes ?? []).map((code) => ({ value: code, label: code })),
  ];
  const updated = forecast?.generatedAt ? formatDay(forecast.generatedAt.slice(0, 10)) : null;

  return (
    <>
      <PageHeader
        title="Forecast"
        subtitle={`Next ${WEEKS} weeks · ${scopeLabel}${updated ? ` · updated ${updated}` : ""}`}
        online={online}
        lastSyncedAt={resource.loadedAt}
        tools={<FilterTabs label="Brand" options={brandOptions} value={brand} onChange={setBrand} />}
      />

      {resource.error && !forecast && <Refusal error={resource.error} what="the forecast" />}
      {forecast && !ready && (
        <Notice tone="info" title="No forecast yet">
          The forecast job runs within the hour and then every Monday morning.
        </Notice>
      )}
      {ready && forecast.degraded && (
        <Notice tone="warning" title="The demand model is not answering">
          These weeks are averages of recent orders by weekday, not the model&apos;s forecast. They miss festival
          ramps and trend.
        </Notice>
      )}

      {(loading || ready) && (
        <>
          <div className="flex w-full gap-3.5 max-md:flex-col">
            <KpiCard
              label={k.next ? `Next week · ${weekLabel(k.next.week)}` : "Next week"}
              value={k.next ? m3(k.next.week.total) : "…"}
              note={
                k.next
                  ? `${pct(k.next.chilledShare)} chilled${k.next.topBrand ? ` · ${k.next.topBrand.code} ${pct(k.next.topBrand.share)}` : ""}`
                  : undefined
              }
            />
            <KpiCard
              label={k.peak ? `Peak week · ${weekLabel(k.peak.week)}${k.peak.week.festival ? ` ${k.peak.week.festival}` : ""}` : "Peak week"}
              value={k.peak ? m3(k.peak.week.total) : "…"}
              valueClassName="text-go-danger"
              note={k.peak ? `+${pct(k.peak.overMedian)} on a typical week` : undefined}
            />
            <KpiCard
              label={k.busiestDay ? `Busiest day · ${weekLabel(k.busiestDay.week)}` : "Busiest day"}
              value={k.busiestDay ? `≈ ${m3(k.busiestDay.m3)}` : "…"}
              note="An average operating day of that week"
            />
            <KpiCard
              label={k.chilled ? `Chilled in ${weekLabel(k.chilled.week)}` : "Chilled"}
              value={k.chilled ? m3(k.chilled.week.chilled) : "…"}
              valueClassName={k.chilled && k.chilled.share >= 0.95 ? "text-go-danger" : "text-go-ink"}
              note={k.chilled ? `${pct(k.chilled.share)} of refrigerated vehicle capacity` : undefined}
            />
          </div>

          <div className="flex w-full gap-5 max-lg:flex-col">
            <section aria-label="Weekly demand" className="flex min-w-0 flex-1 flex-col gap-3 rounded-go-panel bg-go-card p-5 shadow-go-card">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="m-0 text-[16px] font-semibold text-go-ink">{`Weekly demand · ${scopeLabel}`}</h2>
                  <p className="m-0 text-[11px] text-go-secondary">m³ per week · dashed = fleet capacity</p>
                </div>
                <Legend weeks={weeks} />
              </div>
              {loading ? (
                <p className="py-10 text-center text-[13px] text-go-secondary">Loading the forecast…</p>
              ) : (
                <ForecastChart weeks={weeks} />
              )}
              <p className="m-0 text-[11px] text-go-secondary">
                {forecast?.modelLabel
                  ? forecast.degraded
                    ? "Recent weekday averages · the demand model did not answer"
                    : `${forecast.modelLabel} · weekly demand model, refreshed every Monday`
                  : " "}
              </p>
            </section>
            <div className="flex min-w-[300px] flex-col gap-5 lg:max-w-[380px]">
              <DayNeeds rows={dayNeeds(weeks)} />
              <ForecastActions actions={actions(weeks)} />
            </div>
          </div>
        </>
      )}
    </>
  );
}

function Legend({ weeks }: { weeks: Parameters<typeof segments>[0][] }): React.JSX.Element {
  const seen = new Map<string, string>();
  for (const w of weeks) for (const s of segments(w)) if (!seen.has(s.id)) seen.set(s.id, s.label);
  return (
    <ul className="m-0 flex list-none flex-wrap gap-3 p-0 text-[11px] text-go-secondary">
      {[...seen].map(([id, label]) => (
        <li key={id} className="flex items-center gap-1.5">
          <span aria-hidden className="inline-block size-2.5 rounded-[3px]" style={{ background: seriesColour(id) }} />
          {label}
        </li>
      ))}
    </ul>
  );
}
