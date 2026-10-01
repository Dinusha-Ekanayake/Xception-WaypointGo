"use client";

import { useState } from "react";
import type { OutletView, ReleaseTrip } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import { byStop, isChecked, isFlagged, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { BigButton, HoldButton, Sheet } from "../ui.tsx";

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
  busy,
  onRelease,
  onReport,
  onClose,
}: {
  lines: Line[];
  outlets: Map<string, OutletView>;
  /** The server's refusal, or a local reason, when release cannot go ahead. */
  blockedBy: string | null;
  busy: boolean;
  onRelease: (checklist: Checklist) => void;
  onReport: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const [checklist, setChecklist] = useState<Checklist>({
    doorsSealed: false,
    ordersSecured: false,
    driverPresent: false,
  });
  const confirmed = CHECKS.every(({ key }) => checklist[key]);
  const left = lines.filter((l) => !isChecked(l.status));
  const flagged = lines.filter((l) => isFlagged(l.status));

  if (left.length > 0 || blockedBy) {
    return (
      <Sheet label="Release blocked" onClose={onClose}>
        <div className="flex flex-col gap-2 rounded-[24px] bg-go-canvas p-4">
          <span className="flex items-center gap-2 text-[13px] font-medium text-go-danger-strong">
            <span aria-hidden className="size-2 rounded-full bg-go-danger" />
            Release blocked
          </span>
          <h2 className="text-[24px] font-medium">Can&apos;t release yet</h2>
          <p className="text-[15px] text-go-muted">
            {blockedBy ?? `${left.length} ${left.length === 1 ? "order has" : "orders have"} items still to load, or report what's missing.`}
          </p>
          {left.length > 0 && (
            <ul className="mt-1 flex flex-col gap-1 text-[14px]">
              {left.map((l) => (
                <li key={l.orderId}>
                  {orderLabel(l)} · Stop {String(l.stopSequence).padStart(2, "0")} · {placeName(l.outletId, outlets)}
                  {l.recheck && <span className="text-go-warning-text"> · recheck after plan change</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="flex gap-3">
          <BigButton tone="muted" onClick={onReport}>
            Report issue
          </BigButton>
          <BigButton tone="ink" onClick={onClose}>
            Keep loading
          </BigButton>
        </div>
      </Sheet>
    );
  }

  const stops = [...byStop(lines)].reverse();
  return (
    <Sheet label="Confirm and release" onClose={onClose}>
      <div className="flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-full bg-go-signal">
          <Icon name="check-white" />
        </span>
        <div>
          <h2 className="text-[26px] leading-tight font-semibold">All {lines.length} orders loaded</h2>
          <p className="text-[14px] text-go-muted">
            {flagged.length === 0
              ? "Nothing reported"
              : `${flagged.length} ${flagged.length === 1 ? "order has" : "orders have"} items reported to the dispatcher`}
          </p>
        </div>
      </div>
      <table className="w-full overflow-hidden rounded-[16px] text-left text-[15px] outline outline-[#dfe3e8]">
        <thead className="bg-[#f1f3f5] text-[13px] text-go-muted">
          <tr>
            <th className="px-3 py-2 font-normal">Unload</th>
            <th className="px-3 py-2 font-normal">Outlet</th>
            <th className="px-3 py-2 font-normal">Status</th>
          </tr>
        </thead>
        <tbody>
          {stops.map((stop, i) => {
            const bad = stop.lines.some((l) => isFlagged(l.status));
            return (
              <tr key={stop.stopSequence} className={cx(bad && "bg-go-danger-tint")}>
                <td className="px-3 py-3">{ORDINAL(i + 1)}</td>
                <td className="px-3 py-3">{placeName(stop.outletId, outlets)}</td>
                <td className={cx("px-3 py-3 font-medium", bad ? "text-go-danger-strong" : "text-go-success")}>
                  {bad ? "Flagged" : "Ready"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-[15px] font-semibold">Release checklist</legend>
        {CHECKS.map(({ key, label }) => (
          <label key={key} className="flex min-h-14 items-center gap-3 rounded-[16px] bg-go-canvas px-4 text-[15px] font-medium">
            <input
              type="checkbox"
              checked={checklist[key]}
              onChange={(event) => setChecklist((current) => ({ ...current, [key]: event.target.checked }))}
              className="size-5 accent-go-success"
            />
            {label}
          </label>
        ))}
      </fieldset>
      <div className="flex flex-col gap-3">
        <BigButton tone="muted" size="l" onClick={onClose}>
          Not yet
        </BigButton>
        <HoldButton onHeld={() => onRelease(checklist)} disabled={busy || !confirmed}>
          {busy ? "Releasing…" : "Hold to release vehicle"}
        </HoldButton>
        <p className="text-center text-[13px] text-go-muted">Releasing sends the run sheet to the driver.</p>
      </div>
    </Sheet>
  );
}
