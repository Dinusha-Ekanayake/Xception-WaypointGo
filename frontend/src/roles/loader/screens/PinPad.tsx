"use client";

import { useEffect, useRef } from "react";
import { cx } from "@shared/ui";
import { useT } from "../i18n.tsx";
import { BigButton } from "../ui.tsx";
import { usePinShake, type PinState } from "./PinEntry.tsx";
import { countdown } from "@shared/wording";

// The PIN side of the sign-in on a tablet, desk or terminal (Figma 07, 09 and
// 10: 01 Sign in, 01a no employee selected, E1 wrong PIN, E2 too many tries).
// It sits beside the crew list instead of replacing it, and the fourth digit
// signs in: there is no Confirm button. A real input sits under the keypad,
// so a terminal's keyboard, a password manager and a screen reader all work.

const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9"] as const;

export default function PinPad({
  title,
  pin,
  onPin,
  onComplete,
  state,
  busy,
}: {
  /** "Enter PIN" or "Enter PIN to unlock"; null until a name is chosen. */
  title: string | null;
  pin: string;
  onPin: (pin: string) => void;
  /** Called with the fourth digit, once. */
  onComplete: (pin: string) => void;
  state: PinState;
  busy: boolean;
}): React.JSX.Element {
  const tr = useT();
  const input = useRef<HTMLInputElement>(null);
  const chosen = title !== null;
  const paused = state.kind === "paused";
  const wrong = state.kind === "wrong";
  const shake = usePinShake(state);
  const off = !chosen || paused || busy;

  useEffect(() => {
    if (chosen && !paused) input.current?.focus();
  }, [chosen, paused, state]);

  const set = (next: string) => {
    const digits = next.replace(/\D/g, "").slice(0, 4);
    onPin(digits);
    if (digits.length === 4 && pin.length < 4) onComplete(digits);
  };

  const key = "flex size-20 items-center justify-center rounded-full shadow-go-float disabled:cursor-not-allowed";
  const keyTone = off ? "bg-go-subtle text-go-muted shadow-none" : "bg-go-card text-go-ink active:bg-go-surface";

  return (
    <section aria-label={tr("4-digit PIN")} className="flex flex-col items-center gap-5 pt-4">
      <label className="relative flex gap-4 p-2" htmlFor="loader-pin-pad">
        <span className="sr-only">{tr("4-digit PIN")}</span>
        <span key={shake} className={cx("flex gap-4", shake > 0 && wrong && "animate-pin-shake")}>
        {[0, 1, 2, 3].map((i) => (
          <span
            key={i}
            aria-hidden
            className={cx(
              "size-5 rounded-full border-2",
              wrong || paused ? "border-go-danger-strong" : i < pin.length ? "border-go-success bg-go-success" : chosen ? "border-go-signal bg-go-soft" : "border-go-rule",
            )}
          />
        ))}
        </span>
        <input
          ref={input}
          id="loader-pin-pad"
          type="password"
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="[0-9]{4}"
          maxLength={4}
          disabled={off}
          value={paused ? "" : pin}
          onChange={(e) => set(e.target.value)}
          className="absolute inset-0 h-full w-full cursor-default opacity-0"
        />
      </label>
      <span className="sr-only" aria-live="polite">{tr("{n} of 4 digits entered", { n: pin.length })}</span>

      <div className="flex min-h-[72px] max-w-[300px] flex-col items-center gap-1.5 text-center">
        <h2 className="text-[22px] leading-tight font-medium text-go-ink">
          {paused ? tr("PIN entry paused") : chosen ? title : tr("Select your name to continue.")}
        </h2>
        {paused && (
          <p className="text-[15px] text-go-muted">{tr("Too many tries. Wait {time}, or ask your supervisor.", { time: countdown(state.seconds) })}</p>
        )}
        {wrong && (
          <p role="alert" className="text-[15px] text-go-danger-strong">
            {tr(state.triesLeft === 1 ? "Incorrect PIN. {n} try left." : "Incorrect PIN. {n} tries left.", { n: state.triesLeft })}
          </p>
        )}
        {busy && <p className="text-[15px] text-go-muted">{tr("Checking…")}</p>}
      </div>

      <div className="grid grid-cols-3 gap-4">
        {KEYS.map((k) => (
          <button key={k} type="button" disabled={off} onClick={() => set(pin + k)} className={cx(key, keyTone, "text-[28px]")}>
            {k}
          </button>
        ))}
        <button type="button" disabled={off} onClick={() => onPin("")} className={cx(key, keyTone, "text-[18px]")}>
          {tr("Clear")}
        </button>
        <button type="button" disabled={off} onClick={() => set(pin + "0")} className={cx(key, keyTone, "text-[28px]")}>
          0
        </button>
        <button type="button" disabled={off} onClick={() => onPin(pin.slice(0, -1))} aria-label={tr("Delete last digit")} className={cx(key, keyTone)}>
          <svg aria-hidden viewBox="0 0 24 24" className="size-7" fill="currentColor">
            <path d="M21 5H8.5a1 1 0 0 0-.76.35l-5.5 6.5a1 1 0 0 0 0 1.3l5.5 6.5a1 1 0 0 0 .76.35H21a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1Zm-4.3 9.3-1.4 1.4-2.3-2.29-2.3 2.3-1.4-1.42L11.6 12 9.3 9.7l1.4-1.4 2.3 2.29 2.3-2.3 1.4 1.42L14.4 12Z" />
          </svg>
        </button>
      </div>

      {paused ? (
        <div className="w-[272px]">
          <BigButton tone="muted" disabled>{tr("Ask supervisor to reset")}</BigButton>
        </div>
      ) : (
        <p className="text-[14px] text-go-muted">{tr("Forgot PIN? Ask your supervisor.")}</p>
      )}
    </section>
  );
}
