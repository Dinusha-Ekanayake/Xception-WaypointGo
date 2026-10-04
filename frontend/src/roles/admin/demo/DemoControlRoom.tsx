"use client";

import { useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import type { DemoView } from "@shared/domain/types";
import { clock, dayLabel } from "@shared/wording/time";
import { Badge, Empty, card, field, primary, secondary } from "../access/components";
import { depotInstant, loadDemo, loadRuns, resetDay, setClock, setEnabled, updateSettings } from "./data";
import PresenterGuide from "./PresenterGuide";

// Issue #231, the demo control room. Off by default; one switch turns it on or
// off at runtime with a reason, and every control here is a demo:* command
// through the bus. Off, the rest of Waypoint behaves exactly as without it.

const PRESETS: Array<{ label: string; hhmm: string; hint: string }> = [
  { label: "Before cutoff 15:30", hhmm: "15:30", hint: "Stores can still order for the next run" },
  { label: "After cutoff 16:05", hhmm: "16:05", hint: "The dispatcher can close orders and plan" },
  { label: "Early morning 05:00", hhmm: "05:00", hint: "Loading and departures" },
];

function problemText(error: unknown): string {
  if (error instanceof ApiError) return error.problem.detail || error.problem.title;
  return "Waypoint could not be reached. Try again.";
}

export default function DemoControlRoom(): React.JSX.Element {
  const demo = useResource(loadDemo, "admin-demo", 5_000);
  const runs = useResource(loadRuns, "admin-demo-runs", 10_000);
  const [reason, setReason] = useState("Live demo");
  const [custom, setCustom] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "green" | "red"; text: string } | null>(null);
  const view = demo.data;

  async function run(label: string, action: (v: DemoView) => Promise<unknown>) {
    if (!view || busy) return;
    if (reason.trim().length < 3) {
      setMessage({ tone: "red", text: "Write a reason of at least 3 characters first." });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await action(view);
      setMessage({ tone: "green", text: `${label}: done.` });
    } catch (error) {
      setMessage({ tone: "red", text: `${label}: ${problemText(error)}` });
    } finally {
      setBusy(false);
      demo.refresh();
      runs.refresh();
    }
  }

  if (demo.error && !view) {
    return <Empty>Demo settings could not be read: {problemText(demo.error)}. Waypoint keeps running on real time.</Empty>;
  }
  if (!view) return <Empty>Reading demo settings…</Empty>;

  const r = reason.trim();
  return (
    <div className="flex flex-col gap-5">
      <section className={`${card} flex flex-col gap-4 p-5`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-medium text-go-ink">Demo mode</h2>
              <Badge tone={view.enabled ? "amber" : "neutral"}>{view.enabled ? "On" : "Off"}</Badge>
            </div>
            <p className="text-sm text-go-secondary">
              {view.enabled
                ? `Demo clock ${clock(view.now)} · ${dayLabel(depotInstant(view, clock(view.now)).toISOString().slice(0, 10))}. Every role shows a demo banner.`
                : "Off: Waypoint runs on real time and no role sees anything of the demo."}
            </p>
          </div>
          <button
            type="button"
            className={view.enabled ? secondary : primary}
            disabled={busy}
            onClick={() => void run(view.enabled ? "Demo mode off" : "Demo mode on", (v) => setEnabled(v, !v.enabled, r))}
          >
            {view.enabled ? "Turn demo mode off" : "Turn demo mode on"}
          </button>
        </div>
        <label className="flex flex-col gap-1 text-sm text-go-secondary">
          Reason, kept in the audit log
          <input className={field} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
        </label>
        {message && <p role="status" className={message.tone === "red" ? "text-sm text-[#a3261a]" : "text-sm text-[#1d6b3a]"}>{message.text}</p>}
      </section>

      {view.enabled && (
        <>
          <section className={`${card} flex flex-col gap-4 p-5`}>
            <h2 className="text-lg font-medium text-go-ink">Demo clock</h2>
            <p className="text-sm text-go-secondary">Moves cutoff, closing, ETAs and lateness together. Sign-in and security keep real time.</p>
            <div className="flex flex-wrap gap-2">
              {PRESETS.map((p) => (
                <button key={p.hhmm} type="button" title={p.hint} className={secondary} disabled={busy}
                  onClick={() => void run(`Clock ${p.hhmm}`, (v) => setClock(v, depotInstant(v, p.hhmm), r))}>
                  {p.label}
                </button>
              ))}
              <button type="button" className={secondary} disabled={busy} onClick={() => void run("Real time", (v) => setClock(v, new Date(), r))}>
                Real time
              </button>
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-sm text-go-secondary">
                Custom depot time
                <input className={field} type="time" value={custom} onChange={(e) => setCustom(e.target.value)} />
              </label>
              <button type="button" className={secondary} disabled={busy || !custom} onClick={() => void run(`Clock ${custom}`, (v) => setClock(v, depotInstant(v, custom), r))}>
                Set time
              </button>
            </div>
          </section>

          <section className={`${card} flex flex-col gap-4 p-5`}>
            <h2 className="text-lg font-medium text-go-ink">Demo day and settings</h2>
            <div className="flex flex-wrap items-center gap-3">
              <button type="button" className={primary} disabled={busy}
                onClick={() => { if (window.confirm("Prepare a fresh demo day? Only demo records are created; real orders are never touched.")) void run("Prepare demo day", (v) => resetDay(v, r)); }}>
                Prepare demo day
              </button>
              <span className="text-sm text-go-secondary">Seeds the Peliyagoda peak day on the next empty operating day, with the demo accounts&rsquo; access.</span>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-sm text-go-secondary">
              <span>Driver position updates every</span>
              {[5_000, 15_000, 60_000].map((ms) => (
                <button key={ms} type="button" className={secondary} disabled={busy || view.positionFlushMs === ms}
                  onClick={() => void run("Position updates", (v) => updateSettings(v, { positionFlushMs: ms }, r))}>
                  {ms / 1000} s
                </button>
              ))}
              <button type="button" className={secondary} disabled={busy}
                onClick={() => void run("Banner", (v) => updateSettings(v, { banner: !v.banner }, r))}>
                {view.banner ? "Hide demo banner" : "Show demo banner"}
              </button>
            </div>
          </section>
        </>
      )}

      <PresenterGuide />

      <section className={`${card} flex flex-col gap-3 p-5`}>
        <h2 className="text-lg font-medium text-go-ink">Demo log</h2>
        {(runs.data ?? []).length === 0 ? (
          <p className="text-sm text-go-secondary">No demo actions yet.</p>
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {(runs.data ?? []).slice(0, 12).map((run) => (
              <li key={run.id} className="flex flex-wrap gap-x-3 text-go-ink">
                <span className="tabular-nums text-go-secondary">{clock(run.started_at)}</span>
                <span>{run.scenario_key.replace("demo:", "")}</span>
                <span className="text-go-secondary">{run.reason}</span>
                <Badge tone={run.outcome === "failed" ? "red" : run.outcome === "preparing" ? "blue" : "green"}>{run.outcome}</Badge>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
