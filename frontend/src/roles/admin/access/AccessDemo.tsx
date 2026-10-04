"use client";

import { useEffect, useMemo, useState } from "react";
import { Icon, ShellActions, cx, type IconName } from "@shared/ui";
import { CreateMemberModal, type NewMember } from "./AdminAccessFlows";
import { Badge, Modal, card, field, primary, secondary } from "./components";
import { freshState } from "./fixtures";
import { BASELINE, CAPABILITIES, PERSONAS, activeException, effective, labelFor, personaAllows, personaChoice, todayInColombo, type Capability, type Decision, type DemoState, type Member, type Persona } from "./model";
import { ForecastsScreen, VehiclesScreen } from "./operations";
import { DepotsScreen } from "./DepotsScreen";
import { OutletsScreen } from "./OutletsScreen";
import { OrdersScreen } from "./OrdersScreen";
import { TripsScreen, type TripsSubTab } from "./TripsScreen";
import { CatalogueScreen, PeopleScreen, PersonasScreen } from "./screens";
import { ActionsScreen } from "./RoleActionScreens";
import { AuditConsole } from "./audit/AuditConsole";
import AssistantsConsole from "../assistants/AssistantsConsole";
import { fetchRoles, fetchActions } from "../data/access";
import { fetchAccounts, fetchAccount, accountToMember, submitCreateUser, submitGrantScope } from "../data/accounts";
import type { RoleView, ActionView } from "@shared/domain/identity";
import "./access-demo.css";

type Tab = "people" | "personas" | "actions" | "catalogue" | "history" | "audit" | "assistants" | "forecasts" | "vehicles" | "depots" | "outlets" | "orders" | "trips" | "trips_planned" | "trips_live";
type Route = { tab: Tab; member: string | null; persona: Persona | null };
type Draft = { target: "member" | "persona"; id: string; action: string; choice: Decision; reason: string; place: string; expires: string; stage: "edit" | "review" };
type Details = { capability: Capability; member?: Member; persona?: Persona };

const DEFAULT_ADMIN: Member = {
  id: "admin-system",
  name: "System Administrator",
  email: "admin@waypoint.local",
  personas: ["super_admin", "admin"],
  places: ["GLOBAL"],
  active: true,
  source: "live",
};

