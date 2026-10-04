"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import { McpSwitchPolicy, type McpPrincipalAccessView, type McpSwitchPolicyName } from "@shared/domain/types";
import { codeLabel } from "@shared/wording/labels";
import { Badge, Empty, Modal, card, field, primary, secondary } from "../access/components";
import { ROLE_LABELS, endConnections, loadPeople, loadRoles, loadSwitchVersions, refusal, setSwitch } from "./data";

// Assistants per person and per role (R-IAM-38). Each switch is a policy
// attached directly to the person or role, so it takes effect on their next
// request with no deployment; ending connections also removes what they hold.

/** A switch reads as what the person may do, so "on" never means "blocked". */
const SWITCHES: { label: string; policy: McpSwitchPolicyName; onWhenAttached: boolean }[] = [
  { label: "Assistants", policy: McpSwitchPolicy.Blocked, onWhenAttached: false },
  { label: "Changes", policy: McpSwitchPolicy.NoWrites, onWhenAttached: false },
  { label: "Personal details", policy: McpSwitchPolicy.PersonalReader, onWhenAttached: true },
];

const connections = (n: number) => `${n} ${n === 1 ? "connection" : "connections"}`;

export default function PeopleSection({ selfId, onChanged }: { selfId?: string; onChanged: () => void }): React.JSX.Element {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const roles = useResource(loadRoles, "mcp-access-roles");
  const people = useResource((signal) => loadPeople(search, signal), `mcp-access-people:${search}`);
  const versions = useResource(loadSwitchVersions, "mcp-switch-versions");
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const [ending, setEnding] = useState<McpPrincipalAccessView | null>(null);

  const refresh = () => { roles.refresh(); people.refresh(); versions.refresh(); onChanged(); };
  const name = (principal: McpPrincipalAccessView) =>
    principal.principalType === "role" ? codeLabel(ROLE_LABELS, principal.principalId) : principal.label;

  const toggle = async (principal: McpPrincipalAccessView, policy: McpSwitchPolicyName, label: string, on: boolean) => {
    const version = versions.data?.[policy];
    if (version === undefined) return;
    const key = `${principal.principalType}:${principal.principalId}:${policy}`;
    setBusy(key);
    setMessage("");
    try {
      const attach = SWITCHES.find((s) => s.policy === policy)?.onWhenAttached ? on : !on;
      await setSwitch(principal, policy, attach, version);
      setMessage(`${label} ${on ? "on" : "off"} for ${name(principal)}, from their next request.`);
    } catch (error) {
      setMessage(`${label} was not changed: ${refusal(error)}`);
    } finally {
      setBusy("");
      refresh();
    }
  };

  const row = (principal: McpPrincipalAccessView) => (
    <div key={`${principal.principalType}:${principal.principalId}`} className="flex flex-wrap items-center gap-3 border-b border-go-rule px-5 py-4 last:border-0">
      <div className="min-w-48 flex-1">
        <p className="font-semibold">{name(principal)}</p>
        {principal.principalType === "user" && <p className="text-sm text-go-secondary">{connections(principal.liveConnections)} now</p>}
      </div>
      {SWITCHES.map(({ label, policy, onWhenAttached }) => {
        const attached = principal.policies.includes(policy);
        const on = attached === onWhenAttached;
        const key = `${principal.principalType}:${principal.principalId}:${policy}`;
        // Blocked denies mcp:*, this screen's own permission too: never offer the switch that locks the administrator out.
        const locksOut = policy === McpSwitchPolicy.Blocked && on
          && (principal.principalType === "role" ? principal.principalId === "admin" : principal.principalId === selfId);
        return (
          <label key={policy} className="flex min-h-11 items-center gap-2 text-sm">
            <input type="checkbox" className="size-5" checked={on} disabled={busy !== "" || !versions.data || locksOut}
              title={locksOut ? "Turning this off would remove your own access to this screen" : undefined}
              onChange={() => void toggle(principal, policy, label, !on)} />
            {busy === key ? "Saving..." : label}
          </label>
        );
      })}
      {principal.principalType === "user" && (
        <button className={secondary} disabled={principal.liveConnections === 0} onClick={() => setEnding(principal)}>End connections</button>
      )}
    </div>
  );

  return (
    <section aria-label="People and roles" className={card}>
      <h2 className="border-b border-go-rule px-5 py-4 text-lg font-semibold">People and roles</h2>
      <p className="px-5 pt-4 text-sm text-go-secondary">
        Turn assistants, the changes they propose, or personal details off and on for one person or a whole role. A change takes
        effect on their next request. Switches show what is set on that person or role itself: a role switched off also stops its people.
      </p>
      {message && <p role="status" className="mx-5 mt-4 rounded-xl bg-go-subtle px-4 py-3 text-sm">{message}</p>}
      {versions.error && <p className="px-5 pt-4 text-sm text-go-danger">Switches are unavailable: {refusal(versions.error)}</p>}

      <h3 className="px-5 pt-5 text-sm font-semibold text-go-secondary">Roles</h3>
      {roles.error ? <p className="px-5 py-4 text-sm text-go-danger">The roles could not be loaded: {refusal(roles.error)}</p>
        : !roles.data ? <p className="px-5 py-4 text-sm text-go-secondary">Loading...</p>
        : roles.data.map(row)}

      <h3 className="px-5 pt-5 text-sm font-semibold text-go-secondary">People</h3>
      <form className="flex flex-wrap gap-2 px-5 pt-3" onSubmit={(e) => { e.preventDefault(); setSearch(query); }}>
        <label className="min-w-48 flex-1 text-sm">
          <span className="sr-only">Find a person by name or email</span>
          <input className={field} placeholder="Find a person by name or email" maxLength={100} value={query} onChange={(e) => setQuery(e.target.value)} />
        </label>
        <button className={secondary} type="submit">Find</button>
        {search && <button className={secondary} type="button" onClick={() => { setQuery(""); setSearch(""); }}>Clear</button>}
      </form>
      {people.error ? <p className="px-5 py-4 text-sm text-go-danger">People could not be loaded: {refusal(people.error)}</p>
        : !people.data ? <p className="px-5 py-4 text-sm text-go-secondary">Loading...</p>
        : people.data.items.length === 0
          ? <div className="p-5"><Empty>{search ? "Nobody matches that name or email." : "Nobody is connected or switched individually. Find a person to change their switches."}</Empty></div>
          : <div className="pt-2">{people.data.items.map(row)}</div>}
      {people.data?.nextCursor && <p className="px-5 py-3 text-xs text-go-secondary">More people match. <Badge>Narrow the search</Badge></p>}

      {ending && <EndDialog person={ending} onClose={() => setEnding(null)} onDone={(text) => { setEnding(null); setMessage(text); refresh(); }} />}
    </section>
  );
}

function EndDialog({ person, onClose, onDone }: { person: McpPrincipalAccessView; onClose: () => void; onDone: (message: string) => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async () => {
    setBusy(true);
    setError("");
    try {
      await endConnections(person.principalId, reason.trim());
      onDone(`${person.label} is disconnected from every assistant. Turn Assistants off as well to stop them connecting again.`);
    } catch (failure) {
      setError(refusal(failure));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={`End connections of ${person.label}`} onClose={onClose}>
      <p className="text-sm">Every assistant {person.label} has connected is disconnected now. Their own sign-in to Waypoint is not affected.</p>
      <label className="mt-4 block text-sm font-medium">Reason (recorded with your name)
        <textarea className={`${field} mt-1 min-h-24`} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      {error && <p role="alert" className="mt-3 text-sm text-go-danger">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button className={secondary} onClick={onClose} disabled={busy}>Cancel</button>
        <button className={primary} onClick={() => void submit()} disabled={busy || reason.trim().length === 0}>{busy ? "Ending..." : "End connections"}</button>
      </div>
    </Modal>
  );
}
