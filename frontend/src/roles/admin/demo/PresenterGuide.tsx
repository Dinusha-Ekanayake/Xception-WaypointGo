"use client";

import { useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { Badge, card, secondary } from "../access/components";

// The presenter's guide from issue #231: the demo accounts with a copy button,
// and the end-to-end path through the four roles as a checklist. The checklist
// is this browser's own convenience and is never sent anywhere. The accounts,
// the demo password and the loaders' PIN are the ones the sign-in page offers
// (GET /api/session/demo), so the guide never names an account the password
// does not open. The administrator's password is never among them.

type Offered = { email: string; name: string; role: string; fillsPassword?: boolean };
type DemoSignIn = { accounts: Offered[]; password: string | null; pin: string | null };

const ROLES: Record<string, { label: string; purpose: string }> = {
  store_manager: { label: "Store manager", purpose: "Place the order, track the vehicle, confirm receipt" },
  dispatcher: { label: "Dispatcher", purpose: "Close orders, plan, explain deferrals, publish, watch live" },
  loader: { label: "Loader", purpose: "Choose a loader and enter the PIN; load for the stop sequence, flag a shortfall, release" },
  driver: { label: "Driver", purpose: "Drive the trip, record each stop, work offline" },
  admin: { label: "Administrator", purpose: "This control room, people and access" },
};

const STEPS = [
  "Turn demo mode on and prepare the demo day",
  "Clock: before cutoff 15:30",
  "Store places a chilled order and sees it confirmed",
  "Clock: after cutoff 16:05",
  "Dispatcher closes orders and generates the plan; deferrals are explained",
  "Dispatcher publishes the plan",
  "Loader checks the manifest, flags one short, releases the trip",
  "Driver opens the run and starts driving",
  "Store watches the vehicle on Track with its arrival time",
  "Driver records the delivery with a photo",
  "Store confirms receipt and reports the short item",
  "Turn demo mode off: real time is back",
];

const KEY = "waypoint.demo.steps";

function saved(): boolean[] {
  try {
    const raw = window.localStorage.getItem(KEY);
    const list = raw ? (JSON.parse(raw) as unknown) : null;
    return Array.isArray(list) ? STEPS.map((_, i) => list[i] === true) : STEPS.map(() => false);
  } catch {
    return STEPS.map(() => false);
  }
}

export default function PresenterGuide(): React.JSX.Element {
  const [done, setDone] = useState<boolean[]>(() => (typeof window === "undefined" ? STEPS.map(() => false) : saved()));
  const [copied, setCopied] = useState<string | null>(null);
  const [demo, setDemo] = useState<DemoSignIn | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    request<DemoSignIn>("/api/session/demo", { signal: abort.signal }).then(
      (read) => { if (!abort.signal.aborted) setDemo(read); },
      () => { if (!abort.signal.aborted) setDemo({ accounts: [], password: null, pin: null }); },
    );
    return () => abort.abort();
  }, []);

  const keep = (next: boolean[]) => {
    setDone(next);
    try {
      window.localStorage.setItem(KEY, JSON.stringify(next));
    } catch {
      // Private mode: the checklist lasts for this page only.
    }
  };

  const copy = (email: string) => {
    void navigator.clipboard?.writeText(email).then(() => setCopied(email), () => setCopied(null));
  };

  return (
    <>
      <section className={`${card} flex flex-col gap-3 p-5`}>
        <h2 className="text-lg font-medium text-go-ink">Demo accounts</h2>
        {demo === null && <p role="status" className="text-sm text-go-secondary">Loading the demo accounts…</p>}
        {demo !== null && demo.accounts.length === 0 && (
          <p className="text-sm text-go-secondary">No demo accounts are offered: demo mode is off, or this deployment sets no demo sign-in. Accounts then use the password set when they were created.</p>
        )}
        {demo !== null && demo.accounts.length > 0 && (
          <>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
              {demo.password && (
                <>
                  <span className="font-medium text-go-ink">Password</span>
                  <code className="text-go-ink">{demo.password}</code>
                  <button type="button" className={secondary} onClick={() => copy(demo.password ?? "")}>{copied === demo.password ? "Copied" : "Copy"}</button>
                </>
              )}
              {demo.pin && (
                <>
                  <span className="font-medium text-go-ink">Loader PIN</span>
                  <code className="text-go-ink">{demo.pin}</code>
                </>
              )}
            </div>
            <p className="text-sm text-go-secondary">On the sign-in page, Use a demo account fills these in.</p>
            <ul className="flex flex-col gap-2">
              {demo.accounts.map((a) => (
                <li key={a.email} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                  <span className="w-32 font-medium text-go-ink">{ROLES[a.role]?.label ?? a.role}</span>
                  <code className="text-go-ink">{a.email}</code>
                  <button type="button" className={secondary} onClick={() => copy(a.email)}>{copied === a.email ? "Copied" : "Copy"}</button>
                  <span className="text-go-secondary">
                    {a.fillsPassword === false ? "Password in the shared credentials on GitHub. " : ""}
                    {ROLES[a.role]?.purpose ?? ""}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      <section className={`${card} flex flex-col gap-3 p-5`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-medium text-go-ink">Demo path</h2>
          <div className="flex items-center gap-2">
            <Badge tone="blue">{done.filter(Boolean).length} of {STEPS.length}</Badge>
            <button type="button" className={secondary} onClick={() => keep(STEPS.map(() => false))}>Start over</button>
          </div>
        </div>
        <ol className="flex flex-col gap-2">
          {STEPS.map((step, i) => (
            <li key={step}>
              <label className="flex items-center gap-3 text-sm text-go-ink">
                <input type="checkbox" className="size-5" checked={done[i] ?? false} onChange={() => keep(done.map((d, j) => (j === i ? !d : d)))} />
                <span className={done[i] ? "text-go-secondary line-through" : ""}>{i + 1}. {step}</span>
              </label>
            </li>
          ))}
        </ol>
      </section>
    </>
  );
}
