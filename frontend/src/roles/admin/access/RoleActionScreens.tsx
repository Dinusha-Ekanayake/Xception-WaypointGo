"use client";

import { useMemo, useState } from "react";
import type { ActionView, RoleView } from "@shared/domain/identity";
import { Badge, Modal, PersonaIcon, card, field, secondary } from "./components";
import type { Persona } from "./model";

function isPersona(code: string): code is Persona {
  return ["dispatcher", "loader", "driver", "store_manager", "admin", "super_admin"].includes(code);
}

function roleBadgeTone(roleCode: string): "green" | "blue" | "amber" | "neutral" {
  if (roleCode === "super_admin") return "amber";
  if (roleCode === "admin") return "blue";
  if (["dispatcher", "loader", "driver", "store_manager"].includes(roleCode)) return "green";
  return "neutral";
}

function roleCategory(roleCode: string): string {
  if (roleCode === "super_admin") return "Protected governance";
  if (roleCode === "admin") return "Administration";
  if (roleCode === "auditor") return "Read-only inspection";
  return "Operational role";
}

export function RolesScreen({
  roles,
  liveConnected,
}: {
  roles: RoleView[];
  liveConnected: boolean;
}) {
  const [query, setQuery] = useState("");
  const [selectedRole, setSelectedRole] = useState<RoleView | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return roles;
    return roles.filter((r) => r.roleCode.toLowerCase().includes(q) || r.description.toLowerCase().includes(q));
  }, [roles, query]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-2.5">
            <h2 className="text-2xl font-semibold">System roles</h2>
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live API: GET /api/admin/roles" : "Roles unavailable"}
            </Badge>
          </div>
          <p className="mt-1 text-sm text-go-secondary">
            Identity role definitions from <code>iam.roles</code>.
          </p>
        </div>
      </div>

      <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 text-sm text-go-ink">
        <p className="font-semibold text-go-teal">Role catalogue notice</p>
        <p className="mt-1 text-[#486357]">
          This view provides registered role labels and catalogue descriptions from <code>GET /api/admin/roles</code>. Neither this list nor role detail includes account membership or an effective access decision. User scope and dynamic policies are evaluated separately by the policy engine.
        </p>
      </div>

      <div className={`${card} p-4`}>
        <label className="block text-sm font-medium">
          Search roles
          <input
            className={`${field} mt-1`}
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search role code or description..."
          />
        </label>
      </div>

      <p className="text-sm text-go-secondary">{filtered.length} of {roles.length} roles</p>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((role) => {
          const tone = roleBadgeTone(role.roleCode);
          return (
            <article
              key={role.roleCode}
              className={`${card} flex flex-col justify-between p-5 transition hover:shadow-md`}
            >
              <div>
                <div className="flex items-start justify-between gap-3">
                  <span
                    aria-hidden="true"
                    className={`grid size-11 place-items-center rounded-xl ${
                      role.roleCode === "super_admin"
                        ? "bg-[#fef3d6] text-[#b45309]"
                        : "bg-go-mint text-go-teal"
                    }`}
                  >
                    {isPersona(role.roleCode) ? (
                      <PersonaIcon persona={role.roleCode} className="size-6" />
                    ) : (
                      <img src="/icons/go/audit-shield.svg" alt="" className="size-6" />
                    )}
                  </span>
                  <Badge tone={tone}>{roleCategory(role.roleCode)}</Badge>
                </div>
                <h3 className="mt-3 font-mono text-base font-semibold text-go-ink">
                  {role.roleCode}
                </h3>
                <p className="mt-1 text-sm text-go-secondary line-clamp-3">
                  {role.description}
                </p>
              </div>
              <div className="mt-5 border-t border-go-subtle pt-3">
                <button
                  type="button"
                  className={`${secondary} w-full min-h-9 text-xs`}
                  onClick={() => setSelectedRole(role)}
                >
                  View definition
                </button>
              </div>
            </article>
          );
        })}
      </div>

      {selectedRole && (
        <Modal title={`Role: ${selectedRole.roleCode}`} onClose={() => setSelectedRole(null)}>
          <dl className="grid gap-3 text-sm">
            <div>
              <dt className="font-semibold text-go-secondary">Role code</dt>
              <dd className="font-mono text-sm font-bold text-go-ink">{selectedRole.roleCode}</dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Resource URI</dt>
              <dd className="font-mono text-xs text-go-teal">wpt:iam:role:{selectedRole.roleCode}</dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Classification</dt>
              <dd>
                <Badge tone={roleBadgeTone(selectedRole.roleCode)}>
                  {roleCategory(selectedRole.roleCode)}
                </Badge>
              </dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Catalogue description</dt>
              <dd className="mt-1 rounded-xl bg-[#f3faf6] p-3 text-go-ink">
                {selectedRole.description}
              </dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Identity semantics</dt>
              <dd className="text-xs text-go-secondary">
                Assigned to users via <code>iam.user_roles</code>. Attached to policy documents via <code>iam.policy_attachments</code>. Access evaluation requires matching policy effect AND user place scope (depot / outlet).
              </dd>
            </div>
          </dl>
          <button className={`${secondary} mt-5`} onClick={() => setSelectedRole(null)}>
            Close
          </button>
        </Modal>
      )}
    </div>
  );
}

