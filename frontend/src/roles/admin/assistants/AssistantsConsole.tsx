"use client";

import { useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import type { McpClientView, McpUsageView } from "@shared/domain/types";
import { codeLabel } from "@shared/wording/labels";
import { clock, dayLabel, depotToday } from "@shared/wording/time";
import { Badge, Empty, Modal, card, field, primary, secondary } from "../access/components";
import { TOOL_LABELS, blockClient, loadClients, loadUsage, unblockClient } from "./data";

// Live AI assistants console (issue #177): which assistant apps are connected,
// what they did in the last 24 hours, and blocking an app. Unlike the access
// console beside it, this reads live data.

const when = (instant: string | null) => (instant ? `${dayLabel(depotToday(new Date(instant)))} ${clock(instant)}` : "never");

export default function AssistantsConsole(): React.JSX.Element {
  const clients = useResource(loadClients, "mcp-clients");
  const usage = useResource(loadUsage, "mcp-usage", 60_000);
  const [blocking, setBlocking] = useState<McpClientView | null>(null);
  const [message, setMessage] = useState("");

  const refresh = () => { clients.refresh(); usage.refresh(); };
  const unblock = async (client: McpClientView) => {
    setMessage("");
    try {
      await unblockClient(client);
      setMessage(`${client.clientName} is unblocked. People can connect it again.`);
    } catch (error) {
      setMessage(refusal(error));
    } finally {
      refresh();
    }
  };

  return (
    <div className="flex flex-col gap-6 text-go-ink">
      <p className="text-sm text-go-secondary">
          Assistant apps people have connected to Waypoint. Each one only ever acts as the person who connected it, within their own
          permissions, and every change it proposes waits for that person to confirm. Blocking an app disconnects everyone using it.
      </p>
      {message && <p role="status" className="rounded-xl bg-go-subtle px-4 py-3 text-sm">{message}</p>}

      <section aria-label="Assistant apps" className={card}>
        <h2 className="border-b border-go-rule px-5 py-4 text-lg font-semibold">Apps</h2>
        {clients.error ? <p className="px-5 py-4 text-sm text-go-danger">The apps could not be loaded: {refusal(clients.error)}</p>
          : !clients.data ? <p className="px-5 py-4 text-sm text-go-secondary">Loading...</p>
          : clients.data.length === 0 ? <div className="p-5"><Empty>No assistant app has been connected yet.</Empty></div>
          : clients.data.map((client) => (
            <div key={client.clientId} className="flex flex-wrap items-center gap-3 border-b border-go-rule px-5 py-4 last:border-0">
              <div className="min-w-48 flex-1">
                <p className="font-semibold">{client.clientName}</p>
                <p className="text-sm text-go-secondary">
                  {client.activeConnections} connected · last used {when(client.lastUsedAt)}
                  {client.blockReason && ` · blocked: ${client.blockReason}`}
                </p>
              </div>
              {client.blockedAt ? <Badge tone="red">Blocked</Badge> : <Badge tone="green">Allowed</Badge>}
              {client.blockedAt
                ? <button className={secondary} onClick={() => void unblock(client)}>Unblock</button>
                : <button className={secondary} onClick={() => setBlocking(client)}>Block</button>}
            </div>
          ))}
      </section>

      <Usage usage={usage.data} error={usage.error} clients={clients.data ?? []} />

      {blocking && <BlockDialog client={blocking} onClose={() => setBlocking(null)} onDone={(text) => { setBlocking(null); setMessage(text); refresh(); }} />}
    </div>
  );
}

function Usage({ usage, error, clients }: { usage: McpUsageView[] | null; error: Error | null; clients: McpClientView[] }) {
  const name = (clientId: string | null) => clientId === null ? "On a computer" : clients.find((c) => c.clientId === clientId)?.clientName ?? "Removed app";
  return (
    <section aria-label="Last 24 hours" className={card}>
      <h2 className="border-b border-go-rule px-5 py-4 text-lg font-semibold">Last 24 hours</h2>
      {error ? <p className="px-5 py-4 text-sm text-go-danger">Usage could not be loaded: {refusal(error)}</p>
        : !usage ? <p className="px-5 py-4 text-sm text-go-secondary">Loading...</p>
        : usage.length === 0 ? <div className="p-5"><Empty>No assistant used Waypoint in the last 24 hours.</Empty></div>
        : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-left text-sm">
              <thead className="text-go-secondary"><tr>
                <th className="px-5 py-3 font-medium">What</th><th className="px-3 py-3 font-medium">App</th>
                <th className="px-3 py-3 font-medium">Calls</th><th className="px-3 py-3 font-medium">Refused</th>
                <th className="px-3 py-3 font-medium">Failed</th><th className="px-3 py-3 font-medium">Slowest 5%</th><th className="px-5 py-3" />
              </tr></thead>
              <tbody>
                {usage.map((row) => (
                  <tr key={`${row.tool}:${row.clientId ?? "local"}`} className="border-t border-go-rule">
                    <td className="px-5 py-3">{codeLabel(TOOL_LABELS, row.tool)}</td>
                    <td className="px-3 py-3">{name(row.clientId)}</td>
                    <td className="px-3 py-3">{row.calls}</td>
                    <td className="px-3 py-3">{row.denied + row.rateLimited}</td>
                    <td className="px-3 py-3">{row.errors}</td>
                    <td className="px-3 py-3">{row.p95Ms === null ? "" : `${Math.round(row.p95Ms)} ms`}</td>
                    <td className="px-5 py-3">{row.attention && <Badge tone="amber">Needs attention</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      <p className="px-5 py-3 text-xs text-go-secondary">Needs attention: ten or more refused calls. The app may be set up wrongly, or trying records its person cannot see.</p>
    </section>
  );
}

function BlockDialog({ client, onClose, onDone }: { client: McpClientView; onClose: () => void; onDone: (message: string) => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async () => {
    setBusy(true);
    setError("");
    try {
      await blockClient(client, reason.trim());
      onDone(`${client.clientName} is blocked and everyone using it is disconnected.`);
    } catch (failure) {
      setError(refusal(failure));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={`Block ${client.clientName}`} onClose={onClose}>
      <p className="text-sm">Everyone using this app is disconnected now, and nobody can connect it again until you unblock it.</p>
      <label className="mt-4 block text-sm font-medium">Reason (recorded with your name)
        <textarea className={`${field} mt-1 min-h-24`} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      {error && <p role="alert" className="mt-3 text-sm text-go-danger">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button className={secondary} onClick={onClose} disabled={busy}>Cancel</button>
        <button className={primary} onClick={() => void submit()} disabled={busy || reason.trim().length === 0}>{busy ? "Blocking..." : "Block app"}</button>
      </div>
    </Modal>
  );
}

/** Branches on the problem code, never on its title. */
function refusal(error: unknown): string {
  if (!(error instanceof ApiError)) return "Waypoint could not be reached. Try again.";
  switch (error.problem.code) {
    case "FORBIDDEN": return "your account is not allowed to do this.";
    case "VERSION_CONFLICT": return "someone else changed this app first. The list is refreshed; try again.";
    case "CONFLICT": return "this app was already changed. The list is refreshed.";
    default: return "Waypoint could not do this. Try again.";
  }
}
