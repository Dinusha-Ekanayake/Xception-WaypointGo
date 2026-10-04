"use client";

import { useEffect, useState } from "react";
import { Modal, card, field, primary, secondary } from "./components";
import { labelFor, type Member, type Persona } from "./model";
import { fetchAdminDepots, fetchAdminOutlets } from "../data/reference";

export type NewMember = { name: string; email: string; password: string; persona: Exclude<Persona, "super_admin">; place: string; vehicleType: "" | "van" | "truck" };

const PERSONA_OPTIONS: Exclude<Persona, "super_admin">[] = ["dispatcher", "loader", "driver", "store_manager", "admin"];
const placeLabel = (persona: NewMember["persona"]) => persona === "loader" ? "Depot" : persona === "store_manager" ? "Outlet" : "Depot";

export function CreateMemberModal({ members, viewer, initialPersona, onClose, onCreate }: { members: Member[]; viewer: "admin" | "super_admin"; initialPersona?: NewMember["persona"]; onClose: () => void; onCreate: (input: NewMember) => Promise<string | null> }) {
  const [draft, setDraft] = useState<NewMember>({ name: "", email: "", password: "", persona: initialPersona && (initialPersona !== "admin" || viewer === "super_admin") ? initialPersona : "dispatcher", place: "", vehicleType: "" });
  const [reviewing, setReviewing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [depots, setDepots] = useState<string[]>([]);
  const [outlets, setOutlets] = useState<string[]>([]);
  useEffect(() => {
    let cancelled = false;
    void fetchAdminDepots().then((rows) => { if (!cancelled) setDepots(rows.map((d) => d.code)); }).catch(() => {});
    async function loadOutlets() {
      const ids: string[] = [];
      let after: string | undefined;
      do {
        const page = await fetchAdminOutlets({ after, limit: 200 });
        ids.push(...page.items.map((o) => o.outletId));
        after = page.nextCursor ?? undefined;
      } while (after);
      if (!cancelled) setOutlets(ids);
    }
    void loadOutlets().catch(() => {});
    return () => { cancelled = true; };
  }, []);
  const email = draft.email.trim().toLowerCase();
  const duplicate = members.some((member) => member.email.toLowerCase() === email);
  const places = draft.persona === "store_manager" ? outlets : depots;
  const valid = draft.name.trim().length >= 2 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && draft.password.length >= 12 && !duplicate && (draft.persona !== "admin" || viewer === "super_admin") && places.includes(draft.place) && (draft.persona !== "driver" || draft.vehicleType === "van" || draft.vehicleType === "truck");

  async function submitMember() {
    if (!valid || busy) return;
    setBusy(true); setError("");
    const failure = await onCreate({ ...draft, name: draft.name.trim(), email,
      vehicleType: draft.persona === "driver" ? draft.vehicleType : "" });
    if (failure) { setError(failure); setBusy(false); }
  }

  return <Modal title={reviewing ? "Review new member" : "Add member"} onClose={onClose} wide>
    {reviewing ? <div className="space-y-5">
      <p className="text-sm text-go-secondary">Confirm this member and their assignment.</p>
      <dl className={`${card} grid gap-4 p-5 sm:grid-cols-2`}>
        <div><dt className="text-xs font-bold uppercase text-go-secondary">Name</dt><dd className="mt-1 font-semibold">{draft.name.trim()}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-go-secondary">Email</dt><dd className="mt-1 break-all font-semibold">{email}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-go-secondary">Persona</dt><dd className="mt-1">{labelFor(draft.persona)}</dd></div>
        {draft.persona !== "dispatcher" && <div><dt className="text-xs font-bold uppercase text-go-secondary">Assigned {placeLabel(draft.persona).toLowerCase()}</dt><dd className="mt-1">{draft.place}</dd></div>}
        {draft.persona === "driver" && <div><dt className="text-xs font-bold uppercase text-go-secondary">Vehicle type</dt><dd className="mt-1 capitalize">{draft.vehicleType}</dd></div>}
      </dl>
      {error && <p role="alert" className="text-sm text-go-danger">{error}</p>}
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setReviewing(false)}>Back to edit</button><button className={primary} disabled={!valid || busy} onClick={() => void submitMember()}>{busy ? "Saving..." : "Add member"}</button></div>
    </div> : <div className="space-y-5">
      <p className="text-sm text-go-secondary">Choose the member&apos;s persona and the assignment needed for that role. Only Super admin can add an Admin.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-semibold">Full name<input className={`${field} mt-2`} autoFocus value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="e.g. Ruwan Perera" /></label>
        <label className="text-sm font-semibold">Email<input className={`${field} mt-2`} type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} placeholder="name@example.test" aria-invalid={duplicate} />{duplicate && <span className="mt-1 block text-xs text-go-danger">A member already uses this email.</span>}</label>
      </div>
      <label className="block text-sm font-semibold">Initial password<input className={`${field} mt-2`} type="password" autoComplete="new-password" value={draft.password} onChange={(event) => setDraft({ ...draft, password: event.target.value })} /><span className="mt-1 block text-xs text-go-secondary">At least 12 characters. Share it with the member through a private channel.</span></label>
      <div className="grid gap-4 sm:grid-cols-2"><label className="block text-sm font-semibold">Persona<select className={`${field} mt-2`} value={draft.persona} onChange={(event) => setDraft({ ...draft, persona: event.target.value as NewMember["persona"], place: "", vehicleType: "" })}>{PERSONA_OPTIONS.filter((persona) => persona !== "admin" || viewer === "super_admin").map((persona) => <option key={persona} value={persona}>{labelFor(persona)}</option>)}</select></label><label className="block text-sm font-semibold">{placeLabel(draft.persona)}<select className={`${field} mt-2`} value={draft.place} onChange={(event) => setDraft({ ...draft, place: event.target.value })}><option value="">Choose {placeLabel(draft.persona).toLowerCase()}</option>{places.map((place) => <option key={place} value={place}>{place}</option>)}</select></label>{draft.persona === "driver" && <label className="block text-sm font-semibold">Vehicle type<select className={`${field} mt-2`} value={draft.vehicleType} onChange={(event) => setDraft({ ...draft, vehicleType: event.target.value as NewMember["vehicleType"] })}><option value="">Choose vehicle type</option><option value="van">Van</option><option value="truck">Truck</option></select></label>}</div>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={onClose}>Discard</button><button className={primary} disabled={!valid} onClick={() => setReviewing(true)}>Review member</button></div>
    </div>}
  </Modal>;
}