function routeFromHash(): Route {
  if (typeof window === "undefined") return { tab: "people", member: null, persona: null };
  const [rawTab, id] = window.location.hash.replace(/^#\/?/, "").split("/");
  const tab: Tab = rawTab === "roles" ? "personas" : (rawTab === "personas" || rawTab === "actions" || rawTab === "catalogue" || rawTab === "history" || rawTab === "audit" || rawTab === "assistants" || rawTab === "forecasts" || rawTab === "vehicles" || rawTab === "depots" || rawTab === "outlets" || rawTab === "orders" || rawTab === "trips" || rawTab === "trips_planned" || rawTab === "trips_live" ? rawTab : "people");
  return { tab, member: tab === "people" && id || null, persona: tab === "personas" && PERSONAS.some((item) => item.id === id && item.id !== "super_admin") ? id as Persona : null };
}

// One sidebar for the whole admin workspace. Sign in and sign out belong to the
// shell, as for every other role.
const NAV: Array<{ tab: Tab; label: string; icon: IconName; also?: Tab[] }> = [
  { tab: "depots", label: "Depots", icon: "dock" },
  { tab: "outlets", label: "Retail outlets", icon: "cart" },
  { tab: "orders", label: "Orders", icon: "box" },
  { tab: "vehicles", label: "Vehicles", icon: "truck" },
  { tab: "forecasts", label: "Forecasts", icon: "chart-line" },
  { tab: "audit", label: "Audit console", icon: "grid" },
];

const PEOPLE_NAV: Array<{ tab: Tab; label: string; icon: IconName }> = [
  { tab: "personas", label: "Personas", icon: "switch-user" },
  { tab: "actions", label: "Actions", icon: "check" },
  { tab: "catalogue", label: "Permission catalogue", icon: "permission-list" },
  { tab: "history", label: "Change history", icon: "history" },
];
const PEOPLE_TABS: Tab[] = ["people", "personas", "actions", "catalogue", "history"];
const TRIPS_NAV: Array<{ tab: Tab; label: string; icon: IconName }> = [
  { tab: "trips_planned", label: "Planned trips", icon: "plan" },
  { tab: "trips_live", label: "Live trips", icon: "live" },
];
const TRIPS_TABS: Tab[] = ["trips", "trips_planned", "trips_live"];

const PEOPLE: [string, string] = ["People & access", "See who can do what, where they can do it, and why."];
const TRIPS: [string, string] = ["Trips & execution", "Planning run summary, vehicle allocations, planned stops, and live road execution tracking."];
const TITLES: Record<Tab, [string, string]> = {
  people: PEOPLE, personas: PEOPLE, catalogue: PEOPLE, history: PEOPLE,
  actions: ["Actions", "Action catalogue entries."],
  audit: ["Audit console", "Review access changes across the operation."],
  assistants: ["AI assistants", "Review connected assistant apps, their activity, and access."],
  forecasts: ["Forecasts", "See order demand and fleet needs."],
  vehicles: ["Vehicles", "Review fleet by depot and day status."],
  depots: ["Operating depots", "Operational hub assignments connecting people, vehicle fleet capacity, and served retail outlets."],
  outlets: ["Retail outlets", "Receiving store destinations, unloading dock capabilities, and delivery time windows."],
  orders: ["Orders", "Operational store orders, authoritative payload weight and volume, product line items, and audit timeline."],
  trips: TRIPS, trips_planned: TRIPS, trips_live: TRIPS,
};

const initials = (name: string) => name.trim().split(/\s+/).filter(Boolean).map((part) => part[0]).slice(0, 2).join("").toUpperCase() || "?";

export default function AccessDemo({ displayName = "Administrator" }: { displayName?: string }) {
  const [state, setState] = useState<DemoState>(freshState);
  const [route, setRoute] = useState<Route>({ tab: "people", member: null, persona: null });
  const [peopleOpen, setPeopleOpen] = useState(false);
  const [tripsOpen, setTripsOpen] = useState(false);
  const [rolesList, setRolesList] = useState<RoleView[]>([]);
  const [actionsList, setActionsList] = useState<ActionView[]>([]);
  const [liveConnected, setLiveConnected] = useState<boolean>(false);
  const [actionsLiveConnected, setActionsLiveConnected] = useState<boolean>(false);
  const [filterPersona, setFilterPersona] = useState<Persona | "all">("all");
  const [newMemberPersona, setNewMemberPersona] = useState<NewMember["persona"] | null>(null);
  const [details, setDetails] = useState<Details | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [toast, setToast] = useState("");
  const [pendingCreation, setPendingCreation] = useState<{ email: string; userId: string } | null>(null);
  const viewer = "admin" as "admin" | "super_admin";
  useEffect(() => { const sync = () => { const next = routeFromHash(); setRoute(next); setPeopleOpen(PEOPLE_TABS.includes(next.tab)); setTripsOpen(TRIPS_TABS.includes(next.tab)); }; sync(); window.addEventListener("hashchange", sync); return () => window.removeEventListener("hashchange", sync); }, []);

  // Fetch live accounts, roles, and actions from backend
  useEffect(() => {
    let cancelled = false;

    fetchAccounts({ limit: 100 })
      .then((page) => {
        if (cancelled) return;
        setState((prev) => ({ ...prev, members: page.items.map(accountToMember) }));
        setLiveConnected(true);
      })
      .catch(() => {
        if (!cancelled) setLiveConnected(false);
      });

    fetchRoles({ limit: 50 })
      .then((page) => {
        if (cancelled) return;
        setRolesList(page.items);
      })
      .catch(() => {});

    fetchActions({ limit: 100 })
      .then((page) => {
        if (cancelled) return;
        setActionsList(page.items);
        setActionsLiveConnected(true);
      })
      .catch(() => {
        if (!cancelled) setActionsLiveConnected(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const navigate = (tab: Tab, id?: string | null) => { window.location.hash = `${tab}${id ? `/${id}` : ""}`; setRoute({ tab, member: tab === "people" ? id ?? null : null, persona: tab === "personas" ? id as Persona ?? null : null }); setPeopleOpen(PEOPLE_TABS.includes(tab)); setTripsOpen(TRIPS_TABS.includes(tab)); };
  const showDetails = (capability: Capability, member?: Member, persona?: Persona) => setDetails({ capability, member, persona });

  function openEditor() {
    setToast("Permission editing is unavailable until policy attachments and versioned commands are exposed by the server.");
  }

  const draftMember = draft?.target === "member" ? state.members.find((item) => item.id === draft.id) : undefined;
  const draftPersona = draft?.target === "persona" ? draft.id as Persona : undefined;
  const draftCapability = CAPABILITIES.find((item) => item.action === draft?.action);
  const editOptions = useMemo(() => draftMember ? CAPABILITIES.filter((item) => item.implemented && (draftMember.personas.some((persona) => item.relevant.includes(persona)) || state.exceptions.some((ex) => ex.memberId === draftMember.id && ex.action === item.action)) && (draftMember.personas.some((persona) => BASELINE[persona].includes(item.action) || item.optionalFor?.includes(persona)) || state.exceptions.some((ex) => ex.memberId === draftMember.id && ex.action === item.action))).sort((a, b) => a.module.localeCompare(b.module) || a.label.localeCompare(b.label)) : [], [draftMember, state.exceptions]);
  const beforeChoice = draft ? draft.target === "member" ? activeException(state, draft.id, draft.action)?.decision ?? "inherit" : personaChoice(state, draft.id as Persona, draft.action) : "inherit";
  const proposedState = useMemo(() => {
    if (!draft || !draft.action) return state;
    if (draft.target === "persona") return { ...state, personaSettings: { ...state.personaSettings, [`${draft.id}:${draft.action}`]: draft.choice } };
    const exceptions = state.exceptions.filter((item) => !(item.memberId === draft.id && item.action === draft.action));
    if (draft.choice === "allow" || draft.choice === "deny") exceptions.push({ memberId: draft.id, action: draft.action, decision: draft.choice, reason: draft.reason, place: draft.place || null, expires: draft.expires || null });
    return { ...state, exceptions };
  }, [draft, state]);
  const affected = draftPersona && draftCapability ? state.members.filter((item) => item.personas.includes(draftPersona) && effective(state, item, draftCapability).places.join("|") !== effective(proposedState, item, draftCapability).places.join("|")) : [];
  const assigned = draftPersona ? state.members.filter((item) => item.personas.includes(draftPersona)).length : 0;
  const beforeResult = draftMember && draftCapability ? effective(state, draftMember, draftCapability) : null;
  const afterResult = draftMember && draftCapability ? effective(proposedState, draftMember, draftCapability) : null;
  const allowEligible = !!draftCapability && (draft?.target === "persona" ? (draftPersona === "admin" && viewer === "super_admin") || !!draftPersona && (BASELINE[draftPersona].includes(draftCapability.action) || !!draftCapability.optionalFor?.includes(draftPersona)) : !!draftMember && draftMember.personas.some((persona) => BASELINE[persona].includes(draftCapability.action) || !!draftCapability.optionalFor?.includes(persona)));
  const existingException = draft?.target === "member" ? state.exceptions.find((item) => item.memberId === draft.id && item.action === draft.action) : undefined;
  const changed = !!draft && (draft.target === "persona" ? draft.choice !== beforeChoice : draft.choice === "inherit" ? !!existingException : !existingException || draft.choice !== existingException.decision || (draft.place || null) !== existingException.place || (draft.expires || null) !== existingException.expires || draft.reason.trim() !== existingException.reason);
  const valid = !!draft && !!draftCapability && draftCapability.implemented && changed && draft.reason.trim().length >= 3 && (draft.choice !== "allow" || allowEligible) && (draft.target === "persona" || !draft.place || draftMember?.places.includes(draft.place)) && (!draft.expires || draft.expires > todayInColombo());

  async function save() {
    if (!valid || !draft || !draftCapability) return;
    const targetName = draftMember?.name ?? labelFor(draft.id as Persona);
    const description = (choice: Decision, member: boolean) => choice === "inherit" ? member ? "Use persona access" : "Use published default" : choice === "allow" ? member ? "Allow for this member" : "Allow" : choice === "deny" ? member ? "Block for this member" : "Block" : "Do not grant";

    setToast("Permission editing is unavailable until the server exposes a versioned policy command.");
  }

  async function createMember(input: NewMember): Promise<string | null> {
    if (input.persona === "admin" && viewer !== "super_admin") return "Only a super admin can add an admin.";
    if (state.members.some((member) => member.email.toLowerCase() === input.email.toLowerCase())) return "That email is already used by a member.";

    let createdId = pendingCreation?.email === input.email ? pendingCreation.userId : null;
    try {
      if (!createdId) {
        const ack = await submitCreateUser({
          email: input.email, displayName: input.name, password: input.password,
          roleCode: input.persona,
        });
        createdId = ack.result.userId;
        setPendingCreation({ email: input.email, userId: createdId });
      }
      const created = await fetchAccount(createdId);
      await submitGrantScope(createdId, input.place,
        input.persona === "store_manager" ? "outlet" : "depot", created.rowVersion);
      const configured = await fetchAccount(createdId);
      setState((current) => ({ ...current,
        members: [...current.members, accountToMember(configured)],
        history: [...current.history, { id: Math.max(0, ...current.history.map((item) => item.id)) + 1,
          at: new Date().toISOString(), actor: displayName, target: input.name, action: "iam:CreateUser",
          before: "No account", after: `${labelFor(input.persona)} member created`, reason: "Member added",
          place: input.place, expires: null }],
      }));
    } catch (error) {
      return createdId
        ? `Account created, but its place was not confirmed. Retry to finish setup. ${error instanceof Error ? error.message : ""}`
        : `Member creation failed. ${error instanceof Error ? error.message : "Try again."}`;
    }
    setPendingCreation(null);
    setNewMemberPersona(null);
    setFilterPersona(input.persona);
    navigate("people", createdId);
    setToast("Member added.");
    return null;
  }

  const navItem = (item: (typeof NAV)[number], compact: boolean) => {
    const active = route.tab === item.tab || !!item.also?.includes(route.tab);
    return <a key={item.tab} href={`#${item.tab}`} aria-current={active ? "page" : undefined}
      onClick={(event) => { event.preventDefault(); navigate(item.tab); }}
      className={compact
        ? cx("flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-[15px] text-go-ink", active ? "bg-go-mint font-medium" : "bg-go-subtle")
        : cx("flex w-full items-center gap-3 rounded-go-card-l px-3.5 py-2.5 text-[15px] text-go-ink", active ? "bg-go-mint font-medium" : "hover:bg-go-subtle")}>
      <Icon name={item.icon} /><span className="min-w-0 flex-1">{item.label}</span>
    </a>;
  };

  const peopleNav = (compact: boolean) => <>
    <button type="button" aria-expanded={peopleOpen} onClick={() => { if (peopleOpen) setPeopleOpen(false); else navigate("people"); }}
      className={compact
        ? cx("flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-[15px] text-go-ink", PEOPLE_TABS.includes(route.tab) ? "bg-go-mint font-medium" : "bg-go-subtle")
        : cx("flex w-full items-center gap-3 rounded-go-card-l px-3.5 py-2.5 text-[15px] text-go-ink", PEOPLE_TABS.includes(route.tab) ? "bg-go-mint font-medium" : "hover:bg-go-subtle")}>
      <Icon name="users" /><span className="min-w-0 flex-1 text-left">People</span><span className={cx("inline-flex size-3.5 transition-transform", !peopleOpen && "-rotate-90")}><Icon name="chevron-down" /></span>
    </button>
    {peopleOpen && <div className={compact ? "flex gap-1.5" : "ml-5 flex flex-col gap-1 border-l border-go-rule pl-3"}>
      {PEOPLE_NAV.map((item) => <a key={item.tab} href={`#${item.tab}`} aria-current={route.tab === item.tab ? "page" : undefined}
        onClick={(event) => { event.preventDefault(); navigate(item.tab); }}
        className={compact
          ? cx("flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-sm text-go-ink", route.tab === item.tab ? "bg-go-mint font-medium" : "bg-go-subtle")
          : cx("flex w-full items-center gap-3 rounded-go-card-l px-3 py-2 text-sm text-go-ink", route.tab === item.tab ? "bg-go-mint font-medium" : "hover:bg-go-subtle")}>
        <Icon name={item.icon} /><span className="min-w-0 flex-1">{item.label}</span>
      </a>)}
    </div>}
  </>;

  const tripsNav = (compact: boolean) => <>
    <button type="button" aria-expanded={tripsOpen} onClick={() => { if (tripsOpen) setTripsOpen(false); else navigate("trips_planned"); }}
      className={compact
        ? cx("flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-[15px] text-go-ink", TRIPS_TABS.includes(route.tab) ? "bg-go-mint font-medium" : "bg-go-subtle")
        : cx("flex w-full items-center gap-3 rounded-go-card-l px-3.5 py-2.5 text-[15px] text-go-ink", TRIPS_TABS.includes(route.tab) ? "bg-go-mint font-medium" : "hover:bg-go-subtle")}>
      <Icon name="plan" /><span className="min-w-0 flex-1 text-left">Trips</span><span className={cx("inline-flex size-3.5 transition-transform", !tripsOpen && "-rotate-90")}><Icon name="chevron-down" /></span>
    </button>
    {tripsOpen && <div className={compact ? "flex gap-1.5" : "ml-5 flex flex-col gap-1 border-l border-go-rule pl-3"}>
      {TRIPS_NAV.map((item) => <a key={item.tab} href={`#${item.tab}`} aria-current={route.tab === item.tab ? "page" : undefined}
        onClick={(event) => { event.preventDefault(); navigate(item.tab); }}
        className={compact
          ? cx("flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-sm text-go-ink", route.tab === item.tab ? "bg-go-mint font-medium" : "bg-go-subtle")
          : cx("flex w-full items-center gap-3 rounded-go-card-l px-3 py-2 text-sm text-go-ink", route.tab === item.tab ? "bg-go-mint font-medium" : "hover:bg-go-subtle")}>
        <Icon name={item.icon} /><span className="min-w-0 flex-1">{item.label}</span>
      </a>)}
    </div>}
  </>;

  return <main aria-label="Admin workspace" className="access-demo flex min-h-dvh w-full flex-col bg-go-canvas font-go text-go-ink lg:h-dvh lg:flex-row">
    <aside className="hidden h-full w-[260px] shrink-0 flex-col gap-1 overflow-y-auto bg-white px-5 pt-7 pb-6 lg:flex">
      <div className="flex items-center gap-2.5 px-2 pb-5">
        <span className="text-[34px] font-extrabold text-go-ink">GO</span>
        <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Admin</span>
      </div>
      <nav aria-label="Admin" className="flex flex-col gap-1">{peopleNav(false)}{NAV.slice(0, 3).map((item) => navItem(item, false))}{tripsNav(false)}{NAV.slice(3).map((item) => navItem(item, false))}</nav>
      <div className="flex-1" />
      <div className="flex items-center gap-2.5 pt-3.5">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-[20px] bg-go-mint text-sm font-medium text-go-ink">{initials(displayName)}</span>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5"><p className="truncate text-[15px] font-medium text-go-ink">{displayName}</p><p className="text-xs text-go-secondary">Administrator</p></div>
        <ShellActions compact />
      </div>
    </aside>
    <div className="flex flex-col gap-3 bg-white px-4 pt-4 pb-3 lg:hidden">
      <div className="flex items-center gap-2.5">
        <span className="text-[34px] leading-none font-extrabold text-go-ink">GO</span>
        <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Admin</span>
        <span className="flex-1" />
        <ShellActions compact />
      </div>
      <nav aria-label="Admin" className="-mx-4 flex gap-1.5 overflow-x-auto px-4">{peopleNav(true)}{NAV.slice(0, 3).map((item) => navItem(item, true))}{tripsNav(true)}{NAV.slice(3).map((item) => navItem(item, true))}</nav>
    </div>
    <div className="flex min-w-0 flex-1 flex-col px-4 py-5 md:px-9 md:py-7 lg:overflow-y-auto"><div className="mx-auto w-full max-w-[1180px]">
      <header className="mb-6 flex flex-col gap-0.5"><h1 className="text-[26px] font-medium text-go-ink md:text-[30px]">{TITLES[route.tab][0]}</h1><p className="text-sm text-go-secondary">{TITLES[route.tab][1]}</p></header>
      {toast && <div aria-live="polite" className="mb-5 flex items-center justify-between rounded-2xl border border-[#b7e2c6] bg-[#e9f8ef] px-4 py-3 text-sm"><span>{toast}</span><button aria-label="Dismiss message" className="min-h-11 px-2" onClick={() => setToast("")}>✕</button></div>}
      {route.tab === "people" && <PeopleScreen state={state} selected={route.member} setSelected={(id) => navigate("people", id)} filterPersona={filterPersona} setFilterPersona={setFilterPersona} actions={{ onDetails: showDetails, onEdit: openEditor }} viewer={viewer} onAddMember={() => setNewMemberPersona("dispatcher")} liveConnected={liveConnected} />}
      {route.tab === "personas" && <PersonasScreen state={state} selected={route.persona} setSelected={(id) => navigate("personas", id)} actions={{ onDetails: showDetails, onEdit: openEditor }} viewer={viewer} onMembers={(persona) => { setFilterPersona(persona); navigate("people"); }} onAddMember={(persona) => { if (persona !== "admin" || viewer === "super_admin") setNewMemberPersona(persona); }} roles={rolesList} />}
      {route.tab === "actions" && <ActionsScreen actions={actionsList} liveConnected={actionsLiveConnected} />}
      {route.tab === "catalogue" && <CatalogueScreen state={state} actions={{ onDetails: showDetails, onEdit: openEditor }} />}
      {route.tab === "history" && <AuditConsole key="permission-history" changes={state.history} viewer={viewer} focused />}
      {route.tab === "audit" && <AuditConsole key="audit-console" changes={state.history} viewer={viewer} />}
      {route.tab === "assistants" && <AssistantsConsole />}
      {route.tab === "depots" && <DepotsScreen state={state} onNavigateTab={(target) => navigate(target)} onSelectMember={(id) => navigate("people", id)} />}
      {route.tab === "outlets" && <OutletsScreen state={state} onNavigateTab={(target) => navigate(target)} />}
      {route.tab === "orders" && <OrdersScreen state={state} onNavigateTab={(target) => navigate(target)} />}
      {(route.tab === "trips" || route.tab === "trips_planned" || route.tab === "trips_live") && (
        <TripsScreen
          state={state}
          activeSubTab={route.tab === "trips_live" ? "live" : "planned"}
          onNavigateTab={(target) => navigate(target)}
        />
      )}
      {route.tab === "forecasts" && <ForecastsScreen />}
      {route.tab === "vehicles" && <VehiclesScreen />}
    </div></div>
  {details && <Modal title={details.capability.label} onClose={() => setDetails(null)}><div className="space-y-4 text-sm"><p>{details.capability.description}</p><div className="flex flex-wrap gap-2"><Badge tone={details.capability.implemented ? "green" : "amber"}>{details.capability.implemented ? "Catalogue entry" : "Unavailable"}</Badge><Badge>{details.capability.module}</Badge></div><p className="text-go-secondary">This catalogue entry does not establish effective access for a member or role.</p><details className="text-go-secondary"><summary className="cursor-pointer font-medium">Technical details</summary><code className="mt-2 block rounded-xl bg-go-subtle p-3">{details.capability.action}</code></details><button className={secondary} onClick={() => setDetails(null)}>Close</button></div></Modal>}
  {newMemberPersona && <CreateMemberModal members={state.members} viewer={viewer} initialPersona={newMemberPersona} onClose={() => setNewMemberPersona(null)} onCreate={createMember} />}
  {draft && <Modal title={draft.stage === "review" ? "Review access change" : draft.target === "member" ? "Member exception" : "Persona capability"} onClose={() => setDraft(null)} wide>
    {draft.stage === "edit" ? <div className="space-y-5"><div className="rounded-xl bg-go-subtle p-4 text-sm"><strong>{draftMember?.name ?? (draftPersona && labelFor(draftPersona))}</strong><p className="mt-1 text-go-secondary">{draft.target === "persona" ? `This shared choice applies to all ${assigned} assigned members, within their existing places.` : "This choice applies to one member within existing assigned places."}</p></div>
      <label className="block text-sm font-semibold">Capability<select className={`${field} mt-2`} value={draft.action} onChange={(event) => { const action = event.target.value; const ex = draftMember && activeException(state, draftMember.id, action); setDraft({ ...draft, action, choice: draftMember ? ex?.decision ?? "inherit" : personaChoice(state, draftPersona!, action), place: ex?.place ?? "", expires: ex?.expires ?? "" }); }}><option value="">Select a capability</option>{(draftMember ? editOptions : CAPABILITIES.filter((item) => item.implemented && item.relevant.includes(draftPersona!))).map((item) => <option key={item.action} value={item.action}>{item.module} · {item.label}</option>)}</select></label>
      {draftCapability && <p className="text-sm text-go-secondary">Current: {draftMember ? effective(state, draftMember, draftCapability).source : personaAllows(state, draftPersona!, draft.action) ? "Allowed by persona" : "No persona grant"}</p>}
      <fieldset className="space-y-2"><legend className="text-sm font-semibold">New choice</legend>{(draft.target === "member" ? [{ value: "inherit", label: "Use persona access", help: "Remove this member's exception." }, { value: "allow", label: "Give access", help: "Add access for this member, within assigned places." }, { value: "deny", label: "Block access", help: "Block even if a persona allows it." }] : [{ value: "inherit", label: "Use published default", help: "Return to the seeded persona decision." }, { value: "allow", label: "Allow", help: "Give this persona the capability." }, { value: "omit", label: "Do not grant", help: "Remove this persona's grant; other sources may still allow it." }, { value: "deny", label: "Block", help: "Block even when another policy allows it." }]).map((option) => <label key={option.value} className={`flex cursor-pointer items-start gap-3 rounded-xl border p-3 ${draft.choice === option.value ? "border-go-teal bg-go-subtle" : "border-go-rule"}`}><input className="mt-1" type="radio" name="access-choice" value={option.value} checked={draft.choice === option.value} disabled={option.value === "allow" && !allowEligible} onChange={() => setDraft({ ...draft, choice: option.value as Decision })} /><span><strong className="text-sm">{option.label}</strong><span className="block text-xs text-go-secondary">{option.help}{option.value === "allow" && !allowEligible ? " Outside approved grant limit." : ""}</span></span></label>)}</fieldset>
      {draft.target === "member" && <div className="grid gap-4 sm:grid-cols-2"><label className="text-sm font-semibold">Applies in<select className={`${field} mt-2`} value={draft.place} onChange={(event) => setDraft({ ...draft, place: event.target.value })}><option value="">All assigned places</option>{draftMember?.places.map((place) => <option key={place}>{place}</option>)}</select></label><label className="text-sm font-semibold">Until (optional · Asia/Colombo)<input className={`${field} mt-2`} type="date" value={draft.expires} min={new Date(Date.now() + 86_400_000).toISOString().slice(0, 10)} onChange={(event) => setDraft({ ...draft, expires: event.target.value })} /></label></div>}
      {draft.target === "member" && <p className="text-xs text-go-warning-text">Place and expiry controls are unavailable for this permission.</p>}
      <label className="block text-sm font-semibold">Reason <span className="font-normal text-go-secondary">(required)</span><textarea className={`${field} mt-2 min-h-24`} value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} placeholder="Why is this access changing?" /></label>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setDraft(null)}>Discard</button><button className={primary} disabled={!valid} onClick={() => setDraft({ ...draft, stage: "review" })}>Review change</button></div>
    </div> : <div className="space-y-5"><p className="text-sm text-go-secondary">Confirm the effect of this change before saving.</p><div className={`${card} grid gap-4 p-5 sm:grid-cols-2`}><div><p className="text-xs font-bold uppercase text-go-secondary">Target</p><p className="font-semibold">{draftMember?.name ?? (draftPersona && labelFor(draftPersona))}</p></div><div><p className="text-xs font-bold uppercase text-go-secondary">Capability</p><p className="font-semibold">{draftCapability?.label}</p></div><div><p className="text-xs font-bold uppercase text-go-secondary">Before</p><p>{beforeChoice === "inherit" ? "Inherited choice" : beforeChoice}</p>{beforeResult && <p className="text-xs text-go-secondary">{beforeResult.source}</p>}</div><div><p className="text-xs font-bold uppercase text-go-secondary">After</p><p className="capitalize">{draft.choice === "omit" ? "Do not grant" : draft.choice}</p>{afterResult && <p className="text-xs text-go-secondary">{afterResult.source}</p>}</div><div><p className="text-xs font-bold uppercase text-go-secondary">Place</p><p>{draft.target === "persona" ? "Members' existing assignments" : draft.place || draftMember?.places.join(", ") || "No place assigned"}</p></div><div><p className="text-xs font-bold uppercase text-go-secondary">Duration</p><p>{draft.expires || "Until changed"}</p></div><div className="sm:col-span-2"><p className="text-xs font-bold uppercase text-go-secondary">Reason</p><p>{draft.reason}</p></div></div>{draftPersona && <div className="rounded-xl bg-go-subtle p-4 text-sm"><strong>{assigned} assigned members · {affected.length} effective access result{affected.length === 1 ? "" : "s"} change</strong><p className="mt-1 text-go-secondary">Direct member blocks and missing place assignments can keep a member's result unchanged.</p>{affected.length > 0 && <p className="mt-2">{affected.map((member) => member.name).join(", ")}</p>}</div>}<div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setDraft({ ...draft, stage: "edit" })}>Back to edit</button><button className={primary} onClick={save}>Save change</button></div></div>}
  </Modal>}
  </main>;
}
