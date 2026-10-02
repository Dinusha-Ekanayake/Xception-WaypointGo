"use client";

import { useEffect, useRef } from "react";
import type { OutletView } from "@shared/domain/types";
import { useShell } from "@shared/ui";

// Figma "Overlay · Account menu": on a shared counter computer, who is signed in
// and for which outlet, dock and window, with one Sign out. Staff ID and the
// sign-in time are left out by decision. Signing out goes through the shell,
// which first says when writes are still waiting on this device (SEC-01).

export default function AccountMenu({
  displayName,
  initials,
  outlet,
  onClose,
}: {
  displayName: string;
  initials: string;
  outlet: OutletView | null;
  onClose: () => void;
}): React.JSX.Element {
  const shell = useShell();
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });

  // Once, on opening: the screen polls and re-renders, and that must not pull focus back.
  useEffect(() => {
    // The panel takes focus, not Sign out, so a stray Enter signs nobody out.
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close.current();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const rows: [string, string][] = [
    ["Role", "Store manager"],
    ...(outlet
      ? ([
          ["Brand", outlet.brandCode],
          ["Outlet", `${outlet.districtName} · ${outlet.outletId}`],
          ["Receiving", `${outlet.dockType.charAt(0).toUpperCase()}${outlet.dockType.slice(1)} dock · ${outlet.windowOpen.slice(0, 5)}-${outlet.windowClose.slice(0, 5)}`],
          ["Depot", outlet.depotCode],
        ] as [string, string][])
      : []),
  ];

  return (
    <>
      <button type="button" aria-label="Close the account menu" tabIndex={-1} onClick={onClose} className="fixed inset-0 z-40 cursor-default" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-label="Account"
        className="absolute bottom-[84px] left-5 z-50 flex w-[280px] flex-col gap-1 rounded-[20px] bg-white p-[7px] shadow-[0_10px_30px_rgba(0,0,0,0.18)] outline-none"
      >
        <div className="flex items-center gap-3 p-2.5">
          <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-mint text-[14px] font-semibold text-black">
            {initials}
          </span>
          <span className="flex min-w-0 flex-col">
            <span className="truncate text-[15px] font-medium text-black">{displayName}</span>
            <span className="text-[12px] text-go-muted">Store manager</span>
          </span>
        </div>
        <dl className="flex flex-col gap-2 rounded-[14px] bg-go-canvas px-2.5 py-3 text-[12px]">
          {rows.map(([label, value]) => (
            <div key={label} className="flex justify-between gap-3">
              <dt className="text-go-muted">{label}</dt>
              <dd className="text-right font-medium text-black">{value}</dd>
            </div>
          ))}
        </dl>
        <button
          type="button"
          disabled={!shell}
          onClick={() => {
            onClose();
            shell?.onSignOut();
          }}
          className="mt-1 flex min-h-12 items-center gap-2.5 rounded-[14px] bg-go-danger-tint px-3 text-[14px] font-medium text-go-danger-strong disabled:opacity-50"
        >
          <svg aria-hidden viewBox="0 0 24 24" className="size-[18px] fill-none stroke-current stroke-2">
            <path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l-5-5 5-5M5 12h11" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          Sign out
        </button>
      </div>
    </>
  );
}
