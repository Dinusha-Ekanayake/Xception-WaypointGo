import { request, requestAll } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import {
  PolicyCommandKind,
  type PolicyAttachmentView,
  type PolicyDocumentView,
  type PolicyPreviewView,
  type PolicySummaryView,
  type PolicyVersionView,
} from "@shared/domain/identity";

// R-IAM-42: the permission editor's reads and commands. A change is always a
// new version made the default, two commands each guarded by the policy's
// rowVersion; a restore is the default moved back. Nothing is edited in place.

const at = (name: string) => `/api/policies/${encodeURIComponent(name)}`;

export function listPolicies(signal?: AbortSignal): Promise<PolicySummaryView[]> {
  return requestAll<PolicySummaryView>("/api/policies", { signal });
}

export function policyVersions(name: string, signal?: AbortSignal): Promise<PolicyVersionView[]> {
  return request<PolicyVersionView[]>(`${at(name)}/versions`, { signal });
}

export function policyDocument(name: string, version: number, signal?: AbortSignal): Promise<PolicyDocumentView> {
  return request<PolicyDocumentView>(`${at(name)}/versions/${version}`, { signal });
}

export function policyAttachments(name: string, signal?: AbortSignal): Promise<PolicyAttachmentView[]> {
  return request<PolicyAttachmentView[]>(`${at(name)}/attachments`, { signal });
}

export function previewChange(name: string, document: PolicyDocumentView): Promise<PolicyPreviewView> {
  return request<PolicyPreviewView>(`${at(name)}/preview-change`, { method: "POST", body: { document } });
}

async function rowVersionOf(name: string): Promise<number> {
  const found = (await listPolicies()).find((p) => p.name === name);
  if (!found) throw new Error(`No policy ${name}`);
  return found.rowVersion;
}

/** Saves the draft as a new version and puts it in force; returns its number. */
export async function saveVersion(name: string, document: PolicyDocumentView, reason: string): Promise<number> {
  const created = await send<{ versionNumber: number }>(
    newCommand(PolicyCommandKind.CreateVersion, { name, document, reason }, await rowVersionOf(name)),
  );
  const versionNumber = created.result.versionNumber;
  await send(newCommand(PolicyCommandKind.SetDefaultVersion, { name, versionNumber, reason }, await rowVersionOf(name)));
  return versionNumber;
}

/** Puts an older version back in force. */
export async function restoreVersion(name: string, versionNumber: number, reason: string): Promise<void> {
  await send(newCommand(PolicyCommandKind.SetDefaultVersion, { name, versionNumber, reason }, await rowVersionOf(name)));
}

/** A document as the editor's rows: every statement with an Sid, its actions and resources as lists. */
export function rowsOf(document: PolicyDocumentView): PolicyDocumentView {
  return {
    Version: document.Version ?? "2026-10-22",
    Statement: (document.Statement ?? []).map((s, i) => ({
      ...s,
      Sid: s.Sid || `Statement${i + 1}`,
      Effect: s.Effect === "Deny" ? "Deny" : "Allow",
      Action: Array.isArray(s.Action) ? s.Action : s.Action ? [String(s.Action)] : [],
      Resource: Array.isArray(s.Resource) ? s.Resource : s.Resource ? [String(s.Resource)] : ["*"],
    })),
  };
}

/** "plan:Read, plan:Publish" as a list; blanks dropped. */
export function listOf(text: string): string[] {
  return text.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean);
}
