"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { Notice, cx } from "@shared/ui";
import { crew, switchOperator, type CrewMember } from "@app-shell/operators";
import { checkOffline, keptCrew, logOfflineSwitch, pausedFor } from "@app-shell/offlinePin";
import type { Operator } from "@app-shell/session";
import { useT } from "../i18n.tsx";
import { CloseIcon, SearchIcon, WifiOffIcon } from "../icons.tsx";
import { initials } from "../ui.tsx";
import PinEntry, { type PinState } from "./PinEntry.tsx";

// Figma "08 Loader · Phone": 01 Who's loading, 11 Choose an employee, 12 No
// search matches, 13 Offline sign-in, then the PIN screens (PinEntry). Online
// the server checks the PIN. Offline the device checks it against the crew
// list it kept, and logs the switch for the server (R-IAM-27).

export default function OperatorGate({
  account,
  online,
  onOperator,
  recentId,
  unlock,
  onCancelUnlock,
  onPinStep,
}: {
  /** The supervisor account the device is signed in as. */
  account: string;
  online: boolean;
  onOperator: (operator: Operator) => void;
  /** The loader last working on this device, shown first and marked. */
  recentId?: string | null;
  /** Straight to the PIN of the loader who locked the device (Figma 10). */
  unlock?: CrewMember | null;
  onCancelUnlock?: () => void;
  /** True while the PIN screen is up: Figma shows it without the top bar. */
  onPinStep?: (pinStep: boolean) => void;
}): React.JSX.Element {
  const tr = useT();
  const [members, setMembers] = useState<CrewMember[]>(() => keptCrew(account)?.members ?? []);
  const [selected, setSelected] = useState<CrewMember | null>(unlock ?? null);
  const [query, setQuery] = useState("");
  const [pin, setPin] = useState("");
  const [state, setState] = useState<PinState>(() => {
    const wait = unlock ? Math.ceil(pausedFor(account, unlock.userId) / 1000) : 0;
    return wait > 0 ? { kind: "paused", seconds: wait } : { kind: "entering" };
  });
  const [error, setError] = useState<string | null>(null);
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
    onPinStep?.(selected !== null);
    return () => onPinStep?.(false);
  }, [selected, onPinStep]);

  // The pause counts down where the loader can see it (Figma 15).
  const paused = state.kind === "paused";
  useEffect(() => {
    if (!paused) return;
    const timer = window.setInterval(() => {
      setState((s) => (s.kind !== "paused" ? s : s.seconds <= 1 ? { kind: "entering" } : { kind: "paused", seconds: s.seconds - 1 }));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [paused]);

  const choose = (member: CrewMember) => {
    setSelected(member);
    setPin("");
    setError(null);
    // A pause kept on this device from offline tries still applies.
    const wait = Math.ceil(pausedFor(account, member.userId) / 1000);
    setState(wait > 0 ? { kind: "paused", seconds: wait } : { kind: "entering" });
  };

  const back = () => {
    if (unlock && onCancelUnlock) return onCancelUnlock();
    setSelected(null);
    setPin("");
    setError(null);
    setState({ kind: "entering" });
  };

  const refuse = (triesLeft: number, pausedSeconds: number) => {
    setPin("");
    setState(pausedSeconds > 0 ? { kind: "paused", seconds: pausedSeconds } : { kind: "wrong", triesLeft });
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

  const submit = async () => {
    if (!selected) return;
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

  if (selected) {
    return (
      <PinEntry
        title={unlock ? tr("Enter PIN to unlock") : tr("Enter your PIN")}
        who={[selected.displayName, selected.employeeCode].filter(Boolean).join(" · ")}
        pin={pin}
        onPin={(next) => {
          setPin(next);
          if (state.kind === "wrong") setState({ kind: "entering" });
        }}
        state={state}
        busy={busy}
        submitLabel={unlock ? tr("Unlock") : tr("Confirm")}
        onSubmit={() => void submit()}
        onBack={back}
        notice={error && <Notice tone="danger" live title={error} />}
      />
    );
  }

  const usable = (member: CrewMember) => online || member.offlineVerifier !== null;
  const q = query.trim().toLowerCase();
  const ordered = [...members].sort((a, b) => Number(b.userId === recentId) - Number(a.userId === recentId));
  const shown = ordered.filter(
    (m) => q === "" || m.displayName.toLowerCase().includes(q) || m.employeeCode.toLowerCase().includes(q),
  );

  return (
    <main className="flex w-full flex-col gap-5 px-4 pt-2 pb-8 md:mx-auto md:max-w-[560px]">
      <div className="flex flex-col gap-2">
        <h1 className="text-[34px] leading-tight font-semibold text-go-ink">{tr("Who's loading?")}</h1>
        <p className="text-[16px] text-go-muted">{tr("Shared device. Find your name, then enter your PIN.")}</p>
      </div>

      {!online && (
        <div role="status" className="flex items-start gap-3 rounded-go-card-l bg-go-card p-4 text-go-ink shadow-go-card">
          <WifiOffIcon />
          <div className="flex flex-col gap-0.5">
            <p className="text-[16px] font-medium">
              {members.length > 0 ? tr("You're offline. You can still sign in.") : tr("You're offline.")}
            </p>
            <p className="text-[14px] text-go-muted">
              {members.length > 0
                ? tr("Crew list from the last sync. Your loading syncs later.")
                : tr("No crew list on this device. Connect once to download it.")}
            </p>
          </div>
        </div>
      )}
      {error && <Notice tone="danger" live title={error} />}

      <div className="flex flex-col gap-2">
        <label
          className={cx(
            "flex min-h-14 items-center gap-3 rounded-go-card-s border-[1.5px] bg-go-card pr-2 pl-4 focus-within:border-go-signal",
            q ? "border-go-signal" : "border-go-rule",
          )}
        >
          <span className="text-go-muted"><SearchIcon /></span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={tr("Search name or ID, e.g. LDR-00038")}
            aria-label={tr("Search name or ID, e.g. LDR-00038")}
            className="min-w-0 flex-1 bg-transparent text-[17px] text-go-ink outline-none placeholder:text-go-placeholder focus-in-box"
          />
          {query && (
            <button type="button" onClick={() => setQuery("")} aria-label={tr("Clear search")} className="flex size-11 items-center justify-center rounded-full bg-go-surface text-go-ink">
              <CloseIcon />
            </button>
          )}
        </label>
        {q && shown.length === 0 && (
          <p className="px-1 text-[14px] text-go-muted">{tr("0 results for “{q}”", { q: query.trim() })}</p>
        )}
      </div>

      {q && shown.length === 0 ? (
        <div className="flex flex-col items-center gap-2 pt-6 text-center">
          <span className="flex size-14 items-center justify-center rounded-full bg-go-surface text-go-muted"><SearchIcon /></span>
          <p className="text-[18px] font-medium text-go-ink">{tr("No matching employees.")}</p>
          <p className="text-[15px] text-go-muted">{tr("Check the name or employee ID.")}</p>
          <button type="button" onClick={() => setQuery("")} className="mt-1 min-h-12 rounded-full bg-go-card px-6 text-[16px] text-go-ink shadow-go-float">
            {tr("Clear search")}
          </button>
        </div>
      ) : (
        <section className="flex flex-col gap-2.5" aria-label={tr("Crew")}>
          {shown.map((member) => {
            const recent = member.userId === recentId;
            return (
              <button
                key={member.userId}
                type="button"
                disabled={!usable(member)}
                onClick={() => choose(member)}
                className={cx(
                  "flex min-h-[68px] items-center gap-3.5 rounded-go-card-l px-4 text-left disabled:opacity-50",
                  recent ? "bg-go-action text-go-on-action" : "bg-go-card text-go-ink",
                )}
              >
                <span className={cx("flex size-12 shrink-0 items-center justify-center rounded-full text-[16px] font-medium", recent ? "bg-go-mint text-go-night" : "bg-go-surface text-go-ink")}>
                  {initials(member.displayName)}
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-[17px]">{member.displayName}</span>
                  <span className={cx("text-[13px]", recent ? "opacity-70" : "text-go-muted")}>
                    {usable(member) ? member.employeeCode : tr("Needs connection")}
                  </span>
                </span>
              </button>
            );
          })}
          {members.length === 0 && !error && online && (
            <p className="text-center text-[15px] text-go-muted">{tr("No PIN-enabled crew are available for this device.")}</p>
          )}
        </section>
      )}
    </main>
  );
}
