"use client";

import { useState } from "react";
import type { FailureReason } from "@shared/domain/types";
import { Sheet } from "@shared/ui";
import type { Stop } from "../data/run.ts";
import { ActionButton, Banner, Field, OutlineButton, SoftButton, input } from "../ui.tsx";

// The driver's sheets: Figma "Driver: Report a problem", "Sign out: confirm"
// and the confirmed popup. The design slides a row to send; here a row is
// chosen and then sent with a button, which a screen reader and a keyboard can
// both do. Voice notes are left out: nothing stores them.

export type Problem =
  | { kind: "report"; fault: "road" | "vehicle"; description: string }
  | { kind: "not-delivered"; reason: FailureReason };

const CHOICES: Array<{ id: string; label: string; problem: (text: string) => Problem; needsText?: boolean; hint: string }> = [
  { id: "late", label: "Running late", hint: "Tells dispatch you are delayed on the road.", problem: (t) => ({ kind: "report", fault: "road", description: t ? `Running late: ${t}` : "Running late" }) },
  { id: "road", label: "Road closed or blocked", hint: "Tells dispatch about the road.", needsText: true, problem: (t) => ({ kind: "report", fault: "road", description: t }) },
  { id: "vehicle", label: "Vehicle problem", hint: "Tells dispatch the vehicle has a fault.", needsText: true, problem: (t) => ({ kind: "report", fault: "vehicle", description: t }) },
  { id: "unload", label: "Cannot unload here", hint: "Records this stop as not delivered.", problem: () => ({ kind: "not-delivered", reason: "access_blocked" }) },
  { id: "nobody", label: "No one at the outlet", hint: "Records this stop as not delivered.", problem: () => ({ kind: "not-delivered", reason: "outlet_closed" }) },
  { id: "damaged", label: "Goods damaged", hint: "Records this stop as not delivered.", problem: () => ({ kind: "not-delivered", reason: "goods_damaged" }) },
];

export function ProblemSheet({
  stop,
  busy,
  error,
  onSend,
  onClose,
}: {
  stop: Stop | null;
  busy: boolean;
  error: string | null;
  onSend: (problem: Problem) => void;
  onClose: () => void;
}): React.JSX.Element {
  const [chosen, setChosen] = useState<string | null>(null);
  const [text, setText] = useState("");
  const choice = CHOICES.find((c) => c.id === chosen) ?? null;
  // Reports about a stop need one; the road and the vehicle do not.
  const choices = stop ? CHOICES : CHOICES.filter((c) => c.problem("").kind === "report");
  const ready = choice !== null && (!choice.needsText || text.trim().length > 0);
  return (
    <Sheet label="Report a problem" onClose={onClose}>
      <div>
        <h2 className="text-[26px] font-medium text-go-ink">Report a problem</h2>
        {stop && <p className="text-[15px] text-go-muted">Stop {String(stop.sequence).padStart(2, "0")} · {stop.outletId}</p>}
      </div>
      <div role="radiogroup" aria-label="What is wrong?" className="flex flex-col gap-2.5">
        {choices.map((c) => (
          <button
            key={c.id}
            type="button"
            role="radio"
            aria-checked={chosen === c.id}
            onClick={() => setChosen(c.id)}
            className={`flex min-h-16 flex-col justify-center rounded-[20px] px-5 text-left ${
              chosen === c.id ? "bg-go-action text-go-on-action" : "bg-go-surface text-go-ink"
            }`}
          >
            <span className="text-[18px] font-medium">{c.label}</span>
            <span className="text-[13px] opacity-80">{c.hint}</span>
          </button>
        ))}
      </div>
      {choice && choice.problem("").kind === "report" && (
        <Field label={choice.needsText ? "What happened?" : "Anything to add? (optional)"}>
          <input className={input} value={text} maxLength={400} onChange={(event) => setText(event.target.value)} />
        </Field>
      )}
      {error && <Banner tone="bad" title={error} live />}
      <ActionButton disabled={!ready || busy} onClick={() => choice && onSend(choice.problem(text.trim()))}>
        {choice?.problem("").kind === "not-delivered" ? "Record this stop" : "Send to dispatch"}
      </ActionButton>
      <OutlineButton onClick={onClose}>Close</OutlineButton>
    </Sheet>
  );
}

export function SavedSheet({
  title,
  onPhone,
  last,
  warning,
  onNext,
  onHandover,
}: {
  title: string;
  /** Opens the store manager's PIN; absent when nothing was handed over. */
  onHandover?: () => void;
  /** Something about this stop still needs the driver, such as proof the server refused. */
  warning?: string | null;
  /** Saved on this phone and not yet on the server. */
  onPhone: boolean;
  /** No stop is left to do. */
  last: boolean;
  onNext: () => void;
}): React.JSX.Element {
  return (
    <Sheet label={title} onClose={onNext}>
      <div role="status" aria-live="polite">
        <h2 className="text-[26px] font-medium text-go-ink">{title}</h2>
        <p className="mt-1 text-[15px] text-go-muted">
          {onPhone
            ? "Saved on this phone. It is sent to dispatch as soon as the connection is back; keep the app installed until then."
            : "Saved and sent to dispatch."}
        </p>
      </div>
      {warning && <Banner tone="warn" title={warning} />}
      {onHandover && <OutlineButton onClick={onHandover}>Enter store manager PIN</OutlineButton>}
      <ActionButton onClick={onNext}>{last ? "Finish run" : "Next stop"}</ActionButton>
    </Sheet>
  );
}

export function SignOutSheet({
  waiting,
  online,
  onSignOut,
  onClose,
}: {
  /** Writes and proof artifacts still only on this phone. */
  waiting: number;
  online: boolean;
  onSignOut: () => void;
  onClose: () => void;
}): React.JSX.Element {
  return (
    <Sheet label="Sign out" onClose={onClose}>
      <div className="rounded-[22px] bg-go-surface p-5">
        <h2 className="text-[24px] font-medium text-go-ink">Sign out of GO?</h2>
        {waiting > 0 ? (
          <p className="mt-1 text-[16px] text-go-ink">
            {waiting} {waiting === 1 ? "record is" : "records are"} still only on this phone.{" "}
            {online
              ? `Wait for ${waiting === 1 ? "it" : "them"} to be sent, or review anything the server refused, before you sign out.`
              : `Reconnect so ${waiting === 1 ? "it" : "they"} can be sent before you sign out.`}
          </p>
        ) : (
          <p className="mt-1 text-[16px] text-go-muted">You'll need your email and password to sign in again. Your work is saved and sent.</p>
        )}
      </div>
      {waiting === 0 ? <ActionButton onClick={onSignOut}>Sign out</ActionButton> : <SoftButton onClick={onClose}>Stay signed in</SoftButton>}
      {waiting === 0 && <OutlineButton onClick={onClose}>Cancel</OutlineButton>}
    </Sheet>
  );
}
