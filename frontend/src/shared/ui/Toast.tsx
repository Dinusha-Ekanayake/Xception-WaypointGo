"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

// The short confirmation the designs draw at the bottom of the screen after a
// change went through ("Kept deferred · The store gets the reason at publish").
// Only for success: a refusal stays on screen beside what was refused, because a
// message that disappears is not a way to report a failure.

export type ToastMessage = { title: string; detail?: string };

type ToastApi = { show: (message: ToastMessage) => void };

const ToastContext = createContext<ToastApi | null>(null);

const SHOWN_MS = 4000;

export function ToastProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [message, setMessage] = useState<(ToastMessage & { id: number }) | null>(null);
  const next = useRef(0);

  const show = useCallback((toast: ToastMessage) => {
    next.current += 1;
    setMessage({ ...toast, id: next.current });
  }, []);

  useEffect(() => {
    if (!message) return;
    const timer = window.setTimeout(() => setMessage(null), SHOWN_MS);
    return () => window.clearTimeout(timer);
  }, [message]);

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      <div role="status" aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-6 z-50 flex justify-center px-4">
        {message && (
          <p
            key={message.id}
            className="pointer-events-auto flex max-w-[640px] items-center gap-3 rounded-go-input bg-go-ink px-4 py-3 text-[13px] text-white shadow-go-float"
          >
            <span className="font-semibold">{message.title}</span>
            {message.detail && (
              <>
                {" "}
                <span className="text-white/80">{message.detail}</span>
              </>
            )}
          </p>
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
