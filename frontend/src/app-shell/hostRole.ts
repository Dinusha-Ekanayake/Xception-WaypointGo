import type { ShellRole } from "./session.ts";

// Each role has an address of its own, such as loader.waypointgo.live, beside
// the shared one. The address only chooses which surface the shell shows; what
// an account may do is still decided by the server. The same six names are
// listed for nginx and the certificate in deploy/vps.

const HOST_ROLE = new Map<string, ShellRole>([
  ["dispatcher", "dispatcher"],
  ["loader", "loader"],
  ["driver", "driver"],
  ["store", "store_manager"],
  ["admin", "admin"],
  ["auditor", "auditor"],
]);

/** The role this address is for, or null on an address every role shares. */
export function roleForHost(hostname: string): ShellRole | null {
  const labels = hostname.toLowerCase().split(".");
  if (labels.length < 3) return null;
  return HOST_ROLE.get(labels[0]!) ?? null;
}

/** The address of another role, seen from a role address. */
export function hostForRole(hostname: string, role: ShellRole): string {
  const label = [...HOST_ROLE].find(([, r]) => r === role)![0];
  return [label, ...hostname.toLowerCase().split(".").slice(1)].join(".");
}
