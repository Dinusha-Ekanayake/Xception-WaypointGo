"use client";

import { useEffect, useRef, useState } from "react";
import Icon from "./Icon";
import { personas, permissions, roleName, validatePerson, type AdminRole, type Person, type Templates } from "./permissions.ts";

export default function PersonDialog({ person, actor, people, templates, onClose, onSave }: {
  person: Person; actor: AdminRole; people: Person[]; templates: Templates;
  onClose: () => void; onSave: (person: Person, reason: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [draft, setDraft] = useState<Person>(person);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [tab, setTab] = useState("details");
  const editing = people.some(p => p.id === person.id);
  useEffect(() => { dialog.current?.showModal(); }, []);
  const options = draft.role === "store_manager" ? Array.from({ length: 120 }, (_, i) => `OUT${String(i + 1).padStart(3, "0")}`) : draft.role === "driver" ? Array.from({ length: 60 }, (_, i) => `VEH${String(i + 1).padStart(3, "0")}`) : ["Peliyagoda", "Kandy"];
  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = validatePerson(draft, people, actor);
    if (invalid || !reason.trim()) { setError(invalid ?? "Add a reason for this access change."); return; }
    onSave({ ...draft, name: draft.name.trim(), email: draft.email.trim().toLowerCase() }, reason.trim());
  }
  return <dialog ref={dialog} className="ac-dialog" onCancel={onClose} onClose={onClose} aria-labelledby="person-dialog-title">
    <form onSubmit={submit}>
      <div className="ac-dialog-head"><div><span className="ac-eyebrow">ACCOUNT MANAGEMENT · DEMO</span><h2 id="person-dialog-title">{editing ? "Manage account" : draft.role === "admin" ? "Create an admin" : "Add a team member"}</h2></div><button type="button" className="ac-icon-button" onClick={onClose} aria-label="Close account dialog"><Icon name="close" /></button></div>
      <div className="ac-tabs"><button type="button" className={tab === "details" ? "selected" : ""} onClick={() => setTab("details")}>Account & scope</button><button type="button" className={tab === "permissions" ? "selected" : ""} onClick={() => setTab("permissions")}>Individual permissions</button></div>
      <div className="ac-dialog-body">
        {tab === "details" ? <>
          <div className="ac-field-grid"><label>Full name<input value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} maxLength={100} autoFocus placeholder="e.g. Nethmi Fernando" /></label><label>Email address<input type="email" value={draft.email} onChange={e => setDraft({ ...draft, email: e.target.value })} maxLength={160} placeholder="name@example.com" /></label></div>
          <div className="ac-field-grid"><label>Role<select value={draft.role} onChange={e => setDraft({ ...draft, role: e.target.value as Person["role"], scope: [], overrides: {}, from: undefined, until: undefined })}>
            {personas.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}{actor === "super_admin" && <option value="admin">Admin</option>}
          </select></label><label>Account status<select value={draft.status} onChange={e => setDraft({ ...draft, status: e.target.value as Person["status"] })}><option>Active</option><option>Suspended</option></select></label></div>
          <section className="ac-form-section"><h3>Access scope</h3><p>{draft.role === "admin" ? "Admins manage the four operational personas and their permissions. They cannot create or manage admins." : personas.find(p => p.id === draft.role)?.scope + ". Permissions only apply inside this assignment."}</p>
            {draft.role !== "admin" && <><label>{draft.role === "driver" ? "Vehicle" : draft.role === "store_manager" ? "Add outlet" : "Assign depot"}<select value="" onChange={e => { if (e.target.value) setDraft({ ...draft, scope: draft.role === "driver" || draft.role === "loader" ? [e.target.value] : [...new Set([...draft.scope, e.target.value])] }); }}><option value="">Select an assignment…</option>{options.map(o => <option key={o} value={o} disabled={draft.scope.includes(o)}>{o}</option>)}</select></label><div className="ac-chips">{draft.scope.map(s => <button type="button" key={s} onClick={() => setDraft({ ...draft, scope: draft.scope.filter(x => x !== s) })} aria-label={`Remove ${s}`}>{s}<Icon name="close" size={13}/></button>)}</div></>}
            {draft.role === "driver" && <div className="ac-field-grid"><label>Assignment starts<input type="date" value={draft.from ?? ""} onChange={e => setDraft({ ...draft, from: e.target.value })}/></label><label>Assignment ends (inclusive)<input type="date" min={draft.from} value={draft.until ?? ""} onChange={e => setDraft({ ...draft, until: e.target.value })}/></label></div>}
          </section>
        </> : draft.role === "admin" ? <div className="ac-callout"><Icon name="shield"/><div><strong>Administrative access</strong><p>Manage operational accounts, assignments and persona permissions. Creating admins remains exclusive to the super admin.</p></div></div> : <>
          <p className="ac-muted">Inherit the role default, or make an explicit exception for this person.</p>
          {permissions.filter(p => p.role === draft.role).map(p => <div className="ac-override" key={p.id}><div><strong>{p.label}</strong><small>Role default: {templates[draft.role as keyof Templates].includes(p.id) ? "allowed" : "off"}</small></div><select aria-label={`${p.label} override`} value={draft.overrides[p.id] === undefined ? "inherit" : draft.overrides[p.id] ? "allow" : "deny"} onChange={e => { const overrides = { ...draft.overrides }; if (e.target.value === "inherit") delete overrides[p.id]; else overrides[p.id] = e.target.value === "allow"; setDraft({ ...draft, overrides }); }}><option value="inherit">Inherit role</option><option value="allow">Allow</option><option value="deny">Deny</option></select></div>)}
        </>}
        <label className="ac-reason">Reason for change<textarea value={reason} onChange={e => setReason(e.target.value)} placeholder="Explain the assignment or permission change…" maxLength={400} rows={2}/></label>
        {error && <p className="ac-error" role="alert">{error}</p>}
        <p className="ac-footnote">Demo only. No invitation is sent and no real account or login credential is created.</p>
      </div>
      <div className="ac-dialog-footer"><button type="button" className="ac-button" onClick={onClose}>Cancel</button><button className="ac-button primary" type="submit">{editing ? "Save account changes" : "Create demo account"}<Icon name="arrow" size={16}/></button></div>
    </form>
  </dialog>;
}
