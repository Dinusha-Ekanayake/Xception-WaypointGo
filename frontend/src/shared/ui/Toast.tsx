"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { ruleLabel } from "../wording/rules.ts";
import { cx } from "./primitives.tsx";

// The pop-up the designs draw at the bottom of the screen: a short confirmation
// after a change went through ("Kept deferred · The store gets the reason at
// publish"), or a refusal with the server's reasons and every rule it named.
// A pop-up does not take the screen's space. A confirmation goes by itself; a
// refusal stays until it is closed (its button, Escape or a click anywhere
// else, as every pop-up), because a failure that disappears unread is not
// reported.

export type ToastMessage = {
  title: string;
  detail?: string;
  /** "error": a refusal or an outage; stays until closed. */
  tone?: "info" | "error";
  /** One line each, under the title. */
  lines?: string[];
  /** Rule ids the server named, for whoever needs them. */
  rules?: string[];
  /** One step back, such as "Undo"; the toast then stays a little longer. */
  action?: { label: string; onAction: () => void };
};

type ToastApi = { show: (message: ToastMessage) => void };

const ToastContext = createContext<ToastApi | null>(null);

// One timing in every role: 4 s to read a confirmation, 6 s when it offers a step back.
const SHOWN_MS = 4000;
const WITH_ACTION_MS = 6000;

export function ToastProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [message, setMessage] = useState<(ToastMessage & { id: number }) | null>(null);
  const next = useRef(0);
  const card = useRef<HTMLDivElement>(null);

  const show = useCallback((toast: ToastMessage) => {
    next.current += 1;
    setMessage({ ...toast, id: next.current });
  }, []);

  useEffect(() => {
    if (!message || message.tone === "error") return;
    const timer = window.setTimeout(() => setMessage(null), message.action ? WITH_ACTION_MS : SHOWN_MS);
    return () => window.clearTimeout(timer);
  }, [message]);

  // A click outside the pop-up, or Escape, closes it.
  useEffect(() => {
    if (!message) return;
    const outside = (event: PointerEvent) => {
      if (card.current && !card.current.contains(event.target as Node)) setMessage(null);
    };
    const escape = (event: KeyboardEvent) => event.key === "Escape" && setMessage(null);
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [message]);

  const error = message?.tone === "error";

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      <div role={error ? "alert" : "status"} aria-live={error ? "assertive" : "polite"} className="pointer-events-none fixed inset-x-0 bottom-6 z-50 flex justify-center px-4">
        {message && (
          <div
            key={message.id}
            ref={card}
            className={cx(
              "pointer-events-auto flex max-w-[640px] animate-rise-in gap-3 rounded-go-input px-4 py-3 text-[13px] shadow-go-float",
              error ? "items-start bg-go-card text-go-ink ring-1 ring-go-danger/40" : "items-center bg-go-ink text-white",
            )}
          >
            {error && <span aria-hidden className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-go-danger-tint text-[12px] font-semibold text-go-danger-strong">!</span>}
            <div className="flex min-w-0 flex-col gap-1">
              <p>
                <span className={cx("font-semibold", error && "text-go-danger-strong")}>{message.title}</span>
                {message.detail && (
                  <>
                    {" "}
                    <span className={error ? "text-go-ink" : "text-white/80"}>{message.detail}</span>
                  </>
                )}
              </p>
              {(message.lines ?? []).map((line, index) => (
                <p key={index}>{line}</p>
              ))}
              {(message.rules ?? []).length > 0 && (
                <p className="flex flex-wrap gap-1.5">
                  {message.rules!.map((rule) => (
                    <span key={rule} className="rounded-go-chip bg-go-surface px-1.5 py-0.5 text-[11px] font-medium">
                      {ruleLabel(rule)}
                    </span>
                  ))}
                </p>
              )}
            </div>
            {message.action && !error && (
              <button
                type="button"
                onClick={() => {
                  message.action?.onAction();
                  setMessage(null);
                }}
                className="ml-1 shrink-0 rounded-full px-2 py-1 font-semibold text-go-mint active:opacity-70"
              >
                {message.action.label}
              </button>
            )}
            {error && (
              <button type="button" onClick={() => setMessage(null)} aria-label="Close" className="ml-1 shrink-0 rounded-full px-1.5 text-[15px] leading-none text-go-secondary hover:text-go-ink">
                ×
              </button>
            )}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}

/** Shows a toast; a no-op outside a provider, so a screen still renders in isolation. */
export function useToast(): (message: ToastMessage) => void {
  const api = useContext(ToastContext);
  return api?.show ?? noop;
}

function noop(): void {}
