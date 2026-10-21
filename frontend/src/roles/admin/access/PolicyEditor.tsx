"use client";

import { useEffect, useMemo, useState } from "react";
import type { ActionView, PolicyAttachmentView, PolicyDocumentView, PolicyPreviewView, PolicyStatement, PolicyVersionView } from "@shared/domain/identity";
import { clock } from "@shared/wording";
import { listOf, policyAttachments, policyDocument, policyVersions, previewChange, restoreVersion, rowsOf, saveVersion } from "../data/policies";
import { Badge, card, field, primary, secondary } from "./components";

// R-IAM-42: the full rule editor for one policy. Statement rows (Effect,
// Action, Resource, a Condition kept as written), or the raw JSON. Before a
// save, "What changes" says who gains and loses which action, worked out by the
// decision point's own evaluator; the save is a new version made the default,
// with a reason. An older version can be restored.

const MIN_REASON = 3;

export default function PolicyEditor({ name, actions, onBack }: { name: string; actions: ActionView[]; onBack: () => void }) {
  const [versions, setVersions] = useState<PolicyVersionView[] | null>(null);
  const [attached, setAttached] = useState<PolicyAttachmentView[]>([]);
  const [draft, setDraft] = useState<PolicyDocumentView | null>(null);
  const [raw, setRaw] = useState<string | null>(null);
  const [preview, setPreview] = useState<PolicyPreviewView | null>(null);
  const [previewed, setPreviewed] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ tone: "green" | "red"; text: string } | null>(null);
  const [loadedAt, setLoadedAt] = useState(0);

  useEffect(() => {
    const abort = new AbortController();
    void (async () => {
      try {
        const v = await policyVersions(name, abort.signal);
        setVersions(v);
        setAttached(await policyAttachments(name, abort.signal));
        const current = v.find((x) => x.isDefault) ?? v[0];
        if (current) setDraft(rowsOf(await policyDocument(name, current.versionNumber, abort.signal)));
      } catch (e) {
        if (!abort.signal.aborted) setNote({ tone: "red", text: e instanceof Error ? e.message : "The policy could not be read." });
      }
    })();
    return () => abort.abort();
  }, [name, loadedAt]);

  const text = useMemo(() => (draft ? JSON.stringify(draft) : ""), [draft]);
  // A save needs a fresh, clean "What changes" of exactly this draft.
  const reviewed = preview !== null && previewed === text && preview.problems.length === 0;
  const catalogue = useMemo(() => actions.map((a) => a.action).sort(), [actions]);

  const edit = (i: number, change: Partial<PolicyStatement>) =>
    setDraft((d) => (d ? { ...d, Statement: d.Statement.map((s, j) => (j === i ? { ...s, ...change } : s)) } : d));

  const check = async () => {
    if (!draft) return;
    setBusy(true);
    setNote(null);
    try {
      setPreview(await previewChange(name, draft));
      setPreviewed(text);
    } catch (e) {
      setNote({ tone: "red", text: e instanceof Error ? e.message : "The change could not be checked." });
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!draft || !reviewed || reason.trim().length < MIN_REASON) return;
    setBusy(true);
    try {
      const version = await saveVersion(name, draft, reason.trim());
      setNote({ tone: "green", text: `Version ${version} is now in force. It applies from the next request.` });
      setReason("");
      setPreview(null);
      setLoadedAt((n) => n + 1);
    } catch (e) {
      setNote({ tone: "red", text: e instanceof Error ? e.message : "The policy was not saved." });
    } finally {
      setBusy(false);
    }
  };

  const restore = async (version: number) => {
    if (reason.trim().length < MIN_REASON) {
      setNote({ tone: "red", text: "Write a reason of at least 3 characters first." });
      return;
    }
    setBusy(true);
    try {
      await restoreVersion(name, version, reason.trim());
      setNote({ tone: "green", text: `Version ${version} is in force again.` });
      setReason("");
      setLoadedAt((n) => n + 1);
    } catch (e) {
      setNote({ tone: "red", text: e instanceof Error ? e.message : "The version was not restored." });
    } finally {
      setBusy(false);
    }
  };

  const problemsOf = (statement: number) => preview?.problems.filter((p) => p.statement === statement) ?? [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className={secondary} onClick={onBack}>Back to policies</button>
        <h2 className="text-2xl font-semibold">{name}</h2>
        {versions?.find((v) => v.isDefault) && <Badge tone="green">{`In force: version ${versions.find((v) => v.isDefault)!.versionNumber}`}</Badge>}
      </div>
      <p className="text-sm text-go-secondary">
        Attached to {attached.length === 0 ? "nobody yet" : attached.map((a) => (a.principalType === "role" ? `everyone with role ${a.label} (${a.people})` : a.label)).join(", ")}.
      </p>
      {note && <p role="status" className={note.tone === "green" ? "text-sm text-go-teal" : "text-sm text-go-danger"}>{note.text}</p>}

      <section aria-label="Rules" className={`${card} space-y-3 p-5`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-lg font-semibold">Rules</h3>
          <button type="button" className={secondary} onClick={() => setRaw(raw === null ? JSON.stringify(draft, null, 2) : null)}>
            {raw === null ? "Raw JSON" : "Back to the rows"}
          </button>
        </div>
        {problemsOf(0).map((p) => <p key={p.message} className="text-sm text-go-danger">{p.message}</p>)}
        {raw !== null ? (
          <label className="block text-sm font-medium">
            Policy document
            <textarea
              aria-label="Policy document"
              className={`${field} mt-1 min-h-64 font-mono text-[13px]`}
              value={raw}
              onChange={(e) => {
                setRaw(e.target.value);
                try {
                  setDraft(rowsOf(JSON.parse(e.target.value) as PolicyDocumentView));
                } catch {
                  // Not JSON yet: the rows keep the last document that was.
                }
              }}
            />
          </label>
        ) : (
          draft?.Statement.map((s, i) => (
            <fieldset key={i} aria-label={`Rule ${i + 1}`} className="space-y-2 rounded-2xl border border-go-rule p-4">
              <div className="flex flex-wrap items-center gap-2">
                <input aria-label="Name of the rule" className={`${field} max-w-56`} value={s.Sid ?? ""} onChange={(e) => edit(i, { Sid: e.target.value })} />
                <select aria-label="Effect" className={`${field} max-w-32`} value={s.Effect} onChange={(e) => edit(i, { Effect: e.target.value as "Allow" | "Deny" })}>
                  <option>Allow</option>
                  <option>Deny</option>
                </select>
                {s.Condition !== undefined && <Badge tone="amber">Has a condition (kept as written)</Badge>}
                <button type="button" className={secondary} onClick={() => setDraft((d) => (d ? { ...d, Statement: d.Statement.filter((_, j) => j !== i) } : d))}>Remove</button>
              </div>
              <label className="block text-sm">
                Actions
                <input aria-label="Actions" list="policy-actions" className={`${field} mt-1`} value={s.Action.join(", ")} onChange={(e) => edit(i, { Action: listOf(e.target.value) })} />
              </label>
              <label className="block text-sm">
                Resources
                <input aria-label="Resources" className={`${field} mt-1 font-mono`} value={s.Resource.join(", ")} onChange={(e) => edit(i, { Resource: listOf(e.target.value) })} />
              </label>
              {problemsOf(i + 1).map((p) => <p key={p.message} className="text-sm text-go-danger">{p.message}</p>)}
            </fieldset>
          ))
        )}
        <datalist id="policy-actions">{catalogue.map((a) => <option key={a} value={a} />)}</datalist>
        {raw === null && (
          <button type="button" className={secondary} onClick={() => setDraft((d) => (d ? { ...d, Statement: [...d.Statement, { Sid: `Statement${d.Statement.length + 1}`, Effect: "Allow", Action: [], Resource: ["*"] }] } : d))}>
            Add a rule
          </button>
        )}
      </section>

      <section aria-label="What changes" className={`${card} space-y-3 p-5`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-lg font-semibold">What changes</h3>
          <button type="button" className={secondary} disabled={busy || !draft} onClick={() => void check()}>Check what changes</button>
        </div>
        {preview === null || previewed !== text ? (
          <p className="text-sm text-go-secondary">Check the draft to see who gains or loses which action before it is saved.</p>
        ) : preview.problems.length > 0 ? (
          <p className="text-sm text-go-danger">{`Fix ${preview.problems.length} problem${preview.problems.length === 1 ? "" : "s"} in the rules first.`}</p>
        ) : preview.changes.length === 0 ? (
          <p className="text-sm text-go-secondary">Nobody gains or loses anything.</p>
        ) : (
          <ul className="space-y-2">
            {preview.changes.map((c) => (
              <li key={`${c.holds}|${c.people.join(",")}`} className="rounded-2xl bg-go-subtle p-3 text-sm">
                <p className="font-medium">{`${c.count} ${c.count === 1 ? "person" : "people"} · role ${c.holds}: ${c.people.join(", ")}${c.count > c.people.length ? ", and others" : ""}`}</p>
                {c.gained.length > 0 && <p className="text-go-teal">Gains: {c.gained.join(", ")}</p>}
                {c.lost.length > 0 && <p className="text-go-danger">Loses: {c.lost.join(", ")}</p>}
                {c.reachChanged.length > 0 && <p className="text-go-secondary">Reaches other records with: {c.reachChanged.join(", ")}</p>}
              </li>
            ))}
          </ul>
        )}
        <label className="block text-sm font-medium">
          Reason, kept in the audit log
          <input aria-label="Reason" className={`${field} mt-1`} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <button type="button" className={primary} disabled={busy || !reviewed || reason.trim().length < MIN_REASON} onClick={() => void save()}>
          Save as the new version
        </button>
      </section>

      <section aria-label="Version history" className={`${card} space-y-2 p-5`}>
        <h3 className="text-lg font-semibold">Version history</h3>
        {versions?.map((v) => (
          <div key={v.versionNumber} className="flex flex-wrap items-center justify-between gap-2 border-b border-go-rule py-2 text-sm last:border-0">
            <span>{`Version ${v.versionNumber} · ${clock(v.createdAt)}${v.createdBy ? ` · ${v.createdBy}` : ""}`}</span>
            {v.isDefault ? <Badge tone="green">In force</Badge> : (
              <button type="button" className={secondary} disabled={busy} onClick={() => void restore(v.versionNumber)}>{`Restore version ${v.versionNumber}`}</button>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}
