"use client";

import { clock, summarize, type Stop } from "../data/run.ts";
import { ActionButton, CheckIcon, Panel } from "../ui.tsx";

/** Figma "Run complete": what the day came to, and what is still on the phone. */
export default function RunComplete({
  vehicleId,
  stops,
  uploadsWaiting,
  writesWaiting,
  onHome,
}: {
  vehicleId: string;
  stops: Stop[];
  uploadsWaiting: number;
  writesWaiting: number;
  onHome: () => void;
}): React.JSX.Element {
  const summary = summarize(stops);
  const finished = stops
    .map((stop) => stop.completedAt)
    .filter((value): value is string => value !== null)
    .sort()
    .at(-1);
  const proofs = stops.filter((stop) => stop.proofCaptured).length;
  const rows: Array<[string, string]> = [
    ["Stops delivered", `${summary.delivered + summary.partial} of ${summary.total}`],
    ["Partly delivered", String(summary.partial)],
    ["Not delivered", String(summary.failed)],
    ["Replanned by dispatch", String(summary.skipped)],
    ["Proof of delivery", `${proofs} saved${uploadsWaiting > 0 ? ` · ${uploadsWaiting} still sending` : ""}`],
  ];
  return (
    <div className="flex flex-col gap-5 px-5 pb-8 pt-4">
      <span className="flex size-[72px] items-center justify-center rounded-full bg-go-card text-go-signal shadow-go-card">
        <CheckIcon />
      </span>
      <div>
        <p className="text-[15px] text-go-ink">{vehicleId}</p>
        <h1 className="text-[40px] font-medium leading-[1.05] text-go-ink">Run complete</h1>
        <p className="mt-2 text-[16px] text-go-muted">
          All {summary.total} stops recorded{finished ? ` · finished ${clock(finished)}` : ""}
        </p>
      </div>
      <Panel label="Summary">
        <dl className="flex flex-col">
          {rows
            .filter(([label, value]) => value !== "0" || label === "Stops delivered")
            .map(([label, value], index) => (
              <div key={label} className={`flex min-h-12 items-center justify-between gap-4 text-[16px] ${index > 0 ? "border-t border-go-rule" : ""}`}>
                <dt className="text-go-muted">{label}</dt>
                <dd className="text-right font-medium text-go-ink">{value}</dd>
              </div>
            ))}
        </dl>
      </Panel>
      {writesWaiting + uploadsWaiting > 0 && (
        <p role="status" className="text-[15px] text-go-ink">
          {writesWaiting + uploadsWaiting} {writesWaiting + uploadsWaiting === 1 ? "record is" : "records are"} still on this phone and will be sent when the connection is back.
        </p>
      )}
      <ActionButton onClick={onHome}>Back to home</ActionButton>
    </div>
  );
}
