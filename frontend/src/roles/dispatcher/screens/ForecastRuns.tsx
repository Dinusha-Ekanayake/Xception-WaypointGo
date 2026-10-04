"use client";

import { useEffect, useState } from "react";
import { Pill } from "@shared/ui";
import { countdown } from "../data/forecast.ts";
import { depotStamp } from "../data/scope.ts";
import { nowMs } from "@shared/wording";

// The forecast job's last and next run (P-29), in depot time, with a countdown
// to the next. Once the time comes the screen reads again until the run shows.

const RECHECK_MS = 20_000;

export default function ForecastRuns({
  generatedAt,
  nextRunAt,
  modelLabel,
  degraded,
  onDue,
}: {
  generatedAt: string | null;
  nextRunAt: string | null;
  modelLabel: string | null;
  degraded: boolean;
  /** Reads the forecast again; called every 20 seconds while a run is due. */
  onDue: () => void;
}): React.JSX.Element {
  const [now, setNow] = useState(() => nowMs());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(nowMs()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const left = nextRunAt ? countdown(Date.parse(nextRunAt) - now) : null;
  const due = nextRunAt !== null && left === null;

  useEffect(() => {
    if (!due) return;
    const timer = window.setInterval(onDue, RECHECK_MS);
    return () => window.clearInterval(timer);
  }, [due, onDue]);

  return (
    <section
      aria-label="Forecast runs"
      className="flex w-full flex-wrap items-center gap-x-6 gap-y-3 rounded-go-panel bg-go-card px-5 py-3 shadow-go-card"
    >
      <Run label="Last run">
        {generatedAt ? (
          <>
            <time dateTime={generatedAt} className="text-[13px] font-semibold text-go-ink">
              {depotStamp(new Date(generatedAt))}
            </time>
            {degraded && <Pill tone="warning">Recent averages</Pill>}
          </>
        ) : (
          <span className="text-[13px] font-semibold text-go-secondary">Not run yet</span>
        )}
      </Run>

      <span aria-hidden className="h-8 w-px bg-go-divider max-sm:hidden" />

      <Run label="Next run">
        {nextRunAt ? (
          <>
            <time dateTime={nextRunAt} className="text-[13px] font-semibold text-go-ink">
              {depotStamp(new Date(nextRunAt))}
            </time>
            {due ? (
              <Pill tone="info">
                <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-go-info" />
                Running now
              </Pill>
            ) : (
              <span
                role="timer"
                aria-label={`Next run in ${left}`}
                className="rounded-full bg-go-surface px-[9px] py-[3px] text-[11px] font-medium text-go-ink tabular-nums"
              >
                {`in ${left}`}
              </span>
            )}
          </>
        ) : (
          <span className="text-[13px] font-semibold text-go-secondary">Unknown</span>
        )}
      </Run>

      <p className="m-0 ml-auto text-[11px] text-go-secondary max-md:ml-0">
        Weekly on Monday at midnight, depot time · checked hourly
      </p>
    </section>
  );
}

function Run({ label, children }: { label: string; children: React.ReactNode }): React.JSX.Element {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-[11px] font-medium tracking-wide text-go-secondary uppercase">{label}</span>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}
