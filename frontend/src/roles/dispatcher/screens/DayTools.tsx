"use client";

import { cx } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { depotToday } from "../data/scope.ts";

/**
 * The day a screen is looking at, one control on every dated dispatcher screen
 * (Plan, Live, a redelivery date). It reads in depot words ("Mon 5 Oct"), never
 * the browser's own date format, over the browser's date input, which keeps the
 * keyboard and screen-reader support of a native picker. `warnNotToday` marks
 * a screen where another day is easy to miss (Live, after planning tomorrow)
 * and offers the way back to today.
 */
export default function DayField({
  date,
  onDate,
  label = "Day",
  min,
  warnNotToday = false,
}: {
  date: string;
  onDate: (date: string) => void;
  label?: string;
  min?: string;
  warnNotToday?: boolean;
}): React.JSX.Element {
  const today = depotToday();
  const isToday = date === today;
  const warn = warnNotToday && !isToday;
  return (
    <span className="flex shrink-0 items-center gap-1.5">
      <label
        className={cx(
          "relative flex min-w-[140px] cursor-pointer flex-col rounded-go-card px-4 py-1.5 shadow-go-card focus-within:ring-2 focus-within:ring-go-teal",
          warn ? "bg-go-warning-tint" : "bg-go-card",
        )}
      >
        <span className={cx("text-[10px] font-medium tracking-wide uppercase", warn ? "text-go-warning-text" : "text-go-secondary")}>
          {warn ? `${label} · not today` : label}
        </span>
        <span className="text-sm font-medium text-go-ink">{`${dayLabel(date)}${isToday ? " · today" : ""}`}</span>
        <input
          type="date"
          aria-label={label === "Day" ? "Day" : label}
          value={date}
          min={min}
          onChange={(event) => event.target.value && onDate(event.target.value)}
          className="absolute inset-0 cursor-pointer opacity-0"
        />
      </label>
      {!isToday && (
        <button type="button" onClick={() => onDate(today)} className="rounded-full bg-go-card px-3 py-2 text-[13px] font-medium text-go-teal shadow-go-card">
          Today
        </button>
      )}
    </span>
  );
}
