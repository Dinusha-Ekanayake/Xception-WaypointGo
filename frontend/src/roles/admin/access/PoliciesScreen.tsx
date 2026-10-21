"use client";

import { useEffect, useState } from "react";
import type { ActionView, PolicySummaryView } from "@shared/domain/identity";
import { listPolicies } from "../data/policies";
import { Badge, Empty, card, secondary } from "./components";
import PolicyEditor from "./PolicyEditor";

// R-IAM-42: every policy in force, live from GET /api/policies; one opens in
// the full rule editor.

export default function PoliciesScreen({ actions }: { actions: ActionView[] }) {
  const [policies, setPolicies] = useState<PolicySummaryView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    if (open) return;
    const abort = new AbortController();
    listPolicies(abort.signal).then(setPolicies, (e: unknown) => {
      if (!abort.signal.aborted) setError(e instanceof Error ? e.message : "Policies could not be read.");
    });
    return () => abort.abort();
  }, [open]);

  if (open) return <PolicyEditor name={open} actions={actions} onBack={() => setOpen(null)} />;
  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-semibold">Policies</h2>
        <p className="mt-1 text-sm text-go-secondary">What each role and person may do. A change is a new version, checked for who it affects before it is saved.</p>
      </div>
      {error && <p role="status" className="text-sm text-go-danger">{error}</p>}
      {policies === null && !error && <p className="text-sm text-go-secondary">Reading the policies…</p>}
      {policies?.length === 0 && <Empty>No policies yet.</Empty>}
      <ul className="space-y-2">
        {policies?.map((p) => (
          <li key={p.policyId} className={`${card} flex flex-wrap items-center justify-between gap-3 px-5 py-3`}>
            <span className="font-medium">{p.name}</span>
            <span className="flex items-center gap-2">
              <Badge tone="green">{`Version ${p.defaultVersion ?? p.versionNumber ?? "?"}`}</Badge>
              <button type="button" className={secondary} onClick={() => setOpen(p.name)}>{`Edit ${p.name}`}</button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
