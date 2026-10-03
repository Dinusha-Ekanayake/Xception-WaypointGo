"use client";

import { useMemo, useState } from "react";
import { Badge, CapabilityRow, Empty, Modal, PersonaIcon, card, field, primary, secondary } from "./components";
import { CAPABILITIES, PERSONAS, effective, labelFor, personaAllows, personaChoice, personaCounts, todayInColombo, type Capability, type DemoState, type Member, type Persona } from "./model";

import type { RoleView } from "@shared/domain/identity";

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

type Actions = { onDetails: (capability: Capability, member?: Member, persona?: Persona) => void; onEdit: (capability?: Capability, member?: Member, persona?: Persona) => void };

export function PeopleScreen({ state, selected, setSelected, filterPersona, setFilterPersona, actions, viewer, onAddMember, liveConnected }: { state: DemoState; selected: string | null; setSelected: (id: string | null) => void; filterPersona: Persona | "all"; setFilterPersona: (value: Persona | "all") => void; actions: Actions; viewer: "admin" | "super_admin"; onAddMember: () => void; liveConnected?: boolean }) {
  const [query, setQuery] = useState("");
  const [filterDepot, setFilterDepot] = useState<string>("all");
  const [filterOutlet, setFilterOutlet] = useState<string>("all");
  const [exceptionsOnly, setExceptionsOnly] = useState(false);
  const [memberTab, setMemberTab] = useState<"access" | "profile" | "scope">("access");
  const [showUnavailable, setShowUnavailable] = useState(false);

  const availableDepots = useMemo(() => {
    const set = new Set<string>();
    for (const m of state.members) {
      if (m.depots && m.depots.length > 0) {
        m.depots.forEach((d) => set.add(d));
      }
      for (const p of m.places) {
        if (p && !p.startsWith("OUT-") && p !== "GLOBAL") {
          set.add(p);
        }
      }
    }
    return Array.from(set).sort();
  }, [state.members]);

  const availableOutlets = useMemo(() => {
    const set = new Set<string>();
    for (const m of state.members) {
      if (m.outlets && m.outlets.length > 0) {
        m.outlets.forEach((o) => set.add(o));
      }
      for (const p of m.places) {
        if (p && (p.startsWith("OUT") || p.startsWith("OUT-"))) {
          set.add(p);
        }
      }
    }
    return Array.from(set).sort();
  }, [state.members]);

  const member = state.members.find((item) => item.id === selected && (viewer === "super_admin" || !item.personas.includes("admin") && !item.personas.includes("super_admin")));
  const visible = state.members.filter((item) => {
    if (item.personas.includes("super_admin") && viewer !== "super_admin") return false;
    if (item.personas.includes("admin") && viewer !== "super_admin") return false;
    if (filterPersona !== "all" && !item.personas.includes(filterPersona)) return false;
    if (exceptionsOnly && !state.exceptions.some((ex) => ex.memberId === item.id)) return false;

    if ((filterPersona === "loader" || filterPersona === "driver") && filterDepot !== "all") {
      const inDepot =
        (item.depots && item.depots.includes(filterDepot)) ||
        item.places.includes(filterDepot) ||
        item.places.includes("GLOBAL");
      if (!inDepot) return false;
    }

    if (filterPersona === "store_manager" && filterOutlet !== "all") {
      const inOutlet =
        (item.outlets && item.outlets.includes(filterOutlet)) ||
        item.places.includes(filterOutlet) ||
        item.places.includes("GLOBAL");
      if (!inOutlet) return false;
    }

    return `${item.name} ${item.email}`.toLowerCase().includes(query.toLowerCase());
  });

  if (member) {
    const protectedMember = member.personas.some((persona) => persona === "admin" || persona === "super_admin");
    const relevant = CAPABILITIES.filter((item) => member.personas.some((persona) => item.relevant.includes(persona)) || state.exceptions.some((ex) => ex.memberId === member.id && ex.action === item.action));
    return <div className="space-y-5">
      <button className="min-h-11 text-sm font-semibold text-go-teal" onClick={() => setSelected(null)}>← Back to people</button>
      <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="text-sm text-go-secondary">People / {member.name}</p><div className="mt-1 flex items-center gap-3"><h2 className="text-3xl font-semibold">{member.name}</h2>{member.source === "live" && <Badge tone="blue">Live account</Badge>}</div><p className="text-go-secondary">{member.personas.map((value) => value.replaceAll("_", " ")).join(" + ")} · {member.email}</p></div><Badge tone={member.active ? "green" : "neutral"}>{member.active ? "Active member" : "Inactive"}</Badge></div>
      <div className="flex flex-wrap gap-2 border-b border-go-rule">{(["access", "profile", "scope"] as const).map((tab) => <button key={tab} className={`min-h-11 border-b-2 px-4 text-sm font-semibold capitalize ${memberTab === tab ? "border-go-teal text-go-teal" : "border-transparent text-go-secondary"}`} onClick={() => setMemberTab(tab)}>{tab}</button>)}</div>
      {memberTab === "profile" && <div className={`${card} p-6`}><h3 className="text-lg font-semibold">Profile</h3><dl className="mt-4 grid gap-4 sm:grid-cols-2"><div><dt className="text-sm text-go-secondary">Staff ID</dt><dd className="font-mono text-sm">{member.id}</dd></div><div><dt className="text-sm text-go-secondary">Email</dt><dd>{member.email}</dd></div><div><dt className="text-sm text-go-secondary">Persona</dt><dd>{member.personas.map((value) => value.replaceAll("_", " ")).join(" + ")}</dd></div><div><dt className="text-sm text-go-secondary">Status</dt><dd>{member.active ? "Active" : "Inactive"}</dd></div>{member.rowVersion !== undefined && <div><dt className="text-sm text-go-secondary">Row version</dt><dd className="font-mono text-sm">v{member.rowVersion}</dd></div>}{member.vehicleType && <div><dt className="text-sm text-go-secondary">Vehicle type</dt><dd className="capitalize">{member.vehicleType}</dd></div>}</dl></div>}
      {memberTab === "scope" && <div className={`${card} p-6`}><h3 className="text-lg font-semibold">Assigned places</h3><p className="mt-1 text-sm text-go-secondary">Capabilities work only within these existing assignments. </p><div className="mt-4 flex flex-wrap gap-2">{member.places.length ? member.places.map((place) => <Badge key={place} tone="blue">{place === "GLOBAL" ? "All depots" : place}</Badge>) : <Badge tone="amber">No place assigned</Badge>}{member.vehicleType && <Badge tone="blue">{member.vehicleType === "van" ? "Van" : "Truck"}</Badge>}</div></div>}
      {memberTab === "access" && <section className={`${card} p-6`}><h3 className="text-lg font-semibold">Effective access unavailable</h3><p className="mt-2 text-sm text-go-secondary">The server does not provide an effective permission explanation for this account. Assigned places are shown in the Scope tab. Review policy attachments through the IAM API before changing access.</p></section>}
    </div>;
  }
  return <div className="space-y-5"><div className="flex flex-wrap items-end justify-between gap-3"><div><div className="flex items-center gap-2.5"><h2 className="text-2xl font-semibold">People</h2>{liveConnected !== undefined && <Badge tone={liveConnected ? "green" : "neutral"}>{liveConnected ? "Live directory" : "Connecting..."}</Badge>}</div><p className="text-sm text-go-secondary">Find a member, inspect their access and review exceptions.</p></div><button className={primary} onClick={onAddMember}>+ Add member</button></div>
    <div className={`${card} flex flex-wrap items-end gap-3 p-4`}>
      <label className="min-w-[13rem] flex-1 text-sm font-medium">
        Search people
        <input
          className={`${field} mt-1`}
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Name or email"
        />
      </label>
      <label className="w-full sm:w-48 text-sm font-medium">
        Persona
        <select
          className={`${field} mt-1`}
          value={filterPersona}
          onChange={(event) => {
            const nextPersona = event.target.value as Persona | "all";
            setFilterPersona(nextPersona);
            setFilterDepot("all");
            setFilterOutlet("all");
          }}
        >
          <option value="all">All personas</option>
          {PERSONAS.filter((persona) => persona.id !== "super_admin" || viewer === "super_admin").map((persona) => (
            <option value={persona.id} key={persona.id}>
              {persona.label}
            </option>
          ))}
        </select>
      </label>
      {(filterPersona === "loader" || filterPersona === "driver") && (
        <label className="w-full sm:w-48 text-sm font-medium">
          Depot
          <select
            className={`${field} mt-1`}
            value={filterDepot}
            onChange={(event) => setFilterDepot(event.target.value)}
          >
            <option value="all">All depots</option>
            {availableDepots.map((depot) => (
              <option key={depot} value={depot}>
                {depot}
              </option>
            ))}
          </select>
        </label>
      )}
      {filterPersona === "store_manager" && (
        <label className="w-full sm:w-48 text-sm font-medium">
          Outlet
          <select
            className={`${field} mt-1`}
            value={filterOutlet}
            onChange={(event) => setFilterOutlet(event.target.value)}
          >
            <option value="all">All outlets</option>
            {availableOutlets.map((outlet) => (
              <option key={outlet} value={outlet}>
                {outlet}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className="flex min-h-11 items-center gap-2 pb-2 text-sm font-medium text-go-ink cursor-pointer select-none">
        <input
          type="checkbox"
          className="size-4 rounded border-[#cbe3d6] text-go-teal focus:ring-go-teal"
          checked={exceptionsOnly}
          onChange={(event) => setExceptionsOnly(event.target.checked)}
        />
        Has exceptions
      </label>
    </div>
    <p className="text-sm text-go-secondary">{visible.length} of {state.members.filter((item) => !item.personas.includes("super_admin") && (viewer === "super_admin" || !item.personas.includes("admin"))).length} members</p>
    {visible.length === 0 ? <Empty>No members match these filters.</Empty> : <div className={`${card} divide-y divide-go-rule`}>{visible.map((item) => <div key={item.id} className="grid grid-cols-[2.75rem_minmax(0,1fr)] items-center gap-x-3 gap-y-3 p-4 sm:px-5 lg:grid-cols-[2.75rem_minmax(0,2fr)_minmax(10rem,1.4fr)_7rem_9rem] lg:gap-x-4"><div className="grid size-11 shrink-0 place-items-center rounded-full bg-go-mint font-semibold text-go-teal">{item.name.split(" ").map((part) => part[0]).slice(0, 2).join("")}</div><div className="min-w-0"><p className="font-semibold">{item.name}</p><p className="break-words text-sm text-go-secondary">{item.email}</p></div><div className="col-start-2 min-w-0 text-sm lg:col-start-3 lg:row-start-1"><p>{item.personas.map((value) => value.replaceAll("_", " ")).join(" + ")}</p><p className="text-go-secondary">{item.places.map((place) => place === "GLOBAL" ? "All depots" : place).join(", ") || "No place assigned"}{item.vehicleType ? ` · ${item.vehicleType === "van" ? "Van" : "Truck"}` : ""}</p></div>{state.exceptions.some((ex) => ex.memberId === item.id) && <div className="col-start-2 lg:col-start-4 lg:row-start-1"><Badge tone="amber">Exception</Badge></div>}<button className={`${secondary} col-start-2 justify-self-start lg:col-start-5 lg:row-start-1 lg:justify-self-end`} onClick={() => { setSelected(item.id); setMemberTab("access"); }}>View access</button></div>)}</div>}
  </div>;
}

function Summary({ value, label }: { value: number; label: string }) { return <div className={`${card} p-5`}><p className="text-3xl font-semibold text-go-teal">{value}</p><p className="mt-1 text-sm text-go-secondary">{label}</p></div>; }

export function PersonasScreen({
  state,
  selected,
  setSelected,
  actions,
  viewer,
  onMembers,
  onAddMember,
  roles,
  liveConnected,
}: {
  state: DemoState;
  selected: Persona | null;
  setSelected: (value: Persona | null) => void;
  actions: Actions;
  viewer: "admin" | "super_admin";
  onMembers: (persona: Persona) => void;
  onAddMember: (persona: Exclude<Persona, "super_admin">) => void;
  roles?: RoleView[];
  liveConnected?: boolean;
}) {
  const [module, setModule] = useState<"all" | string>("all");
  const [showLater, setShowLater] = useState(false);

  if (!selected) {
    return (
      <div className="space-y-7">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-2xl font-semibold tracking-[-.025em]">Personas & system roles</h2>
              {liveConnected !== undefined && (
                <Badge tone={liveConnected ? "green" : "neutral"}>
                  {liveConnected ? "Accounts loaded" : "Accounts unavailable"}
                </Badge>
              )}
            </div>
            <p className="mt-1 text-sm text-go-secondary">
              Shared defaults and role definitions for each operational and administrative role.
            </p>
          </div>
        </div>

        <section>
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-lg font-semibold text-go-ink">Operational personas</h3>
            <span className="text-xs font-medium text-go-secondary">4 field roles</span>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {PERSONAS.slice(0, 4).map((persona) => (
              <PersonaCard
                key={persona.id}
                persona={persona.id}
                state={state}
                roles={roles}
                onOpen={() => {
                  setSelected(persona.id);
                  setModule("all");
                }}
                onMembers={() => onMembers(persona.id)}
              />
            ))}
          </div>
        </section>

        <section>
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-lg font-semibold text-go-ink">Administration & governance</h3>
            <span className="text-xs font-medium text-go-secondary">2 governance roles</span>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <PersonaCard
              persona="admin"
              state={state}
              roles={roles}
              onOpen={() => {
                setSelected("admin");
                setModule("all");
              }}
              onMembers={() => onMembers("admin")}
            />
            <PersonaCard
              persona="super_admin"
              state={state}
              protectedRole
              roles={roles}
              onOpen={() => {
                setSelected("super_admin");
                setModule("all");
              }}
              onMembers={() => onMembers("super_admin")}
            />
          </div>
        </section>
      </div>
    );
  }

  const info = PERSONAS.find((item) => item.id === selected)!;
  const roleInfo = roles?.find((r) => r.roleCode === selected);
  const relevant = CAPABILITIES.filter((item) => item.relevant.includes(selected) || state.personaSettings[`${selected}:${item.action}`]);
  const modules = [...new Set(relevant.map((item) => item.module))];
  const counts = personaCounts(state, selected);
  const members = state.members.filter((item) => item.personas.includes(selected));
  const permitted = (viewer === "super_admin" || selected !== "admin" && selected !== "super_admin") && selected !== "super_admin";
  const shown = relevant.filter((item) => (module === "all" || item.module === module) && (showLater || item.implemented));

  return (
    <div className="space-y-6">
      <button className="min-h-11 text-sm font-semibold text-go-teal hover:underline" onClick={() => setSelected(null)}>
        ← Back to personas
      </button>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-sm text-go-secondary">Personas / {info.label}</p>
          <div className="mt-1 flex items-center gap-3">
            <span
              aria-hidden="true"
              className={`grid size-11 place-items-center rounded-2xl shadow-xs ${
                selected === "super_admin" ? "bg-[#fef3d6] text-[#b45309]" : "bg-go-mint text-go-teal"
              }`}
            >
              <PersonaIcon persona={selected} className="size-6" />
            </span>
            <h2 className="text-3xl font-semibold">{info.label} capabilities & role</h2>
          </div>
          <p className="mt-1 text-go-secondary">{info.description}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {selected !== "admin" && selected !== "super_admin" && (
            <button className={primary} onClick={() => onAddMember(selected)}>
              + Add member
            </button>
          )}
          <button className={secondary} onClick={() => onMembers(selected)}>
            View {members.length} members
          </button>
        </div>
      </div>

      {/* Role definition & catalogue specification card */}
      <div className={`${card} p-5 space-y-3.5 border border-go-rule bg-gradient-to-br from-white to-[#f7fbf9]`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-base font-semibold text-go-ink">Role definition & catalogue metadata</h3>
            {liveConnected !== undefined && (
              <Badge tone={liveConnected ? "green" : "neutral"}>
                {liveConnected ? "Live data" : "Connecting..."}
              </Badge>
            )}
            <Badge tone={roleBadgeTone(selected)}>{roleCategory(selected)}</Badge>
          </div>
          <span className="font-mono text-xs font-semibold text-go-teal bg-[#e4f7f1] px-2.5 py-1 rounded-lg">
            wpt:iam:role:{selected}
          </span>
        </div>

        <div className="rounded-xl bg-go-subtle p-3.5 text-sm text-go-ink border border-[#d8ebe1]">
          <p className="font-semibold text-xs text-go-teal uppercase tracking-wide">Registered catalogue description</p>
          <p className="mt-1">{roleInfo?.description || info.description}</p>
        </div>

        <div className="grid gap-3 text-xs text-go-secondary sm:grid-cols-2 pt-1">
          <div className="rounded-lg bg-white p-3 border border-[#e5efe9]">
            <strong className="text-go-ink block mb-1">IAM assignment</strong>
            Assigned to users via <code>iam.user_roles</code>. Attached to policy documents via <code>iam.policy_attachments</code>.
          </div>
          <div className="rounded-lg bg-white p-3 border border-[#e5efe9]">
            <strong className="text-go-ink block mb-1">Authorization semantics</strong>
            Access evaluation requires matching policy effect AND user place scope (depot / outlet). Neither endpoint evaluates membership or runtime authorization decisions alone.
          </div>
        </div>
      </div>

      {/* Catalogue entries do not evaluate a user's policies or scope. */}
      <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 text-sm text-go-ink">
        These are catalogue descriptions. Effective role permissions are unavailable from the current API.
      </div>

      <div className="flex flex-wrap gap-2"><Badge>{shown.length} catalogue entries</Badge></div>

      <div className="grid gap-5 lg:grid-cols-[15rem_1fr]">
        <aside className={`${card} h-fit p-2`}>
          <p className="px-3 py-2 text-xs font-bold uppercase tracking-wider text-go-secondary">Modules</p>
          {["all", ...modules].map((name) => {
            const count = name === "all" ? relevant.filter((item) => item.implemented).length : relevant.filter((item) => item.module === name && item.implemented).length;
            return (
              <button
                key={name}
                className={`flex min-h-11 w-full items-center justify-between rounded-xl px-3 text-left text-sm font-medium ${
                  module === name ? "bg-go-mint text-go-teal" : "hover:bg-go-subtle"
                }`}
                onClick={() => setModule(name)}
              >
                <span>{name === "all" ? "All available" : name}</span>
                <span className="text-xs">{count}</span>
              </button>
            );
          })}
        </aside>

        <section className={card}>
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-go-rule p-5">
            <div>
              <h3 className="text-lg font-semibold">{module === "all" ? "Available capabilities" : module}</h3>
              <p className="text-sm text-go-secondary">{shown.length} capabilities in this view</p>
            </div>
            <label className="flex min-h-11 items-center gap-2 text-sm">
              <input type="checkbox" checked={showLater} onChange={(event) => setShowLater(event.target.checked)} /> Show unavailable
            </label>
          </div>
          {shown.length ? (
            shown.map((item) => {
              return (
                <CapabilityRow
                  key={item.action}
                  capability={item}
                  status={item.implemented ? "Available" : "Unavailable"}
                  source={item.description}
                  onDetails={() => actions.onDetails(item, undefined, selected)}
                />
              );
            })
          ) : (
            <div className="p-5">
              <Empty>No capabilities in this module and filter.</Empty>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function PersonaCard({
  persona,
  state,
  protectedRole,
  roles,
  onOpen,
  onMembers,
}: {
  persona: Persona;
  state: DemoState;
  protectedRole?: boolean;
  roles?: RoleView[];
  onOpen: () => void;
  onMembers: () => void;
}) {
  const info = PERSONAS.find((item) => item.id === persona)!;
  const roleInfo = roles?.find((r) => r.roleCode === persona);
  const members = state.members.filter((item) => item.personas.includes(persona));
  const catalogueEntries = CAPABILITIES.filter((item) => item.relevant.includes(persona)).length;

  return (
    <article className="flex w-full min-w-0 flex-col justify-between rounded-2xl border border-[#dcebe3] bg-white p-4 sm:p-5 shadow-[0_4px_16px_rgba(12,65,45,0.04)] transition hover:shadow-md hover:border-[#bde3d2]">
      <div>
        <button
          type="button"
          className="group flex w-full min-h-24 flex-col items-center justify-center rounded-xl bg-gradient-to-b from-[#f5fbf8] to-[#edf7f2] p-4 text-center transition hover:from-[#e8f7f0] hover:to-[#def2e8] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#087562]"
          onClick={onOpen}
          aria-label={`${info.label}: manage capabilities and role`}
        >
          <span
            aria-hidden="true"
            className={`grid size-12 place-items-center rounded-2xl shadow-xs ${
              protectedRole ? "bg-[#fef3d6] text-[#b45309]" : "bg-go-mint text-go-teal"
            }`}
          >
            <PersonaIcon persona={persona} className="size-6.5" />
          </span>
          <span className="mt-2.5 text-base font-semibold leading-tight text-go-ink group-hover:text-go-teal">
            {info.label}
          </span>
          {protectedRole ? (
            <span className="mt-1 text-[11px] font-semibold text-[#8a6200] bg-[#fef5dd] px-2 py-0.5 rounded-full">
              Protected access
            </span>
          ) : (
            <span className="mt-1 font-mono text-[11px] text-go-secondary">
              wpt:iam:role:{persona}
            </span>
          )}
        </button>

        <p className="mt-3 text-xs text-go-secondary line-clamp-2 min-h-8">
          {roleInfo?.description || info.description}
        </p>

        <dl className="mt-3.5 space-y-2 rounded-xl bg-go-subtle p-3 text-xs border border-[#edf3f0]">
          <div className="flex items-center justify-between gap-2">
            <dt className="text-go-secondary">Members</dt>
            <dd>
              <button
                type="button"
                className="font-semibold text-go-teal hover:underline"
                onClick={onMembers}
              >
                {members.length} {members.length === 1 ? "member" : "members"}
              </button>
            </dd>
          </div>
          <div className="flex items-center justify-between gap-2">
            <dt className="text-go-secondary">Catalogue entries</dt>
            <dd className="font-semibold text-go-ink">{catalogueEntries}</dd>
          </div>
          <div className="flex items-center justify-between gap-2">
            <dt className="text-go-secondary">Effective policies</dt>
            <dd className="font-semibold text-go-secondary">Unavailable</dd>
          </div>
        </dl>
      </div>

      <button
        type="button"
        className="mt-4 min-h-10 w-full rounded-xl border border-[#cbe3d6] bg-white px-3 text-xs font-semibold text-[#145747] transition hover:bg-[#edf7f3] hover:border-go-teal"
        onClick={onOpen}
      >
        {protectedRole ? "View governance & role" : "Manage persona & role"}
      </button>
    </article>
  );
}

export function CatalogueScreen({ state, actions }: { state: DemoState; actions: Actions }) {
  const [query, setQuery] = useState(""); const [module, setModule] = useState("all"); const [availability, setAvailability] = useState("all");
  const modules = useMemo(() => [...new Set(CAPABILITIES.map((item) => item.module))], []);
  const rows = CAPABILITIES.filter((item) => (module === "all" || item.module === module) && (availability === "all" || item.implemented === (availability === "available")) && `${item.label} ${item.description} ${item.action}`.toLowerCase().includes(query.toLowerCase()));
  return <div className="space-y-5"><div><h2 className="text-2xl font-semibold">Permission catalogue</h2><p className="text-sm text-go-secondary">Search the capabilities in this catalogue. Technical action names are in details.</p></div><div className={`${card} grid gap-3 p-4 md:grid-cols-[1fr_12rem_12rem]`}><label className="text-sm font-medium">Search<input className={`${field} mt-1`} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Capability or action" /></label><label className="text-sm font-medium">Module<select className={`${field} mt-1`} value={module} onChange={(event) => setModule(event.target.value)}><option value="all">All modules</option>{modules.map((name) => <option key={name}>{name}</option>)}</select></label><label className="text-sm font-medium">Availability<select className={`${field} mt-1`} value={availability} onChange={(event) => setAvailability(event.target.value)}><option value="all">All</option><option value="available">Available</option><option value="later">Unavailable</option></select></label></div><p className="text-sm text-go-secondary">{rows.length} capabilities</p>{rows.length ? <div className={card}>{rows.map((item) => <CapabilityRow key={item.action} capability={item} status={!item.implemented ? "Unavailable" : "Available"} source={`${item.module} · ${item.relevant.map(labelFor).join(", ")}`} onDetails={() => actions.onDetails(item)} detailsLabel="Details" />)}</div> : <Empty>No capabilities match these filters.</Empty>}</div>;
}

export function HistoryScreen({ state }: { state: DemoState }) {
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = state.history.find((item) => item.id === selectedId);
  const title = (action: string) => CAPABILITIES.find((capability) => capability.action === action)?.label ?? action;
  const rows = state.history.filter((item) => `${item.target} ${item.action} ${title(item.action)} ${item.actor} ${item.reason}`.toLowerCase().includes(query.toLowerCase())).sort((a, b) => b.id - a.id);
  return <div className="space-y-5"><div><h2 className="text-2xl font-semibold">Change history</h2><p className="text-sm text-go-secondary">Changes are recorded here with a reason and before/after choice.</p></div><label className="block max-w-md text-sm font-medium">Search changes<input className={`${field} mt-1`} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Person, action or reason" /></label>{rows.length ? <div className={`${card} divide-y divide-go-rule`}>{rows.map((item) => <article key={item.id} className="grid gap-2 p-5 sm:grid-cols-[11rem_1fr]"><time className="text-sm text-go-secondary">{new Date(item.at).toLocaleString("en-LK", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Colombo" })}</time><div><p className="font-semibold">{item.target} · {title(item.action)}</p><p className="mt-1 text-sm">{item.before} → {item.after}</p><p className="mt-1 text-sm text-go-secondary">By {item.actor} · {item.reason}{item.place ? ` · ${item.place}` : ""}{item.expires ? ` · until ${item.expires}` : ""}</p><button className="mt-2 min-h-11 text-sm font-semibold text-go-teal" onClick={() => setSelectedId(item.id)}>View change</button></div></article>)}</div> : <Empty>No changes match this search.</Empty>}{selected && <Modal title="Change details" onClose={() => setSelectedId(null)}><dl className="grid gap-3 text-sm"><div><dt className="font-semibold">Target</dt><dd>{selected.target}</dd></div><div><dt className="font-semibold">Capability</dt><dd>{title(selected.action)}</dd></div><div><dt className="font-semibold">Change</dt><dd>{selected.before} → {selected.after}</dd></div><div><dt className="font-semibold">Actor and time</dt><dd>{selected.actor} · {new Date(selected.at).toLocaleString("en-LK", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Colombo" })}</dd></div><div><dt className="font-semibold">Scope</dt><dd>{selected.place ?? "Assigned places"}</dd></div><div><dt className="font-semibold">Until</dt><dd>{selected.expires ?? "Until changed"}</dd></div><div><dt className="font-semibold">Reason</dt><dd>{selected.reason}</dd></div></dl><button className={`${secondary} mt-5`} onClick={() => setSelectedId(null)}>Close</button></Modal>}</div>;
}
