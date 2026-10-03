"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "./Icon.tsx";
import { cx } from "./primitives.tsx";

export type MenuItem = {
  id: string;
  label: string;
  /** A second line under the label, in the secondary colour. */
  hint?: string;
  disabled?: boolean;
  /** Marks the item that is current, as the plan switcher marks the plan being looked at. */
  selected?: boolean;
};

/**
 * A button that opens a short list, as the plan screen draws "Regenerate",
 * "Filter" and the plan switcher. Escape and a click outside close it, and a
 * choice closes it. The list is positioned under the button, so it needs a
 * parent that does not clip overflow.
 */
export function Menu({
  label,
  items,
  onSelect,
  align = "left",
  className,
  children,
  disabled = false,
  chevron = false,
}: {
  /** What a screen reader calls the list. */
  label: string;
  items: MenuItem[];
  onSelect: (id: string) => void;
  align?: "left" | "right";
  /** Styles the button, so one Menu can be a pill, a field or a quiet link. */
  className?: string;
  children: ReactNode;
  disabled?: boolean;
  /** A small arrow after the label, as the design draws a menu button. */
  chevron?: boolean;
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

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
        className={cx("disabled:cursor-not-allowed disabled:opacity-40", className)}
      >
        {children}
        {chevron && <Icon name="chevron-down" />}
      </button>
      {open && (
        <ul
          role="menu"
          aria-label={label}
          className={cx(
            "absolute top-full z-30 mt-1.5 flex min-w-[220px] flex-col gap-0.5 rounded-go-card bg-go-card p-1.5 shadow-go-card ring-1 ring-go-rule",
            align === "right" ? "right-0" : "left-0",
          )}
        >
          {items.map((item) => (
            <li key={item.id} role="none">
              <button
                type="button"
                role="menuitem"
                disabled={item.disabled}
                onClick={() => {
                  setOpen(false);
                  onSelect(item.id);
                }}
                className={cx(
                  "flex w-full flex-col rounded-go-card-s px-3 py-2 text-left disabled:cursor-not-allowed disabled:opacity-40",
                  item.selected ? "bg-go-success-tint" : "hover:bg-go-subtle",
                )}
              >
                <span className="text-[13px] font-medium text-go-ink">{item.label}</span>
                {item.hint && <span className="text-xs text-go-secondary">{item.hint}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
