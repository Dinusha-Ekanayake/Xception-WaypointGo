import type { ShellRole } from "./session.ts";

// Each role has an address of its own beside the shared one:
// loader.waypointgo.live on production, loader-preview.waypointgo.live on
// preview. The address only chooses which surface the shell shows; what an
// account may do is still decided by the server. The same six names are listed
// for nginx and the certificate in deploy/vps.

const PREVIEW = "-preview";

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
  const first = labels[0]!;
  return HOST_ROLE.get(first.endsWith(PREVIEW) ? first.slice(0, -PREVIEW.length) : first) ?? null;
}

/** The address of another role in the same environment, seen from a role address. */
export function hostForRole(hostname: string, role: ShellRole): string {
  const [first, ...rest] = hostname.toLowerCase().split(".");
  const label = [...HOST_ROLE].find(([, r]) => r === role)![0];
  return [first!.endsWith(PREVIEW) ? label + PREVIEW : label, ...rest].join(".");
}
