"use client";

import { useEffect, useRef, useState } from "react";
import { ProofImages } from "./ProofImages";
import { useDeliveryDraft } from "./useDeliveryDraft";
import type {
  ButtonHTMLAttributes,
  FormEvent,
  MouseEvent,
  ReactNode,
} from "react";
import {
  NoteFormSchema,
  ProofFormSchema,
  type AppState,
  type Order,
  type OrderStatus,
} from "../lib/types";

/* ---------- Primary button: one black pill per viewport ---------- */

const BTN_BASE =
  "inline-flex items-center justify-center gap-2 leading-snug text-center font-semibold transition-all active:scale-[0.98] " +
  "disabled:cursor-not-allowed disabled:opacity-40 disabled:active:scale-100 cursor-pointer";

const BTN_SIZE = {
  sm: "min-h-11 px-4 py-2 text-copy rounded-full",
  md: "min-h-11 px-5 py-2.5 text-copy rounded-full",
  lg: "min-h-14 px-4 py-3 w-full text-copy rounded-full",
} as const;

const BTN_VARIANT = {
  primary: "bg-[#0a6b63] text-white hover:bg-[#084f4a]",
  outline: "border border-[var(--ui-line)] bg-[var(--ui-surface)] text-[var(--ui-text)] hover:bg-[var(--ui-soft)]",
  ghost: "min-h-0 border-0 bg-transparent p-0 text-[var(--ui-link)] font-medium hover:text-[var(--ui-link)]",
  danger: "bg-[#e11900] text-white hover:brightness-110 border border-[#e11900]",
  dark: "bg-[#161616] text-white hover:bg-[#282828] border border-[#2a2a2a]",
} as const;

export type BtnVariant = keyof typeof BTN_VARIANT;
export type BtnSize = keyof typeof BTN_SIZE;

export interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: BtnVariant;
  size?: BtnSize;
}

export function Btn({
  variant = "outline",
  size = "md",
  className = "",
  ...props
}: BtnProps): ReactNode {
  return (
    <button
      className={[BTN_BASE, BTN_SIZE[size], BTN_VARIANT[variant], className].join(" ")}
      {...props}
    />
  );
}

/* ---------- Card: 16px radius, flat, hairline border ---------- */

export function Card({
  className = "",
  ...props
}: React.HTMLAttributes<HTMLElement>): ReactNode {
  return <section className={`wp-card overflow-hidden ${className}`} {...props} />;
}

export function CardHeader({
  title,
  badge,
  meta,
}: {
  title: string;
  badge?: ReactNode;
  meta?: ReactNode;
}): ReactNode {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
      <h2 className="m-0 text-copy font-semibold tracking-tight">{title}</h2>
      {badge ?? <span className="text-caption text-muted">{meta}</span>}
    </div>
  );
}

export function Empty({
  title = "Nothing here yet",
  children,
}: {
  title?: string;
  children?: ReactNode;
}): ReactNode {
  return (
    <div className="px-6 py-12 text-center">
      <div className="mx-auto mb-3 flex min-h-12 w-12 items-center justify-center rounded-full bg-[#efefef] text-xl text-muted">○</div>
      <h3 className="m-0 text-copy font-semibold">{title}</h3>
      <p className="mx-auto mt-1 max-w-[280px] text-copy text-muted">{children}</p>
    </div>
  );
}

export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}): ReactNode {
  return (
    <label className="mb-0 block text-copy font-medium text-[var(--ui-text)]">
      {label}
      <span className="mt-1.5 block">{children}</span>
    </label>
  );
}

export const inputClass =
  "min-h-[48px] w-full rounded-xl border border-[var(--ui-line)] bg-[var(--ui-soft)] px-4 py-3 text-copy text-[var(--ui-text)] placeholder:text-[#888f8d] transition-colors focus:border-[#0a6b63] focus:bg-[var(--ui-surface)] focus:outline-none focus:ring-2 focus:ring-[#0a6b63]/15";

/* ---------- time helpers (Asia/Colombo) ---------- */

const TZ = "Asia/Colombo";

export function fmtClock(iso: string | null | undefined): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: TZ });
}

