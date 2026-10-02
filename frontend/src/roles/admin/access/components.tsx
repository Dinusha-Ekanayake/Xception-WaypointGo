"use client";

import { useEffect, useRef, type ReactNode } from "react";
import type { Capability } from "./model";

export const card = "rounded-[22px] border border-[#deebe6] bg-white shadow-[0_3px_18px_rgba(15,61,45,.035)]";
export const field = "min-h-11 w-full rounded-xl border border-[#cbded5] bg-white px-3 py-2 text-sm text-[#10251e] outline-none focus:border-[#00896d] focus:ring-2 focus:ring-[#bdf3e9]";
export const primary = "inline-flex min-h-11 items-center justify-center rounded-full bg-[#006b57] px-5 py-2 text-sm font-semibold text-white hover:bg-[#004b3e] disabled:cursor-not-allowed disabled:opacity-50";
export const secondary = "inline-flex min-h-11 items-center justify-center rounded-full border border-[#cbded5] bg-white px-4 py-2 text-sm font-semibold text-[#1a3a2e] hover:bg-[#edf8f5] disabled:opacity-50";

export function Badge({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "green" | "amber" | "red" | "blue" }) {
  const colors = { neutral: "bg-[#eef4f1] text-[#53665a]", green: "bg-[#d8f4e7] text-[#006b47]", amber: "bg-[#fff2d8] text-[#835500]", red: "bg-[#fce7e5] text-[#a82a1d]", blue: "bg-[#d8f5ee] text-[#006b57]" };
  return <span className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold ${colors[tone]}`}>{children}</span>;
}

export function CapabilityRow({ capability, status, source, onDetails, onEdit, detailsLabel = "Why?" }: { capability: Capability; status: "Active" | "Available" | "Optional" | "Restricted" | "Coming later" | "No grant"; source?: string; onDetails: () => void; onEdit?: () => void; detailsLabel?: string }) {
  return <div className="flex flex-wrap items-center gap-3 border-b border-[#edf1ed] px-4 py-4 last:border-0 sm:px-5">
    <span aria-hidden="true" className={`grid size-9 shrink-0 place-items-center rounded-xl text-lg ${status === "Active" ? "bg-[#d8f5ee] text-[#006b57]" : "bg-[#f1f5f1] text-[#66756b]"}`}>{status === "Active" ? "✓" : status === "Coming later" ? "◷" : "○"}</span>
    <div className="min-w-44 flex-1"><p className="font-semibold text-[#14231e]">{capability.label}</p><p className="text-sm text-[#58685f]">{source ?? capability.description}</p></div>
    <Badge tone={status === "Active" ? "green" : status === "Available" ? "blue" : status === "Coming later" ? "amber" : status === "Restricted" ? "red" : "neutral"}>{status}</Badge>
    <button className="min-h-11 px-2 text-sm font-semibold text-[#006b57] hover:underline" onClick={onDetails}>{detailsLabel}</button>
    {onEdit && <button className={secondary} onClick={onEdit}>Edit</button>}
  </div>;
}

export function Modal({ title, children, onClose, wide = false }: { title: string; children: ReactNode; onClose: () => void; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const node = ref.current; node?.showModal(); return () => node?.close(); }, []);
  return <dialog ref={ref} onCancel={(event) => { event.preventDefault(); onClose(); }}
    aria-label={title} className={`fixed inset-0 m-auto max-h-[94dvh] w-[calc(100%-1.5rem)] overflow-y-auto rounded-3xl border border-[#d9e3de] bg-white p-0 text-[#14231e] shadow-2xl backdrop:bg-[#10261bb3] ${wide ? "max-w-2xl" : "max-w-xl"}`}>
    <div className="sticky top-0 z-10 flex items-center justify-between border-b border-[#e4ebe5] bg-white px-5 py-4 sm:px-7"><h2 className="text-xl font-semibold">{title}</h2><button aria-label="Close dialog" className="grid size-11 place-items-center rounded-full hover:bg-[#f1f5f1]" onClick={onClose}>✕</button></div>
    <div className="p-5 sm:p-7">{children}</div>
  </dialog>;
}

export function Empty({ children }: { children: ReactNode }) { return <div className="rounded-2xl border border-dashed border-[#cddbd3] bg-white px-6 py-12 text-center text-sm text-[#58685f]">{children}</div>; }
