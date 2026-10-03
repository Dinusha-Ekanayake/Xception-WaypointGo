"use client";

import { cx } from "@shared/ui";
import { missing, type RecordDraft, type Stop } from "../data/run.ts";

// What a real delivery record needs beyond the Figma report (issue #114): the
// units handed over, and, when that is fewer than ordered or the stop is late,
// the reasons the server asks for. Drawn under the delivered items in the
// report's own card style. The rules are run.ts's `missing`, the server's.

export type RecordFields = { delivered: number; reason: string; note: string };

export const startFields = (stop: Stop): RecordFields => ({ delivered: stop.itemCount, reason: "", note: "" });

export function draftOf(stop: Stop, fields: RecordFields): RecordDraft {
  return {
    outcome: fields.delivered < stop.itemCount ? "PARTIAL" : "DELIVERED",
    deliveredUnits: fields.delivered,
    reason: fields.reason,
    dispositionNote: fields.note,
  };
}

/** Why the record cannot be sent yet, or null when it can. */
export function blocker(stop: Stop, fields: RecordFields): string | null {
  return missing(stop, draftOf(stop, fields), stop.waiting);
}

export default function LiveRecordFields({
  stop,
  fields,
  onChange,
  isNight,
}: {
  stop: Stop;
  fields: RecordFields;
  onChange: (fields: RecordFields) => void;
  isNight: boolean;
}): React.JSX.Element {
  const partial = fields.delivered < stop.itemCount;
  const late = (stop.lateMinutes ?? 0) > 0 && !stop.waiting;
  const text = isNight ? "text-white" : "text-black";
  const muted = isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]";
  const field = cx("w-full rounded-[14px] px-4 py-3 text-[16px] outline-none", isNight ? "bg-[#1f1f1f] text-white placeholder:text-[#7a7a7a]" : "bg-[#F2F7F6] text-black placeholder:text-[#8a9594]");
  const step = cx("flex size-11 items-center justify-center rounded-full text-[22px] disabled:opacity-40", isNight ? "bg-[#1f1f1f] text-white" : "bg-[#E7F3F2] text-black");
  const set = (delivered: number) => onChange({ ...fields, delivered: Math.min(stop.itemCount, Math.max(1, delivered)) });

  return (
    <section
      aria-label="Units handed over"
      className={cx("mt-[14px] flex flex-col gap-3 rounded-[22px] px-[17px] py-[16px]", isNight ? "bg-[#292929]" : "bg-white shadow-[0px_5px_20px_rgba(0,0,0,0.05)]")}
    >
      <div className="flex items-center justify-between">
        <div className="flex flex-col">
          <span className={cx("text-[16px] font-medium", text)}>Units handed over</span>
          <span className={cx("text-[13px]", muted)}>{stop.itemCount} ordered</span>
        </div>
        <div className="flex items-center gap-3">
          <button type="button" className={step} aria-label="One unit fewer" disabled={fields.delivered <= 1} onClick={() => set(fields.delivered - 1)}>−</button>
          <output aria-live="polite" aria-label="Units handed over" className={cx("min-w-8 text-center text-[22px] font-semibold tabular-nums", text)}>{fields.delivered}</output>
          <button type="button" className={step} aria-label="One unit more" disabled={fields.delivered >= stop.itemCount} onClick={() => set(fields.delivered + 1)}>+</button>
        </div>
      </div>
      {(partial || late) && (
        <input
          className={field}
          value={fields.reason}
          onChange={(event) => onChange({ ...fields, reason: event.target.value })}
          placeholder={partial ? "Why were fewer units handed over?" : "This stop is late. Why?"}
          aria-label={partial ? "Why were fewer units handed over?" : "This stop is late. Why?"}
        />
      )}
      {partial && (
        <input
          className={field}
          value={fields.note}
          onChange={(event) => onChange({ ...fields, note: event.target.value })}
          placeholder="What happened to the units not handed over?"
          aria-label="What happened to the units not handed over?"
        />
      )}
    </section>
  );
}
