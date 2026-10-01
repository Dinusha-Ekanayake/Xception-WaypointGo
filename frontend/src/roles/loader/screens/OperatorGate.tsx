"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { Icon, Notice } from "@shared/ui";
import { crew, switchOperator, type CrewMember } from "@app-shell/operators";
import type { Operator } from "@app-shell/session";
import { BigButton } from "../ui.tsx";

export default function OperatorGate({
  online,
  pending,
  onOperator,
}: {
  online: boolean;
  pending: number;
  onOperator: (operator: Operator) => void;
}): React.JSX.Element {
  const [members, setMembers] = useState<CrewMember[]>([]);
  const [selected, setSelected] = useState<CrewMember | null>(null);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [triesLeft, setTriesLeft] = useState<number | null>(null);
  const [retryAfter, setRetryAfter] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!online) return;
    let active = true;
    crew().then((result) => active && setMembers(result)).catch((failure: unknown) => {
      if (active) setError(failure instanceof Error ? failure.message : "Could not load the crew list.");
    });
    return () => { active = false; };
  }, [online]);

  useEffect(() => {
    if (retryAfter <= 0) return;
    const timer = window.setTimeout(() => setRetryAfter((seconds) => Math.max(0, seconds - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [retryAfter]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!selected || pin.length !== 4 || !online || pending > 0 || retryAfter > 0) return;
    setBusy(true);
    setError(null);
    try {
      onOperator(await switchOperator(selected.userId, pin));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        setTriesLeft(Number(failure.problem.extensions.triesLeft ?? 0));
        setError("That PIN didn't match. Try again.");
        setPin("");
      } else if (failure instanceof ApiError && failure.status === 429) {
        const seconds = Number(failure.problem.extensions.retryAfterSeconds ?? 300);
        setRetryAfter(seconds);
        setError(`Too many attempts. Try again in ${seconds} seconds.`);
      } else {
        setError(failure instanceof Error ? failure.message : "Could not switch operator.");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-[70dvh] w-full max-w-[440px] flex-col justify-center gap-5 px-5 py-8">
      <div className="flex flex-col items-center gap-2 text-center">
        <span className="flex size-16 items-center justify-center rounded-full bg-go-mint"><Icon name="lock" /></span>
        <h1 className="text-[28px] font-semibold text-black">Who&apos;s loading?</h1>
        <p className="text-[15px] text-go-muted">Choose your name and enter your 4-digit PIN.</p>
      </div>
      {pending > 0 && (
        <Notice tone="warning" title="Send saved loading work before switching">
          {pending} saved {pending === 1 ? "change is" : "changes are"} still on this device. Reconnect and sync them first.
        </Notice>
      )}
      {!online && <Notice tone="warning" title="Connect to switch operator">PIN switching needs a connection. Saved work stays on this device.</Notice>}
      {error && <Notice tone="danger" live title={error}>{triesLeft !== null && ` ${triesLeft} ${triesLeft === 1 ? "try" : "tries"} left.`}</Notice>}
      {!selected ? (
        <section className="flex flex-col gap-3" aria-label="Crew">
          {members.map((member) => (
            <button key={member.userId} type="button" disabled={!online || pending > 0} onClick={() => { setSelected(member); setError(null); }}
              className="flex min-h-16 items-center gap-3 rounded-[20px] bg-white px-4 text-left shadow-[0_5px_20px_rgba(0,0,0,0.08)] disabled:opacity-50">
              <span className="flex size-11 items-center justify-center rounded-full bg-go-mint font-semibold">{member.displayName.slice(0, 1)}</span>
              <span className="flex flex-1 flex-col"><span className="text-[17px] font-medium">{member.displayName}</span><span className="text-[13px] text-go-muted">{member.employeeCode}</span></span>
              <Icon name="chevron-right" />
            </button>
          ))}
          {members.length === 0 && !error && <p className="text-center text-[15px] text-go-muted">No PIN-enabled crew are available for this device.</p>}
        </section>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-5 rounded-[28px] bg-white p-6 shadow-[0_5px_20px_rgba(0,0,0,0.08)]">
          <button type="button" onClick={() => { setSelected(null); setPin(""); setError(null); }} className="min-h-12 self-start text-[15px] font-medium text-go-teal">‹ Crew list</button>
          <div className="text-center"><h2 className="text-[22px] font-semibold">{selected.displayName}</h2><p className="text-[14px] text-go-muted">Enter your PIN to unlock</p></div>
          <label className="sr-only" htmlFor="loader-pin">4-digit PIN</label>
          <input id="loader-pin" autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{4}" maxLength={4} value={pin}
            onChange={(event) => setPin(event.target.value.replace(/\D/g, "").slice(0, 4))} placeholder="••••" type="password"
            className="h-16 rounded-[18px] border border-[#dfe3e8] text-center text-[28px] tracking-[0.65em]" />
          <BigButton type="submit" size="l" disabled={busy || pin.length !== 4 || !online || pending > 0 || retryAfter > 0}>
            {retryAfter > 0 ? `Try again in ${retryAfter}s` : busy ? "Checking…" : "Unlock loader"}
          </BigButton>
        </form>
      )}
    </main>
  );
}
