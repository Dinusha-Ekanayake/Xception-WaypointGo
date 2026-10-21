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

