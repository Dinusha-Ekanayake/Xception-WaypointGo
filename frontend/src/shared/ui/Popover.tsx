"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { cx } from "./primitives.tsx";

/**
 * A button that opens a small card next to it: the trip checks and trip
 * details on the plan board (dark), the account card and the depot scope in
 * the sidebar rail (light). Escape and a click outside close it. Unlike Menu it
 * holds any content, not a list of choices.
 */
export function Popover({
  label,
  trigger,
  children,
  tone = "light",
  side = "bottom",
  align = "left",
  className,
  panelClassName,
}: {
  /** What a screen reader calls the card. */
  label: string;
  /** The button's own content. */
  trigger: ReactNode;
  /** The card, or a function given a way to close it from inside. */
  children: ReactNode | ((close: () => void) => ReactNode);
  tone?: "light" | "dark";
  side?: "bottom" | "top" | "right";
  align?: "left" | "right";
  /** Styles the button. */
  className?: string;
  panelClassName?: string;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const outside = (event: MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  const close = () => setOpen(false);
  const place =
    side === "top"
      ? cx("bottom-full mb-2", align === "right" ? "right-0" : "left-0")
      : side === "right"
        ? "bottom-0 left-full ml-3"
        : cx("top-full mt-2", align === "right" ? "right-0" : "left-0");

  return (
    <div ref={root} className="relative">
      <button type="button" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((value) => !value)} className={className}>
        {trigger}
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={label}
          className={cx(
            "absolute z-40 rounded-go-card p-3",
            tone === "dark" ? "bg-go-ink text-white shadow-go-float" : "bg-go-card text-go-ink shadow-go-card ring-1 ring-go-rule",
            place,
            panelClassName,
          )}
        >
          {typeof children === "function" ? children(close) : children}
        </div>
      )}
    </div>
  );
}
