// Offline PIN switching on a shared loader device (decision 2026-10-01, R-IAM-27).
//
// Online, the device keeps the crew list with each member's PBKDF2 verifier
// (identity PinVerifier.java), never a PIN. Offline, a PIN is checked against
// that verifier here, and the switch is logged so it reaches the server's
// operator history before the work queued under it. A four-digit PIN can be
// recovered from its verifier by trying all ten thousand; the list lives only
// on a supervisor-signed-in device, for twelve hours, and sign-out wipes it.

export type CrewMember = {
  userId: string;
  displayName: string;
  employeeCode: string;
  /** Null until the member has entered their PIN online once. */
  offlineVerifier: string | null;
};

export type CrewList = { members: CrewMember[]; expiresAt: string };

/** A switch made offline; `userId` null is a lock. */
export type OfflineSwitch = { userId: string | null; at: string };

/** PinPolicy.java: five wrong PINs pause entry for five minutes. */
export const MAX_FAILURES = 5;
export const PAUSE_MS = 5 * 60_000;

const CREW_KEY = (account: string) => `waypoint.crew.${account}`;
const SWITCH_KEY = (account: string) => `waypoint.offline-switches.${account}`;
const FAIL_KEY = (account: string, userId: string) => `waypoint.pin-fail.${account}.${userId}`;

function base64(bytes: ArrayBuffer): string {
  let text = "";
  for (const b of new Uint8Array(bytes)) text += String.fromCharCode(b);
  return btoa(text);
}

function unbase64(text: string): Uint8Array<ArrayBuffer> {
  const raw = atob(text);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/** Whether `pin` matches a verifier in PinVerifier's format. Malformed verifiers never match. */
export async function pinMatches(pin: string, verifier: string): Promise<boolean> {
  const parts = verifier.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256" || !/^\d{4}$/.test(pin)) return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 1) return false;
  try {
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits(
      { name: "PBKDF2", hash: "SHA-256", salt: unbase64(parts[2]!), iterations },
      key,
      256,
    );
    return base64(bits) === parts[3];
  } catch {
    return false;
  }
}

function readJson<T>(key: string): T | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJson(key: string, value: unknown): boolean {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function keepCrew(account: string, list: CrewList): void {
  writeJson(CREW_KEY(account), list);
}

/** The crew this device may switch to offline, or null when none is kept or it has expired. */
export function keptCrew(account: string, now: Date = new Date()): CrewList | null {
  const list = readJson<CrewList>(CREW_KEY(account));
  if (!list || !Array.isArray(list.members) || !(Date.parse(list.expiresAt) > now.getTime())) return null;
  return list;
}

/** How long entry is paused for this member on this device, in ms; 0 when they may try. */
export function pausedFor(account: string, userId: string, now: Date = new Date()): number {
  const failures = readJson<number[]>(FAIL_KEY(account, userId)) ?? [];
  if (failures.length < MAX_FAILURES) return 0;
  return Math.max(0, failures[failures.length - 1]! + PAUSE_MS - now.getTime());
}

export type OfflineCheck = { ok: true } | { ok: false; triesLeft: number; pausedMs: number };

/** Checks a PIN on the device, counting failures toward the same pause the server applies. */
export async function checkOffline(account: string, member: CrewMember, pin: string, now: Date = new Date()): Promise<OfflineCheck> {
  const paused = pausedFor(account, member.userId, now);
  if (paused > 0) return { ok: false, triesLeft: 0, pausedMs: paused };
  const ok = member.offlineVerifier !== null && (await pinMatches(pin, member.offlineVerifier));
  const key = FAIL_KEY(account, member.userId);
  if (ok) {
    try {
      window.localStorage.removeItem(key);
    } catch {
      // Nothing to clear.
    }
    return { ok: true };
  }
  const failures = [...(readJson<number[]>(key) ?? []).filter((t) => now.getTime() - t < 30 * 60_000), now.getTime()];
  writeJson(key, failures);
  return {
    ok: false,
    triesLeft: Math.max(0, MAX_FAILURES - failures.length),
    pausedMs: failures.length >= MAX_FAILURES ? PAUSE_MS : 0,
  };
}

/** Logs a switch or lock made offline. False when storage refused it, so the caller can say so. */
export function logOfflineSwitch(account: string, change: OfflineSwitch): boolean {
  return writeJson(SWITCH_KEY(account), [...offlineSwitches(account), change]);
}

export function offlineSwitches(account: string): OfflineSwitch[] {
  return readJson<OfflineSwitch[]>(SWITCH_KEY(account)) ?? [];
}

export function clearOfflineSwitches(account: string): void {
  try {
    window.localStorage.removeItem(SWITCH_KEY(account));
  } catch {
    // Already gone.
  }
}

/** Sign-out: the verifiers and failure counts leave the device. */
export function forgetCrew(): void {
  try {
    for (let i = window.localStorage.length - 1; i >= 0; i--) {
      const key = window.localStorage.key(i);
      if (key && (key.startsWith("waypoint.crew.") || key.startsWith("waypoint.pin-fail."))) window.localStorage.removeItem(key);
    }
  } catch {
    // Storage blocked: nothing was kept.
  }
}