export function fmtDayLabel(day: string | null | undefined): string {
  if (!day) return "";
  return new Date(`${day}T00:00:00+05:30`).toLocaleDateString("en-GB", {
    weekday: "short", day: "numeric", month: "short", timeZone: TZ,
  });
}

/** Operating-day number Mon-Sat (1-6); Sunday is closed. */
export function operatingDayNo(day: string): number | null {
  const dow = new Date(`${day}T00:00:00+05:30`).getDay();
  return dow === 0 ? null : dow;
}

export function fmtMinutes(min: number | null | undefined): string {
  if (min == null || Number.isNaN(min)) return "-";
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

/** Countdown to the next 16:00 order cutoff. */
export function cutoffCountdown(nowIso: string | null | undefined): string {
  if (!nowIso) return "";
  const now = new Date(nowIso).getTime();
  const colombo = new Date(new Date(now).toLocaleString("en-US", { timeZone: TZ }));
  const cutoff = new Date(colombo);
  cutoff.setHours(16, 0, 0, 0);
  if (colombo >= cutoff) cutoff.setDate(cutoff.getDate() + 1);
  const mins = Math.max(0, Math.round((cutoff.getTime() - colombo.getTime()) / 60000));
  return `${Math.floor(mins / 60)}h ${mins % 60}m left`;
}

/** Plain-language unloading note derived from the outlet's real dock type. */
export function dockNote(dockType: string | undefined): string {
  if (dockType === "rear_dock") return "Rear dock · Ring twice, receiver is in the cold room.";
  if (dockType === "mall_bay") return "Mall bay · Deliver inside the mall access window.";
  return "Curbside · Park clear of traffic, keep reefer closed until receiver arrives.";
}

/* ---------- domain badges: pills ---------- */

export const STATUS_LABELS: Record<OrderStatus, string> = {
  resolved: "Resolved",
  confirmed_order: "Confirmed order",
  planned: "Planned",
  loaded: "Loaded",
  departed: "On the way",
  arrived: "Arrived",
  delivered: "Delivered",
  confirmed: "Receipt confirmed",
  deferred: "Deferred",
  shortfall: "Needs attention",
  partial: "Partial",
  failed: "Not delivered",
  disputed: "Disputed",
};

const STATUS_TONE: Partial<Record<OrderStatus, string>> = {
  loaded: "bg-okbg text-ok",
  delivered: "bg-okbg text-ok",
  confirmed: "bg-[#0a6b63] text-white",
  departed: "bg-[#edf3f1] text-[var(--ui-link)]",
  arrived: "bg-[#edf3f1] text-[var(--ui-link)]",
  shortfall: "bg-badbg text-bad",
  failed: "bg-[#e11900] text-white",
  disputed: "bg-badbg text-bad",
  deferred: "bg-grapebg text-grape",
  partial: "bg-warnbg text-warn",
};

export function Badge({ status }: { status: string }): ReactNode {
  const tone = (STATUS_TONE as Record<string, string>)[status] ?? "bg-[#efefef] text-[#1f1f1f]";
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-caption font-semibold whitespace-nowrap ${tone}`}>
      <i className="h-1.5 w-1.5 rounded-full bg-current" />
      {(STATUS_LABELS as Record<string, string>)[status] ?? status}
    </span>
  );
}

const BRAND_TONE: Record<string, string> = {
  Fresh: "bg-okbg text-ok",
  Style: "bg-[#fbe9f1] text-[#9b2a5e]",
  Tech: "bg-[#edf3f1] text-[var(--ui-link)]",
};

export function Brand({ order }: { order: Pick<Order, "brand" | "temp" | "parking_constraint"> }): ReactNode {
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <span className={`rounded-full px-2.5 py-1 text-caption font-semibold whitespace-nowrap ${BRAND_TONE[order.brand] ?? "bg-[#efefef] text-[#1f1f1f]"}`}>
        {order.brand}
      </span>
      {order.temp === "chilled" && (
        <span className="rounded-full bg-[#e3f4fa] px-2.5 py-1 text-caption font-semibold whitespace-nowrap text-[#0b6179]">
          ❄ Chilled
        </span>
      )}
      {order.parking_constraint === "van_only" && (
        <span className="rounded-full bg-[#efefef] px-2.5 py-1 text-caption font-semibold whitespace-nowrap text-[#1f1f1f]">
          Van only
        </span>
      )}
    </span>
  );
}

/* ---------- meter: black fill, green when good ---------- */

export function Meter({
  label,
  text,
  pct,
  warn,
}: {
  label: string;
  text: string;
  pct: number;
  warn?: boolean;
}): ReactNode {
  const fill = warn ? "bg-[#e11900]" : pct >= 90 ? "bg-[#7a4a00]" : "bg-[#0a6b63]";
  return (
    <div className="mt-2 flex items-center gap-2 font-mono text-caption text-muted">
      <span className="w-7 font-semibold">{label}</span>
      <span className="h-[6px] flex-1 overflow-hidden rounded-full bg-[#efefef]">
        <i className={`block h-full rounded-full ${fill}`} style={{ width: `${Math.min(100, pct)}%` }} />
      </span>
      <span className={`font-semibold ${warn ? "text-bad" : "text-[var(--ui-text)]"}`}>{text}</span>
    </div>
  );
}

/* ---------- bottom-sheet modal ---------- */

export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}): ReactNode {
  const ref = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    if (ref.current && !ref.current.open) ref.current.showModal();
    return () => {
      try {
        ref.current?.close();
      } catch {
        /* already closed */
      }
    };
  }, []);

  function dismissOnBackdrop(e: MouseEvent<HTMLDialogElement>): void {
    if (e.target === ref.current) onClose();
  }

  return (
    <dialog
      ref={ref}
      onCancel={onClose}
      onClick={dismissOnBackdrop}
      className="bg-[var(--ui-surface)] text-[var(--ui-text)] m-auto w-[min(640px,94vw)] rounded-2xl border-0 p-0 shadow-2xl backdrop:bg-black/60 max-md:mt-auto max-md:mb-0 max-md:w-full max-md:max-w-full max-md:rounded-b-none max-md:rounded-t-3xl"
    >
      <div className="mx-auto mt-3 h-1 w-10 rounded-full bg-[#e2e2e2] md:hidden" />
      <div className="flex items-center justify-between px-5 py-4">
        <h2 className="m-0 text-[19px] font-semibold tracking-tight">{title}</h2>
        <button aria-label="Close dialog" onClick={onClose} className="flex h-9 w-9 items-center justify-center rounded-full bg-[var(--ui-soft)] text-[18px] text-[var(--ui-text)] hover:bg-[var(--ui-hover)]">
          ×
        </button>
      </div>
      <div className="max-h-[75vh] overflow-auto px-5 pb-6">{children}</div>
    </dialog>
  );
}

export interface NoteFormData {
  note: string;
  count?: number;
}

export function NoteForm({
  title,
  label = "Explanation",
  onSubmit,
  onClose,
  count = false,
  max = 99999,
  busy,
}: {
  title: string;
  label?: string;
  onSubmit: (data: NoteFormData) => void | Promise<void>;
  onClose: () => void;
  count?: boolean;
  max?: number;
  busy?: boolean;
}): ReactNode {
  const [error, setError] = useState("");

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const raw = {
      note: String(data.get("note") ?? ""),
      ...(count ? { count: Number(data.get("count")) } : {}),
    };
    const parsed = NoteFormSchema.safeParse(raw);
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "A non-empty explanation is required.");
      return;
    }
    if (count && (parsed.data.count == null || parsed.data.count < 1 || parsed.data.count > max)) {
      setError("Invalid quantity.");
      return;
    }
    setError("");
    void onSubmit({ note: parsed.data.note, ...(count ? { count: parsed.data.count } : {}) });
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Field label={label}>
          <textarea name="note" required maxLength={500} rows={4} placeholder="Tell the next role exactly what to do…" className={inputClass} />
        </Field>
        {count && (
          <Field label="Missing or damaged cases">
            <input name="count" type="number" min="1" max={max} defaultValue="1" required className={inputClass} />
          </Field>
        )}
        {error && <p role="alert" className="rounded-xl bg-badbg p-3 text-copy font-medium text-bad">{error}</p>}
        <div className="mt-1 flex gap-2">
          <Btn type="button" onClick={onClose} className="flex-1">Cancel</Btn>
          <Btn type="submit" variant="primary" disabled={busy} className="flex-[2]">Save</Btn>
        </div>
      </form>
    </Modal>
  );
}

/* ---------- trip receipt: order detail ---------- */

export function OrderDetail({
  order,
  state,
  onClose,
}: {
  order: Order;
  state: AppState;
  onClose: () => void;
}): ReactNode {
  const events = state.events.filter((e) => e.order_id === order.id);

  return (
    <Modal title="Delivery receipt" onClose={onClose}>
      <div className="flex flex-col gap-4">
        {/* ride header */}
        <div className="rounded-2xl bg-[#0a6b63] p-5 text-white">
          <div className="flex items-center justify-between">
            <span className="font-mono text-caption text-white/80">{order.id}</span>
            <Badge status={order.status} />
          </div>
          <h3 className="m-0 mt-2 text-[24px] font-semibold tracking-tight">{order.brand} · {order.district}</h3>
          <p className="m-0 mt-1 text-copy text-white/80">{order.outlet_id} · {order.window_open_time}-{order.window_close_time} · ETA {order.eta || "-"}</p>
          <div className="mt-3"><Brand order={order} /></div>
        </div>

        {order.parent_order_id && <p>Follow-up for order {order.parent_order_id}</p>}
        {order.exception_resolution && <div className="rounded-xl bg-okbg p-4">
          <strong>Exception resolved: {order.exception_resolution.decision}</strong>
          <p>{order.exception_resolution.note}</p>
          {order.exception_resolution.replacement_order_id && <p>Replacement order: {order.exception_resolution.replacement_order_id}</p>}
        </div>}
        <dl className="grid grid-cols-1 min-[400px]:grid-cols-2 gap-3 text-copy">
          <div className="rounded-2xl bg-[var(--ui-soft)] p-3.5">
            <dt className="text-caption text-muted">Load</dt><dd className="m-0 mt-0.5 font-semibold">{order.units} cases · {order.weight} kg · {order.volume} m³</dd>
          </div>
          <div className="rounded-2xl bg-[var(--ui-soft)] p-3.5">
            <dt className="text-caption text-muted">Vehicle / stop</dt><dd className="m-0 mt-0.5 font-semibold">{order.vehicle_id || "Unassigned"} · {order.route_id || "-"}{order.sequence ? ` · #${order.sequence}` : ""}</dd>
          </div>
          <div className="rounded-2xl bg-[var(--ui-soft)] p-3.5">
            <dt className="text-caption text-muted">Day</dt><dd className="m-0 mt-0.5 font-semibold">{order.day}</dd>
          </div>
          <div className="rounded-2xl bg-[var(--ui-soft)] p-3.5">
            <dt className="text-caption text-muted">Dock</dt><dd className="m-0 mt-0.5 font-semibold">{dockNote(order.dock_type)}</dd>
          </div>
        </dl>

        {order.deferral && (
          <div className="rounded-2xl bg-grapebg p-4 text-copy">
            <strong>Deferred - {order.deferral.next_date}</strong>
            <p className="m-0 mt-1">{order.deferral.reason}</p>
            {order.deferral.justification && <p className="m-0 mt-1 text-muted">Dispatcher: {order.deferral.justification}</p>}
          </div>
        )}

        {order.shortfall && (
          <div className="rounded-2xl bg-warnbg p-4 text-copy">
            <strong>Shortfall · {order.shortfall.count} cases</strong>
            <p className="m-0 mt-1">{order.shortfall.note}</p>
            {order.resolution && <p className="m-0 mt-1 font-medium text-ok">Resolved: {order.resolution.note}</p>}
          </div>
        )}

        {order.proof && (
          <section className="rounded-2xl border border-[var(--ui-line)] p-4">
            <h3 className="m-0 text-copy font-semibold">Proof of delivery</h3>
            <p className="mt-1 text-copy">{order.proof.count} / {order.units} cases · {order.proof.receiver || order.proof.note}</p>
            <ProofImages accountId={state.user.id} order={order} />
          </section>
        )}

        <div>
          <h3 className="m-0 text-copy font-semibold">Shared timeline</h3>
          {events.length ? (
            <ol className="m-0 mt-2 list-none p-0">
              {events.map((e) => (
                <li key={e.id} className="flex gap-3 border-l-2 border-black pl-4 pb-3 last:pb-0 ml-1">
                  <div>
                    <strong className="text-copy">{e.kind.replace(/_/g, " ")}</strong>
                    <div className="text-caption text-muted">{e.actor.split("@")[0]} · {new Date(e.created).toLocaleString()}</div>
                  </div>
                </li>
              ))}
            </ol>
          ) : (
            <p className="m-0 mt-1 text-copy text-muted">Imported from the supplied operational dataset.</p>
          )}
        </div>
      </div>
    </Modal>
  );
}

