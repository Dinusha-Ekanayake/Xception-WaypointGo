"use client";
import { useEffect, useState } from "react";
import Icon from "./Icon";
import { permissions, personas, type Persona, type Templates, type Person } from "./permissions.ts";

export default function PermissionPanel({ templates, people, onSave, onDirty, readOnly = false }: { templates: Templates; people: Person[]; onSave: (role: Persona, ids: string[], reason: string) => void; onDirty: (dirty: boolean) => void; readOnly?: boolean }) {
  const [role, setRole] = useState<Persona>("dispatcher");
  const [draft, setDraft] = useState(templates);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const persona = personas.find(p => p.id === role)!;
  const changedRoles = personas.filter(p => [...draft[p.id]].sort().join() !== [...templates[p.id]].sort().join());
  const dirty = changedRoles.length > 0;
  useEffect(() => { onDirty(dirty); }, [dirty, onDirty]);
  const disabledCore = permissions.filter(p => p.required && !draft[p.role].includes(p.id) && changedRoles.some(r => r.id === p.role));
  function save() {
    if (!reason.trim()) { setError("Add a reason before saving permission changes."); return; }
    changedRoles.forEach(p => onSave(p.id, draft[p.id], reason.trim()));
    setReason(""); setError("");
  }
  return <>
    <div className="ac-personas">{personas.map(p => <button key={p.id} className={`ac-persona ${role === p.id ? "selected" : ""}`} onClick={() => { setRole(p.id); setSearch(""); }} aria-pressed={role === p.id}><span className={`ac-role-icon ${p.id}`}><Icon name={p.icon}/></span><strong>{p.name}</strong><small>{draft[p.id].length} permissions enabled</small></button>)}</div>
    <section className="ac-panel">
      <div className="ac-panel-heading"><div><h2>{persona.name} permissions</h2><p>{persona.description}</p></div><span className="ac-badge neutral"><Icon name="pin" size={13}/>{persona.scope}</span></div>
      <div className="ac-permission-toolbar"><label className="ac-search"><Icon name="search" size={18}/><input placeholder="Find a permission…" aria-label="Find a permission" value={search} onChange={e => setSearch(e.target.value)}/></label><span>{people.filter(p => p.role === role).length} assigned people</span></div>
      <div className="ac-permission-list">{permissions.filter(p => p.role === role && (p.label + p.detail).toLowerCase().includes(search.toLowerCase())).map(p => <div className="ac-permission-row" key={p.id}>
        <div className="ac-permission-copy"><strong>{p.label} {p.required && <span className="ac-core">Core workflow</span>}</strong><p>{p.detail}</p><small>{p.source === "Booklet" ? `Challenge booklet · pp. ${p.page}` : "Product extension · recommended"}</small></div>
        <button className="ac-switch" role="switch" aria-checked={draft[role].includes(p.id)} aria-label={p.label} disabled={readOnly} onClick={() => setDraft({ ...draft, [role]: draft[role].includes(p.id) ? draft[role].filter(id => id !== p.id) : [...draft[role], p.id] })}><span/></button>
      </div>)}</div>
      {permissions.filter(p => p.role === role && (p.label + p.detail).toLowerCase().includes(search.toLowerCase())).length === 0 && <div className="ac-empty">No permissions match “{search}”.</div>}
      <div className="ac-policy-note"><Icon name="lock" size={17}/><p>Permissions never remove capacity, temperature, fuel, delivery-window or assignment rules. Offline recording and synchronization are built-in driver capabilities, not optional permission switches.</p></div>
    </section>
    {dirty && <section className="ac-save-panel"><div><strong>Review your permission changes</strong><p>Updates apply to {changedRoles.map(p => p.name).join(", ")}. Individual overrides remain in effect.</p></div>{disabledCore.length > 0 && <p className="ac-warning">{disabledCore.length} core workflow permission(s) will be disabled. Affected people may be unable to complete their work.</p>}<label>Reason for change<input value={reason} onChange={e => setReason(e.target.value)} placeholder="Why are these permissions changing?" maxLength={400}/></label>{error && <p className="ac-error" role="alert">{error}</p>}<div className="ac-actions"><button className="ac-button" onClick={() => { setDraft(templates); setReason(""); setError(""); }}>Discard changes</button><button className="ac-button primary" onClick={save}>Save permissions<Icon name="check" size={16}/></button></div></section>}
  </>;
}
