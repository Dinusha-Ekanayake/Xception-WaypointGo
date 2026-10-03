"use client";

import { useEffect, useRef, type ReactNode } from "react";
import type { Capability, Persona } from "./model";

export function PersonaIcon({ persona, className = "size-6" }: { persona: Persona; className?: string }) {
  switch (persona) {
    case "dispatcher":
      // Route & waypoint dispatch navigation
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <circle cx="6" cy="19" r="2.5" />
          <circle cx="18" cy="5" r="2.5" />
          <path d="M8.5 19H13a5 5 0 0 0 5-5V7.5" />
          <polyline points="15 11 18 8 21 11" />
        </svg>
      );
    case "loader":
      // Warehouse cargo box / package crate
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" />
          <path d="m3.3 7 8.7 5 8.7-5" />
          <path d="M12 22V12" />
        </svg>
      );
    case "driver":
      // Delivery vehicle / transport truck
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <path d="M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2" />
          <path d="M15 8h4l3 4v5a1 1 0 0 1-1 1h-2" />
          <circle cx="7.5" cy="18.5" r="2.5" />
          <circle cx="16.5" cy="18.5" r="2.5" />
          <path d="M10 18h4" />
        </svg>
      );
    case "store_manager":
      // Storefront / retail outlet
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <path d="M3 9l1.5-6h15L21 9" />
          <path d="M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0" />
          <path d="M4 9v11a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V9" />
          <path d="M9 21v-7a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v7" />
        </svg>
      );
    case "admin":
      // Administrative security / IAM user governance
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
          <circle cx="12" cy="10" r="3" />
          <path d="M7 18a5 5 0 0 1 10 0" />
        </svg>
      );
    case "super_admin":
      // Protected root governance shield with crown
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
          <path d="M8 14h8l-1-4-3 2-3-2-1 4Z" fill="currentColor" fillOpacity="0.2" />
          <circle cx="12" cy="7.5" r="1" fill="currentColor" />
        </svg>
      );
  }
}

export function VehicleTypeIcon({ type, className = "size-6" }: { type: string; className?: string }) {
  if (type.toLowerCase() === "van") {
    return (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
        <path d="M3 6h10a1 1 0 0 1 1 1v10H4a1 1 0 0 1-1-1V6Z" />
        <path d="M14 9h3.5a1 1 0 0 1 .8.4l2.4 3.2a1 1 0 0 1 .3.6V17h-3" />
        <circle cx="7" cy="17.5" r="2" />
        <circle cx="17" cy="17.5" r="2" />
        <path d="M9 17.5h6" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
      <rect x="2" y="5" width="12" height="11" rx="1" />
      <path d="M14 8h4.5a1 1 0 0 1 .8.4l2.4 3.2a1 1 0 0 1 .3.6V16h-3" />
      <circle cx="6" cy="18" r="2" />
      <circle cx="18" cy="18" r="2" />
      <path d="M8 18h8" />
    </svg>
  );
}

export const card = "rounded-go-panel bg-go-card shadow-go-card";
export const field = "min-h-11 w-full rounded-go-card-s bg-go-surface px-3 py-2 text-sm text-go-ink outline-none focus:ring-2 focus:ring-go-teal";
export const primary = "inline-flex min-h-11 items-center justify-center rounded-full bg-go-ink px-5 py-2 text-sm font-medium text-go-card disabled:cursor-not-allowed disabled:opacity-40";
export const secondary = "inline-flex min-h-11 items-center justify-center rounded-full border border-go-rule bg-go-card px-4 py-2 text-sm font-medium text-go-ink hover:bg-go-subtle disabled:opacity-40";

export function Badge({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "green" | "amber" | "red" | "blue" }) {
  const colors = { neutral: "bg-go-surface text-go-secondary", green: "bg-go-mint text-go-ink", amber: "bg-go-warning-tint text-go-warning-text", red: "bg-go-danger-tint text-go-danger", blue: "bg-go-subtle text-go-teal" };
  return <span className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold ${colors[tone]}`}>{children}</span>;
}

export function CapabilityRow({ capability, status, source, onDetails, onEdit, detailsLabel = "Why?" }: { capability: Capability; status: "Active" | "Available" | "Optional" | "Restricted" | "Unavailable" | "No grant"; source?: string; onDetails: () => void; onEdit?: () => void; detailsLabel?: string }) {
  return <div className="flex flex-wrap items-center gap-3 border-b border-go-rule px-4 py-4 last:border-0 sm:px-5">
    <span aria-hidden="true" className={`grid size-9 shrink-0 place-items-center rounded-xl text-lg ${status === "Active" ? "bg-go-mint text-go-teal" : "bg-go-subtle text-go-secondary"}`}>{status === "Active" ? "✓" : status === "Unavailable" ? "◷" : "○"}</span>
    <div className="min-w-44 flex-1"><p className="font-semibold text-go-ink">{capability.label}</p><p className="text-sm text-go-secondary">{source ?? capability.description}</p></div>
    <Badge tone={status === "Active" ? "green" : status === "Available" ? "blue" : status === "Unavailable" ? "amber" : status === "Restricted" ? "red" : "neutral"}>{status}</Badge>
    <button className="min-h-11 px-2 text-sm font-semibold text-go-teal hover:underline" onClick={onDetails}>{detailsLabel}</button>
    {onEdit && <button className={secondary} onClick={onEdit}>Edit</button>}
  </div>;
}

export function Modal({ title, children, onClose, wide = false }: { title: string; children: ReactNode; onClose: () => void; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const node = ref.current; node?.showModal(); return () => node?.close(); }, []);
  return <dialog ref={ref} onCancel={(event) => { event.preventDefault(); onClose(); }}
    aria-label={title} className={`fixed inset-0 m-auto max-h-[94dvh] w-[calc(100%-1.5rem)] overflow-y-auto rounded-3xl border border-go-rule bg-white p-0 text-go-ink shadow-2xl backdrop:bg-[#10261bb3] ${wide ? "max-w-2xl" : "max-w-xl"}`}>
    <div className="sticky top-0 z-10 flex items-center justify-between border-b border-go-rule bg-white px-5 py-4 sm:px-7"><h2 className="text-xl font-semibold">{title}</h2><button aria-label="Close dialog" className="grid size-11 place-items-center rounded-full hover:bg-go-subtle" onClick={onClose}>✕</button></div>
    <div className="p-5 sm:p-7">{children}</div>
  </dialog>;
}

export function Empty({ children }: { children: ReactNode }) { return <div className="rounded-2xl border border-dashed border-go-rule bg-white px-6 py-12 text-center text-sm text-go-secondary">{children}</div>; }
