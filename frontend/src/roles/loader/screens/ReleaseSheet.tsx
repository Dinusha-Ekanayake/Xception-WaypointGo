"use client";

import { useState } from "react";
import type { OutletView, ReleaseTrip } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import { ISSUE_KIND_LABEL, byStop, durationText, isChecked, isFlagged, kg, m3, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { Bar, BigButton, Sheet, SwipeButton } from "../ui.tsx";
import { useT } from "../i18n.tsx";
import { nowMs } from "@shared/wording";

// Figma "04 Confirm & release" and "E9 Release blocked". Release is refused
// while any item is unchecked (R-LOD-07), and the refusal lists what is left.
// Flagged items do not block: they are on record and the dispatcher knows.

const ORDINAL = (n: number) => `${n}${n % 10 === 1 && n !== 11 ? "st" : n % 10 === 2 && n !== 12 ? "nd" : n % 10 === 3 && n !== 13 ? "rd" : "th"}`;
type Checklist = Omit<ReleaseTrip, "tripId">;
const CHECKS = [
  { key: "doorsSealed", label: "Doors sealed" },
  { key: "ordersSecured", label: "Orders secured" },
  { key: "driverPresent", label: "Driver present" },
] as const;

export default function ReleaseSheet({
  lines,
  outlets,
  blockedBy,
  capacity,
  busy,
  onRelease,
  onReport,
  onClose,
}: {
  lines: Line[];
  outlets: Map<string, OutletView>;
  /** The server's refusal, or a local reason, when release cannot go ahead. */
  blockedBy: string | null;
  /** Loaded and vehicle totals, by order-level weight and volume (never product lines). */
  capacity: { weight: number; weightCap: number; volume: number; volumeCap: number };
  busy: boolean;
  onRelease: (checklist: Checklist) => void;
  onReport: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const [checklist, setChecklist] = useState<Checklist>({
    doorsSealed: false,
    ordersSecured: false,
    driverPresent: false,
  });
  const confirmed = CHECKS.every(({ key }) => checklist[key]);
  const left = lines.filter((l) => !isChecked(l.status));

  if (left.length > 0 || blockedBy) {
    return (
      <Sheet label={tr("Release blocked")} onClose={onClose}>
        <div className="flex flex-col gap-2 rounded-[24px] bg-go-canvas p-4">
          <span className="flex items-center justify-between text-[13px]">
            <span className="flex items-center gap-2 font-medium text-go-danger-strong">
              <span aria-hidden className="size-2 rounded-full bg-go-danger" />
              {tr("Release blocked")}
            </span>
            <span className="text-go-muted">{tr("Just now")}</span>
          </span>
          <h2 className="text-[24px] font-medium">{tr("Can't release yet")}</h2>
          <p className="text-[15px] text-go-muted">
            {blockedBy ?? tr(left.length === 1 ? "{n} order still to load, or report what's missing." : "{n} orders still to load, or report what's missing.", { n: left.length })}
          </p>
          {left.length > 0 && (
            <ul className="mt-1 flex flex-col gap-1 text-[14px]">
              {left.map((l) => (
                <li key={l.orderId}>
                  {orderLabel(l)} · Stop {String(l.stopSequence).padStart(2, "0")} · {placeName(l.outletId, outlets)}
                  {l.recheck && <span className="text-go-warning-text"> · {tr("recheck after plan change")}</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <BigButton tone="grey" size="l" onClick={onReport}>
            {tr("Report issue")}
          </BigButton>
          <BigButton tone="ink" size="l" onClick={onClose}>
            {tr("Keep loading")}
          </BigButton>
        </div>
      </Sheet>
    );
  }

  const stops = [...byStop(lines)].reverse();
  const items = lines.flatMap((l) => l.items);
  const reported = items.filter((i) => isFlagged(i.status));
  const loaders = new Set(items.map((i) => i.checkedBy).filter(Boolean)).size;
  const firstCheck = Math.min(...items.map((i) => (i.checkedAt ? Date.parse(i.checkedAt) : Infinity)));
  const minutes = Number.isFinite(firstCheck) ? Math.max(1, Math.round((nowMs() - firstCheck) / 60_000)) : null;
  const summary = [
    reported.length === 0 ? tr("Nothing reported") : tr(reported.length === 1 ? "{n} item reported" : "{n} items reported", { n: reported.length }),
    loaders > 0 && tr(loaders === 1 ? "{n} loader" : "{n} loaders", { n: loaders }),
    minutes !== null && durationText(minutes),
  ].filter(Boolean).join(" · ");
  const callouts = stops
    .filter((stop) => stop.lines.some((l) => isFlagged(l.status)))
    .map((stop) => {
      const kinds = [...new Set(stop.lines.flatMap((l) => l.items).filter((i) => isFlagged(i.status)).map((i) => tr(ISSUE_KIND_LABEL[i.status as keyof typeof ISSUE_KIND_LABEL]).toLowerCase()))];
      return tr("{place}: {kinds}. Dispatcher and store manager notified.", { place: placeName(stop.outletId, outlets), kinds: kinds.join(", ") });
    });
  return (
    <Sheet label={tr("Confirm and release")} onClose={onClose}>
      <div className="flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-full bg-go-signal">
          <Icon name="check-white" />
        </span>
        <div>
          <h2 className="text-[26px] leading-tight font-semibold">{tr("All {n} orders loaded", { n: lines.length })}</h2>
          <p className="text-[14px] text-go-muted">{summary}</p>
        </div>
      </div>
      <table className="w-full overflow-hidden rounded-[16px] text-left text-[15px] outline outline-go-rule">
        <thead className="bg-go-surface text-[13px] text-go-muted">
          <tr>
            <th className="px-3 py-2 font-normal">{tr("Unload")}</th>
            <th className="px-3 py-2 font-normal">{tr("Outlet")}</th>
            <th className="px-3 py-2 font-normal">{tr("Status")}</th>
          </tr>
        </thead>
        <tbody>
          {stops.map((stop, i) => {
            const bad = stop.lines.some((l) => isFlagged(l.status));
            return (
              <tr key={stop.stopSequence} className={cx(bad && "bg-go-danger-tint")}>
                <td className="px-3 py-3">{tr(ORDINAL(i + 1))}</td>
                <td className="px-3 py-3">{placeName(stop.outletId, outlets)}</td>
                <td className={cx("px-3 py-3 font-medium", bad ? "text-go-danger-strong" : "text-go-success")}>
                  {tr(bad ? "Flagged" : "Ready")}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {callouts.map((text) => (
        <p key={text} className="flex items-start gap-3 rounded-[20px] bg-go-canvas px-4 py-3 text-[15px] font-medium">
          <Icon name="triangle" />
          {text}
        </p>
      ))}
      <div className="grid grid-cols-2 gap-4">
        <Bar label="Volume" value={`${m3(capacity.volume)} / ${m3(capacity.volumeCap)}`} share={capacity.volumeCap ? capacity.volume / capacity.volumeCap : 0} />
        <Bar label="Weight" value={`${kg(capacity.weight)} / ${kg(capacity.weightCap)}`} share={capacity.weightCap ? capacity.weight / capacity.weightCap : 0} />
      </div>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-[15px] font-semibold">{tr("Release checklist")}</legend>
        {CHECKS.map(({ key, label }) => (
          <label key={key} className="flex min-h-14 items-center gap-3 rounded-[16px] bg-go-canvas px-4 text-[15px] font-medium">
            <input
              type="checkbox"
              checked={checklist[key]}
              onChange={(event) => setChecklist((current) => ({ ...current, [key]: event.target.checked }))}
              className="size-5 accent-go-success"
            />
            {tr(label)}
          </label>
        ))}
      </fieldset>
      <div className="flex flex-col gap-3">
        <BigButton tone="grey" size="l" onClick={onClose}>
          {tr("Not yet")}
        </BigButton>
        <SwipeButton
          onSwiped={() => onRelease(checklist)}
          disabled={busy || !confirmed}
          busy={busy}
          label={tr("Swipe to release vehicle")}
          busyLabel={tr("Releasing…")}
        />
        <p className="text-center text-[13px] text-go-muted">{tr("Releasing sends the run sheet to the driver.")}</p>
      </div>
    </Sheet>
  );
}
