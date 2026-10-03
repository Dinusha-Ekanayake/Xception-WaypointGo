"use client";

import type { SnapshotView } from "@shared/domain/types";
import { Menu, Segmented, cx, type MenuItem } from "@shared/ui";
import { clock } from "@shared/wording";
import DayPicker from "./DayTools.tsx";

// The Plan header's controls, as Figma draws them: which plan you are looking at
// (the working draft, the engine's own plan, a saved one), Save snapshot,
// Regenerate with its two ways, and Compare. Depot and day stay as on every
// dated screen.

export default function PlanTools({
  depots,
  depot,
  onDepot,
  date,
  onDate,
  hasPlan,
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
}: {
  depots: string[];
  depot: string;
  onDepot: (depot: string) => void;
  date: string;
  onDate: (date: string) => void;
  hasPlan: boolean;
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
  const pill = "flex items-center gap-1.5 rounded-full border border-go-rule bg-go-card px-4 py-2.5 text-sm font-medium text-go-ink";

  return (
    <>
      {depots.length > 1 && <Segmented label="Depot to plan" value={depot} onChange={onDepot} options={depots.map((code) => ({ value: code, label: code }))} />}
      <DayPicker date={date} onDate={onDate} />
      {hasPlan && (
        <>
          <Menu
            label="Plan"
            align="right"
            items={items}
            onSelect={(id) => onView(id === "" ? null : id)}
            className="flex min-w-[170px] flex-col rounded-go-card bg-go-card px-4 py-1.5 text-left shadow-go-card"
          >
            <span className="text-[10px] font-medium tracking-wide text-go-secondary uppercase">Plan</span>
            <span className="text-sm font-medium text-go-ink">{current ? current.label : workingLabel}</span>
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
          >
            Regenerate
          </Menu>
          <button type="button" onClick={onCompare} className={cx(pill, "border-go-ink")}>
            Compare
          </button>
        </>
      )}
    </>
  );
}
