"use client";

import { useState } from "react";
import { newCommand, send } from "@shared/api/commands";
import { friendlyError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import { IssueCommandKind, type ApproveWriteOff, type RecordInquiry, type WriteOffView } from "@shared/domain/types";
import { Card, CardHead, Notice, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { clock, dayLabel } from "@shared/wording";
import { TYPE_LABEL, actionLabel, mayDecide, parseRupees, photoUrl, readCase, rupees } from "../data.ts";

// Roadmap FF-03: one case. What happened (the issue and its photos), what was
// done (the history), and Operations' part: record what the inquiry found, with
// a write-off value when goods are lost, and approve or reject a write-off
// someone else recorded (P-DG-1). Every step keeps its actor, reason and time.

const field = "min-h-11 rounded-go-input border border-go-rule bg-go-surface px-3 py-2.5 text-[14px] text-go-ink outline-none focus:border-go-teal";
/** On the grey write-off panel a field is white, so it stands out. */
const onPanel = field.replace("bg-go-surface", "bg-white");
const at = (iso: string) => `${dayLabel(iso.slice(0, 10))} · ${clock(iso)}`;

export default function CaseDetail({ issueId, userId, isHead }: { issueId: string; userId: string; isHead: boolean }): React.JSX.Element {
  const view = useResource((signal) => readCase(issueId, signal), issueId);
  const [outcome, setOutcome] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (!view.data) {
    return <Card label="Issue">{view.error ? <Notice tone="danger" title={friendlyError(view.error)} /> : <p className="text-[14px] text-go-secondary">Reading the issue…</p>}</Card>;
  }
  const { issue, history, writeOffs } = view.data;
  const typed = value.trim() ? parseRupees(value) : "";
  const ready = outcome.trim().length >= 3 && typed !== null;

  const record = async () => {
    if (!ready) return;
    setBusy(true);
    setError(null);
    try {
      const payload: RecordInquiry = { issueId, outcome: outcome.trim(), ...(typed ? { writeOffLkr: typed } : {}) };
      await send(newCommand(IssueCommandKind.recordInquiry, payload));
      setOutcome("");
      setValue("");
      setNotice(typed ? "Inquiry recorded with its write-off." : "Inquiry recorded.");
      view.refresh();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <Card label="Issue">
        <CardHead title={`${TYPE_LABEL[issue.type] ?? issue.type} · ${issue.outletId ?? issue.depotCode}`} meta={`Raised ${at(issue.raisedAt)} · ${issue.status.toLowerCase()}`} />
        <p className="text-[14px] text-go-ink">{issue.description}</p>
        {(issue.attachments ?? []).length > 0 ? (
          <div className="flex flex-wrap gap-2" aria-label="Photos">
            {(issue.attachments ?? []).map((a) => (
              <a key={a.attachmentId} href={photoUrl(issueId, a.attachmentId)} target="_blank" rel="noreferrer" className="block size-28 overflow-hidden rounded-go-card-s bg-go-surface">
                {/* A photo served by the API to those who can see the issue; nothing for next/image to optimise. */}
                <img src={photoUrl(issueId, a.attachmentId)} alt="Photo of the problem" loading="lazy" decoding="async" className="size-full object-cover" />
              </a>
            ))}
          </div>
        ) : (
          <p className="text-[13px] text-go-secondary">No photos.</p>
        )}
      </Card>

      <Card label="Write-offs">
        <CardHead title="Write-offs" meta="Recorded by an inquiry, approved by someone else" />
        {writeOffs.length === 0 && <p className="text-[13px] text-go-secondary">None recorded.</p>}
        <ul className="flex flex-col gap-2">
          {writeOffs.map((w) => (
            <WriteOff key={w.writeOffId} w={w} canDecide={mayDecide(w, userId, isHead)} onDone={view.refresh} />
          ))}
        </ul>
      </Card>

      <Card label="Record inquiry">
        <CardHead title="Record inquiry" meta="What the inquiry found; a write-off value when goods are lost" />
        {notice && <Notice tone="info" live title={notice} />}
        <label className="flex flex-col gap-1 text-[13px] font-medium">
          What was found
          <textarea className={field} rows={3} maxLength={1000} value={outcome} onChange={(e) => setOutcome(e.target.value)} placeholder="For example: pallet crushed by the forklift at the dock, photos confirm" />
        </label>
        <label className="flex flex-col gap-1 text-[13px] font-medium">
          Write-off value in rupees (optional)
          <input className={field} inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. 4500" />
        </label>
        {typed === null && <p className="text-[13px] text-go-danger">Enter rupees, with at most two decimals.</p>}
        {error && <Notice tone="danger" live title={error} />}
        <div>
          <PrimaryButton busy={busy} disabled={!ready} onClick={() => void record()}>
            Record inquiry
          </PrimaryButton>
        </div>
      </Card>

      <Card label="History">
        <CardHead title="History" meta="Every step with who and when" />
        <ol className="flex flex-col gap-1.5">
          {history.map((h, i) => (
            <li key={i} className="text-[13px]">
              <span className="font-medium">{actionLabel(h.action)}</span>
              <span className="text-go-secondary"> · {at(h.at)}</span>
              <span className="block text-go-ink">{h.reason}</span>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}

function WriteOff({ w, canDecide, onDone }: { w: WriteOffView; canDecide: boolean; onDone: () => void }): React.JSX.Element {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const decide = async (approve: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const payload: ApproveWriteOff = { writeOffId: w.writeOffId, approve, note: note.trim() };
      await send(newCommand(IssueCommandKind.approveWriteOff, payload, w.rowVersion));
      onDone();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };
  const tone = w.status === "approved" ? "success" : w.status === "rejected" ? "danger" : "warning";
  return (
    <li className="flex flex-col gap-2 rounded-go-card-s bg-go-surface px-3.5 py-3">
      <p className="flex flex-wrap items-center gap-2 text-[14px] font-semibold">
        {rupees(w.valueLkr)}
        <Pill tone={tone}>{w.status === "recorded" ? "Waiting for approval" : w.status === "approved" ? "Approved" : "Rejected"}</Pill>
        {w.needsHead && <Pill tone="info">{w.techLine ? "Tech line · Head of Distribution" : "Above the depot limit · Head of Distribution"}</Pill>}
      </p>
      <p className="text-[13px] text-go-ink">{w.outcome}</p>
      {w.decisionNote && <p className="text-[13px] text-go-secondary">Decision: {w.decisionNote}</p>}
      {canDecide && (
        <div className="flex flex-col gap-2">
          <input className={onPanel} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="Why approve or reject" aria-label="Why approve or reject" />
          <div className="flex gap-2">
            <PrimaryButton busy={busy} disabled={note.trim().length < 3} onClick={() => void decide(true)}>
              Approve
            </PrimaryButton>
            <SecondaryButton disabled={busy || note.trim().length < 3} onClick={() => void decide(false)}>
              Reject
            </SecondaryButton>
          </div>
        </div>
      )}
      {error && <Notice tone="danger" live title={error} />}
    </li>
  );
}
