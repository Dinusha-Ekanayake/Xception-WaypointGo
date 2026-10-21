"use client";

import { useState } from "react";
import { cx } from "@shared/ui";
import { useT } from "../i18n.tsx";
import { BigButton } from "../ui.tsx";

// R-ISS-17: the goods of a reported item turned up before the vehicle left. The
// loader takes the flag back with a reason; the dispatcher's issue is resolved
// "found at the depot" and the item is ticked loaded. Sits inside the report
// sheet, so it is a group with its own button, not a second form.

export default function FoundFlag({
  online,
  busy,
  onFound,
}: {
  online: boolean;
  busy: boolean;
  /** Resolves to null when done, or the reason it could not be done, shown here rather than behind the sheet. */
  onFound: (reason: string) => Promise<string | null>;
}): React.JSX.Element {
  const tr = useT();
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const ready = online && reason.trim().length >= 3 && !busy;

  return (
    <div role="group" aria-label={tr("Found it")} className="flex flex-col gap-3 rounded-[18px] bg-go-success-tint p-4">
      <span className="text-[18px] font-medium">{tr("Found it?")}</span>
      <label className="flex flex-col gap-1.5">
        <span className="text-[14px] text-go-muted">{tr("Where it was")}</span>
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className={cx("min-h-12 rounded-[14px] border border-go-rule bg-go-card px-4 text-[17px]")}
        />
      </label>
      {!online && <span className="text-[14px] text-go-muted">{tr("Needs a signal, so the dispatcher sees it at once.")}</span>}
      {problem && <p role="alert" className="text-[14px] text-go-danger">{problem}</p>}
      <BigButton size="l" disabled={!ready} busy={busy} onClick={() => void onFound(reason.trim()).then(setProblem)}>
        {tr("Found, remove flag")}
      </BigButton>
    </div>
  );
}
