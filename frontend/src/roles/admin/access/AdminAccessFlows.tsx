"use client";

import { useRef, useState } from "react";
import { Modal, card, field, primary, secondary } from "./components";
import { labelFor, type Member, type Persona } from "./model";
import { DEMO_DEPOTS } from "./fixtures";

export type NewMember = { name: string; email: string; persona: Exclude<Persona, "super_admin">; place: string; vehicleType: "" | "van" | "truck" };

const DEPOTS: readonly string[] = DEMO_DEPOTS;
const OUTLETS = ["OUT001", "OUT002", "OUT003", "OUT004", "OUT005", "OUT006", "OUT007", "OUT008", "OUT009", "OUT010"];
const PERSONA_OPTIONS: Exclude<Persona, "super_admin">[] = ["dispatcher", "loader", "driver", "store_manager", "admin"];
const placeLabel = (persona: NewMember["persona"]) => persona === "loader" ? "Depot" : persona === "store_manager" ? "Outlet" : "Depot";

export function CreateMemberModal({ members, viewer, initialPersona, onClose, onCreate }: { members: Member[]; viewer: "admin" | "super_admin"; initialPersona?: NewMember["persona"]; onClose: () => void; onCreate: (input: NewMember) => void }) {
  const [draft, setDraft] = useState<NewMember>({ name: "", email: "", persona: initialPersona && (initialPersona !== "admin" || viewer === "super_admin") ? initialPersona : "dispatcher", place: "", vehicleType: "" });
  const [reviewing, setReviewing] = useState(false);
  const submitted = useRef(false);
  const email = draft.email.trim().toLowerCase();
  const duplicate = members.some((member) => member.email.toLowerCase() === email);
  const places = draft.persona === "store_manager" ? OUTLETS : DEPOTS;
  const valid = draft.name.trim().length >= 2 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && !duplicate && (draft.persona !== "admin" || viewer === "super_admin") && (draft.persona === "dispatcher" || places.includes(draft.place)) && (draft.persona !== "driver" || draft.vehicleType === "van" || draft.vehicleType === "truck");

  function submitMember() {
    if (!valid || submitted.current) return;
    submitted.current = true;
    onCreate({ name: draft.name.trim(), email, persona: draft.persona, place: draft.persona === "dispatcher" ? "GLOBAL" : draft.place, vehicleType: draft.persona === "driver" ? draft.vehicleType : "" });
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
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setReviewing(false)}>Back to edit</button><button className={primary} disabled={!valid} onClick={submitMember}>Add member</button></div>
    </div> : <div className="space-y-5">
      <p className="text-sm text-go-secondary">Choose the member&apos;s persona and the assignment needed for that role. Only Super admin can add an Admin.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-semibold">Full name<input className={`${field} mt-2`} autoFocus value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="e.g. Ruwan Perera" /></label>
        <label className="text-sm font-semibold">Email<input className={`${field} mt-2`} type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} placeholder="name@example.test" aria-invalid={duplicate} />{duplicate && <span className="mt-1 block text-xs text-go-danger">A member already uses this email.</span>}</label>
      </div>
      <div className="grid gap-4 sm:grid-cols-2"><label className="block text-sm font-semibold">Persona<select className={`${field} mt-2`} value={draft.persona} onChange={(event) => setDraft({ ...draft, persona: event.target.value as NewMember["persona"], place: "", vehicleType: "" })}>{PERSONA_OPTIONS.filter((persona) => persona !== "admin" || viewer === "super_admin").map((persona) => <option key={persona} value={persona}>{labelFor(persona)}</option>)}</select></label>{draft.persona !== "dispatcher" && <label className="block text-sm font-semibold">{placeLabel(draft.persona)}<select className={`${field} mt-2`} value={draft.place} onChange={(event) => setDraft({ ...draft, place: event.target.value })}><option value="">Choose {placeLabel(draft.persona).toLowerCase()}</option>{places.map((place) => <option key={place} value={place}>{place}</option>)}</select></label>}{draft.persona === "driver" && <label className="block text-sm font-semibold">Vehicle type<select className={`${field} mt-2`} value={draft.vehicleType} onChange={(event) => setDraft({ ...draft, vehicleType: event.target.value as NewMember["vehicleType"] })}><option value="">Choose vehicle type</option><option value="van">Van</option><option value="truck">Truck</option></select></label>}</div>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={onClose}>Discard</button><button className={primary} disabled={!valid} onClick={() => setReviewing(true)}>Review member</button></div>
    </div>}
  </Modal>;
}
