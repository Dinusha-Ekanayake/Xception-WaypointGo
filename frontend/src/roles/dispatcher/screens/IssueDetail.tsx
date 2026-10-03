"use client";

import { Pill } from "@shared/ui";
import { SEVERITY, STATUS, TYPE, age, shortId } from "../data/issues.ts";
import { useIssue } from "../data/useDay.ts";
import IssueActions from "./IssueActions.tsx";
import { Retry } from "./Orders.tsx";
import Refusal from "./Refusal.tsx";

// One issue: what it is about, who has it, every decision on it with the
// person and the reason (rule 8), and what can be done now. Read by id, so a
// resolved issue stays here to be closed after it leaves the open list.

const WHEN = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

export default function IssueDetail({
  issueId,
  userId,
  online,
  now,
  onChanged,
}: {
  issueId: string;
  userId: string;
  online: boolean;
  now: Date;
  /** The open list moved: read it again. */
  onChanged: () => void;
}): React.JSX.Element {
  const record = useIssue(issueId);
  const issue = record.data?.issue ?? null;
  const person = (id: string | null) => (id === null ? "the system" : id === userId ? "you" : shortId(id));

  return (
    <section aria-label="Selected issue" className="flex flex-col gap-3 rounded-go-panel bg-go-card p-5">
      {record.error && <Refusal error={record.error} what="this issue" action={<Retry onClick={record.refresh} />} />}
      {!issue ? (
        !record.error && <p className="text-[13px] text-go-secondary">Reading the issue…</p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="min-w-0 flex-1 text-[22px] font-medium text-go-ink">{TYPE[issue.type]}</h2>
            <Pill tone={issue.status === "OPEN" ? "danger" : STATUS[issue.status].tone}>{issue.status === "OPEN" ? "New" : STATUS[issue.status].label}</Pill>
          </div>
          <p className="-mt-2 text-[13px] text-go-secondary">{`${SEVERITY[issue.severity].label} · ${issue.depotCode}${issue.outletId ? ` · ${issue.outletId}` : ""}`}</p>
          <h3 className="text-[15px] font-medium text-go-ink">What was reported</h3>
          <div className="rounded-go-card bg-go-surface px-4 py-3">
            <p className="flex justify-between gap-2 text-xs text-go-secondary">
              <span>{`Raised by ${person(issue.raisedBy)}`}</span>
              <span>{`${age(issue.raisedAt, now)} ago`}</span>
            </p>
            <p className="text-[14px] font-medium text-go-ink">{issue.description}</p>
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
            <dt className="text-go-secondary">Where</dt>
            <dd className="text-go-ink">
              {issue.depotCode}
              {issue.outletId ? ` · outlet ${issue.outletId}` : ""}
            </dd>
            <dt className="text-go-secondary">About</dt>
            <dd className="text-go-ink">{issue.subjects.map((ref) => `${ref.type} ${shortId(ref.id)}`).join(" · ")}</dd>
            <dt className="text-go-secondary">Raised</dt>
            <dd className="text-go-ink">
              {age(issue.raisedAt, now)} ago by {person(issue.raisedBy)}
            </dd>
            <dt className="text-go-secondary">Assigned</dt>
            <dd className="text-go-ink">{issue.assignee ? person(issue.assignee) : "nobody yet"}</dd>
            {issue.resolutionAction && (
              <>
                <dt className="text-go-secondary">Resolved</dt>
                <dd className="text-go-ink">
                  {issue.resolutionAction.toLowerCase().replaceAll("_", " ")}
                  {issue.resolutionNote ? `: ${issue.resolutionNote}` : ""}
                </dd>
              </>
            )}
          </dl>

          <IssueActions
            issue={issue}
            userId={userId}
            online={online}
            onDone={() => {
              record.refresh();
              onChanged();
            }}
          />

          <div className="flex flex-col gap-1.5">
            <h3 className="text-[13px] font-semibold text-go-ink">History</h3>
            <ol aria-label="Issue history" className="flex flex-col">
              {(record.data?.history ?? []).map((entry, index) => (
                <li key={index} className="flex flex-col gap-0.5 border-t border-go-rule py-2 text-[13px] first:border-t-0">
                  <span className="text-go-ink">
                    <span className="font-medium">{entry.action}</span> by {person(entry.actorId)}
                    <span className="text-go-secondary"> · {WHEN.format(new Date(entry.at))}</span>
                  </span>
                  {entry.reason && <span className="text-xs text-go-secondary">{entry.reason}</span>}
                </li>
              ))}
            </ol>
          </div>
        </>
      )}
    </section>
  );
}
