"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import { friendlyError } from "@shared/api/problem";
import { Card, CardHead, Notice, Pill, cx } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { TYPE_LABEL, readInquiries } from "./data.ts";
import CaseDetail from "./screens/CaseDetail.tsx";

// Roadmap FF-03: the Operations workspace for damaged-goods inquiries (P-DG-4).
// The depot's damaged, short and disputed cases on the left; one case on the
// right. The Head of Distribution sees the same, and may approve above the
// depot limit and any Tech line (P-DG-1).

export default function Inquiries({
  userId,
  displayName,
  scope,
  head,
}: {
  userId: string;
  displayName: string;
  scope: string[];
  /** The Head of Distribution (ops_head). */
  head: boolean;
}): React.JSX.Element {
  const cases = useResource(scope.length ? (signal) => readInquiries(scope, signal) : null, scope.join(","), 30_000);
  const [open, setOpen] = useState<string | null>(null);
  const list = cases.data ?? [];
  const shown = open ?? list[0]?.issueId ?? null;

  return (
    <main className="mx-auto flex w-full max-w-[1280px] flex-col gap-4 px-4 py-6 font-go">
      <header className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-[28px] font-medium text-go-ink">Inquiries</h1>
          <p className="text-[14px] text-go-secondary">
            {displayName} · {head ? "Head of Distribution" : "Operations"} · {scope.join(", ") || "no depot"}
          </p>
        </div>
      </header>
      {scope.length === 0 && <Notice tone="warning" title="No depot is assigned to you yet. Ask an administrator." />}
      {cases.error && !cases.data && <Notice tone="danger" title={friendlyError(cases.error)} />}
      <div className="flex gap-4 max-lg:flex-col">
        <Card label="Issues" className="w-full max-w-[380px] max-lg:max-w-none">
          <CardHead title="Issues" meta="Damaged, short or disputed goods" />
          {cases.loading && !cases.data && <p className="text-[13px] text-go-secondary">Reading issues…</p>}
          {cases.data && list.length === 0 && <p className="text-[13px] text-go-secondary">No damaged, short or disputed goods in your depots.</p>}
          <ul className="flex flex-col gap-1.5">
            {list.map((i) => (
              <li key={i.issueId}>
                <button
                  type="button"
                  aria-pressed={shown === i.issueId}
                  onClick={() => setOpen(i.issueId)}
                  className={cx("flex w-full flex-col gap-0.5 rounded-go-card-s px-3 py-2.5 text-left", shown === i.issueId ? "bg-go-mint" : "bg-go-surface hover:bg-go-subtle")}
                >
                  <span className="flex items-center gap-2 text-[14px] font-medium">
                    {TYPE_LABEL[i.type] ?? i.type}
                    <Pill tone={i.status === "OPEN" ? "warning" : "muted"}>{i.status.toLowerCase()}</Pill>
                  </span>
                  <span className="truncate text-[12px] text-go-secondary">
                    {i.outletId ?? i.depotCode} · {dayLabel(i.raisedAt.slice(0, 10))} · {(i.attachments ?? []).length} photos
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </Card>
        <div className="min-w-0 flex-1">{shown ? <CaseDetail key={shown} issueId={shown} userId={userId} isHead={head} /> : null}</div>
      </div>
    </main>
  );
}
