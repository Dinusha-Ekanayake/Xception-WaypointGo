"use client";

import { useEffect, useState } from "react";
import { Badge, Empty, card, field, secondary } from "../components";
import type { Change } from "../model";
import { auditEvents, eventTime, localDay, type AuditEvent } from "./model";
import { EventDetails } from "./EventDetails";
export function AuditConsole({ changes, viewer, focused = false }: { changes: Change[]; viewer: "admin" | "super_admin"; focused?: boolean }) {
  const [hash, setHash] = useState("");
  const [more, setMore] = useState(false);
  const [limit, setLimit] = useState(12);
  useEffect(() => { const read = () => setHash(window.location.hash); read(); window.addEventListener("hashchange", read); return () => window.removeEventListener("hashchange", read); }, []);
  const [path, search = ""] = hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(search);
  const view = focused ? "activity" : path?.split("/")[1] || "overview";
  const all = auditEvents(changes, viewer);
  const base = all.filter((event) => !focused || event.module === "People & access");
  const defaultEnd = base.reduce((end, event) => localDay(event.at) > end ? localDay(event.at) : end, "2026-10-02");
  const start = params.get("from") ?? "2026-09-25";
  const end = params.get("to") ?? defaultEnd;
  const query = params.get("q") ?? "";
  function update(values: Record<string, string>, nextView = view, push = false) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) { if (value) next.set(key, value); else next.delete(key); }
    const route = `${focused ? "history" : "audit"}/${nextView}${next.size ? `?${next}` : ""}`;
    if (push) window.location.hash = route;
    else { window.history.replaceState(null, "", `#${route}`); setHash(`#${route}`); }
    if (Object.keys(values).some((key) => key !== "event")) setLimit(12);
  }
  const period = base.filter((event) => localDay(event.at) >= start && localDay(event.at) <= end);
  const rows = period.filter((event) => (view !== "security" || event.access)
    && (!params.get("module") || event.module === params.get("module"))
    && (!params.get("outcome") || event.outcome === params.get("outcome"))
    && (!params.get("actor") || event.actor === params.get("actor"))
    && (!params.get("place") || event.place === params.get("place"))
    && (!params.get("correlation") || event.correlation === params.get("correlation"))
    && (!params.get("device") || event.device === params.get("device"))
    && `${event.id} ${event.actor} ${event.title} ${event.target} ${event.action} ${event.reason ?? ""} ${event.correlation ?? ""}`.toLowerCase().includes(query.toLowerCase()));
  const selected = base.find((event) => event.id === params.get("event"));
  const state = params.get("state") ?? "ready";
  const available = state !== "denied" && state !== "unavailable";
  const chips = [...params.entries()].filter(([key]) => !["event", "state"].includes(key));
  const open = (event: AuditEvent) => update({ event: event.id }, view, true);
  const clear = () => { const route = `${focused ? "history" : "audit"}/${view}`; window.history.replaceState(null, "", `#${route}`); setHash(`#${route}`); setLimit(12); };
  return <div className="space-y-5">
    <div className="flex flex-wrap items-end justify-between gap-4"><div><h2 className="text-2xl font-semibold">{focused ? "Permission change history" : "Follow every decision"}</h2><p className="mt-1 text-sm text-go-secondary">{focused ? "The same access events shown in Audit console." : "Find the actor, reason and context behind recorded activity."}</p></div><Badge tone="blue">Asia/Colombo</Badge></div>
    {!focused && <nav aria-label="Audit views" className="flex gap-2 overflow-x-auto border-b border-go-rule">{[["overview", "Overview"], ["activity", "Activity"], ["security", "Access & security"]].map(([id, label]) => <button key={id} className={`min-h-11 whitespace-nowrap border-b-2 px-4 text-sm font-semibold ${view === id ? "border-go-teal text-go-teal" : "border-transparent text-go-secondary"}`} aria-current={view === id ? "page" : undefined} onClick={() => update({ event: "" }, id, true)}>{label}</button>)}</nav>}
    <div className={`${card} p-4`}><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"><label className="text-sm font-medium">From<input className={`${field} mt-1`} type="date" value={start} onChange={(e) => update({ from: e.target.value })}/></label><label className="text-sm font-medium">To<input className={`${field} mt-1`} type="date" value={end} onChange={(e) => update({ to: e.target.value })}/></label><label className="text-sm font-medium sm:col-span-2">Search events<input className={`${field} mt-1`} type="search" value={query} placeholder="Person, order, trip, event ID or action" onChange={(e) => update({ q: e.target.value })}/></label></div>
      <div className="mt-3 grid gap-3 sm:grid-cols-3"><Select label="Module" value={params.get("module") ?? ""} options={[...new Set(base.map((e) => e.module))].sort()} onChange={(module) => update({ module })}/><Select label="Outcome" value={params.get("outcome") ?? ""} options={["Completed", "Denied", "Failed", "Pending sync", "Not recorded"]} onChange={(outcome) => update({ outcome })}/><button className={`${secondary} self-end`} aria-expanded={more} onClick={() => setMore(!more)}>{more ? "Fewer filters" : "More filters"}</button></div>
      {more && <div className="mt-3 grid gap-3 sm:grid-cols-2"><Select label="Actor" value={params.get("actor") ?? ""} options={[...new Set(base.map((e) => e.actor))].sort()} onChange={(actor) => update({ actor })}/><Select label="Place" value={params.get("place") ?? ""} options={[...new Set(base.map((e) => e.place))].sort()} onChange={(place) => update({ place })}/><Select label="Device" value={params.get("device") ?? ""} options={[...new Set(base.flatMap((e) => e.device ? [e.device] : []))]} onChange={(device) => update({ device })}/><label className="text-sm font-medium">Correlation ID<input className={`${field} mt-1`} value={params.get("correlation") ?? ""} onChange={(e) => update({ correlation: e.target.value })}/></label></div>}
      {chips.length > 0 && <div className="mt-4 flex flex-wrap gap-2">{chips.map(([key, value]) => <button key={key} className="rounded-full bg-go-subtle px-3 py-2 text-xs text-go-teal" onClick={() => update({ [key]: "" })} aria-label={`Remove ${key} filter`}>{key}: {value} ×</button>)}<button className="px-3 text-sm font-semibold text-go-teal" onClick={clear}>Clear filters</button></div>}
    </div>
    <details className="text-xs text-go-secondary"><summary className="cursor-pointer">Feed states</summary><div className="mt-2 max-w-xs"><Select label="Feed state" value={state} options={["ready", "stale", "unavailable", "denied"]} onChange={(value) => update({ state: value, event: "" })}/></div></details>
    {state === "stale" && <Notice>Showing a stale snapshot. Its counts do not indicate current feed health.</Notice>}
    {start > end ? <Empty>Choose an end date on or after the start date.</Empty> : !available ? <Empty>{state === "denied" ? "Access denied for this view. Existing filters are preserved." : "Audit source unavailable. Counts cannot be determined."}</Empty> : <>
      {view === "overview" && !focused && <><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{[["Events in view", rows.length, ""], ["Denied attempts", rows.filter((e) => e.outcome === "Denied").length, "Denied"], ["Access changes", rows.filter((e) => e.access && e.outcome === "Completed").length, "access"], ["Recorded failures", rows.filter((e) => e.outcome === "Failed").length, "Failed"]].map(([label, value, filter]) => <button key={label} className={`${card} p-5 text-left hover:border-[#8ecdbc]`} onClick={() => update({ outcome: filter === "access" ? "Completed" : String(filter), event: "" }, filter === "access" ? "security" : "activity", true)}><span className="block text-3xl font-semibold text-go-teal">{value}</span><span className="mt-2 block text-sm text-go-secondary">{label} →</span></button>)}</div><section className={`${card} p-5`}><h3 className="font-semibold">Worth a closer look</h3><p className="mt-1 text-xs text-go-secondary">Failures, denials and governance changes in this view.</p><div className="mt-3 space-y-2">{rows.filter((e) => ["Denied", "Failed"].includes(e.outcome) || e.governance).slice(0, 5).map((e) => <button key={e.id} className="flex min-h-11 w-full items-center justify-between gap-3 rounded-xl bg-go-subtle p-3 text-left text-sm" onClick={() => open(e)}><span>{e.title}<span className="block text-xs text-go-secondary">{e.actor} · {e.target}</span></span><Outcome event={e}/></button>)}{!rows.some((e) => ["Denied", "Failed"].includes(e.outcome) || e.governance) && <p className="text-sm text-go-secondary">No review items match these filters.</p>}</div></section></>}
      <div className="flex items-center justify-between"><h3 className="font-semibold">{view === "overview" ? "Recent activity" : view === "security" ? "Access & security activity" : "Activity timeline"}</h3><p className="text-sm text-go-secondary">{rows.length} matching events</p></div>
      {rows.length ? <div className={`${card} overflow-hidden`}><div className="hidden grid-cols-[9rem_1fr_1.5fr_1fr_7rem] gap-3 bg-go-subtle px-5 py-3 text-xs font-semibold text-go-secondary lg:grid"><span>Time</span><span>Actor</span><span>Activity / target</span><span>Place</span><span>Result</span></div>{rows.slice(0, view === "overview" ? 6 : limit).map((event) => <button key={event.id} className="grid w-full gap-2 border-t border-go-rule p-5 text-left text-sm hover:bg-[#f6fbf8] lg:grid-cols-[9rem_1fr_1.5fr_1fr_7rem] lg:gap-3" onClick={() => open(event)}><time className="text-xs text-go-secondary">{eventTime(event.at)}</time><span>{event.actor}</span><span><strong className="block font-semibold">{event.title}</strong><span className="text-xs text-go-secondary">{event.target} · {event.id}</span></span><span className="text-xs text-go-secondary">{event.place}</span><span><Outcome event={event}/><span className="mt-2 block text-xs font-semibold text-go-teal">View details →</span></span></button>)}</div> : <Empty>{period.length ? "No events match these filters." : "No events in this date range."}</Empty>}
      {view === "overview" && rows.length > 6 ? <button className={secondary} onClick={() => update({}, "activity", true)}>View all activity</button> : rows.length > limit && <button className={secondary} onClick={() => setLimit(limit + 12)}>Show more events</button>}
    </>}
    {params.get("event") && !selected && <Notice>This event is unavailable in your current access scope. <button className="underline" onClick={() => update({ event: "" })}>Dismiss</button></Notice>}
    {selected && available && <EventDetails key={selected.id} event={selected} related={base.filter((e) => e.id !== selected.id && e.correlation === selected.correlation).length} onClose={() => update({ event: "" })} onRelated={() => update({ correlation: selected.correlation ?? "", event: "", module: "", outcome: "", actor: "", place: "", q: "", device: "", from: "2026-09-25", to: defaultEnd }, "activity", true)}/>}
  </div>;
}

function Select({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) { return <label className="block text-sm font-medium">{label}<select className={`${field} mt-1`} value={value} onChange={(e) => onChange(e.target.value)}><option value="">All</option>{options.map((item) => <option key={item}>{item}</option>)}</select></label>; }
function Outcome({ event }: { event: AuditEvent }) { return <Badge tone={event.outcome === "Completed" ? "green" : ["Denied", "Failed"].includes(event.outcome) ? "red" : "amber"}>{event.outcome}</Badge>; }
function Notice({ children }: { children: React.ReactNode }) { return <div role="status" className="rounded-2xl border border-[#efd89e] bg-go-warning-tint p-4 text-sm text-[#775500]">{children}</div>; }
