"use client";
import { useEffect, useState } from "react";
import AdminConsole from "@roles/admin/AdminConsole";
import type { AdminRole } from "@roles/admin/permissions";
import { request } from "@shared/api/client";
import type { Session } from "./session";

export function AdminDemo({ role }: { role: AdminRole }) { return <AdminConsole role={role} demo/>; }

export default function AdminEntry({ requiredRole }: { requiredRole: AdminRole }) {
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { let active = true; request<Session>("/api/session").then(s => { if (active) setSession(s); }).catch(() => {}).finally(() => { if (active) setChecking(false); }); return () => { active = false; }; }, []);
  async function signIn(e: React.FormEvent) { e.preventDefault(); setBusy(true); setError(""); try { setSession(await request<Session>("/api/session", { method: "POST", body: { email, password } })); setPassword(""); } catch { setError("Sign-in failed. Check your credentials and that the backend is available."); } finally { setBusy(false); } }
  async function signOut() { try { await request("/api/session/end", { method: "POST" }); setSession(null); setError(""); } catch { setError("Sign-out failed. Your session may still be active; please retry."); } }
  if (checking) return <main className="ac-loading">Checking your session…</main>;
  if (session) {
    const role = session.roles.includes("super_admin") ? "super_admin" : session.roles.includes("admin") ? "admin" : null;
    if (!role || (requiredRole === "super_admin" && role !== "super_admin")) return <main className="ac-auth"><section><span className="ac-eyebrow">WAYPOINT · ACCESS CONSOLE</span><h1>Access restricted</h1><p>This workspace requires {requiredRole === "super_admin" ? "a super admin" : "an administrator"} account.</p>{error && <p role="alert" className="ac-error">{error}</p>}<button className="ac-button primary" onClick={signOut}>Sign out</button><a href="/">Return to your workspace</a></section></main>;
    return <>{error && <div className="ac-error" role="alert">{error}</div>}<AdminConsole role={role} displayName={session.displayName} onSignOut={signOut}/></>;
  }
  return <main className="ac-auth"><section><span className="ac-eyebrow">WAYPOINT · ACCESS CONSOLE</span><h1>Your team.<br/>In the right hands.</h1><p>Sign in to your {requiredRole === "super_admin" ? "super admin" : "admin"} workspace.</p><form onSubmit={signIn}><label>Email address<input required type="email" autoComplete="username" value={email} onChange={e => setEmail(e.target.value)}/></label><label>Password<input required type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)}/></label>{error && <p className="ac-error" role="alert">{error}</p>}<button className="ac-button primary" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button></form><p className="ac-footnote">Reviewing the UI? <a href={requiredRole === "super_admin" ? "/super-admin/demo" : "/admin/demo"}>Open the interactive demo</a></p></section></main>;
}
