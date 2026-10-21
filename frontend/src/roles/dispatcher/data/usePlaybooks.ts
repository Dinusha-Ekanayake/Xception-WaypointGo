import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { PlaybookView } from "@shared/domain/types";

/** Every situation's playbook (issue #269), read once a session: it changes with a release, not during a shift. */
export function usePlaybooks(): Resource<PlaybookView[]> {
  return useResource((signal: AbortSignal) => request<PlaybookView[]>("/api/ml/playbooks", { signal }), "playbooks", 0);
}
