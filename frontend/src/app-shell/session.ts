import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { prefetchesWorkingSet, type Role } from "@shared/offline";
import { forgetCrew } from "./offlinePin.ts";

// Who is signed in, according to the server. The client never decides this: it
// asks, and renders what it is told. Mirrors identity's SessionView.

export type ShellRole = "dispatcher" | "loader" | "driver" | "store_manager" | "admin" | "auditor";

export type Session = {
  userId: string;
  displayName: string;
  roles: ShellRole[];
  /** Loader working on this shared device, or null while the screen is locked. */
  operator: Operator | null;
  /** Prefixed grants such as `depot:PELIYAGODA` or `outlet:OUT001`. */
  scope: string[];
};

export type Operator = {
  userId: string;
  displayName: string;
  employeeCode: string;
  since: string;
};

/** The three states the shell must tell apart, because each needs different words. */
export type SessionState =
  | { kind: "signed-in"; session: Session; unverified?: boolean }
  | { kind: "signed-out" }
  | { kind: "unreachable"; message: string; status?: number };

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
  const status = error instanceof ApiError ? error.status : undefined;
  return {
    kind: "unreachable",
    status,
    message: offline ? "This device is offline." : error instanceof ApiError ? `The server answered ${error.status}.` : "The server did not answer.",
  };
}

const SESSION_KEY = "waypoint.session";

/**
 * Who the server last confirmed on this device: name, roles and scope, and no
 * credential. It lets a role that works with no signal carry on when the server
 * cannot be asked, and nothing else: it is never sent anywhere, the server
 * still decides every write, and a 401 or a sign-out forgets it.
 */
function remember(session: Session): void {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  } catch {
    // Storage blocked: the device simply cannot carry on offline.
  }
}

function forget(): void {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch {
    // Nothing to forget.
  }
}

function remembered(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return typeof session.userId === "string" && Array.isArray(session.roles) ? session : null;
  } catch {
    return null;
  }
}

/** Roles whose working set is on the device, so they can work while the server is unreachable. */
function worksOffline(session: Session): boolean {
  return session.roles.some((role) => role !== "admin" && role !== "auditor" && prefetchesWorkingSet(role as Role));
}

export async function currentSession(): Promise<SessionState> {
  try {
    const session = await request<Session>("/api/session");
    remember(session);
    return { kind: "signed-in", session };
  } catch (error) {
    const state = classify(error);
    if (state.kind === "signed-out") {
      forget();
      return state;
    }
    // The server could not be asked. That is not "signed out": someone the
    // server confirmed earlier, in a role built to work with no signal, carries
    // on with what is on the device (EXE-01). Everything they record waits here
    // and is authorized by the server when it is sent.
    const last = remembered();
    if (last && worksOffline(last)) return { kind: "signed-in", session: last, unverified: true };
    return state;
  }
}

// No device id yet: the backend only accepts ids already in iam.devices, and
// nothing registers devices until the device-registration flow exists.
export async function signIn(email: string, password: string): Promise<Session> {
  const session = await request<Session>("/api/session", { method: "POST", body: { email, password } });
  remember(session);
  return session;
}

export async function signOut(): Promise<void> {
  await request<null>("/api/session/end", { method: "POST" });
  forget();
  forgetCrew();
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
