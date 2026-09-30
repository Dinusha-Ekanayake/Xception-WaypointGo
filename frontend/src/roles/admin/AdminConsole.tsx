"use client";
import { useEffect, useState } from "react";
import Icon from "./Icon";
import Overview from "./Overview";
import PeoplePanel, { Avatar } from "./PeoplePanel";
import PermissionPanel from "./PermissionPanel";
import PersonDialog from "./PersonDialog";
import ActivityPanel from "./ActivityPanel";
import { initialDemo } from "./demo.ts";
import { canManage, roleName, defaultTemplates, validatePerson, type AdminRole, type ConsoleState, type Person, type Persona } from "./permissions.ts";

const sections = [
  { id: "overview", label: "Overview", icon: "grid", detail: "Your people, permissions and access. All in one place." },
  { id: "people", label: "Team members", icon: "users", detail: "Assign the right role and access to every person on your team." },
  { id: "admins", label: "Administrators", icon: "shield", detail: "Create and manage the admins who look after your operational team." },
  { id: "permissions", label: "Role permissions", icon: "lock", detail: "Define what each persona can do. Fine-tune access without changing their scope." },
  { id: "scopes", label: "Access scopes", icon: "pin", detail: "Control which depots, outlets and vehicle assignments each person can access." },
  { id: "activity", label: "Activity log", icon: "clock", detail: "See who changed access, what changed and why." },
];
export default function AdminConsole({ role, demo = false, displayName, onSignOut }: { role: AdminRole; demo?: boolean; displayName?: string; onSignOut?: () => void }) {
  const [state, setState] = useState<ConsoleState>({ schema: 1, people: [], templates: defaultTemplates(), activity: [] });
  const [ready, setReady] = useState(false);
  const [view, setView] = useState("overview");
  const [person, setPerson] = useState<Person | null>(null);
  const [mobile, setMobile] = useState(false);
  const [toast, setToast] = useState("");
  const [storageError, setStorageError] = useState(false);
  const [permissionDirty, setPermissionDirty] = useState(false);
  const actor = displayName ?? `${roleName(role)} demo`;
  const key = `waypoint-access-demo-v1-${role}`;
  useEffect(() => {
    if (demo) {
      let next = initialDemo();
      try { const raw = sessionStorage.getItem(key); if (raw) { const parsed = JSON.parse(raw); if (parsed.schema === 1 && Array.isArray(parsed.people) && parsed.templates && Array.isArray(parsed.activity)) next = parsed; } } catch { setStorageError(true); }
      if (role === "admin") next.people = next.people.filter(p => p.role !== "admin");
      setState(next);
    }
    setReady(true);
  }, [demo, key, role]);
  useEffect(() => { if (ready && demo) { try { sessionStorage.setItem(key, JSON.stringify(state)); } catch { setStorageError(true); } } }, [state, ready, demo, key]);
  useEffect(() => { if (!toast) return; const timer = setTimeout(() => setToast(""), 5500); return () => clearTimeout(timer); }, [toast]);
  useEffect(() => { if (!permissionDirty) return; const warn = (e: BeforeUnloadEvent) => e.preventDefault(); window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [permissionDirty]);
  function navigate(next: string) { if (next === view) return; if (permissionDirty && !window.confirm("Discard your unsaved permission changes?")) return; setPermissionDirty(false); setView(next); setMobile(false); }
  function create(admin = false) { if (!demo || (admin && role !== "super_admin")) return; setPerson({ id: crypto.randomUUID(), name: "", email: "", role: admin ? "admin" : "dispatcher", status: "Active", scope: [], overrides: {} }); }
  function savePerson(next: Person, reason: string) {
    if (!demo || !canManage(role, next.role)) return;
    const old = state.people.find(p => p.id === next.id);
    if (old && !canManage(role, old.role)) return;
    const error = validatePerson(next, state.people, role); if (error) { setToast(error); return; }
    setState(s => ({ ...s, people: old ? s.people.map(p => p.id === next.id ? next : p) : [...s.people, next], activity: [{ id: crypto.randomUUID(), actor, action: old ? "Account access updated" : next.role === "admin" ? "Administrator created" : "Team member created", target: next.name, reason, at: new Date().toISOString() }, ...s.activity] }));
    setPerson(null); setToast(`${next.name}: demo account ${old ? "updated" : "created"}.`);
  }
  function savePermissions(persona: Persona, ids: string[], reason: string) {
    if (!demo) return;
    setState(s => ({ ...s, templates: { ...s.templates, [persona]: ids }, activity: [{ id: crypto.randomUUID(), actor, action: "Role permissions updated", target: roleName(persona), reason, at: new Date().toISOString() }, ...s.activity] }));
    setToast("Permission changes saved to this demo.");
  }
  const section = sections.find(s => s.id === view)!;
  if (!ready) return <main className="ac-loading">Preparing access workspace…</main>;
  return <div className="access-console">
    <a className="ac-skip" href="#access-main">Skip to content</a>
    {mobile && <button className="ac-sidebar-backdrop" aria-label="Close navigation" onClick={() => setMobile(false)}/>}
    <aside className={`ac-sidebar ${mobile ? "open" : ""}`}><a className="ac-brand" href={demo ? role === "super_admin" ? "/super-admin/demo" : "/admin/demo" : "/"}><span className="ac-brand-mark"><Icon name="route" size={26}/></span><span>waypoint<span className="ac-brand-sub">ACCESS CONSOLE</span></span></a>
      <div className="ac-workspace"><span className="ac-workspace-logo">W</span><div><strong>Waypoint Group</strong><small>Distribution network</small></div><span className="ac-workspace-dot"/></div>
      <span className="ac-nav-label">WORKSPACE</span><nav aria-label="Administration">{sections.filter(s => role === "super_admin" || s.id !== "admins").map(s => <button key={s.id} className={view === s.id ? "active" : ""} onClick={() => navigate(s.id)} aria-current={view === s.id ? "page" : undefined}><Icon name={s.icon}/>{s.label}{s.id === "admins" && <span className="ac-nav-tag">SA</span>}</button>)}</nav>
      <div className="ac-sidebar-bottom"><div className="ac-boundary"><Icon name="shield" size={20}/><strong>{role === "super_admin" ? "Full workspace access" : "Team administration"}</strong><p>{role === "super_admin" ? "You can manage every role, including administrators." : "Manage the four operational personas and their access."}</p></div><div className="ac-current-user"><Avatar name={actor}/><div><strong>{actor}</strong><small>{roleName(role)}</small></div>{onSignOut && <button className="ac-icon-button" onClick={onSignOut} aria-label="Sign out"><Icon name="logout" size={18}/></button>}</div></div>
    </aside>
    <div className="ac-workspace-main"><header className="ac-topbar"><div><button className="ac-menu ac-icon-button" aria-label="Open navigation" onClick={() => setMobile(true)}><Icon name="menu"/></button><span>Workspace</span><span className="ac-breadcrumb">/</span><strong>{section.label}</strong></div><div><span className="ac-badge neutral"><Icon name="shield" size={13}/>{roleName(role)}</span><span className="ac-timezone">Asia/Colombo</span></div></header>
      {demo && <div className="ac-demo-banner"><span><strong>Interactive demo</strong> · Fictional accounts. Changes stay in this browser tab.</span><div><a href={role === "super_admin" ? "/admin/demo" : "/super-admin/demo"} onClick={e => { if (permissionDirty && !window.confirm("Discard unsaved permission changes?")) e.preventDefault(); }}>Preview {role === "super_admin" ? "admin" : "super admin"}<Icon name="arrow" size={14}/></a><button onClick={() => { if (window.confirm("Reset all accounts, permissions and activity in this demo?")) { const fresh = initialDemo(); if (role === "admin") fresh.people = fresh.people.filter(p => p.role !== "admin"); setState(fresh); setView("overview"); setPermissionDirty(false); setToast("Demo reset to its starting state."); } }}>Reset demo</button></div></div>}
      <main id="access-main" className="ac-main"><div className="ac-page-heading"><div><span className="ac-eyebrow">{role === "super_admin" ? "SUPER ADMINISTRATION" : "TEAM ADMINISTRATION"}</span><h1>{view === "overview" ? "Access, made clear." : section.label}</h1><p>{section.detail}</p></div>{demo && ["overview", "people", "admins", "scopes"].includes(view) && <button className="ac-button primary" onClick={() => create(view === "admins")}><Icon name="plus" size={18}/>{view === "admins" ? "Create admin" : "Add team member"}</button>}</div>
        {storageError && <p className="ac-warning" role="alert">Browser storage is unavailable or unreadable. Changes may be lost when you reload.</p>}
        {!demo ? <section className="ac-panel ac-unavailable"><Icon name="lock" size={36}/><h2>Account administration is not connected yet</h2><p>Your session is verified. The backend does not yet expose the account directory, assignments or the super admin hierarchy required by this console.</p><p>Live changes are unavailable. Explore the complete workflow using fictional accounts in the demo.</p><a className="ac-button primary" href={role === "super_admin" ? "/super-admin/demo" : "/admin/demo"}>Open interactive demo<Icon name="arrow" size={17}/></a></section> : <>
          {view === "overview" && <Overview state={state} role={role} onNavigate={navigate}/>}
          {["people", "admins", "scopes"].includes(view) && <PeoplePanel key={view} people={state.people} templates={state.templates} admins={view === "admins"} scopes={view === "scopes"} onEdit={setPerson}/>}
          {view === "permissions" && <PermissionPanel templates={state.templates} people={state.people} onSave={savePermissions} onDirty={setPermissionDirty}/>}
          {view === "activity" && <ActivityPanel activity={state.activity}/>}
        </>}
        <footer className="ac-page-footer"><span>Waypoint Group <span>·</span> People & access</span><span>{demo ? "Demo workspace" : "Authenticated workspace"} <span>·</span> {roleName(role)}</span></footer>
      </main>
    </div>
    {person && <PersonDialog key={person.id} person={person} actor={role} people={state.people} templates={state.templates} onClose={() => setPerson(null)} onSave={savePerson}/>}
    {toast && <div className="ac-toast" role="status"><Icon name="check" size={19}/>{toast}<button className="ac-icon-button" onClick={() => setToast("")} aria-label="Dismiss notification"><Icon name="close" size={16}/></button></div>}
  </div>;
}
