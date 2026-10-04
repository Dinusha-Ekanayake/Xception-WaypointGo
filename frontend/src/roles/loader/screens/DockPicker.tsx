"use client";

import { useEffect, useRef } from "react";
import { Icon, cx } from "@shared/ui";
import { useT } from "../i18n.tsx";

export const DOCK_KEY = "waypoint.loader.dock";

/**
 * Figma 03 Change dock: the dock pill, and a short list over the board to pick
 * another. The board stays in view behind it. Escape or a tap outside closes it.
 */
export default function DockPicker({
  docks,
  dock,
  allLabel,
  open,
  onOpen,
  onPick,
}: {
  docks: string[];
  dock: string;
  allLabel: string;
  open: boolean;
  onOpen: (open: boolean) => void;
  onPick: (dock: string) => void;
}): React.JSX.Element {
  const tr = useT();
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    box.current?.querySelector<HTMLElement>("[aria-selected='true'], [role='option']")?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onOpen(false);
    const onDown = (e: PointerEvent) => !box.current?.contains(e.target as Node) && onOpen(false);
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onDown);
    };
  }, [open, onOpen]);
  const options = [{ value: "", label: allLabel }, ...docks.map((d) => ({ value: d, label: d }))];

  return (
    <div ref={box} className="relative w-fit">
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={tr("Dock")}
        onClick={() => onOpen(!open)}
        className="flex min-h-14 items-center gap-2.5 rounded-full bg-go-card pr-4 pl-4 text-[18px] font-medium text-go-ink shadow-go-float"
      >
        <Icon name="dock" />
        {dock || allLabel}
        <span aria-hidden className="h-6 w-px bg-go-rule" />
        <Icon name="chevron-down" />
      </button>
      {open && (
        <div role="listbox" aria-label={tr("Dock")} className="absolute top-full left-0 z-20 mt-2 flex min-w-[200px] flex-col gap-1 rounded-[24px] bg-go-card p-3 shadow-go-card">
          {options.map((o) => (
            <button
              key={o.value || "all"}
              type="button"
              role="option"
              aria-selected={o.value === dock}
              onClick={() => onPick(o.value)}
              className={cx(
                "min-h-12 rounded-[16px] px-4 text-left text-[17px] text-go-ink",
                o.value === dock ? "border border-go-mint bg-go-canvas" : "border border-transparent",
              )}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
