"use client";
import { useState } from "react";
import Icon from "./Icon";
import type { Activity } from "./permissions.ts";
export default function ActivityPanel({ activity }: { activity: Activity[] }) {
  const [search, setSearch] = useState("");
  const filtered = activity.filter(a => [a.actor, a.action, a.target, a.reason].join(" ").toLowerCase().includes(search.toLowerCase()));
  function download() {
    const cell = (value: string) => '"' + (/^[=+@\-\t\r]/.test(value) ? "'" : "") + value.replaceAll('"', '""') + '"';
    const csv = [["Time (UTC)", "Actor", "Action", "Target", "Reason"], ...filtered.map(a => [a.at, a.actor, a.action, a.target, a.reason])].map(row => row.map(cell).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a"); link.href = url; link.download = "waypoint-demo-access-activity.csv"; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section className="ac-panel"><div className="ac-table-toolbar"><label className="ac-search"><Icon name="search" size={18}/><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search actions, people or reasons…" aria-label="Search activity"/></label><button className="ac-button" onClick={download}><Icon name="download" size={16}/>Export CSV</button></div>{filtered.map(a => <article className="ac-activity-row" key={a.id}><span className="ac-activity-dot"><Icon name="shield" size={17}/></span><div><strong>{a.action} <span className="ac-muted">· {a.target}</span></strong><p>{a.reason}</p><small>{a.actor} · {new Date(a.at).toLocaleString("en-GB", { timeZone: "Asia/Colombo" })} · Asia/Colombo</small></div></article>)}{!filtered.length && <div className="ac-empty">No activity matches your search.</div>}<div className="ac-table-footer">{filtered.length} events<span>Demo history stored in this browser tab. Not a production audit log.</span></div></section>;
}