export function ActionsScreen({
  actions,
  liveConnected,
}: {
  actions: ActionView[];
  liveConnected: boolean;
}) {
  const [query, setQuery] = useState("");
  const [selectedModule, setSelectedModule] = useState("all");
  const [availability, setAvailability] = useState<"all" | "available" | "unavailable">("all");
  const [selectedAction, setSelectedAction] = useState<ActionView | null>(null);

  const modules = useMemo(() => {
    return Array.from(new Set(actions.map((a) => a.module))).sort();
  }, [actions]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return actions.filter((item) => {
      const matchQuery =
        !q ||
        item.action.toLowerCase().includes(q) ||
        item.module.toLowerCase().includes(q) ||
        item.description.toLowerCase().includes(q);
      const matchModule = selectedModule === "all" || item.module === selectedModule;
      const matchAvail =
        availability === "all" ||
        (availability === "available" && item.implemented) ||
        (availability === "unavailable" && !item.implemented);
      return matchQuery && matchModule && matchAvail;
    });
  }, [actions, query, selectedModule, availability]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-2.5">
            <h2 className="text-2xl font-semibold">Action catalogue</h2>
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live API: GET /api/admin/actions" : "Actions unavailable"}
            </Badge>
          </div>
          <p className="mt-1 text-sm text-go-secondary">
            Registered IAM actions from <code>iam.action_catalogue</code>.
          </p>
        </div>
      </div>

      <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 text-sm text-go-ink">
        <p className="font-semibold text-go-teal">Action catalogue notice</p>
        <p className="mt-1 text-[#486357]">
          Every permission evaluated in policy documents must be registered in <code>iam.action_catalogue</code>. Unregistered actions fail closed. Note that this catalogue provides action definitions only and does not compute account membership or runtime authorization decisions.
        </p>
      </div>

      <div className={`${card} grid gap-3 p-4 md:grid-cols-[1fr_13rem_13rem]`}>
        <label className="text-sm font-medium">
          Search action
          <input
            className={`${field} mt-1`}
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="e.g. order:Place, plan:Publish"
          />
        </label>
        <label className="text-sm font-medium">
          Module
          <select
            className={`${field} mt-1`}
            value={selectedModule}
            onChange={(e) => setSelectedModule(e.target.value)}
          >
            <option value="all">All modules ({actions.length})</option>
            {modules.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium">
          Readiness
          <select
            className={`${field} mt-1`}
            value={availability}
            onChange={(e) => setAvailability(e.target.value as "all" | "available" | "unavailable")}
          >
            <option value="all">All readiness</option>
            <option value="available">Implemented</option>
            <option value="unavailable">Unavailable / Planned</option>
          </select>
        </label>
      </div>

      <p className="text-sm text-go-secondary">{filtered.length} of {actions.length} action verbs</p>

      <div className={`${card} divide-y divide-go-rule`}>
        {filtered.map((item) => (
          <div
            key={item.action}
            className="flex flex-wrap items-center gap-3 px-4 py-3.5 last:border-0 sm:px-5"
          >
            <span
              aria-hidden="true"
              className={`grid size-9 shrink-0 place-items-center rounded-xl text-sm font-bold ${
                item.implemented ? "bg-go-mint text-go-teal" : "bg-[#fef3d6] text-[#b45309]"
              }`}
            >
              {item.implemented ? "✓" : "◷"}
            </span>
            <div className="min-w-44 flex-1">
              <p className="font-mono text-sm font-bold text-go-ink">{item.action}</p>
              <p className="text-xs text-go-secondary">{item.description}</p>
            </div>
            <Badge tone="blue">{item.module}</Badge>
            <Badge tone={item.implemented ? "green" : "amber"}>
              {item.implemented ? "Implemented" : "Unavailable"}
            </Badge>
            <button
              type="button"
              className="min-h-11 px-2 text-sm font-semibold text-go-teal hover:underline"
              onClick={() => setSelectedAction(item)}
            >
              Details
            </button>
          </div>
        ))}
      </div>

      {selectedAction && (
        <Modal title={`Action: ${selectedAction.action}`} onClose={() => setSelectedAction(null)}>
          <dl className="grid gap-3 text-sm">
            <div>
              <dt className="font-semibold text-go-secondary">Action verb</dt>
              <dd className="font-mono text-sm font-bold text-go-ink">{selectedAction.action}</dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Owning module</dt>
              <dd>
                <Badge tone="blue">{selectedAction.module}</Badge>
              </dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Implementation status</dt>
              <dd>
                <Badge tone={selectedAction.implemented ? "green" : "amber"}>
                  {selectedAction.implemented ? "Implemented & Ready" : "Unavailable (Planned)"}
                </Badge>
              </dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Description</dt>
              <dd className="mt-1 rounded-xl bg-[#f3faf6] p-3 text-go-ink">
                {selectedAction.description}
              </dd>
            </div>
            <div>
              <dt className="font-semibold text-go-secondary">Authorization note</dt>
              <dd className="text-xs text-go-secondary">
                Policies matching <code>Action: &quot;{selectedAction.action}&quot;</code> are evaluated against requests on <code>wpt:{selectedAction.action.split(":")[0]}:*</code>. Unregistered actions fail closed.
              </dd>
            </div>
          </dl>
          <button className={`${secondary} mt-5`} onClick={() => setSelectedAction(null)}>
            Close
          </button>
        </Modal>
      )}
    </div>
  );
}
