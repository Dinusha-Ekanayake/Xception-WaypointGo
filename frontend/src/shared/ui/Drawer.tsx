"use client";

import { useRef, type ReactNode } from "react";
import { cx } from "./primitives.tsx";
import { useDialogFocus } from "./useDialogFocus.ts";

/**
 * A panel that slides in from the right over a lightly dimmed page: the order
 * on Orders, the trip on the plan board. A click outside it closes it, as every
 * pop-up does; so do Escape and its own close button. Focus moves in, stays
 * inside, and returns where it was.
 */
export function Drawer({
  label,
  onClose,
  children,
  className,
}: {
  label: string;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}): React.JSX.Element {
  const panel = useRef<HTMLElement>(null);
  useDialogFocus(panel, onClose);
  return (
    <div className="fixed inset-0 z-40" role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close" onClick={onClose} className="absolute inset-0 cursor-default bg-black/15" />
      <aside
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={cx(
          "absolute inset-y-0 right-0 flex w-full max-w-[420px] animate-slide-in-end flex-col overflow-y-auto overscroll-contain rounded-l-go-panel bg-go-card shadow-go-float outline-none",
          className,
        )}
      >
        {children}
      </aside>
    </div>
  );
}