/* ---------- signature pad ---------- */

export function Signature({ value = "", onChange }: { value?: string; onChange: (url: string) => void }): ReactNode {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const drawing = useRef(false);
  const hasStroke = useRef(false);
  const last = useRef<[number, number] | null>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d")!;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!value) return;
    const image = new Image();
    let active = true;
    image.onload = () => { if (active) ctx.drawImage(image, 0, 0); };
    image.src = value;
    return () => { active = false; };
  }, [value]);

  function point(e: React.PointerEvent<HTMLCanvasElement>): [number, number] {
    const rect = ref.current!.getBoundingClientRect();
    return [(e.clientX - rect.left) * 600 / rect.width, (e.clientY - rect.top) * 180 / rect.height];
  }

  function draw(e: React.PointerEvent<HTMLCanvasElement>): void {
    if (!drawing.current || !ref.current || !last.current) return;
    const ctx = ref.current.getContext("2d")!;
    const p = point(e);
    ctx.strokeStyle = "#000000";
    ctx.lineWidth = 3;
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.moveTo(...last.current);
    ctx.lineTo(...p);
    ctx.stroke();
    hasStroke.current = true;
    last.current = p;
  }

  function clear(): void {
    ref.current?.getContext("2d")?.clearRect(0, 0, 600, 180);
    onChange("");
  }

  return (
    <div>
      <canvas
        aria-label="Draw receiver signature"
        ref={ref}
        width="600"
        height="180"
        className="touch-none w-full rounded-xl border border-[var(--ui-line)] bg-[#edf3f1]"
        onPointerDown={(e) => {
          drawing.current = true;
          hasStroke.current = false;
          last.current = point(e);
          e.currentTarget.setPointerCapture(e.pointerId);
        }}
        onPointerMove={draw}
        onPointerUp={() => {
          drawing.current = false;
          if (hasStroke.current && ref.current) onChange(ref.current.toDataURL());
        }}
        onPointerCancel={() => {
          drawing.current = false;
          if (hasStroke.current && ref.current) onChange(ref.current.toDataURL());
        }}
      />
      <button type="button" onClick={clear} className="mt-1 min-h-10 rounded-lg px-2 text-copy font-semibold text-[var(--ui-link)] hover:bg-[#edf3f1]">Clear signature</button>
    </div>
  );
}

