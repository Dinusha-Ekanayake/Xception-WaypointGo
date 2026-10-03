"use client";

import { useEffect, useRef } from "react";
import type { OutletView } from "@shared/domain/types";
import { cx, useShell } from "@shared/ui";
import { dockLabel } from "./data/format.ts";

// Figma "Overlay · Account menu": on a shared counter computer, who is signed in
// and for which outlet, dock and window, with one Sign out. Staff ID and the
// sign-in time are left out by decision. From here the manager also edits their
// own profile (R-IAM-32) and the store's details (R-REF-01). Signing out goes
// through the shell, which first says when writes are still waiting on this
// device (SEC-01). Beside the sidebar on a desktop; a bottom sheet on a phone.

export default function AccountMenu({
  displayName,
  initials,
  outlet,
  placement = "sidebar",
  onEditProfile,
  onEditStore,
  onClose,
}: {
  displayName: string;
  initials: string;
  outlet: OutletView | null;
  placement?: "sidebar" | "sheet";
  onEditProfile: () => void;
  onEditStore: () => void;
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
          ["Receiving", `${dockLabel(outlet.dockType)} · ${outlet.windowOpen.slice(0, 5)}-${outlet.windowClose.slice(0, 5)}`],
          ["Depot", outlet.depotCode],
        ] as [string, string][])
      : []),
  ];

  return (
    <>
      <button
        type="button"
        aria-label="Close the account menu"
        tabIndex={-1}
        onClick={onClose}
        className={cx("fixed inset-0 z-40 cursor-default", placement === "sheet" && "bg-black/20 backdrop-blur-[6px]")}
      />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-label="Account"
        className={cx(
          "z-50 flex flex-col gap-1 bg-white p-[7px] shadow-[0_10px_30px_rgba(0,0,0,0.18)] outline-none",
          placement === "sheet" ? "fixed inset-x-0 bottom-0 rounded-t-[28px] px-4 pt-4 pb-8" : "absolute bottom-[84px] left-5 w-[280px] rounded-[20px]",
        )}
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
        {[
          { label: "Your profile", note: "Name and phone", run: onEditProfile },
          { label: "Store details", note: "Window, dock and contacts", run: onEditStore },
        ].map((item) => (
          <button
            key={item.label}
            type="button"
            onClick={() => {
              onClose();
              item.run();
            }}
            className="flex min-h-12 flex-col justify-center rounded-[14px] px-3 text-left hover:bg-go-surface"
          >
            <span className="text-[14px] font-medium text-black">{item.label}</span>
            <span className="text-[12px] text-go-secondary">{item.note}</span>
          </button>
        ))}
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
