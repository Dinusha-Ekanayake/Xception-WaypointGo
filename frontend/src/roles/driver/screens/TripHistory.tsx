"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import type { RunSheetStopView, RunSheetView } from "@shared/domain/types";
import { SkeletonRows, cx } from "@shared/ui";
import { addDays, clock, dayLabel, depotToday } from "@shared/wording";
import { DAYS_SHOWN, tripsOf, type Trip } from "../data/history.ts";
import { useT } from "../i18n.tsx";

// "My trips": the driver's own trips of the last 7 days, from the profile sheet
// (agreed by @jv-ransika, 2026-10-23). Read only and online only: a past trip is
// for looking back, not for working offline, so with no signal the screen says so
// rather than show an old copy. One card per trip, its stores on a tap.

export default function TripHistory({
  load,
  online,
}: {
  load: (dates: string[], signal: AbortSignal) => Promise<RunSheetView[]>;
  online: boolean;
}): React.JSX.Element {
  const t = useT();
  const today = depotToday();
  const dates = Array.from({ length: DAYS_SHOWN }, (_, i) => addDays(today, -i));
  const read = useResource(online ? (signal: AbortSignal) => load(dates, signal) : null, `driver-history|${today}`);
  const trips = tripsOf(read.data ?? []);
  const days = [...new Set(trips.map((x) => x.date))];

  return (
    <div className="flex flex-col gap-4 px-5 pt-3 pb-10">
      <div className="flex flex-col gap-1">
        <h1 className="text-[28px] font-semibold leading-tight">{t("My trips")}</h1>
        <p className="text-[15px] text-go-secondary">{t("Your trips of the last 7 days.")}</p>
      </div>
      {!online ? (
        <p role="status" className="rounded-[18px] bg-go-warning-tint px-4 py-3 text-[15px] text-go-warning-text">
          {t("Past trips need a connection. They show here when the phone is online again.")}
        </p>
      ) : read.error ? (
        <div className="flex flex-col gap-2">
          <p role="alert" className="text-[15px] text-go-danger">{t("Could not read your trips.")}</p>
          <button type="button" onClick={read.refresh} className="self-start rounded-full bg-go-card px-4 py-2 text-[15px] font-medium text-go-teal">
            {t("Try again")}
          </button>
        </div>
      ) : read.loading && !read.data ? (
        <SkeletonRows rows={3} label={t("Loading your trips…")} />
      ) : trips.length === 0 ? (
        <p className="text-[15px] text-go-secondary">{t("No trips in the last 7 days.")}</p>
      ) : (
        days.map((day) => (
          <section key={day} aria-label={day === today ? t("Today") : dayLabel(day)} className="flex flex-col gap-2">
            <h2 className="text-[15px] font-medium text-go-secondary">{day === today ? t("Today") : dayLabel(day)}</h2>
            <ul className="flex flex-col gap-2">
              {trips.filter((x) => x.date === day).map((trip) => <TripCard key={trip.key} trip={trip} />)}
            </ul>
          </section>
        ))
      )}
    </div>
  );
}

function TripCard({ trip }: { trip: Trip }): React.JSX.Element {
  const t = useT();
  const [open, setOpen] = useState(false);
  const count = (o: RunSheetStopView["outcome"]) => trip.stops.filter((s) => s.outcome === o).length;
  const delivered = count("DELIVERED") + count("PARTIAL");
  const skipped = count("SKIPPED");
  const failed = count("FAILED");
  const left = trip.stops.map((s) => s.startedAt).filter((x): x is string => x !== null).sort()[0] ?? null;
  const done = trip.stops.map((s) => s.completedAt).filter((x): x is string => x !== null).sort().at(-1) ?? null;
  const units = trip.stops.reduce((n, s) => n + (s.deliveredUnits ?? 0), 0);
  const summary = [
    t(trip.stops.length === 1 ? "1 stop" : "{n} stops", { n: trip.stops.length }),
    delivered > 0 ? t("{n} delivered", { n: delivered }) : null,
    skipped > 0 ? t("{n} skipped", { n: skipped }) : null,
    failed > 0 ? t("{n} not delivered", { n: failed }) : null,
  ].filter(Boolean).join(" · ");
  return (
    <li className="rounded-[22px] bg-go-card shadow-go-float">
      <button type="button" aria-expanded={open} onClick={() => setOpen((v) => !v)} className="flex w-full flex-col gap-1 px-5 py-4 text-left">
        <span className="flex items-baseline justify-between gap-3">
          <span className="text-[19px] font-semibold">{`${trip.vehicleId} · ${t("Trip {n}", { n: trip.number })}`}</span>
          {left && <span className="text-[15px] text-go-secondary tabular-nums">{done ? `${clock(left)}-${clock(done)}` : clock(left)}</span>}
        </span>
        <span className="text-[15px] text-go-secondary">{summary}</span>
        {units > 0 && <span className="text-[15px] text-go-secondary">{t("{n} units handed over", { n: units })}</span>}
      </button>
      {open && (
        <ol aria-label={t("Stops")} className="flex flex-col gap-2 border-t border-go-rule px-5 py-3">
          {trip.stops.map((s) => (
            <li key={s.deliveryId} className="flex items-baseline justify-between gap-3 text-[15px]">
              <span className="font-medium">{`${s.sequence}. ${s.outletId}`}</span>
              <span className={cx("text-right", s.outcome === "FAILED" ? "text-go-danger" : s.outcome === "SKIPPED" ? "text-go-warning-text" : "text-go-secondary")}>
                {[t(OUTCOME[s.outcome] ?? "Not reached"), s.completedAt ? clock(s.completedAt) : null, s.deliveredUnits !== null ? t("{n} units", { n: s.deliveredUnits }) : null]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            </li>
          ))}
        </ol>
      )}
    </li>
  );
}

const OUTCOME: Partial<Record<RunSheetStopView["outcome"], string>> = {
  DELIVERED: "Delivered",
  PARTIAL: "Partly delivered",
  SKIPPED: "Skipped",
  FAILED: "Not delivered",
  ARRIVED: "Arrived",
  PENDING: "Not reached",
};
