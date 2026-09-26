"use client";

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useWorkspace } from "./useWorkspace";
import Dispatcher from "./Dispatcher";
import FieldWork from "./FieldWork";
import Store from "./Store";
import {
  Badge,
  Btn,
  Empty,
  Field,
  Modal,
  OrderDetail,
  fmtDayLabel,
  inputClass,
} from "./components";
import type { AppState, Order, QueuedCommand, Role } from "../lib/types";

export type WorkspaceTab =
  | "overview"
  | "orders"
  | "plan"
  | "live"
  | "capacity"
  | "sync";

const ROLE_NAMES: Record<Role, string> = {
  dispatcher: "Dispatcher",
  loader: "Loader",
  driver: "Driver",
  store: "Store manager",
};

const ROLE_ICON: Record<Role, string> = {
  dispatcher: "◈",
  loader: "▤",
  driver: "●",
  store: "○",
};

const ROLE_BLURB: Record<Role, string> = {
  dispatcher: "Plan & publish every run",
  loader: "Load in stop order",
  driver: "Deliver stop by stop",
  store: "Order & confirm receipt",
};

function normalizeTab(tab: string): string {
  if (tab === "allocation") return "plan";
  if (tab === "progress" || tab === "exceptions") return "live";
  if (tab === "fleet") return "capacity";
  return tab;
}

/* ---------------- login ---------------- */

