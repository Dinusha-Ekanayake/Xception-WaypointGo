import { request } from "@shared/api/client";
import type { Role } from "@shared/offline";

export type Session = {
  userId: string;
  displayName: string;
  roles: Role[];
  /** Depot codes, outlet ids or a vehicle id, depending on the role. */
  scope: string[];
};

/**
 * Who is signed in, according to the server. The client never decides this: it
 * asks, and renders what it is told.
 */
export async function currentSession(): Promise<Session | null> {
  try {
    return await request<Session>("/api/session");
  } catch {
    return null;
  }
}
