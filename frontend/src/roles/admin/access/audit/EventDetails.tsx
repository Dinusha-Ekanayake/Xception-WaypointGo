"use client";

import { useEffect, useRef, useState } from "react";
import { Badge, secondary } from "../components";
import { eventTime, type AuditEvent } from "./model";

export function EventDetails({ event, related, onClose, onRelated }: { event: AuditEvent; related: number; onClose: () => void; onRelated: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [message, setMessage] = useState("");
  useEffect(() => { const node = ref.current; node?.showModal(); return () => node?.close(); }, []);
  return <dialog ref={ref} aria-labelledby="audit-detail-title" onCancel={(e) => { e.preventDefault(); onClose(); }} className="fixed inset-y-0 left-auto right-0 m-0 h-dvh max-h-dvh w-full max-w-[560px] overflow-y-auto border-0 bg-white p-0 text-[#14231e] shadow-2xl backdrop:bg-[#10261b80]">
    <header className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b bg-white p-6"><div><p className="text-xs font-bold uppercase tracking-widest text-[#62766a]">Sample event · {event.id}</p><h2 id="audit-detail-title" className="mt-2 text-xl font-semibold">{event.title}</h2></div><button className={secondary} aria-label="Close event details" onClick={onClose}>✕</button></header>
    <div className="space-y-6 p-6"><Badge tone={event.outcome === "Denied" || event.outcome === "Failed" ? "red" : event.outcome === "Completed" ? "green" : "amber"}>{event.outcome}</Badge>
      <dl className="grid grid-cols-2 gap-4 text-sm"><Field label="Actor" value={event.actor}/><Field label="Target" value={event.target}/><Field label="Recorded time · Asia/Colombo" value={eventTime(event.at)}/><Field label="Place" value={event.place}/><Field label="Persona at event time" value={event.persona ?? "Not recorded"}/><Field label="Device" value={event.device ?? "Not recorded"}/></dl>
      <section><h3 className="font-semibold">What happened</h3><p className="mt-2 text-sm text-[#52665b]">{event.reason || "Reason not recorded"}</p></section>
      <section><h3 className="font-semibold">What changed</h3>{event.before !== undefined && event.after !== undefined ? <dl className="mt-3 grid gap-3 rounded-2xl bg-[#f0f8f4] p-4 text-sm"><Field label="Before" value={event.before}/><Field label="After" value={event.after}/></dl> : <p className="mt-2 text-sm text-[#62766a]">Before/after values were not captured.</p>}</section>
      <section><h3 className="font-semibold">Access and execution</h3><dl className="mt-3 grid grid-cols-2 gap-3 text-sm"><Field label="Authorization" value={event.authorization}/><Field label="Execution / result" value={event.outcome}/><Field label="Policy version" value="Not recorded"/>{event.expires && <Field label="Exception expiry" value={event.expires}/>}</dl><p className="mt-3 text-xs text-[#62766a]">An allowed request does not by itself prove successful execution.</p></section>
      {event.occurred && <section className="rounded-2xl bg-[#fff7e6] p-4 text-sm"><h3 className="font-semibold">Delayed synchronization</h3><p className="mt-2">Occurred on device: {eventTime(event.occurred)}. Received later at the recorded time shown above.</p></section>}
      <section><h3 className="font-semibold">Related activity</h3>{event.correlation ? <><p className="my-2 text-sm text-[#62766a]">{related} other event{related === 1 ? "" : "s"} with the same recorded correlation ID.</p><button className={secondary} onClick={onRelated}>View related activity</button></> : <p className="mt-2 text-sm text-[#62766a]">No correlation ID was recorded.</p>}</section>
      <details className="rounded-xl border p-4 text-sm"><summary className="cursor-pointer font-semibold">Technical details</summary><dl className="mt-3 space-y-3 break-words"><Field label="Action" value={event.action}/><Field label="Correlation ID" value={event.correlation ?? "Not recorded"}/><Field label="Source" value={event.id.startsWith("CHANGE-") ? "Demo member / permission change" : "Illustrative operational fixture"}/></dl></details>
      <button className={secondary} onClick={async () => { try { await navigator.clipboard.writeText(event.id); setMessage("Event ID copied."); } catch { setMessage(`Copy unavailable. Event ID: ${event.id}`); } }}>Copy event ID</button><p role="status" className="text-sm text-[#006b57]">{message}</p>
    </div>
  </dialog>;
}

function Field({ label, value }: { label: string; value: string }) { return <div className="min-w-0"><dt className="text-xs text-[#62766a]">{label}</dt><dd className="mt-1 break-words">{value}</dd></div>; }
