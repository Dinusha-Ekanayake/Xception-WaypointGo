"use client";

import { useState } from "react";
import { IssueCommandKind, type IssueView } from "@shared/domain/types";
import { Notice, PrimaryButton, SecondaryButton } from "@shared/ui";
import { RESOLUTIONS, actionsFor, nextDay, subject, type IssueAction } from "../data/issues.ts";
import { depotToday } from "../data/scope.ts";
import { useCommand } from "../data/useCommand.ts";
import DayField from "./DayTools.tsx";
import Refusal from "./Refusal.tsx";

// What the dispatcher can do to one issue. Every write is one command with the
// issue's rowVersion, so a change someone else made first comes back as a 409
// and the screen reads the issue again. Every decision asks for a reason
// (rule 8); the server refuses the ones its rules do not allow, with the rule.

const LABEL: Record<IssueAction, string> = {
  take: "Take it",
  resolve: "Resolve",
  replacement: "Record replacement",
  redelivery: "Schedule redelivery",
  close: "Close",
  cancel: "Cancel issue",
};

const DONE: Record<IssueAction, string> = {
  take: "The issue is assigned to you.",
  resolve: "The issue is resolved. Close it once nothing more is owed.",
  replacement: "Replacement recorded. Loading is told it may recheck the trip.",
  redelivery: "Redelivery requested. Ordering creates the new order, which the next plan serves first.",
  close: "The issue is closed.",
  cancel: "The issue is cancelled.",
};

/** For a refusal: "<what> was refused", "<what> could not be reached". */
const WHAT: Record<IssueAction, string> = {
  take: "assigning the issue",
  resolve: "resolving the issue",
  replacement: "recording the replacement",
  redelivery: "the redelivery",
  close: "closing the issue",
  cancel: "cancelling the issue",
};

const input = "rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal";

export default function IssueActions({
  issue,
  userId,
  online,
  onDone,
}: {
  issue: IssueView;
  userId: string;
  online: boolean;
  onDone: () => void;
}): React.JSX.Element | null {
  const { busy, run } = useCommand();
  const [open, setOpen] = useState<IssueAction | null>(null);
  const [note, setNote] = useState("");
  const [resolution, setResolution] = useState<(typeof RESOLUTIONS)[number]["value"]>("write_off");
  const [date, setDate] = useState(() => nextDay(depotToday()));
  const [outcome, setOutcome] = useState<{ action: IssueAction; error: Error | null } | null>(null);

  const actions = actionsFor(issue, userId);
  if (actions.length === 0) return outcome && !outcome.error ? <Notice tone="info" title={DONE[outcome.action]} live /> : null;

  const send = async (action: IssueAction) => {
    const id = issue.issueId;
    const order = subject(issue, "order");
    const [kind, payload] =
      action === "take"
        ? [IssueCommandKind.assign, { issueId: id, assigneeUserId: userId }]
        : action === "resolve"
          ? [IssueCommandKind.resolve, { issueId: id, action: resolution, note: note.trim() }]
          : action === "replacement"
            ? [IssueCommandKind.recordReplacement, { issueId: id, tripId: subject(issue, "trip"), orderId: order, note: note.trim() }]
            : action === "redelivery"
              ? [IssueCommandKind.scheduleRedelivery, { issueId: id, orderId: order, requestedDate: date, note: note.trim() }]
              : action === "close"
                ? [IssueCommandKind.close, { issueId: id }]
                : [IssueCommandKind.cancel, { issueId: id, reason: note.trim() }];
    const sent = await run(kind, payload, issue.rowVersion);
    setOutcome({ action, error: sent.ok ? null : sent.error });
    if (sent.ok) {
      setOpen(null);
      setNote("");
    }
    // Whatever the answer: a refusal usually means the issue moved.
    onDone();
  };

  // Taking and closing decide nothing new, so they go at once; the rest ask why.
  const immediate = (action: IssueAction) => action === "take" || action === "close";
  const disabled = busy || !online;

  return (
    <div className="flex flex-col gap-2">
      <div role="group" aria-label="Act on this issue" className="flex flex-wrap gap-2">
        {actions.map((action) =>
          action === actions[0] && action !== "cancel" ? (
            <PrimaryButton key={action} disabled={disabled} onClick={() => (immediate(action) ? void send(action) : setOpen(action))}>
              {LABEL[action]}
            </PrimaryButton>
          ) : (
            <SecondaryButton key={action} disabled={disabled} onClick={() => (immediate(action) ? void send(action) : setOpen(action))}>
              {LABEL[action]}
            </SecondaryButton>
          ),
        )}
      </div>
      {!online && <p className="text-xs text-go-secondary">Offline: issues are read only until the connection returns.</p>}

      {open && (
        <form
          aria-label={LABEL[open]}
          className="flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3"
          onSubmit={(event) => {
            event.preventDefault();
            void send(open);
          }}
        >
          {open === "resolve" && (
            <label className="flex flex-col gap-1 text-xs font-medium text-go-secondary">
              Outcome
              <select value={resolution} onChange={(event) => setResolution(event.target.value as typeof resolution)} className={input}>
                {RESOLUTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
          )}
          {open === "redelivery" && (
            <div className="flex flex-col gap-1 text-xs font-medium text-go-secondary">
              <DayField label="Deliver on" date={date} min={depotToday()} onDate={setDate} />
              <span className="font-normal">The whole order goes again, only because nothing arrived (A-24).</span>
            </div>
          )}
          <label className="flex flex-col gap-1 text-xs font-medium text-go-secondary">
            {open === "cancel" ? "Why it was raised in error" : "Reason"}
            <input value={note} maxLength={500} onChange={(event) => setNote(event.target.value)} className={input} />
          </label>
          <div className="flex gap-2">
            <PrimaryButton type="submit" disabled={disabled || note.trim().length < 3 || (open === "redelivery" && !date)}>
              {LABEL[open]}
            </PrimaryButton>
            <SecondaryButton onClick={() => setOpen(null)}>Back</SecondaryButton>
          </div>
        </form>
      )}

      {outcome?.error && <Refusal error={outcome.error} what={WHAT[outcome.action]} />}
      {outcome && !outcome.error && <Notice tone="info" title={DONE[outcome.action]} live />}
    </div>
  );
}
