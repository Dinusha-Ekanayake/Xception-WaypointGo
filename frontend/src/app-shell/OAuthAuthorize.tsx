"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import type { McpAuthorizationView } from "@shared/domain/identity";
import { scopeWords } from "@shared/ui";

const field = "min-h-12 w-full rounded-xl border border-gray-300 px-3 text-black focus:outline-2 focus:outline-teal-700";
export default function OAuthAuthorize() {
  const [client, setClient] = useState<McpAuthorizationView | null>(null);
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const [cancelled, setCancelled] = useState(false); const [email, setEmail] = useState("");
  const [password, setPassword] = useState(""); const submitting = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/oauth/authorize${window.location.search}`, { cache: "no-store", credentials: "omit", signal: controller.signal })
      .then(async r => { if (!r.ok) throw new Error("This connection request is invalid or unavailable. Start again from your assistant."); return r.json(); })
      .then(setClient).catch(e => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, []);
  async function approve(event: FormEvent) {
    event.preventDefault(); if (submitting.current) return;
    submitting.current = true; setBusy(true); setError("");
    const params = new URLSearchParams(window.location.search);
    const body: Record<string, string | null> = { email, password };
    for (const [camel, snake] of Object.entries({ clientId: "client_id", redirectUri: "redirect_uri", responseType: "response_type", codeChallenge: "code_challenge", codeChallengeMethod: "code_challenge_method", state: "state", resource: "resource", scope: "scope" })) body[camel] = params.get(snake);
    try {
      const response = await fetch("/api/oauth/authorize", { method: "POST", credentials: "omit", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(15000) });
      if (!response.ok) throw new Error(response.status === 401 ? "Email or password is incorrect." : response.status === 403 ? "Your account is not authorized for MCP access." : "Authorization could not be completed. Start again or try later.");
      const result = await response.json(); window.location.assign(result.redirectTo);
    } catch (e) { setError(e instanceof Error ? e.message : "Connection unavailable. Try again."); }
    finally { setPassword(""); setBusy(false); submitting.current = false; }
  }
  return <main className="min-h-screen bg-gray-50 px-5 py-14 text-gray-900"><section className="mx-auto max-w-md rounded-2xl border border-gray-200 bg-white p-7 shadow-sm">
    <p className="mb-5 font-semibold text-teal-800">Waypoint Dispatch</p>
    <h1 className="mb-4 text-2xl font-semibold">Connect your assistant</h1>
    {error && <p role="alert" className="mb-4 rounded-lg bg-red-50 p-3 text-red-800">{error}</p>}
    {cancelled ? <p role="status">Connection cancelled. No access was granted. You can close this window.</p> : client ? <>
      <p><strong>{client.clientName}</strong> requests access to Waypoint.</p>
      <p className="mt-2 text-sm text-gray-600">Client names are self-declared. You will return to <strong className="break-all">{client.redirectHost}</strong>. Continue only if you started this connection and trust this destination.</p>
      <p className="mt-4 text-sm font-semibold">Approving lets it:</p>
      <ul className="mb-4 list-disc pl-5 text-sm">{(client.scopes ?? []).map(s => <li key={s}>{scopeWords([s])}</li>)}</ul>
      <p className="my-4 text-sm">Only records your account permissions and scope already allow. It cannot cancel, publish or confirm anything, and every change it proposes waits for your confirmation. Sign in with your personal account to approve.</p>
      <form onSubmit={approve} className="space-y-4">
        <label className="block">Email<input className={field} type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} disabled={busy} /></label>
        <label className="block">Password<input className={field} type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} disabled={busy} /></label>
        <button className="min-h-12 w-full rounded-xl bg-teal-800 px-3 font-semibold text-white disabled:opacity-60" disabled={busy}>{busy ? "Authorizing..." : "Approve access"}</button>
        <button type="button" className="min-h-12 w-full rounded-xl border border-gray-300" disabled={busy} onClick={() => { setPassword(""); setCancelled(true); }}>Cancel</button>
      </form>
    </> : !error && <p role="status">Checking connection request...</p>}
  </section></main>;
}
