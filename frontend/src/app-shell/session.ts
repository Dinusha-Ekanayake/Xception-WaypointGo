import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";

// Who is signed in, according to the server. The client never decides this: it
// asks, and renders what it is told. Mirrors identity's SessionView.

export type ShellRole = "dispatcher" | "loader" | "driver" | "store_manager" | "admin" | "auditor";

export type Session = {
  userId: string;
  displayName: string;
  roles: ShellRole[];
  /** Prefixed grants such as `depot:PELIYAGODA` or `outlet:OUT001`. */
  scope: string[];
};

/** The three states the shell must tell apart, because each needs different words. */
export type SessionState =
  | { kind: "signed-in"; session: Session }
  | { kind: "signed-out" }
  | { kind: "unreachable"; message: string };

/**
 * The values of one kind of grant, without the prefix. An unprefixed value is
 * passed through, so a session from an older server still routes.
 */
export function scopeOf(session: Session, kind: "depot" | "outlet" | "vehicle"): string[] {
  return session.scope.flatMap((s) => {
    const at = s.indexOf(":");
    if (at < 0) return [s];
    return s.slice(0, at) === kind ? [s.slice(at + 1)] : [];
  });
}

/** 401 means signed out. Anything else that fails means we could not ask. */
function classify(error: unknown): SessionState {
  if (error instanceof ApiError && error.status === 401) return { kind: "signed-out" };
  const offline = typeof navigator !== "undefined" && !navigator.onLine;
  return {
    kind: "unreachable",
    message: offline ? "This device is offline." : error instanceof ApiError ? `The server answered ${error.status}.` : "The server did not answer.",
  };
}

export async function currentSession(): Promise<SessionState> {
  try {
    return { kind: "signed-in", session: await request<Session>("/api/session") };
  } catch (error) {
    return classify(error);
  }
}

// No device id yet: the backend only accepts ids already in iam.devices, and
// nothing registers devices until the device-registration flow exists.
export function signIn(email: string, password: string): Promise<Session> {
  return request<Session>("/api/session", { method: "POST", body: { email, password } });
}

export async function signOut(): Promise<void> {
  await request<null>("/api/session/end", { method: "POST" });
}

const ROLE_KEY = "waypoint.lastRole";

/** The role this person last chose on this device, if they still hold it. */
export function rememberedRole(session: Session): ShellRole {
  try {
    const last = localStorage.getItem(`${ROLE_KEY}.${session.userId}`) as ShellRole | null;
    if (last && session.roles.includes(last)) return last;
  } catch {
    // Storage blocked: fall back to the first role.
  }
  return session.roles[0]!;
}

export function rememberRole(session: Session, role: ShellRole): void {
  try {
    localStorage.setItem(`${ROLE_KEY}.${session.userId}`, role);
  } catch {
    // A convenience only.
  }
}

export const ROLE_LABEL: Record<ShellRole, string> = {
  dispatcher: "Dispatcher",
  loader: "Loader",
  driver: "Driver",
  store_manager: "Store",
  admin: "Admin",
  auditor: "Auditor",
};
