import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { StoreContactView } from "@shared/domain/types";

// R-IAM-41: a trip's stores' managers in one read, each with the phone on
// their profile, shown as text (no Call button, owner decision 2026-10-21).

export type StoreContacts = Record<string, StoreContactView[]>;

export function useStoreContacts(outletIds: string[]): { data: StoreContacts | null; error: boolean } {
  const ids = [...new Set(outletIds)].sort();
  const key = ids.join(",");
  const read = useResource<StoreContacts>(
    (signal) => request<StoreContacts>(`/api/outlets/contacts?ids=${encodeURIComponent(key)}`, { signal }),
    `store-contacts|${key}`,
  );
  return { data: ids.length ? read.data ?? null : {}, error: Boolean(read.error) };
}

/** "Nimali Silva · 077 123 4567", several managers joined; a missing phone said so. */
export function contactLine(contacts: StoreContactView[] | undefined): string {
  if (!contacts) return "";
  if (contacts.length === 0) return "No store manager";
  return contacts.map((c) => `${c.name} · ${c.phone ?? "no phone on their profile"}`).join("; ");
}
