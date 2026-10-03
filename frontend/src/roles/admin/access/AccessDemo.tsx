"use client";

import { useEffect, useMemo, useState } from "react";
import { CreateMemberModal, DemoSignIn, type NewMember } from "./AdminAccessFlows";
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
import { fetchRoles, fetchActions, submitManagePermission, FALLBACK_ROLES, FALLBACK_ACTIONS } from "../data/access";
import { fetchAccounts, accountToMember, submitCreateUser } from "../data/accounts";
import type { RoleView, ActionView } from "@shared/domain/identity";
import "./access-demo.css";

type Tab = "people" | "personas" | "actions" | "catalogue" | "history" | "audit" | "forecasts" | "vehicles" | "depots" | "outlets" | "orders" | "trips" | "trips_planned" | "trips_live";
type Route = { tab: Tab; member: string | null; persona: Persona | null };
type Draft = { target: "member" | "persona"; id: string; action: string; choice: Decision; reason: string; place: string; expires: string; stage: "edit" | "review" };
type Details = { capability: Capability; member?: Member; persona?: Persona };

function routeFromHash(): Route {
  if (typeof window === "undefined") return { tab: "people", member: null, persona: null };
  const [rawTab, id] = window.location.hash.replace(/^#\/?/, "").split("/");
  const tab: Tab = rawTab === "roles" ? "personas" : (rawTab === "personas" || rawTab === "actions" || rawTab === "catalogue" || rawTab === "history" || rawTab === "audit" || rawTab === "forecasts" || rawTab === "vehicles" || rawTab === "depots" || rawTab === "outlets" || rawTab === "orders" || rawTab === "trips" || rawTab === "trips_planned" || rawTab === "trips_live" ? rawTab : "people");
  return { tab, member: tab === "people" && id || null, persona: tab === "personas" && PERSONAS.some((item) => item.id === id && item.id !== "super_admin") ? id as Persona : null };
}

export default function AccessDemo() {
  const [state, setState] = useState<DemoState>(freshState);
  const [route, setRoute] = useState<Route>({ tab: "people", member: null, persona: null });
  const [accessExpanded, setAccessExpanded] = useState(false);
  const [tripsExpanded, setTripsExpanded] = useState(false);
  const [rolesList, setRolesList] = useState<RoleView[]>(FALLBACK_ROLES);
  const [actionsList, setActionsList] = useState<ActionView[]>(FALLBACK_ACTIONS);
  const [liveConnected, setLiveConnected] = useState<boolean>(false);
  const [actionsLiveConnected, setActionsLiveConnected] = useState<boolean>(false);
  const [filterPersona, setFilterPersona] = useState<Persona | "all">("all");
  const [signedInId, setSignedInId] = useState<string | null>(null);
  const [newMemberPersona, setNewMemberPersona] = useState<NewMember["persona"] | null>(null);
  const [details, setDetails] = useState<Details | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [toast, setToast] = useState("");
  const signedIn = state.members.find((member) => member.id === signedInId);
  const viewer: "admin" | "super_admin" = signedIn?.personas.includes("super_admin") ? "super_admin" : "admin";
  useEffect(() => { const sync = () => setRoute(routeFromHash()); sync(); window.addEventListener("hashchange", sync); return () => window.removeEventListener("hashchange", sync); }, []);

  // Fetch live accounts, roles, and actions from backend
  useEffect(() => {
    let cancelled = false;

    fetchAccounts({ limit: 100 })
      .then((page) => {
        if (cancelled) return;
        if (page.items && page.items.length > 0) {
          const liveMembers = page.items.map(accountToMember);
          setState((prev) => {
            const preservedAdmins = prev.members.filter(
              (m) =>
                (m.personas.includes("admin") || m.personas.includes("super_admin")) &&
                !liveMembers.some((lm) => lm.id === m.id || lm.email.toLowerCase() === m.email.toLowerCase())
            );
            return {
              ...prev,
              members: [...preservedAdmins, ...liveMembers],
            };
          });
          setLiveConnected(true);
        }
      })
      .catch(() => {
        if (!cancelled) setLiveConnected(false);
      });

    fetchRoles({ limit: 50 })
      .then((page) => {
        if (cancelled) return;
        if (page.items && page.items.length > 0) {
          setRolesList(page.items);
        }
      })
      .catch(() => {});

    fetchActions({ limit: 100 })
      .then((page) => {
        if (cancelled) return;
        if (page.items && page.items.length > 0) {
          setActionsList(page.items);
          setActionsLiveConnected(true);
        }
      })
      .catch(() => {
        if (!cancelled) setActionsLiveConnected(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const navigate = (tab: Tab, id?: string | null) => { window.location.hash = `${tab}${id ? `/${id}` : ""}`; setRoute({ tab, member: tab === "people" ? id ?? null : null, persona: tab === "personas" ? id as Persona ?? null : null }); };
  const showDetails = (capability: Capability, member?: Member, persona?: Persona) => setDetails({ capability, member, persona });

  function openEditor(capability?: Capability, member?: Member, persona?: Persona) {
    if (!member && !persona) return;
    if (persona && (persona === "super_admin" || persona === "admin" && viewer !== "super_admin")) return;
    if (member && (member.personas.includes("super_admin") || member.personas.includes("admin") && viewer !== "super_admin")) return;
    const existing = member && capability ? activeException(state, member.id, capability.action) : undefined;
    setDraft({ target: member ? "member" : "persona", id: member?.id ?? persona!, action: capability?.action ?? "", choice: member ? existing?.decision ?? "inherit" : capability ? personaChoice(state, persona!, capability.action) : "inherit", reason: "", place: existing?.place ?? "", expires: existing?.expires ?? "", stage: "edit" });
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

    // Attempt backend persistence through iam:ManagePermission command
    try {
      await submitManagePermission({
        principalType: draft.target === "persona" ? "role" : "user",
        principalId: draft.id,
        action: draft.action,
        choice: draft.choice === "omit" ? "inherit" : (draft.choice as "allow" | "deny" | "inherit"),
        reason: draft.reason.trim(),
        place: draft.place || null,
        expires: draft.expires || null,
      });
    } catch {
      // Degrades gracefully to in-memory state when offline or backend unavailable
    }

    setState({ ...proposedState, history: [...state.history, { id: Math.max(0, ...state.history.map((item) => item.id)) + 1, at: new Date().toISOString(), actor: signedIn?.name ?? "Administrator", target: targetName, action: draft.action, before: description(beforeChoice, draft.target === "member"), after: description(draft.choice, draft.target === "member"), reason: draft.reason.trim(), place: draft.place || null, expires: draft.expires || null }] });
    setDraft(null); setToast("Change saved. Access counts and history have updated.");
  }

  async function createMember(input: NewMember) {
    if (!signedIn || (input.persona === "admin" && viewer !== "super_admin")) return;
    if (state.members.some((member) => member.email.toLowerCase() === input.email.toLowerCase())) { setToast("That email is already used by a member."); return; }

    let createdId = `mock-member-${crypto.randomUUID()}`;
    try {
      const ack = await submitCreateUser({
        email: input.email,
        displayName: input.name,
        roleCode: input.persona,
      });
      if (ack?.result?.userId) {
        createdId = ack.result.userId;
      }
    } catch {
      // Degrades gracefully to local identifier
    }

    setState((current) => {
      if (current.members.some((member) => member.email.toLowerCase() === input.email.toLowerCase())) return current;
      return {
        ...current,
        members: [...current.members, { id: createdId, name: input.name, email: input.email, personas: [input.persona], places: [input.place], active: true, vehicleType: input.persona === "driver" ? input.vehicleType as "van" | "truck" : undefined, source: "live" }],
        history: [...current.history, { id: Math.max(0, ...current.history.map((item) => item.id)) + 1, at: new Date().toISOString(), actor: signedIn.name, target: input.name, action: "iam:CreateUser", before: "No account", after: `${labelFor(input.persona)} member created${input.persona === "driver" ? ` . ${input.vehicleType}` : ""}`, reason: "Member added", place: input.place, expires: null }],
      };
    });
    setNewMemberPersona(null);
    setFilterPersona(input.persona);
    navigate("people", createdId);
    setToast("Member added.");
  }

  if (!signedIn) return <DemoSignIn members={state.members} onSignIn={(id) => { setSignedInId(id); setAccessExpanded(false); navigate("people"); setToast(""); }} />;

  return <main className="access-demo min-h-dvh font-sans text-[#10251e]"><div className="mx-auto flex min-h-dvh max-w-[1760px] flex-col lg:flex-row">
    <aside className="go-sidebar flex flex-col border-b border-[#dce9e5] bg-white px-4 py-5 lg:sticky lg:top-0 lg:h-dvh lg:w-[254px] lg:shrink-0 lg:border-b-0 lg:border-r lg:px-5 lg:py-7">
      <div className="flex items-center gap-2 px-3"><span className="go-mark" aria-label="GO">GO</span></div>
      <nav aria-label="Admin workspace" className="mt-7 flex flex-col gap-1.5">        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            ["people", "personas", "actions", "catalogue", "history"].includes(route.tab)
              ? "bg-[#b7f2ed] text-[#031b08]"
              : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-expanded={accessExpanded}
          aria-controls="access-subpages"
          onClick={() => {
            setAccessExpanded((value) => !value);
            if (!["people", "personas", "actions", "catalogue", "history"].includes(route.tab)) navigate("people");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <rect x="3" y="3" width="7" height="7" rx="2" />
              <rect x="14" y="3" width="7" height="7" rx="2" />
              <rect x="14" y="14" width="7" height="7" rx="2" />
              <rect x="3" y="14" width="7" height="7" rx="2" />
            </svg>
          </span>
          <span className="flex-1">People &amp; access</span>
          <span aria-hidden="true" className="text-xs">{accessExpanded ? "⌄" : "›"}</span>
        </button>

        <div id="access-subpages" hidden={!accessExpanded} className="space-y-1 pl-4">
          {(["people", "personas", "actions", "catalogue", "history"] as const).map((tab) => (
            <button
              key={tab}
              className={`go-nav-item flex min-h-10 w-full items-center gap-3 rounded-full px-4 text-left text-sm font-medium transition-colors ${
                route.tab === tab ? "bg-[#d8f6f2] text-[#031b08]" : "text-[#475569] hover:bg-[#f1f6f5]"
              }`}
              aria-current={route.tab === tab ? "page" : undefined}
              onClick={() => navigate(tab)}
            >
              <span aria-hidden="true" className="go-nav-glyph">
                <img
                  src={
                    tab === "people"
                      ? "/icons/go/home.svg"
                      : tab === "personas"
                      ? "/icons/go/users.svg"
                      : tab === "actions"
                      ? "/icons/go/permission-list.svg"
                      : tab === "catalogue"
                      ? "/icons/go/permission-list.svg"
                      : "/icons/go/history.svg"
                  }
                  alt=""
                  className="size-4.5"
                />
              </span>
              {tab === "actions"
                ? "Actions"
                : tab === "catalogue"
                ? "Permission catalogue"
                : tab === "history"
                ? "Change history"
                : tab[0].toUpperCase() + tab.slice(1)}
            </button>
          ))}
        </div>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "depots" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "depots" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("depots");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <line x1="8" y1="6" x2="21" y2="6" />
              <line x1="8" y1="12" x2="21" y2="12" />
              <line x1="8" y1="18" x2="21" y2="18" />
              <circle cx="4" cy="6" r="1.5" />
              <circle cx="4" cy="12" r="1.5" />
              <circle cx="4" cy="18" r="1.5" />
            </svg>
          </span>
          <span>Depots</span>
        </button>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "outlets" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "outlets" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("outlets");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <path d="M3 9l1.5-6h15L21 9" />
              <path d="M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0" />
              <path d="M4 9v11a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V9" />
              <path d="M9 21v-7a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v7" />
            </svg>
          </span>
          <span>Retail outlets</span>
        </button>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "orders" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "orders" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("orders");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
              <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
              <line x1="12" y1="22.08" x2="12" y2="12" />
            </svg>
          </span>
          <span>Orders</span>
        </button>

        {/* Trips with expandable sub-pages: Planned & Live */}
        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            ["trips", "trips_planned", "trips_live"].includes(route.tab)
              ? "bg-[#b7f2ed] text-[#031b08]"
              : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-expanded={tripsExpanded}
          aria-controls="trips-subpages"
          onClick={() => {
            setTripsExpanded((value) => !value);
            if (!["trips", "trips_planned", "trips_live"].includes(route.tab)) navigate("trips");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <polyline points="9 18 15 12 9 6" />
              <path d="M3 12h12" />
              <circle cx="19" cy="12" r="2" />
            </svg>
          </span>
          <span className="flex-1">Trips</span>
          <span aria-hidden="true" className="text-xs">{tripsExpanded ? "⌄" : "›"}</span>
        </button>

        <div id="trips-subpages" hidden={!tripsExpanded} className="space-y-1 pl-4">
          <button
            className={`go-nav-item flex min-h-10 w-full items-center gap-3 rounded-full px-4 text-left text-sm font-medium transition-colors ${
              route.tab === "trips_planned" || route.tab === "trips" ? "bg-[#d8f6f2] text-[#031b08]" : "text-[#475569] hover:bg-[#f1f6f5]"
            }`}
            aria-current={route.tab === "trips_planned" || route.tab === "trips" ? "page" : undefined}
            onClick={() => navigate("trips_planned")}
          >
            <span aria-hidden="true" className="go-nav-glyph flex size-4 items-center justify-center">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
            </span>
            <span>Planned</span>
          </button>

          <button
            className={`go-nav-item flex min-h-10 w-full items-center gap-3 rounded-full px-4 text-left text-sm font-medium transition-colors ${
              route.tab === "trips_live" ? "bg-[#d8f6f2] text-[#031b08]" : "text-[#475569] hover:bg-[#f1f6f5]"
            }`}
            aria-current={route.tab === "trips_live" ? "page" : undefined}
            onClick={() => navigate("trips_live")}
          >
            <span aria-hidden="true" className="go-nav-glyph flex size-4 items-center justify-center">
              <span className="size-2 rounded-full bg-[#1d4ed8]"></span>
            </span>
            <span>Live</span>
          </button>
        </div>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "vehicles" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "vehicles" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("vehicles");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <rect x="1" y="3" width="15" height="13" rx="1" />
              <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
              <circle cx="5.5" cy="18.5" r="2.5" />
              <circle cx="18.5" cy="18.5" r="2.5" />
            </svg>
          </span>
          <span>Vehicles</span>
        </button>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "forecasts" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "forecasts" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("forecasts");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
            </svg>
          </span>
          <span>Forecasts</span>
        </button>

        <button
          className={`go-nav-item flex min-h-11 w-full items-center gap-3 rounded-full px-4 text-left text-[15px] font-medium transition-colors ${
            route.tab === "audit" ? "bg-[#b7f2ed] text-[#031b08]" : "text-[#10251e] hover:bg-[#f1f6f5]"
          }`}
          aria-current={route.tab === "audit" ? "page" : undefined}
          onClick={() => {
            setAccessExpanded(false);
            navigate("audit");
          }}
        >
          <span aria-hidden="true" className="go-nav-glyph flex size-5 items-center justify-center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
            </svg>
          </span>
          <span>Audit console</span>
        </button>
      </nav>

      <div className="mt-5 border-t border-[#e5ece8] pt-4 lg:mt-auto">
        <div className="hidden items-center gap-3 lg:flex">
          <span className="grid size-9 place-items-center rounded-full bg-[#c9eee5] text-xs font-bold text-[#005e4c]">
            {signedIn.name.split(" ").map((part) => part[0]).slice(0, 2).join("")}
          </span>
          <div>
            <p className="text-sm font-semibold">{signedIn.name}</p>
            <p className="text-xs text-[#6a7e73]">{viewer === "super_admin" ? "Super admin" : "Admin"}</p>
          </div>
        </div>
        <button
          type="button"
          className="mt-3 min-h-11 w-full rounded-full px-3 text-left text-sm font-semibold text-[#006b57] hover:bg-[#edf8f5]"
          onClick={() => {
            setSignedInId(null);
            setDraft(null);
            setDetails(null);
            setNewMemberPersona(null);
          }}
        >
          Sign out
        </button>
      </div>
    </aside>
    <div className="go-content min-w-0 flex-1 px-4 py-6 sm:px-8 sm:py-9 lg:px-10"><div className="mx-auto max-w-[1180px]"><header className="mb-7 flex flex-wrap items-start justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.12em] text-[#227968]">GO / Admin workspace</p><h1 className="mt-2 text-3xl font-semibold tracking-[-.035em] sm:text-4xl">{route.tab === "audit" ? "Audit console" : route.tab === "forecasts" ? "Forecasts" : route.tab === "vehicles" ? "Vehicles" : route.tab === "depots" ? "Operating depots" : route.tab === "outlets" ? "Retail outlets" : route.tab === "orders" ? "Orders" : route.tab === "trips" || route.tab === "trips_planned" || route.tab === "trips_live" ? "Trips & execution" : route.tab === "actions" ? "Actions" : "People & access"}</h1><p className="mt-2 max-w-2xl text-sm text-[#526a60]">{route.tab === "audit" ? "Review access changes across the operation." : route.tab === "forecasts" ? "See order demand and fleet needs." : route.tab === "vehicles" ? "Review fleet by depot and day status." : route.tab === "depots" ? "Operational hub assignments connecting people, vehicle fleet capacity, and served retail outlets." : route.tab === "outlets" ? "Receiving store destinations, unloading dock capabilities, and delivery time windows." : route.tab === "orders" ? "Operational store orders, authoritative payload weight and volume, product line items, and audit timeline." : route.tab === "trips" || route.tab === "trips_planned" || route.tab === "trips_live" ? "Planning run summary, vehicle allocations, planned stops, and live road execution tracking." : route.tab === "actions" ? "Action catalogue entries from GET /api/admin/actions." : "See who can do what, where they can do it, and why."}</p></div></header>
      {toast && <div aria-live="polite" className="mb-5 flex items-center justify-between rounded-2xl border border-[#b7e2c6] bg-[#e9f8ef] px-4 py-3 text-sm"><span>{toast}</span><button aria-label="Dismiss message" className="min-h-11 px-2" onClick={() => setToast("")}>✕</button></div>}
      {route.tab === "people" && <PeopleScreen state={state} selected={route.member} setSelected={(id) => navigate("people", id)} filterPersona={filterPersona} setFilterPersona={setFilterPersona} actions={{ onDetails: showDetails, onEdit: openEditor }} viewer={viewer} onAddMember={() => setNewMemberPersona("dispatcher")} liveConnected={liveConnected} />}
      {route.tab === "personas" && <PersonasScreen state={state} selected={route.persona} setSelected={(id) => navigate("personas", id)} actions={{ onDetails: showDetails, onEdit: openEditor }} viewer={viewer} onMembers={(persona) => { setFilterPersona(persona); navigate("people"); }} onAddMember={(persona) => { if (persona !== "admin" || viewer === "super_admin") setNewMemberPersona(persona); }} roles={rolesList} />}
      {route.tab === "actions" && <ActionsScreen actions={actionsList} liveConnected={actionsLiveConnected} />}
      {route.tab === "catalogue" && <CatalogueScreen state={state} actions={{ onDetails: showDetails, onEdit: openEditor }} />}
      {route.tab === "history" && <AuditConsole key="permission-history" changes={state.history} viewer={viewer} focused />}
      {route.tab === "audit" && <AuditConsole key="audit-console" changes={state.history} viewer={viewer} />}
      {route.tab === "depots" && <DepotsScreen state={state} onNavigateTab={(target) => navigate(target)} onSelectMember={(id) => navigate("people", id)} />}
      {route.tab === "outlets" && <OutletsScreen state={state} onNavigateTab={(target) => navigate(target)} />}
      {route.tab === "orders" && <OrdersScreen state={state} onNavigateTab={(target) => navigate(target)} />}
      {(route.tab === "trips" || route.tab === "trips_planned" || route.tab === "trips_live") && (
        <TripsScreen
          state={state}
          activeSubTab={route.tab === "trips_live" ? "live" : "planned"}
          onSubTabChange={(sub) => navigate(sub === "live" ? "trips_live" : "trips_planned")}
          onNavigateTab={(target) => navigate(target)}
        />
      )}
      {route.tab === "forecasts" && <ForecastsScreen />}
      {route.tab === "vehicles" && <VehiclesScreen />}
    </div></div>
  </div>
  {details && <Modal title={details.capability.label} onClose={() => setDetails(null)}><div className="space-y-4 text-sm"><p>{details.capability.description}</p><div className="flex flex-wrap gap-2"><Badge tone={details.capability.implemented ? "green" : "amber"}>{details.capability.implemented ? "Available" : "Unavailable"}</Badge><Badge>{details.capability.module}</Badge></div><p className="text-[#58685f]">Relevant to: {details.capability.relevant.filter((persona) => persona !== "super_admin").map(labelFor).join(", ")}</p>{details.member && <p className="rounded-xl bg-[#f3f7f4] p-3"><strong>{details.member.name}:</strong> {effective(state, details.member, details.capability).source}. Assigned places: {details.member.places.join(", ") || "none"}.</p>}{details.persona && <p className="rounded-xl bg-[#f3f7f4] p-3"><strong>{labelFor(details.persona)}:</strong> {personaChoice(state, details.persona, details.capability.action) === "deny" ? "Explicitly blocked" : personaAllows(state, details.persona, details.capability.action) ? "Allowed by persona" : "No persona grant"}.</p>}<details className="text-[#58685f]"><summary className="cursor-pointer font-medium">Technical details</summary><code className="mt-2 block rounded-xl bg-[#f3f7f4] p-3">{details.capability.action}</code></details><button className={secondary} onClick={() => setDetails(null)}>Close</button></div></Modal>}
  {newMemberPersona && <CreateMemberModal members={state.members} viewer={viewer} initialPersona={newMemberPersona} onClose={() => setNewMemberPersona(null)} onCreate={createMember} />}
  {draft && <Modal title={draft.stage === "review" ? "Review access change" : draft.target === "member" ? "Member exception" : "Persona capability"} onClose={() => setDraft(null)} wide>
    {draft.stage === "edit" ? <div className="space-y-5"><div className="rounded-xl bg-[#eff9f3] p-4 text-sm"><strong>{draftMember?.name ?? (draftPersona && labelFor(draftPersona))}</strong><p className="mt-1 text-[#58685f]">{draft.target === "persona" ? `This shared choice applies to all ${assigned} assigned members, within their existing places.` : "This choice applies to one member within existing assigned places."}</p></div>
      <label className="block text-sm font-semibold">Capability<select className={`${field} mt-2`} value={draft.action} onChange={(event) => { const action = event.target.value; const ex = draftMember && activeException(state, draftMember.id, action); setDraft({ ...draft, action, choice: draftMember ? ex?.decision ?? "inherit" : personaChoice(state, draftPersona!, action), place: ex?.place ?? "", expires: ex?.expires ?? "" }); }}><option value="">Select a capability</option>{(draftMember ? editOptions : CAPABILITIES.filter((item) => item.implemented && item.relevant.includes(draftPersona!))).map((item) => <option key={item.action} value={item.action}>{item.module} · {item.label}</option>)}</select></label>
      {draftCapability && <p className="text-sm text-[#58685f]">Current: {draftMember ? effective(state, draftMember, draftCapability).source : personaAllows(state, draftPersona!, draft.action) ? "Allowed by persona" : "No persona grant"}</p>}
      <fieldset className="space-y-2"><legend className="text-sm font-semibold">New choice</legend>{(draft.target === "member" ? [{ value: "inherit", label: "Use persona access", help: "Remove this member's exception." }, { value: "allow", label: "Give access", help: "Add access for this member, within assigned places." }, { value: "deny", label: "Block access", help: "Block even if a persona allows it." }] : [{ value: "inherit", label: "Use published default", help: "Return to the seeded persona decision." }, { value: "allow", label: "Allow", help: "Give this persona the capability." }, { value: "omit", label: "Do not grant", help: "Remove this persona's grant; other sources may still allow it." }, { value: "deny", label: "Block", help: "Block even when another policy allows it." }]).map((option) => <label key={option.value} className={`flex cursor-pointer items-start gap-3 rounded-xl border p-3 ${draft.choice === option.value ? "border-[#0a6b63] bg-[#eff9f3]" : "border-[#d9e3de]"}`}><input className="mt-1" type="radio" name="access-choice" value={option.value} checked={draft.choice === option.value} disabled={option.value === "allow" && !allowEligible} onChange={() => setDraft({ ...draft, choice: option.value as Decision })} /><span><strong className="text-sm">{option.label}</strong><span className="block text-xs text-[#58685f]">{option.help}{option.value === "allow" && !allowEligible ? " Outside approved grant limit." : ""}</span></span></label>)}</fieldset>
      {draft.target === "member" && <div className="grid gap-4 sm:grid-cols-2"><label className="text-sm font-semibold">Applies in<select className={`${field} mt-2`} value={draft.place} onChange={(event) => setDraft({ ...draft, place: event.target.value })}><option value="">All assigned places</option>{draftMember?.places.map((place) => <option key={place}>{place}</option>)}</select></label><label className="text-sm font-semibold">Until (optional · Asia/Colombo)<input className={`${field} mt-2`} type="date" value={draft.expires} min={new Date(Date.now() + 86_400_000).toISOString().slice(0, 10)} onChange={(event) => setDraft({ ...draft, expires: event.target.value })} /></label></div>}
      {draft.target === "member" && <p className="text-xs text-[#835500]">Place and expiry controls are unavailable for this permission.</p>}
      <label className="block text-sm font-semibold">Reason <span className="font-normal text-[#58685f]">(required)</span><textarea className={`${field} mt-2 min-h-24`} value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} placeholder="Why is this access changing?" /></label>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setDraft(null)}>Discard</button><button className={primary} disabled={!valid} onClick={() => setDraft({ ...draft, stage: "review" })}>Review change</button></div>
    </div> : <div className="space-y-5"><p className="text-sm text-[#58685f]">Confirm the effect of this change before saving.</p><div className={`${card} grid gap-4 p-5 sm:grid-cols-2`}><div><p className="text-xs font-bold uppercase text-[#66756b]">Target</p><p className="font-semibold">{draftMember?.name ?? (draftPersona && labelFor(draftPersona))}</p></div><div><p className="text-xs font-bold uppercase text-[#66756b]">Capability</p><p className="font-semibold">{draftCapability?.label}</p></div><div><p className="text-xs font-bold uppercase text-[#66756b]">Before</p><p>{beforeChoice === "inherit" ? "Inherited choice" : beforeChoice}</p>{beforeResult && <p className="text-xs text-[#58685f]">{beforeResult.source}</p>}</div><div><p className="text-xs font-bold uppercase text-[#66756b]">After</p><p className="capitalize">{draft.choice === "omit" ? "Do not grant" : draft.choice}</p>{afterResult && <p className="text-xs text-[#58685f]">{afterResult.source}</p>}</div><div><p className="text-xs font-bold uppercase text-[#66756b]">Place</p><p>{draft.target === "persona" ? "Members' existing assignments" : draft.place || draftMember?.places.join(", ") || "No place assigned"}</p></div><div><p className="text-xs font-bold uppercase text-[#66756b]">Duration</p><p>{draft.expires || "Until changed"}</p></div><div className="sm:col-span-2"><p className="text-xs font-bold uppercase text-[#66756b]">Reason</p><p>{draft.reason}</p></div></div>{draftPersona && <div className="rounded-xl bg-[#eff9f3] p-4 text-sm"><strong>{assigned} assigned members · {affected.length} effective access result{affected.length === 1 ? "" : "s"} change</strong><p className="mt-1 text-[#58685f]">Direct member blocks and missing place assignments can keep a member's result unchanged.</p>{affected.length > 0 && <p className="mt-2">{affected.map((member) => member.name).join(", ")}</p>}</div>}<div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setDraft({ ...draft, stage: "edit" })}>Back to edit</button><button className={primary} onClick={save}>Save change</button></div></div>}
  </Modal>}
  </main>;
}
