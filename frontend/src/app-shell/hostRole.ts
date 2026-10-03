import type { ShellRole } from "./session.ts";

// Each role has an address of its own beside the shared one:
// loader.waypointgo.live on production, loader-preview.waypointgo.live on
// preview. The address only chooses which surface the shell shows; what an
// account may do is still decided by the server. The same six names are listed
// for nginx and the certificate in deploy/vps.

/**
 * Whether this build is served with the role addresses beside it. A build-time
 * value, so the page stays static and the offline shell stays one file.
 */
export const ROLE_ADDRESSES = process.env.NEXT_PUBLIC_ROLE_ADDRESSES === "1";

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

/**
 * A role's own address, seen from the address every role shares: the bare name
 * on production, `preview.` in front of it on preview. Null anywhere else.
 *
 * The preview name says by itself that the role addresses exist. A bare name
 * does not: local development and a deployment without them are shared
 * addresses too, and must keep every role in one place. So a name that is not
 * the preview's is a shared home only where the build says the role addresses
 * are served (`roleAddresses`, set by the VPS deployment).
 */
export function sharedHomeFor(hostname: string, role: ShellRole, roleAddresses: boolean): string | null {
  const [first, ...rest] = hostname.toLowerCase().split(".");
  const label = [...HOST_ROLE].find(([, r]) => r === role)![0];
  if (first === "preview") return rest.length < 2 ? null : [label + PREVIEW, ...rest].join(".");
  if (!roleAddresses || rest.length < 1 || first === "www" || roleForHost(hostname) !== null) return null;
  // An address made only of numbers has no names under it.
  if (/^[\d.]+$/.test(hostname) || hostname.includes(":")) return null;
  return [label, first, ...rest].join(".");
}

/** True on the shared preview address, where the landing says it is the preview. */
export function isPreviewHome(hostname: string): boolean {
  return sharedHomeFor(hostname, "dispatcher", false) !== null;
}

/** The address of another role in the same environment, seen from a role address. */
export function hostForRole(hostname: string, role: ShellRole): string {
  const [first, ...rest] = hostname.toLowerCase().split(".");
  const label = [...HOST_ROLE].find(([, r]) => r === role)![0];
  return [first!.endsWith(PREVIEW) ? label + PREVIEW : label, ...rest].join(".");
}
