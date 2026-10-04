"use client";

import { useRef, type ReactNode } from "react";
import { useSheetDrag } from "./sheetDrag.ts";
import { useOverlay } from "./useOverlay.ts";

/**
 * A bottom sheet over a dimmed page, as the designs use for confirmations and
 * reports on a phone; a dialog near the top on wider screens. Escape closes it,
 * focus moves into it on open, stays inside while it is open, and returns to
 * where it was. It rises in, and on a phone a swipe down closes it
 * (useOverlay, useSheetDrag). Built on theme tokens, so it follows a dark subtree.
 */
export function Sheet({
  label,
  onClose,
  children,
  size = "default",
  dismissible = true,
  placement = "viewport",
}: {
  label: string;
  onClose: () => void;
  children: ReactNode;
  /** "wide" is the plan's trip window: the page width, no padding, the content lays out its own header, body and footer. */
  size?: "default" | "wide";
  /** False for a sheet holding typed input, so a stray swipe cannot drop it. */
  dismissible?: boolean;
  /** "frame" sits at the bottom of the nearest positioned ancestor, such as the driver's phone frame on a desk, and blurs everything in it, header included. */
  placement?: "viewport" | "frame";
}): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  useSheetDrag(panel, onClose, dismissible);

  if (placement === "frame") {
    return (
      <div data-closing={closing || undefined} className="go-overlay absolute inset-0 z-50 flex items-end justify-center" role="presentation">
        <button type="button" tabIndex={-1} aria-label="Close" onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/35 backdrop-blur-[6px]" />
        <div
          ref={panel}
          tabIndex={-1}
          role="dialog"
          aria-modal="true"
          aria-label={label}
          data-full-frame
          className="go-panel go-panel-sheet go-panel-frame relative flex max-h-[92%] w-full flex-col gap-4 overflow-y-auto rounded-t-[40px] bg-go-card px-5 pt-5 pb-9 text-go-ink outline-none"
        >
          {children}
        </div>
      </div>
    );
  }

  return (
    <div data-closing={closing || undefined} className={`go-overlay fixed inset-0 z-40 flex items-end justify-center md:p-6 ${size === "wide" ? "md:items-center" : "md:items-start md:pt-10"}`} role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close" onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/40 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={
          size === "wide"
            ? "go-panel go-panel-sheet relative flex max-h-[92dvh] w-full max-w-[1220px] flex-col overflow-hidden rounded-t-[28px] bg-go-card text-go-ink outline-none md:max-h-[calc(100dvh-48px)] md:rounded-[28px]"
            : "go-panel go-panel-sheet relative flex max-h-[92dvh] w-full max-w-[560px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-go-card px-5 pt-6 pb-8 text-go-ink outline-none md:max-h-[calc(100dvh-64px)] md:rounded-[32px] md:px-7 md:pb-7"
        }
      >
        {children}
      </div>
    </div>
  );
}
