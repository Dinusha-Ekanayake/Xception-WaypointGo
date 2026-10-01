"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { Icon, Notice } from "@shared/ui";
import { crew, switchOperator, type CrewMember } from "@app-shell/operators";
import { checkOffline, keptCrew, logOfflineSwitch } from "@app-shell/offlinePin";
import type { Operator } from "@app-shell/session";
import { BigButton } from "../ui.tsx";
import { useT } from "../i18n.tsx";

// Figma "00 Who's loading", "00b PIN", E1 wrong PIN and E2 paused. Online the
// server checks the PIN. Offline the device checks it against the crew list it
// kept, and logs the switch for the server (R-IAM-20).

export default function OperatorGate({
  account,
  online,
  onOperator,
}: {
  /** The supervisor account the device is signed in as. */
  account: string;
  online: boolean;
  onOperator: (operator: Operator) => void;
}): React.JSX.Element {
  const tr = useT();
  const [members, setMembers] = useState<CrewMember[]>(() => keptCrew(account)?.members ?? []);
  const [selected, setSelected] = useState<CrewMember | null>(null);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [triesLeft, setTriesLeft] = useState<number | null>(null);
  const [retryAfter, setRetryAfter] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!online) {
      setMembers(keptCrew(account)?.members ?? []);
      return;
    }
    let active = true;
    crew(account).then((result) => active && setMembers(result.members)).catch((failure: unknown) => {
      if (active) setError(failure instanceof Error ? failure.message : "Could not load the crew list.");
    });
    return () => { active = false; };
  }, [online, account]);

  useEffect(() => {
    if (retryAfter <= 0) return;
    const timer = window.setTimeout(() => setRetryAfter((seconds) => Math.max(0, seconds - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [retryAfter]);

  const refuse = (left: number, pausedSeconds: number) => {
    setPin("");
    if (pausedSeconds > 0) {
      setRetryAfter(pausedSeconds);
      setTriesLeft(null);
      setError(tr("Too many attempts. Try again in {n} seconds.", { n: pausedSeconds }));
    } else {
      setTriesLeft(left);
      setError(tr("That PIN didn't match. Try again."));
    }
  };

  const unlockOffline = async (member: CrewMember) => {
    const result = await checkOffline(account, member, pin);
    if (!result.ok) return refuse(result.triesLeft, Math.ceil(result.pausedMs / 1000));
    const since = new Date().toISOString();
    if (!logOfflineSwitch(account, { userId: member.userId, at: since })) {
      return setError(tr("This device can't save the switch. Connect to switch operator."));
    }
    onOperator({ userId: member.userId, displayName: member.displayName, employeeCode: member.employeeCode, since });
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!selected || pin.length !== 4 || retryAfter > 0) return;
    setBusy(true);
    setError(null);
    try {
      if (!online) await unlockOffline(selected);
      else onOperator(await switchOperator(account, selected.userId, pin));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        refuse(Number(failure.problem.extensions.triesLeft ?? 0), 0);
      } else if (failure instanceof ApiError && failure.status === 429) {
        refuse(0, Number(failure.problem.extensions.retryAfterSeconds ?? 300));
      } else {
        setError(failure instanceof Error ? failure.message : "Could not switch operator.");
      }
    } finally {
      setBusy(false);
    }
  };

  const usable = (member: CrewMember) => online || member.offlineVerifier !== null;

  return (
    <main className="mx-auto flex min-h-[70dvh] w-full max-w-[440px] flex-col justify-center gap-5 px-5 py-8">
      <div className="flex flex-col items-center gap-2 text-center">
        <span className="flex size-16 items-center justify-center rounded-full bg-go-mint"><Icon name="lock" /></span>
        <h1 className="text-[28px] font-semibold text-black">{tr("Who's loading?")}</h1>
        <p className="text-[15px] text-go-muted">{tr("Choose your name and enter your 4-digit PIN.")}</p>
      </div>
      {!online && (
        <Notice tone="warning" title={tr("Offline: PIN checked on this device")}>
          {members.length > 0 ? tr("Your work is saved here and sent under your name when the connection returns.") : tr("No crew list on this device. Connect once to download it.")}
        </Notice>
      )}
      {error && <Notice tone="danger" live title={error}>{triesLeft !== null && ` ${tr(triesLeft === 1 ? "{n} try left." : "{n} tries left.", { n: triesLeft })}`}</Notice>}
      {!selected ? (
        <section className="flex flex-col gap-3" aria-label="Crew">
          {members.map((member) => (
            <button key={member.userId} type="button" disabled={!usable(member)} onClick={() => { setSelected(member); setError(null); setTriesLeft(null); }}
              className="flex min-h-16 items-center gap-3 rounded-[20px] bg-white px-4 text-left shadow-[0_5px_20px_rgba(0,0,0,0.08)] disabled:opacity-50">
              <span className="flex size-11 items-center justify-center rounded-full bg-go-mint font-semibold">{member.displayName.slice(0, 1)}</span>
              <span className="flex flex-1 flex-col">
                <span className="text-[17px] font-medium">{member.displayName}</span>
                <span className="text-[13px] text-go-muted">{usable(member) ? member.employeeCode : tr("Needs connection")}</span>
              </span>
              <Icon name="chevron-right" />
            </button>
          ))}
          {members.length === 0 && !error && online && <p className="text-center text-[15px] text-go-muted">No PIN-enabled crew are available for this device.</p>}
        </section>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-5 rounded-[28px] bg-white p-6 shadow-[0_5px_20px_rgba(0,0,0,0.08)]">
          <button type="button" onClick={() => { setSelected(null); setPin(""); setError(null); }} className="min-h-12 self-start text-[15px] font-medium text-go-teal">{tr("‹ Crew list")}</button>
          <div className="text-center"><h2 className="text-[22px] font-semibold">{selected.displayName}</h2><p className="text-[14px] text-go-muted">{tr("Enter your PIN to unlock")}</p></div>
          <label className="sr-only" htmlFor="loader-pin">4-digit PIN</label>
          <input id="loader-pin" autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{4}" maxLength={4} value={pin}
            onChange={(event) => setPin(event.target.value.replace(/\D/g, "").slice(0, 4))} placeholder="••••" type="password"
            className="h-16 rounded-[18px] border border-[#dfe3e8] text-center text-[28px] tracking-[0.65em]" />
          <BigButton type="submit" size="l" disabled={busy || pin.length !== 4 || retryAfter > 0}>
            {retryAfter > 0 ? tr("Try again in {n}s", { n: retryAfter }) : tr(busy ? "Checking…" : "Unlock loader")}
          </BigButton>
        </form>
      )}
    </main>
  );
}