/* ---------- proof of delivery ---------- */

export type ProofOutcome = "delivered" | "partial" | "failed";

export interface ProofSubmitData {
  outcome: ProofOutcome;
  receiver: string;
  count: number;
  note: string;
  signature: string;
  photo: string;
}

export function ProofForm({
  order,
  draftKey,
  initialOutcome = "delivered",
  onSubmit,
  onClose,
  busy,
}: {
  order: Order;
  initialOutcome?: ProofOutcome;
  draftKey: string;
  onSubmit: (data: ProofSubmitData) => Promise<boolean>;
  onClose: () => void;
  busy?: boolean;
}): ReactNode {
  const { draft, update, ready, status, clear } = useDeliveryDraft(draftKey, {
    outcome: initialOutcome, receiver: "", count: String(initialOutcome === "failed" ? 0 : order.units),
    note: "", signature: "", photo: "",
  });
  const { outcome, signature, photo } = draft;
  const [error, setError] = useState("");

  function handlePhoto(e: React.ChangeEvent<HTMLInputElement>): void {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!["image/png", "image/jpeg"].includes(file.type) || file.size > 1000000) {
      setError("Use a PNG/JPEG photo smaller than 1 MB.");
      update({ photo: "" });
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      update({ photo: String(reader.result) });
      setError("");
    };
    reader.readAsDataURL(file);
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>): Promise<void> {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const raw = {
      outcome,
      receiver: String(data.get("receiver") ?? ""),
      count: Number(data.get("count")),
      note: String(data.get("note") ?? ""),
      signature,
      photo,
    };
    const parsed = ProofFormSchema.safeParse(raw);
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "Check the delivery record.");
      return;
    }
    if (parsed.data.count < 0 || parsed.data.count > order.units) {
      setError("Delivered count does not match the outcome.");
      return;
    }
    if (
      (outcome === "delivered" && parsed.data.count !== order.units) ||
      (outcome === "partial" && (parsed.data.count <= 0 || parsed.data.count >= order.units)) ||
      (outcome === "failed" && parsed.data.count !== 0)
    ) {
      setError("Delivered count does not match the outcome.");
      return;
    }
    setError("");
    const submitted = await onSubmit({
      outcome,
      receiver: parsed.data.receiver,
      count: parsed.data.count,
      note: parsed.data.note,
      signature,
      photo,
    });
    if (submitted) await clear().catch(() => {});
  }

  return (
    <Modal title="Record delivery" onClose={onClose}>
      <p className="m-0 text-copy text-muted">{order.outlet_id} · {order.id} · {order.units} cases expected</p>
      <p role="status" className="mt-3 rounded-xl bg-accentbg p-3 text-copy text-accentdark">{status}</p>
      <form onSubmit={handleSubmit} className="mt-3">
        <fieldset disabled={!ready || busy} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
        <div className="grid grid-cols-3 gap-2">
          {(["delivered", "partial", "failed"] as ProofOutcome[]).map((o) => (
            <button
              key={o} type="button" onClick={() => update({ outcome: o, count: String(o === "failed" ? 0 : order.units) })}
              className={`min-h-12 rounded-full text-copy font-semibold ${outcome === o ? "bg-[#0a6b63] text-white" : "bg-[var(--ui-soft)] text-[var(--ui-text)]"}`}
            >
              {o === "delivered" ? "✓ Full" : o === "partial" ? "◐ Partial" : "✕ Failed"}
            </button>
          ))}
        </div>

        <Field label="Cases delivered">
          <input name="count" type="number" value={draft.count} onChange={(e) => update({ count: e.target.value })} min="0" max={order.units} required className={inputClass} />
        </Field>

        {outcome !== "failed" && (
          <>
            <Field label="Receiver name">
              <input name="receiver" value={draft.receiver} onChange={(e) => update({ receiver: e.target.value })} required maxLength={100} placeholder="Who received the goods?" className={inputClass} />
            </Field>
            <Field label="Delivery photo">
              <input type="file" accept="image/png,image/jpeg" capture="environment" onChange={handlePhoto} className={inputClass} />
            </Field>
            {photo && <img className="h-32 w-full rounded-2xl border border-[var(--ui-line)] object-cover" src={photo} alt="Selected delivery photo" />}
            <span className="text-copy font-medium">Receiver signature</span>
            <Signature value={signature} onChange={(signature) => update({ signature })} />
          </>
        )}

        <Field label="Delivery note">
          <textarea name="note" value={draft.note} onChange={(e) => update({ note: e.target.value })} required={outcome !== "delivered"} maxLength={500} placeholder={outcome === "failed" ? "Why couldn't you deliver? (required)" : "Anything the store should know…"} className={inputClass} />
        </Field>

        {error && <p role="alert" className="rounded-xl bg-badbg p-3 text-copy font-medium text-bad">{error}</p>}
        <Btn type="submit" variant="primary" size="lg" disabled={busy}>Save delivery record</Btn>
        <p className="m-0 text-center text-caption text-muted">Saved on this device first · store sees it after sync</p>
        </fieldset>
      </form>
    </Modal>
  );
}
