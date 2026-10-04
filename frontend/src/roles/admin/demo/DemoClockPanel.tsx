"use client";

import { useEffect, useState } from "react";
import type { DemoView } from "@shared/domain/types";
import { dayLabel } from "@shared/wording/time";
import { card, field, secondary } from "../access/components";
import { addDays, clockWindow, depotAt, depotParts, inWindow, offsetText, STEPS, stepped } from "./clock";
import { setClock } from "./data";

// The demo clock (issue #231): where it reads, steps back and forward, the
// moments a demo needs, and any depot date and time. Every change is one
// demo:SetClock with the reason above; the server keeps the clock within seven
// days of real time and never before the last committed order close, and says
// so when a change is refused.

const PRESETS: Array<{ label: string; hhmm: string; hint: string; nextDay?: boolean }> = [
  { label: "Before cutoff 15:30", hhmm: "15:30", hint: "Stores can still order for the next run" },
  { label: "After cutoff 16:05", hhmm: "16:05", hint: "The dispatcher can close orders and plan" },
  { label: "Early morning 05:00", hhmm: "05:00", hint: "Loading and departures, on the demo day" },
  { label: "Next day 05:00", hhmm: "05:00", hint: "The morning after: loading the day just planned", nextDay: true },
];

export default function DemoClockPanel({
  view,
  busy,
  reason,
  run,
}: {
  view: DemoView;
  busy: boolean;
  reason: string;
  run: (label: string, action: (v: DemoView) => Promise<unknown>) => void;
}): React.JSX.Element {
  const now = depotParts(view.now);
  const window = clockWindow(view.now, view.offsetSeconds);
  const [date, setDate] = useState(now.date);
  const [time, setTime] = useState(now.time);
  const [touched, setTouched] = useState(false);

  // Until the presenter edits it, the custom field follows the clock.
  useEffect(() => {
    if (touched) return;
    setDate(now.date);
    setTime(now.time);
  }, [now.date, now.time, touched]);

  const target = depotAt(date, time);
  const targetOk = target !== null && inWindow(target, window);
  const minDate = depotParts(window.min).date;
  const maxDate = depotParts(window.max).date;

  const go = (label: string, at: Date) => run(label, (v) => setClock(v, at, reason));

  return (
    <section className={`${card} flex flex-col gap-4 p-5`} aria-label="Demo clock">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-lg font-medium text-go-ink">Demo clock</h2>
          <p className="text-sm text-go-secondary">Moves cutoff, closing, ETAs and lateness together. Sign-in and security keep real time.</p>
        </div>
        <div className="text-right">
          <p data-testid="demo-clock-now" className="text-2xl font-medium tabular-nums text-go-ink">
            {dayLabel(now.date)} · {now.time}
          </p>
          <p className="text-xs text-go-secondary">{offsetText(view.offsetSeconds)}</p>
        </div>
      </div>

      <div role="group" aria-label="Move the clock" className="flex flex-wrap items-center gap-2">
        {STEPS.map((s, i) => {
          const at = stepped(view.now, s.ms);
          const ok = inWindow(at, window);
          const when = depotParts(at);
          return (
            <span key={s.label} className="contents">
              {i === STEPS.length / 2 && <span aria-hidden className="mx-1 h-6 w-px bg-go-divider" />}
              <button
                type="button"
                className={`${secondary} tabular-nums`}
                disabled={busy || !ok}
                title={ok ? `To ${dayLabel(when.date)} · ${when.time}` : "Beyond the 7 days the demo clock may move"}
                aria-label={`${s.label.replace("-", "Back ").replace("+", "Forward ")}, to ${dayLabel(when.date)} ${when.time}`}
                onClick={() => go(`Clock ${s.label}`, at)}
              >
                {s.label}
              </button>
            </span>
          );
        })}
      </div>

      <div className="flex flex-wrap gap-2">
        {PRESETS.map((p) => {
          const at = depotAt(p.nextDay ? addDays(now.date, 1) : now.date, p.hhmm)!;
          return (
            <button key={p.label} type="button" title={p.hint} className={secondary} disabled={busy || !inWindow(at, window)}
              onClick={() => go(p.nextDay ? `Clock next day ${p.hhmm}` : `Clock ${p.hhmm}`, at)}>
              {p.label}
            </button>
          );
        })}
        <button type="button" className={secondary} disabled={busy || view.offsetSeconds === 0} onClick={() => go("Real time", new Date())}>
          Real time
        </button>
      </div>

      <form
        className="flex flex-wrap items-end gap-2"
        aria-label="Set the demo date and time"
        onSubmit={(e) => {
          e.preventDefault();
          if (target && targetOk) go(`Clock ${dayLabel(date)} ${time}`, target);
        }}
      >
        <label className="flex flex-col gap-1 text-sm text-go-secondary">
          Depot date
          <input
            className={field}
            type="date"
            value={date}
            min={minDate}
            max={maxDate}
            onChange={(e) => {
              setTouched(true);
              setDate(e.target.value);
            }}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-go-secondary">
          Depot time
          <input
            className={field}
            type="time"
            value={time}
            onChange={(e) => {
              setTouched(true);
              setTime(e.target.value);
            }}
          />
        </label>
        <button type="submit" className={secondary} disabled={busy || !targetOk}>
          Set date and time
        </button>
        {touched && (
          <button type="button" className="min-h-9 px-2 text-sm text-go-teal" onClick={() => setTouched(false)}>
            Back to the clock
          </button>
        )}
        <p className="basis-full text-xs text-go-secondary">
          {target && !targetOk
            ? `Choose between ${dayLabel(minDate)} and ${dayLabel(maxDate)}: the demo clock stays within 7 days of real time.`
            : `Any time from ${dayLabel(minDate)} to ${dayLabel(maxDate)}. Going back before an order day already closed is refused.`}
        </p>
      </form>
    </section>
  );
}
