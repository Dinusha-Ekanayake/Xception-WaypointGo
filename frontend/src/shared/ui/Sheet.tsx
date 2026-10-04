"use client";

import { useEffect, useRef, type ReactNode } from "react";

/**
 * A bottom sheet over a dimmed page, as the designs use for confirmations and
 * reports on a phone; a dialog near the top on wider screens. Escape closes it,
 * focus moves into it on open, stays inside while it is open, and returns to
 * where it was. Built on theme tokens, so it follows a dark subtree.
 */
export function Sheet({
  label,
  onClose,
  children,
  size = "default",
}: {
  label: string;
  onClose: () => void;
  children: ReactNode;
  /** "wide" is the plan's trip window: the page width, no padding, the content lays out its own header, body and footer. */
  size?: "default" | "wide";
}): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () =>
      Array.from(
        panel.current?.querySelectorAll<HTMLElement>("button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])") ?? [],
      ).filter((el) => !el.hasAttribute("disabled"));
    (focusable()[0] ?? panel.current)?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close.current();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      previous?.focus();
    };
  }, []);

  return (
    <div className={`fixed inset-0 z-40 flex items-end justify-center md:p-6 ${size === "wide" ? "md:items-center" : "md:items-start md:pt-10"}`} role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close" onClick={onClose} className="absolute inset-0 bg-black/40 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={
          size === "wide"
            ? "relative flex max-h-[92dvh] w-full max-w-[1220px] flex-col overflow-hidden rounded-t-[28px] bg-go-card text-go-ink outline-none md:max-h-[calc(100dvh-48px)] md:rounded-[28px]"
            : "relative flex max-h-[92dvh] w-full max-w-[560px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-go-card px-5 pt-6 pb-8 text-go-ink outline-none md:max-h-[calc(100dvh-64px)] md:rounded-[32px] md:px-7 md:pb-7"
        }
      >
        {children}
      </div>
    </div>
  );
}
