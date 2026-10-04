"use client";

import type { SnapshotView } from "@shared/domain/types";
import { Icon, Menu, cx, type MenuItem } from "@shared/ui";
import { clock } from "@shared/wording";

// The Plan header's controls, as Figma draws them: which plan you are looking at
// (the working draft, the engine's own plan, a saved one), Save snapshot,
// Regenerate with its two ways, and Compare, in each depot's section bar. The
// depot is the sidebar's scope; the day is the page header's DayField.

/** The day being planned, drawn like the plan picker; it sits in the page header. */
export function DayField({ date, onDate }: { date: string; onDate: (date: string) => void }): React.JSX.Element {
  return (
    <label className="flex min-w-[150px] flex-col rounded-go-card bg-go-card px-4 py-1.5 shadow-go-card">
      <span className="text-[10px] font-medium tracking-wide text-go-secondary uppercase">Day</span>
      <input
        type="date"
        aria-label="Day to plan"
        value={date}
        onChange={(event) => event.target.value && onDate(event.target.value)}
        className="bg-transparent text-sm font-medium text-go-ink outline-none"
      />
    </label>
  );
}

export default function PlanTools({
  draft,
  workingLabel,
  snapshots,
  viewing,
  onView,
  online,
  busy,
  onSave,
  onRegenerate,
  onCompare,
  comparing = false,
}: {
  /** The working plan is an open draft, so it can be saved, regenerated and returned to a saved plan. */
  draft: boolean;
  workingLabel: string;
  snapshots: SnapshotView[];
  /** The saved plan being looked at; null is the working plan. */
  viewing: string | null;
  onView: (snapshotId: string | null) => void;
  online: boolean;
  busy: boolean;
  onSave: () => void;
  onRegenerate: (keepDecisions: boolean) => void;
  onCompare: () => void;
  /** The compare view is open: the button is drawn pressed. */
  comparing?: boolean;
}): React.JSX.Element {
  const current = snapshots.find((s) => s.snapshotId === viewing);
  const items: MenuItem[] = [
    { id: "", label: workingLabel, selected: viewing === null },
    ...snapshots.slice(0, 8).map((s) => ({
      id: s.snapshotId,
      label: s.label,
      hint: `${clock(s.createdAt)} · read only`,
      selected: s.snapshotId === viewing,
    })),
  ];
  const pill = "flex items-center gap-1.5 rounded-full bg-go-card px-4 py-2 text-sm font-medium text-go-ink shadow-go-card";

  return (
    <>
      <span className="flex flex-wrap items-center gap-2.5">
          <Menu
            label="Plan"
            align="right"
            items={items}
            onSelect={(id) => onView(id === "" ? null : id)}
            className="flex min-w-[190px] items-center justify-between gap-3 rounded-go-card bg-go-card px-4 py-1.5 text-left shadow-go-card"
          >
            <span className="flex flex-col">
              <span className="text-[10px] font-medium tracking-wide text-go-secondary uppercase">Plan</span>
              <span className="text-sm font-medium text-go-ink">{current ? current.label : workingLabel}</span>
            </span>
            <Icon name="chevron-down" />
          </Menu>
          <button type="button" disabled={!online || busy || !draft || viewing !== null} onClick={onSave} className={cx(pill, "disabled:cursor-not-allowed disabled:opacity-40")}>
            Save snapshot
          </button>
          <Menu
            label="Regenerate"
            align="right"
            disabled={!online || busy || !draft || viewing !== null}
            items={[
              { id: "keep", label: "Keep my decisions", hint: "Placed, locked and kept orders stay; the rest is planned again" },
              { id: "scratch", label: "Start over", hint: "The engine decides every order again; the draft is saved first" },
            ]}
            onSelect={(id) => onRegenerate(id === "keep")}
            className={pill}
            chevron
          >
            Regenerate
          </Menu>
          <button type="button" aria-pressed={comparing} onClick={onCompare} className={
              comparing
                ? "flex items-center gap-1.5 rounded-full bg-go-ink px-4 py-2 text-sm font-medium text-go-card"
                : cx(pill, "shadow-none ring-1 ring-go-ink")
            }>
            Compare
          </button>
      </span>
    </>
  );
}
