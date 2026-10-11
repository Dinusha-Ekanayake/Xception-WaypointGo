import { request } from "@shared/api/client";
import type { ShellRole } from "./session.ts";

// Demo sign-in (for judges): the accounts the sign-in page offers to pick from
// and the loaders' PIN. The server offers them only in a configured demo while
// demo mode is on, and only while the demo password and PIN still open them, so
// a changed password is never listed. Outside a demo the list is empty and the
// page shows nothing. Mirrors identity's DemoSignInController.

// fillsPassword is false for an administrator: the list never carries its
// password, so picking it fills the email and points to the shared credentials.
export type DemoAccount = { email: string; name: string; role: ShellRole; fillsPassword?: boolean };
export type DemoSignIn = { accounts: DemoAccount[]; password: string | null; pin: string | null };

const NONE: DemoSignIn = { accounts: [], password: null, pin: null };

/** What the demo offers; nothing when there is no demo or the read fails. */
export async function demoSignIn(signal?: AbortSignal): Promise<DemoSignIn> {
  try {
    const read = await request<DemoSignIn>("/api/session/demo", { signal });
    return read.accounts.length > 0 ? read : { ...NONE, pin: read.pin ?? null };
  } catch {
    return NONE;
  }
}

/** Where each role is meant to be opened, as the README's account table says. */
export const DEMO_DEVICE: Partial<Record<ShellRole, string>> = {
  dispatcher: "desktop",
  driver: "phone",
  store_manager: "desktop or phone",
  loader: "phone or tablet",
  admin: "desktop",
};
