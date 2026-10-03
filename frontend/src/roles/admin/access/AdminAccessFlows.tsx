"use client";

import { useRef, useState } from "react";
import { Modal, card, field, primary, secondary } from "./components";
import { labelFor, type Member, type Persona } from "./model";
import { DEMO_DEPOTS } from "./fixtures";

export type NewMember = { name: string; email: string; persona: Exclude<Persona, "super_admin">; place: string; vehicleType: "" | "van" | "truck" };

const DEPOTS: readonly string[] = DEMO_DEPOTS;
const OUTLETS = ["OUT-SAMPLE-01", "OUT-SAMPLE-02", "OUT-SAMPLE-03", "OUT-SAMPLE-04"];
const PERSONA_OPTIONS: Exclude<Persona, "super_admin">[] = ["dispatcher", "loader", "driver", "store_manager", "admin"];
const placeLabel = (persona: NewMember["persona"]) => persona === "loader" ? "Depot" : persona === "store_manager" ? "Outlet" : "Depot";

export function DemoSignIn({ members, onSignIn }: { members: Member[]; onSignIn: (memberId: string) => void }) {
  const [staffId, setStaffId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [dark, setDark] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const member = members.find((item) => item.active && item.personas.some((persona) => persona === "admin" || persona === "super_admin") && (item.id.toLowerCase() === staffId.trim().toLowerCase() || item.email.toLowerCase() === staffId.trim().toLowerCase()));
    if (!member || password !== "demo123") { setError("Check the demo staff ID and password."); return; }
    setError("");
    onSignIn(member.id);
  }

  return <main className={`access-demo access-login relative flex min-h-dvh flex-col overflow-hidden ${dark ? "access-login-dark" : ""}`}>
    <header className="relative z-10 flex items-center justify-between px-5 py-2.5"><div className="flex items-center gap-2"><span className="go-mark">GO</span><span className="go-preview-badge">Preview</span></div><button type="button" className="grid size-10 place-items-center rounded-full bg-white text-xl text-[#17352b] shadow-sm" aria-label={dark ? "Use light theme" : "Use dark theme"} onClick={() => setDark((value) => !value)}>{dark ? "☾" : "☼"}</button></header>
    <div className="relative z-10 mx-auto flex w-full max-w-[390px] flex-1 flex-col justify-center px-5 pb-20 pt-4 sm:px-0 sm:pb-28">
      <h1 className="text-[38px] font-medium leading-tight tracking-[-.055em]">Welcome back</h1>
      <p className="mt-1 text-sm">Sign in to open your workspace.</p>
      <form onSubmit={submit} className="mt-6 space-y-3 rounded-[30px] bg-white p-5 shadow-[0_12px_50px_rgba(16,54,44,.035)]">
        <label className="sr-only" htmlFor="demo-staff-id">Staff ID or email</label><input id="demo-staff-id" className="access-login-input w-full rounded-2xl px-5 text-sm outline-none" autoComplete="username" placeholder="Enter your staff ID" value={staffId} onChange={(event) => { setStaffId(event.target.value); setError(""); }} />
        <label className="sr-only" htmlFor="demo-password">Password</label><input id="demo-password" type="password" className="access-login-input w-full rounded-2xl px-5 text-sm outline-none" autoComplete="current-password" placeholder="Enter your password" value={password} onChange={(event) => { setPassword(event.target.value); setError(""); }} />
        <p className="px-1 text-xs">Forgot password? <button type="button" className="font-semibold text-[#006b63] underline" onClick={() => setShowHelp((value) => !value)}>Call support</button></p>
        {showHelp && <p className="rounded-xl bg-[#eef8f5] px-3 py-2 text-xs">This is a mock login. Use a demo staff ID and password shown below.</p>}
        {error && <p role="alert" className="rounded-xl bg-[#fff0ed] px-3 py-2 text-xs text-[#a82a1d]">{error}</p>}
        <button type="submit" className="min-h-12 w-full rounded-2xl bg-[#001f0b] text-sm font-semibold text-white hover:bg-[#084020]">Sign in</button>
      </form>
      <p className="mt-5 text-xs text-[#5d726f]">Demo accounts: Super admin <strong>sa1</strong> or Admin <strong>a1</strong> · password <strong>demo123</strong></p>
      <p className="mt-3 text-xs text-[#728381]">Preview data stays in this tab and resets when you refresh.</p>
    </div>
  </main>;
}

