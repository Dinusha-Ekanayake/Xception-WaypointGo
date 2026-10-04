import type { ShellRole } from "./session.ts";

/** What each role does, in one line, for the pages that hand a person to a role. */
export const ROLE_DOES: Partial<Record<ShellRole, string>> = {
  store_manager: "Place the store's order and confirm what arrived.",
  dispatcher: "Turn confirmed orders into a plan for the day's trucks.",
  loader: "Load each truck against the plan and record the checks.",
  driver: "Deliver stop by stop and capture proof, online or offline.",
  admin: "Manage accounts, roles and access policies.",
};

/** The four field roles, in the order they hand a delivery on. */
export const FIELD_ROLES: ShellRole[] = ["store_manager", "dispatcher", "loader", "driver"];
