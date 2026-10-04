"use client";

import { useState } from "react";
import { Badge, card, secondary } from "../access/components";

// The presenter's guide from issue #231: the demo accounts with a copy button,
// and the end-to-end path through the four roles as a checklist. The checklist
// is this browser's own convenience and is never sent anywhere. Passwords are
// not shown: they are the deployment's SEED_PASSWORD.

const ACCOUNTS: Array<{ role: string; email: string; purpose: string }> = [
  { role: "Store manager", email: "store_manager@waypoint.local", purpose: "Place the order, track the vehicle, confirm receipt" },
  { role: "Dispatcher", email: "dispatcher@waypoint.local", purpose: "Close orders, plan, explain deferrals, publish, watch live" },
  { role: "Loader", email: "loader@waypoint.local", purpose: "Load for the stop sequence, flag a shortfall, release" },
  { role: "Driver", email: "driver@waypoint.local", purpose: "Drive the trip, record each stop, work offline" },
  { role: "Administrator", email: "admin@waypoint.local", purpose: "This control room, people and access" },
];

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
        <p className="text-sm text-go-secondary">Every account uses this deployment&rsquo;s demo password.</p>
        <ul className="flex flex-col gap-2">
          {ACCOUNTS.map((a) => (
            <li key={a.email} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
              <span className="w-32 font-medium text-go-ink">{a.role}</span>
              <code className="text-go-ink">{a.email}</code>
              <button type="button" className={secondary} onClick={() => copy(a.email)}>{copied === a.email ? "Copied" : "Copy"}</button>
              <span className="text-go-secondary">{a.purpose}</span>
            </li>
          ))}
        </ul>
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