export function CreateMemberModal({ members, viewer, initialPersona, onClose, onCreate }: { members: Member[]; viewer: "admin" | "super_admin"; initialPersona?: NewMember["persona"]; onClose: () => void; onCreate: (input: NewMember) => void }) {
  const [draft, setDraft] = useState<NewMember>({ name: "", email: "", persona: initialPersona && (initialPersona !== "admin" || viewer === "super_admin") ? initialPersona : "dispatcher", place: "", vehicleType: "" });
  const [reviewing, setReviewing] = useState(false);
  const submitted = useRef(false);
  const email = draft.email.trim().toLowerCase();
  const duplicate = members.some((member) => member.email.toLowerCase() === email);
  const places = draft.persona === "store_manager" ? OUTLETS : DEPOTS;
  const valid = draft.name.trim().length >= 2 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && !duplicate && (draft.persona !== "admin" || viewer === "super_admin") && (draft.persona === "dispatcher" || places.includes(draft.place)) && (draft.persona !== "driver" || draft.vehicleType === "van" || draft.vehicleType === "truck");

  return <Modal title={reviewing ? "Review new member" : "Add member"} onClose={onClose} wide>
    {reviewing ? <div className="space-y-5">
      <p className="text-sm text-[#526a60]">Confirm this sample member and their assignment.</p>
      <dl className={`${card} grid gap-4 p-5 sm:grid-cols-2`}>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Name</dt><dd className="mt-1 font-semibold">{draft.name.trim()}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Email</dt><dd className="mt-1 break-all font-semibold">{email}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Persona</dt><dd className="mt-1">{labelFor(draft.persona)}</dd></div>
        {draft.persona !== "dispatcher" && <div><dt className="text-xs font-bold uppercase text-[#62766a]">Assigned {placeLabel(draft.persona).toLowerCase()}</dt><dd className="mt-1">{draft.place}</dd></div>}
        {draft.persona === "driver" && <div><dt className="text-xs font-bold uppercase text-[#62766a]">Vehicle type</dt><dd className="mt-1 capitalize">{draft.vehicleType}</dd></div>}
      </dl>
      <p className="rounded-xl bg-[#e0f8f0] px-4 py-3 text-xs text-[#315a4b]">Saving adds a sample member to this demo. It does not create a real login or change the backend.</p>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setReviewing(false)}>Back to edit</button><button className={primary} disabled={!valid} onClick={() => { if (!valid || submitted.current) return; submitted.current = true; onCreate({ name: draft.name.trim(), email, persona: draft.persona, place: draft.persona === "dispatcher" ? "GLOBAL" : draft.place, vehicleType: draft.persona === "driver" ? draft.vehicleType : "" }); }}>Add sample member</button></div>
    </div> : <div className="space-y-5">
      <p className="text-sm text-[#526a60]">Choose the member&apos;s persona and the assignment needed for that role. Only Super admin can add an Admin.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-semibold">Full name<input className={`${field} mt-2`} autoFocus value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="e.g. Ruwan Perera" /></label>
        <label className="text-sm font-semibold">Email<input className={`${field} mt-2`} type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} placeholder="name@example.test" aria-invalid={duplicate} />{duplicate && <span className="mt-1 block text-xs text-[#a82a1d]">A demo member already uses this email.</span>}</label>
      </div>
      <div className="grid gap-4 sm:grid-cols-2"><label className="block text-sm font-semibold">Persona<select className={`${field} mt-2`} value={draft.persona} onChange={(event) => setDraft({ ...draft, persona: event.target.value as NewMember["persona"], place: "", vehicleType: "" })}>{PERSONA_OPTIONS.filter((persona) => persona !== "admin" || viewer === "super_admin").map((persona) => <option key={persona} value={persona}>{labelFor(persona)}</option>)}</select></label>{draft.persona !== "dispatcher" && <label className="block text-sm font-semibold">{placeLabel(draft.persona)}<select className={`${field} mt-2`} value={draft.place} onChange={(event) => setDraft({ ...draft, place: event.target.value })}><option value="">Choose {placeLabel(draft.persona).toLowerCase()}</option>{places.map((place) => <option key={place} value={place}>{place}</option>)}</select></label>}{draft.persona === "driver" && <label className="block text-sm font-semibold">Vehicle type<select className={`${field} mt-2`} value={draft.vehicleType} onChange={(event) => setDraft({ ...draft, vehicleType: event.target.value as NewMember["vehicleType"] })}><option value="">Choose vehicle type</option><option value="van">Van</option><option value="truck">Truck</option></select></label>}</div>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={onClose}>Discard</button><button className={primary} disabled={!valid} onClick={() => setReviewing(true)}>Review member</button></div>
    </div>}
  </Modal>;
}
