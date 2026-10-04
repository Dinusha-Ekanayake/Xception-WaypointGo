"use client";

import { useEffect, useRef, useState } from "react";
import { cx } from "@shared/ui";
import { useT } from "../i18n.tsx";
import { ChevronLeftIcon } from "../icons.tsx";
import { BigButton } from "../ui.tsx";
import { countdown } from "@shared/wording";

// Figma "08 Loader · Phone": 09 Enter PIN, 10 Unlock with PIN, 14 Incorrect PIN
// and 15 PIN entry paused. Four boxes over one real input, so the phone's
// number pad opens, a password manager can fill it, and a screen reader reads
// one field.

export type PinState =
  | { kind: "entering" }
  | { kind: "wrong"; triesLeft: number }
  | { kind: "paused"; seconds: number };

/** A new wrong answer shakes the boxes. The digits themselves are cleared by the caller. */
export function usePinShake(state: PinState): number {
  const [shake, setShake] = useState(0);
  useEffect(() => {
    if (state.kind === "wrong") setShake((n) => n + 1);
  }, [state]);
  return shake;
}

export default function PinEntry({
  title,
  who,
  pin,
  onPin,
  state,
  busy,
  submitLabel,
  onSubmit,
  onBack,
  notice,
}: {
  /** "Enter your PIN" or "Enter PIN to unlock". */
  title: string;
  /** The person, "Isuru Sudarshana · LDR-00038". */
  who: string;
  pin: string;
  onPin: (pin: string) => void;
  state: PinState;
  busy: boolean;
  /** "Confirm" or "Unlock". */
  submitLabel: string;
  onSubmit: () => void;
  onBack: () => void;
  /** A connection or storage message, under the card. */
  notice?: React.ReactNode;
}): React.JSX.Element {
  const tr = useT();
  const input = useRef<HTMLInputElement>(null);
  const paused = state.kind === "paused";
  const wrong = state.kind === "wrong";
  const shake = usePinShake(state);
  useEffect(() => {
    if (!paused) input.current?.focus();
  }, [paused, state]);

  return (
    <main className="flex w-full flex-col gap-6 px-4 pt-2 pb-8 md:mx-auto md:max-w-[480px]">
      <button type="button" onClick={onBack} className="flex min-h-12 items-center gap-3 self-start text-[18px] text-go-ink">
        <ChevronLeftIcon /> {tr("Back")}
      </button>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!paused && pin.length === 4 && !busy) onSubmit();
        }}
        className="mt-[12vh] flex flex-col items-center gap-4 rounded-go-panel bg-go-card px-6 py-7 shadow-go-card"
      >
        <h1 className="text-center text-[22px] font-medium text-go-ink">{paused ? tr("PIN entry paused") : title}</h1>
        <p className="text-center text-[14px] text-go-secondary">
          {paused ? tr("Too many tries. Wait {time}.", { time: countdown(state.seconds) }) : who}
        </p>
        <label className="relative rounded-[14px] p-1 has-[input:focus-visible]:ring-2 has-[input:focus-visible]:ring-go-signal" htmlFor="loader-pin">
          <span className="sr-only">{tr("4-digit PIN")}</span>
          <span key={shake} className={cx("flex gap-3", shake > 0 && wrong && "animate-pin-shake")} aria-hidden>
            {[0, 1, 2, 3].map((i) => {
              const filled = !paused && i < pin.length;
              return (
                <span
                  key={i}
                  className={cx(
                    "flex size-14 items-center justify-center rounded-[12px] border-[1.5px] bg-go-subtle",
                    wrong ? "border-go-danger-strong" : filled ? "border-go-success" : "border-go-rule",
                  )}
                >
                  {filled && <span className="size-3 rounded-full bg-go-success" />}
                </span>
              );
            })}
          </span>
          <input
            ref={input}
            id="loader-pin"
            type="password"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{4}"
            maxLength={4}
            disabled={paused}
            value={paused ? "" : pin}
            onChange={(e) => onPin(e.target.value.replace(/\D/g, "").slice(0, 4))}
            className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
          />
        </label>
        {wrong && (
          <p role="alert" className="text-[15px] text-go-danger-strong">
            {tr(state.triesLeft === 1 ? "Incorrect PIN. {n} try left." : "Incorrect PIN. {n} tries left.", { n: state.triesLeft })}
          </p>
        )}
        {paused ? (
          <BigButton tone="muted" size="l" disabled>
            {tr("Ask supervisor to reset")}
          </BigButton>
        ) : (
          <BigButton tone="ink" size="l" type="submit" busy={busy}>
            {busy ? tr("Checking…") : submitLabel}
          </BigButton>
        )}
      </form>
      {notice}
    </main>
  );
}