function LoginScreen({
  loginRole,
  onRole,
  busy,
  error,
  onLogin,
}: {
  loginRole: Role;
  onRole: (r: Role) => void;
  busy: boolean;
  error: string;
  onLogin: (email: string, password: string) => void;
}): React.JSX.Element {
  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    onLogin(String(form.get("email")), String(form.get("password")));
  }

  return (
    <div className="flex min-h-screen flex-col bg-[var(--ui-surface)] md:flex-row">
      {/* Left: black hero */}
      <section className="relative z-0 flex flex-1 flex-col overflow-hidden bg-gradient-to-br from-[#062e2a] via-[#0a6b63] to-[#12a48f] p-8 text-white md:p-12">
        <div aria-hidden className="absolute inset-0 -z-10">
          <div className="absolute -top-24 -right-24 h-80 w-80 rounded-full bg-[var(--ui-surface)]/15 blur-3xl" />
          <div className="absolute bottom-16 -left-24 h-72 w-72 rounded-full bg-black/25 blur-3xl" />
          <div
            className="absolute inset-0 opacity-20"
            style={{
              backgroundImage:
                "radial-gradient(rgba(255,255,255,0.65) 1px, transparent 1px)",
              backgroundSize: "22px 22px",
            }}
          />
        </div>
        <div className="flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-[var(--ui-surface)] text-[20px] font-bold text-[var(--ui-link)]">
            W
          </span>
          <span className="text-copy font-semibold tracking-tight">
            Waypoint
          </span>
          <span className="ml-2 rounded-full bg-[var(--ui-surface)]/15 px-3 py-1 font-mono text-caption text-white/80">
            Waypoint Group · Sri Lanka
          </span>
        </div>
        <div className="mt-10 max-w-[560px] md:mt-16">
          <p className="font-mono text-caption font-semibold tracking-widest text-white/80">
            ONE CONNECTED DELIVERY OPERATION
          </p>
          <h1 className="mt-3 text-[40px] leading-[1.05] font-semibold tracking-tight md:text-[56px]">
            One delivery. Every handoff accounted for.
          </h1>
          <p className="mt-4 max-w-[460px] text-copy leading-relaxed text-white/80">
            Waypoint&apos;s three brands share 60 vehicles, two depots and one 4
            PM cutoff. Each role gets one screen built for where they work -
            office, dock, road, counter.
          </p>
          <div className="mt-6 flex flex-wrap gap-2">
            {[
              "120 outlets",
              "60 vehicles",
              "2 depots",
              "4 roles",
              "Offline delivery",
            ].map((s) => (
              <span
                key={s}
                className="rounded-full bg-white/10 px-3.5 py-1.5 text-copy font-medium text-white"
              >
                {s}
              </span>
            ))}
          </div>
        </div>
        {/* trip preview */}
        <div className="mt-8 max-w-[560px] rounded-2xl border border-white/15 bg-black/25 p-5 backdrop-blur">
          <div className="flex items-center justify-between">
            <span className="text-copy font-semibold">
              Today&apos;s operation
            </span>
            <span className="rounded-full bg-[#06c167]/20 px-2.5 py-1 text-caption font-semibold text-[#30d158]">
              ● Live operations
            </span>
          </div>
          <div className="mt-4 space-y-0">
            {[
              [
                "Store orders",
                "Counter places ambient / chilled before 16:00",
                "○",
              ],
              [
                "Dispatcher plans",
                "Auto-assigns trips, flags what must wait",
                "○",
              ],
              [
                "Loader + driver",
                "Load in reverse stop order, deliver offline-first",
                "○",
              ],
              ["Store confirms", "Receipt or dispute closes the loop", "●"],
            ].map(([t, d, dot]) => (
              <div key={t} className="flex gap-3 pb-4 last:pb-0">
                <div className="flex flex-col items-center">
                  <span
                    className={`text-copy ${dot === "●" ? "text-[#30d158]" : "text-white/40"}`}
                  >
                    {dot}
                  </span>
                  <span className="w-px flex-1 bg-[var(--ui-surface)]/15" />
                </div>
                <div>
                  <div className="text-copy font-semibold">{t}</div>
                  <div className="text-copy text-white/80">{d}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
        <p className="mt-auto hidden pt-8 font-mono text-caption text-white/40 md:block">
          Fresh before 8 AM · Reefer for chilled · Van-only access · Fuel quotas
          · Mall windows
        </p>
      </section>

      {/* Right: white sign-in sheet */}
      <section className="flex w-full flex-col justify-center bg-[var(--ui-surface)] p-8 md:w-[520px] md:p-12">
        <h2 className="m-0 text-[28px] font-semibold tracking-tight">
          Sign in to your workspace
        </h2>
        <p className="mt-1 text-copy text-muted">
          The right information for your part of the journey.
        </p>

        <div className="mt-5 grid grid-cols-2 gap-2">
          {(Object.entries(ROLE_NAMES) as [Role, string][]).map(
            ([role, name]) => (
              <button
                key={role}
                onClick={() => onRole(role)}
                className={`rounded-2xl border p-3.5 text-left transition-all ${loginRole === role ? "border-[#0a6b63] bg-[#0a6b63] text-white" : "border-[var(--ui-line)] bg-[var(--ui-surface)] hover:bg-[var(--ui-soft)]"}`}
              >
                <div className="text-copy">{ROLE_ICON[role]}</div>
                <div className="mt-1 text-copy font-semibold">{name}</div>
                <div
                  className={`text-caption ${loginRole === role ? "text-white/80" : "text-muted"}`}
                >
                  {ROLE_BLURB[role]}
                </div>
              </button>
            ),
          )}
        </div>

        <form
          key={loginRole}
          onSubmit={handleSubmit}
          className="mt-5 flex flex-col gap-3.5"
        >
          <Field label="Email address">
            <input
              name="email"
              type="email"
              autoComplete="username"
              defaultValue={`${loginRole}@waypoint.local`}
              required
              className={inputClass}
            />
          </Field>
          <Field label="Password">
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              required
              placeholder="••••••••"
              className={inputClass}
            />
          </Field>
          {error && (
            <p
              role="alert"
              className="rounded-xl bg-badbg p-3 text-copy font-medium text-bad"
            >
              {error}
            </p>
          )}
          <Btn type="submit" variant="primary" size="lg" disabled={busy}>
            {busy ? "Signing in…" : "Sign in →"}
          </Btn>
        </form>

        <div className="mt-5 rounded-2xl bg-[var(--ui-soft)] p-4 text-copy leading-relaxed">
          <strong className="text-copy">
            How it works: 2 minutes per role
          </strong>
          <p className="m-0 mt-1 text-muted">
            Team accounts · default password{" "}
            <code className="rounded bg-[#0a6b63] px-1.5 py-0.5 font-mono text-caption text-white">
              Waypoint2026!
            </code>
          </p>
          <ol className="m-0 mt-2 list-none space-y-1 p-0 text-muted">
            <li>
              <strong className="text-[var(--ui-text)]">1.</strong> Store places an order
              before 16:00
            </li>
            <li>
              <strong className="text-[var(--ui-text)]">2.</strong> Dispatcher generates &
              publishes the plan
            </li>
            <li>
              <strong className="text-[var(--ui-text)]">3.</strong> Loader flags shortfall
              → dispatcher resolves → load
            </li>
            <li>
              <strong className="text-[var(--ui-text)]">4.</strong> Driver delivers offline
              → syncs → store confirms
            </li>
          </ol>
          <p className="m-0 mt-2 text-caption text-muted">
            Try scenario day 16 Feb (workshop shortage) or 17 Feb (fuel
            pressure) in the dispatcher to see deferrals with reasons.
          </p>
        </div>
      </section>
    </div>
  );
}

/* ---------------- shared chrome ---------------- */

function Connection({
  online,
  pending,
  dark,
}: {
  online: boolean;
  pending: number;
  dark?: boolean;
}): React.JSX.Element {
  return (
    <span
      className={`flex items-center gap-1.5 text-caption font-semibold ${online ? (dark ? "text-[#30d158]" : "text-ok") : dark ? "text-[#ffc043]" : "text-warn"}`}
    >
      <i className="h-1.5 w-1.5 rounded-full bg-current" />
      {online ? (pending ? `${pending} to sync` : "Connected") : "Offline"}
    </span>
  );
}

function AccountBar({
  role,
  scope,
  online,
  pending,
  busy,
  onSyncTab,
  onLogout,
  dark,
}: {
  role: Role;
  scope: string;
  online: boolean;
  pending: number;
  busy: boolean;
  onSyncTab: () => void;
  onLogout: () => void;
  dark?: boolean;
}): React.JSX.Element {
  return (
    <div
      className={`flex min-h-[60px] flex-wrap items-center gap-2.5 px-4 py-2.5 ${dark ? "bg-[#0a2723] text-white" : "border-b border-[var(--ui-line)] bg-[var(--ui-surface)] text-[var(--ui-text)]"}`}
    >
      <div className="flex min-w-0 items-center gap-3">
      <span
        className={`flex h-8 w-8 items-center justify-center rounded-xl text-copy font-bold ${dark ? "bg-[var(--ui-surface)] text-[var(--ui-link)]" : "bg-[#0a6b63] text-white"}`}
      >
        W
      </span>
      <span className="text-copy font-semibold">{ROLE_NAMES[role]}</span>
      <span
        className={`hidden text-caption sm:inline ${dark ? "text-white/80" : "text-muted"}`}
      >
        · {scope === "all" ? "Both depots" : scope}
      </span>
      </div>
      <span className="ml-auto">
        <Connection online={online} pending={pending} dark={dark} />
      </span>
      <div className="flex w-full gap-3 sm:w-auto">
      <button
        aria-label="Sync queue"
        onClick={onSyncTab}
        className={`min-h-12 flex-1 sm:flex-none rounded-full px-4 text-copy font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4fd8a4] focus-visible:ring-offset-2 ${dark ? "bg-white/10 text-white hover:bg-white/20" : "bg-[#edf3f1] text-[var(--ui-link)] hover:bg-[#ddefec]"}`}
      >
        Sync{pending > 0 && ` (${pending})`}
      </button>
      <button
        disabled={busy}
        onClick={onLogout}
        className={`min-h-12 flex-1 sm:flex-none rounded-full border px-4 text-copy font-semibold transition-colors disabled:cursor-wait disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4fd8a4] focus-visible:ring-offset-2 ${dark ? "border-white/20 bg-white/10 text-white hover:bg-white/20" : "border-[#d9e6e2] bg-[var(--ui-surface)] text-[var(--ui-link)] hover:border-[#0a6b63] hover:bg-[#f3faf8]"}`}
      >
        Sign out
      </button>
      </div>
    </div>
  );
}

function Banners({
  online,
  error,
  notice,
  onReauth,
  hideOffline,
}: {
  online: boolean;
  error: string;
  notice: string;
  onReauth: () => void;
  hideOffline?: boolean;
}): React.JSX.Element {
  return (
    <>
      {!online && !hideOffline && (
        <div className="mx-4 mt-3 rounded-2xl bg-[#0a6b63] p-4 text-copy text-white">
          <strong>No connection. Your work stays with you.</strong>
          <p className="m-0 mt-1 text-white/80">
            Showing the last downloaded records. Actions are saved on this
            device, then sync when you reconnect.
          </p>
        </div>
      )}
      {error && (
        <div
          role="alert"
          className="mx-4 mt-3 rounded-2xl bg-badbg p-4 text-copy font-medium text-bad"
        >
          {error}
          {/sign in|session/i.test(error) && (
            <button
              onClick={onReauth}
              className="ml-2 min-h-10 rounded-lg px-2 font-semibold text-[var(--ui-link)] underline hover:bg-[#edf3f1] cursor-pointer"
            >
              Sign in again
            </button>
          )}
        </div>
      )}
      {notice && (
        <div
          role="status"
          className="mx-4 mt-3 rounded-2xl bg-okbg p-3 text-copy font-medium text-ok"
        >
          {notice}
        </div>
      )}
    </>
  );
}

const ACTION_LABEL: Record<string, string> = {
  load: "Marked loaded",
  shortfall: "Flagged shortfall",
  resolve: "Resolved shortfall",
  depart: "Started stop",
  arrive: "Arrived at stop",
  deliver: "Recorded delivery",
  receive: "Confirmed receipt",
  dispute: "Reported issue",
  order: "Placed order",
  plan: "Generated draft plan",
  publish: "Published plan",
  move: "Moved order",
  defer_note: "Explained deferral",
};

const EVENT_LABEL: Record<string, string> = {
  load: "Loaded",
  shortfall: "Shortfall flagged",
  resolve: "Shortfall resolved",
  depart: "Departed",
  arrive: "Arrived",
  deliver: "Delivered",
  receive: "Receipt confirmed",
  dispute: "Receipt disputed",
  publish: "Published",
  deferred: "Deferred",
  move: "Moved",
  defer_note: "Deferral explained",
};

function commandDetail(command: Record<string, unknown>): string {
  const bits: string[] = [];
  if (command.count != null) bits.push(`${String(command.count)} cases`);
  if (command.outcome) bits.push(String(command.outcome));
  if (command.receiver) bits.push(`to ${String(command.receiver)}`);
  if (command.note) bits.push(`“${String(command.note).slice(0, 80)}”`);
  return bits.join(" · ");
}

function SyncView({
  state,
  queue,
  online,
  busy,
  updated,
  onSync,
  onDiscard,
  onDetail,
  onBack,
}: {
  state: AppState;
  queue: QueuedCommand[];
  online: boolean;
  busy: boolean;
  updated: string;
  onSync: () => void;
  onDiscard: (id: string) => void;
  onDetail: (o: Order) => void;
  onBack: () => void;
}): React.JSX.Element {
  function confirmDiscard(id: string): void {
    if (
      confirm(
        "Discard this local action? The server record stays unchanged. Re-enter the action after reviewing the latest record.",
      )
    ) {
      onDiscard(id);
    }
  }

  const ordersById = Object.fromEntries(
    (state.orders || []).map((o) => [o.id, o]),
  );
  const recent = (state.events || []).slice(0, 8);

  return (
    <div className="mx-auto w-full max-w-2xl p-4 pb-16">
      <div className="mb-3">
        <button
          onClick={onBack}
          aria-label="Back to workspace"
          className="inline-flex min-h-12 cursor-pointer items-center gap-2.5 rounded-full border border-[var(--ui-line)] bg-[var(--ui-surface)] py-1.5 pr-6 pl-1.5 text-copy font-semibold shadow-sm"
        >
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#0a6b63] text-[20px] font-bold text-white">
            ‹
          </span>
          Back to workspace
        </button>
      </div>
      <div className="mt-2 flex items-center justify-between gap-4">
        <div>
          <p className="m-0 font-mono text-caption font-semibold tracking-widest text-muted">
            DEVICE RECORDS
          </p>
          <h1 className="m-0 mt-1 text-[28px] font-semibold tracking-tight">
            Sync queue
          </h1>
          <p className="m-0 mt-1 text-copy text-muted">
            Local work is kept until the server confirms it.
          </p>
        </div>
        <Btn variant="primary" disabled={!online || busy} onClick={onSync}>
          Sync now ↻
        </Btn>
      </div>

      <div
        className={`mt-4 flex items-center gap-2.5 rounded-2xl p-4 text-copy ${online ? "bg-okbg" : "bg-[#0a6b63] text-white"}`}
      >
        <span
          className={`inline-block h-2 w-2 rounded-full ${online ? "bg-[#06c167]" : "bg-[#ffc043]"}`}
        />
        {online ? (
          <span>
            <strong>Connected.</strong>{" "}
            <span className="text-muted">
              Saved work is confirmed only after the server accepts it. Pending
              records remain below.
            </span>
          </span>
        ) : (
          <span>
            <strong>Offline.</strong>{" "}
            <span className="opacity-70">
              New actions stay on this device until you reconnect.
            </span>
          </span>
        )}
      </div>

      {queue.length ? (
        queue.map((q) => {
          const qc = q.command as {
            id: string;
            kind: string;
            order_id?: string;
            client_time?: string;
          };
          const order = qc.order_id ? ordersById[qc.order_id] : null;
          return (
            <section key={qc.id} className="wp-card mt-3 p-5">
              <div>
                <Badge status={q.error ? "shortfall" : "planned"} />
                <span className="ml-2 text-copy font-semibold">
                  {q.error ? "Needs review" : "Waiting to send"}
                </span>
                <h3 className="mt-1.5 text-copy font-semibold tracking-tight">
                  {ACTION_LABEL[qc.kind] || qc.kind}
                  {qc.order_id ? ` · ${qc.order_id}` : ""}
                </h3>
                {order && (
                  <p className="m-0 text-copy text-muted">
                    {order.outlet_id} · {order.district} · {order.units} cases
                  </p>
                )}
                {commandDetail(q.command) && (
                  <p className="m-0 mt-1 text-copy text-muted">
                    {commandDetail(q.command)}
                  </p>
                )}
                <p className="m-0 mt-1 text-caption text-muted">
                  Saved {new Date(q.created).toLocaleString()}
                  {qc.client_time &&
                    ` · device ${new Date(qc.client_time).toLocaleTimeString()}`}
                </p>
                {q.error && (
                  <p
                    role="alert"
                    className="rounded-xl bg-badbg p-3 text-copy font-medium text-bad"
                  >
                    {q.error}
                  </p>
                )}
              </div>
              <div className="mt-3 flex gap-2">
                {order && (
                  <Btn size="sm" onClick={() => onDetail(order)}>
                    View receipt
                  </Btn>
                )}
                {q.error && (
                  <Btn size="sm" onClick={() => confirmDiscard(qc.id)}>
                    Discard
                  </Btn>
                )}
              </div>
            </section>
          );
        })
      ) : (
        <div className="wp-card mt-3">
          <Empty title="Everything is synced">
            No unsent records on this device.
          </Empty>
        </div>
      )}

      <h3 className="mt-6 text-copy font-semibold">Recently confirmed</h3>
      {recent.length ? (
        <ol className="wp-card m-0 list-none p-2">
          {recent.map((e) => (
            <li
              key={e.id}
              className="border-b border-[#f0f0f0] px-3 py-2.5 text-copy last:border-0"
            >
              <strong>
                {EVENT_LABEL[e.kind] || e.kind.replace(/_/g, " ")}
              </strong>
              {e.order_id && e.order_id !== "*" ? (
                <span className="font-mono text-caption"> · {e.order_id}</span>
              ) : null}
              <span className="text-muted">
                {" "}
                · {String(e.actor).split("@")[0]} ·{" "}
                {new Date(e.created).toLocaleString()}
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <p className="text-copy text-muted">
          No shared records yet. Activity from every role lands here once
          confirmed.
        </p>
      )}
      <p className="text-caption text-muted">
        Last snapshot: {new Date(updated).toLocaleString()}
      </p>
    </div>
  );
}

/* ---------------- workspace ---------------- */

export default function Workspace(): React.JSX.Element {
  const w = useWorkspace();
  const [tab, setTab] = useState<string>("plan");
  const [syncReturn, setSyncReturn] = useState<string>("plan");
  const [detail, setDetail] = useState<Order | null>(null);
  const [loginRole, setLoginRole] = useState<Role>("dispatcher");
  const [reauth, setReauth] = useState<boolean>(false);
  const [appearance, setAppearance] = useState<"system" | "light" | "dark">("system");
  const [systemDark, setSystemDark] = useState(false);
  const driverDark = appearance === "dark" || (appearance === "system" && systemDark);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setSystemDark(media.matches);
    update();
    try {
      const saved = localStorage.getItem("waypoint-driver-appearance");
      if (saved === "light" || saved === "dark") setAppearance(saved);
    } catch { /* Device preferences may be unavailable. */ }
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    if (w.state?.user.id) window.scrollTo(0, 0);
  }, [w.state?.user.id]);

  useEffect(() => {
    if ("serviceWorker" in navigator && process.env.NODE_ENV === "production") {
      navigator.serviceWorker.register("/sw.js").catch(() => {});
    }
  }, []);

  useEffect(() => {
    const switchTab = (e: Event): void =>
      setTab((e as CustomEvent<string>).detail);
    window.addEventListener("dtab", switchTab);
    return () => window.removeEventListener("dtab", switchTab);
  }, []);

  if (!w.ready) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[var(--ui-surface)]">
        <div className="text-center">
          <span className="mx-auto flex min-h-12 w-12 items-center justify-center rounded-2xl bg-[#0a6b63] text-xl font-bold text-white">
            W
          </span>
          <p className="mt-3 font-medium">Opening your workspace…</p>
        </div>
      </div>
    );
  }

  if (!w.state) {
    return (
      <LoginScreen
        loginRole={loginRole}
        onRole={setLoginRole}
        busy={w.busy}
        error={w.error}
        onLogin={w.login}
      />
    );
  }

  const role = w.state.user.role;
  const selected = normalizeTab(tab) || "plan";
  const viewingSync = selected === "sync";
  const showDetail =
    detail && (w.state.orders.find((o) => o.id === detail.id) || detail);

  function openSync(): void {
    if (!viewingSync) setSyncReturn(selected);
    setTab("sync");
  }

  const content = viewingSync ? (
    <SyncView
      state={w.state}
      queue={w.queue}
      online={w.online}
      busy={w.busy}
      updated={w.state.updated}
      onSync={w.sync}
      onDiscard={w.discard}
      onDetail={setDetail}
      onBack={() => setTab(syncReturn)}
    />
  ) : role === "dispatcher" ? (
    <Dispatcher
      state={w.state}
      act={w.act}
      busy={w.busy}
      onDetail={setDetail}
      tab={selected}
    />
  ) : role === "store" ? (
    <Store
      state={w.state}
      act={w.act}
      busy={w.busy}
      queue={w.queue}
      onDetail={setDetail}
    />
  ) : (
    <FieldWork
      state={w.state}
      act={w.act}
      busy={w.busy}
      queue={w.queue}
      onDetail={setDetail}
      tab={selected}
    />
  );

  const reauthDialog = reauth ? (
    <Modal title="Sign in to sync" onClose={() => setReauth(false)}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (!w.state) return;
          if (
            await w.login(
              w.state.user.id,
              String(new FormData(e.currentTarget).get("password")),
            )
          )
            setReauth(false);
        }}
        className="flex flex-col gap-3"
      >
        <p className="text-copy">{w.state.user.id}</p>
        <Field label="Password">
          <input
            type="password"
            name="password"
            required
            className={inputClass}
          />
        </Field>
        {w.error && (
          <p role="alert" className="text-copy text-bad">
            {w.error}
          </p>
        )}
        <Btn variant="primary" size="lg" disabled={w.busy}>
          Sign in
        </Btn>
      </form>
    </Modal>
  ) : null;

  if (role === "dispatcher") {
    return (
      <div className="min-h-screen bg-[var(--ui-soft)]">
        <AccountBar
          role={role}
          scope={w.state.user.scope}
          online={w.online}
          pending={w.queue.length}
          busy={w.busy}
          onSyncTab={openSync}
          onLogout={w.logout}
        />
        <Banners
          online={w.online}
          error={w.error}
          notice={w.notice}
          onReauth={() => setReauth(true)}
        />
        {content}
        <footer className="p-6 text-center text-caption text-muted">
          Waypoint Dispatch · Shared records · Planned times are estimates ·
          Asia/Colombo
        </footer>
        {showDetail && (
          <OrderDetail
            order={showDetail}
            state={w.state}
            onClose={() => setDetail(null)}
          />
        )}
        {reauthDialog}
      </div>
    );
  }

  return (
    <div
      data-theme={role === "driver" ? (driverDark ? "dark" : "light") : undefined}
      className={`flex min-h-screen justify-center ${role === "driver" ? "driver-workspace" : "bg-[var(--ui-soft)]"}`}
    >
      <div className="w-full max-w-md md:max-w-6xl">
        <AccountBar
          role={role}
          scope={w.state.user.scope}
          online={w.online}
          pending={w.queue.length}
          busy={w.busy}
          onSyncTab={openSync}
          onLogout={w.logout}
          dark={role === "driver" && driverDark}
        />
        {role === "driver" && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--ui-line)] px-4 py-3">
            <span className="text-copy font-medium">Display</span>
            <div role="group" aria-label="Driver display" className="flex rounded-full bg-[var(--ui-soft)] p-1">
              {(["system", "light", "dark"] as const).map((mode) => (
                <button key={mode} type="button" aria-pressed={appearance === mode}
                  onClick={() => {
                    setAppearance(mode);
                    try { localStorage.setItem("waypoint-driver-appearance", mode); } catch { /* Apply for this visit. */ }
                  }}
                  className={`min-h-10 rounded-full px-3 text-copy font-semibold ${appearance === mode ? "bg-accent text-white" : "text-[var(--ui-text)]"}`}>
                  {mode === "system" ? "Auto" : mode === "light" ? "Day" : "Night"}
                </button>
              ))}
            </div>
          </div>
        )}
        <Banners
          online={w.online}
          error={w.error}
          notice={w.notice}
          onReauth={() => setReauth(true)}
          hideOffline={role === "driver"}
        />
        {content}
        <footer
          className={`p-4 text-center text-caption ${role === "driver" ? "text-[var(--driver-muted)]" : "text-muted"}`}
        >
          Waypoint · Asia/Colombo
        </footer>
      </div>
      {reauthDialog}
      {showDetail && (
        <OrderDetail
          order={showDetail}
          state={w.state}
          onClose={() => setDetail(null)}
        />
      )}
    </div>
  );
}
