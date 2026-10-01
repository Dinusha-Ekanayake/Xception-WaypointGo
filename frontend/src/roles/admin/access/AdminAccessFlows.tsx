"use client";

import { useState } from "react";
import { Badge, Modal, card, field, primary, secondary } from "./components";
import type { Member } from "./model";

export type NewAdmin = { name: string; email: string; depot: string; reason: string };

const DEPOTS = ["PELIYAGODA", "KANDY", "GALLE"];

export function DemoSignIn({ members, onSignIn }: { members: Member[]; onSignIn: (memberId: string) => void }) {
  const administrators = members.filter((member) => member.active && member.personas.some((persona) => persona === "admin" || persona === "super_admin"));
  administrators.sort((first, second) => Number(second.personas.includes("super_admin")) - Number(first.personas.includes("super_admin")));

  return <main className="access-demo flex min-h-dvh items-center justify-center px-4 py-10 text-[#10251e]">
    <div className="w-full max-w-3xl">
      <div className="mb-7 flex items-center gap-3"><span className="go-mark" aria-label="GO">GO</span><span className="rounded-full bg-white px-3 py-1 text-xs font-semibold text-[#006b57]">Permission UI demo</span></div>
      <section className={`${card} p-6 sm:p-9`}>
        <p className="text-xs font-bold uppercase tracking-[.12em] text-[#227968]">Local preview</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-[-.035em]">Sign in to the access demo</h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-[#526a60]">Choose a sample identity to explore its controls. This preview does not verify a password or create a real server session.</p>
        <div className="mt-7 grid gap-3 sm:grid-cols-2">{administrators.map((member) => {
          const isSuperAdmin = member.personas.includes("super_admin");
          return <button key={member.id} className="flex min-h-28 items-center gap-4 rounded-2xl border border-[#d8e8e1] bg-white p-4 text-left hover:border-[#85d5c3] hover:bg-[#f3fbf8]" onClick={() => onSignIn(member.id)}>
            <span className="grid size-12 shrink-0 place-items-center rounded-full bg-[#d8f5ee] text-sm font-bold text-[#006b57]">{member.name.split(" ").map((part) => part[0]).slice(0, 2).join("")}</span>
            <span className="min-w-0 flex-1"><span className="block font-semibold">{member.name}</span><span className="block truncate text-xs text-[#62766a]">{member.email}</span><span className="mt-2 inline-block"><Badge tone={isSuperAdmin ? "blue" : "neutral"}>{isSuperAdmin ? "Super admin" : "Admin"}</Badge></span></span>
            <span aria-hidden="true" className="text-lg text-[#006b57]">→</span>
          </button>;
        })}</div>
        <p className="mt-6 rounded-xl bg-[#e0f8f0] px-4 py-3 text-xs leading-5 text-[#315a4b]">Demo identities are sample data. Super admin actions here change only this browser session and reset on refresh.</p>
      </section>
    </div>
  </main>;
}

export function CreateAdminModal({ members, onClose, onCreate }: { members: Member[]; onClose: () => void; onCreate: (input: NewAdmin) => void }) {
  const [draft, setDraft] = useState<NewAdmin>({ name: "", email: "", depot: "", reason: "" });
  const [reviewing, setReviewing] = useState(false);
  const email = draft.email.trim().toLowerCase();
  const duplicate = members.some((member) => member.email.toLowerCase() === email);
  const valid = draft.name.trim().length >= 2 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && !duplicate && DEPOTS.includes(draft.depot) && draft.reason.trim().length >= 3;

  return <Modal title={reviewing ? "Review new admin" : "Add admin"} onClose={onClose} wide>
    {reviewing ? <div className="space-y-5">
      <p className="text-sm text-[#526a60]">Confirm this sample admin and their initial place assignment.</p>
      <dl className={`${card} grid gap-4 p-5 sm:grid-cols-2`}>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Name</dt><dd className="mt-1 font-semibold">{draft.name.trim()}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Email</dt><dd className="mt-1 break-all font-semibold">{email}</dd></div>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Persona</dt><dd className="mt-1">Admin</dd></div>
        <div><dt className="text-xs font-bold uppercase text-[#62766a]">Assigned depot</dt><dd className="mt-1">{draft.depot}</dd></div>
        <div className="sm:col-span-2"><dt className="text-xs font-bold uppercase text-[#62766a]">Reason</dt><dd className="mt-1">{draft.reason.trim()}</dd></div>
      </dl>
      <p className="rounded-xl bg-[#e0f8f0] px-4 py-3 text-xs text-[#315a4b]">Saving adds a sample member to this demo. It does not create a real login or grant live admin access.</p>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={() => setReviewing(false)}>Back to edit</button><button className={primary} onClick={() => { if (valid) onCreate({ name: draft.name.trim(), email, depot: draft.depot, reason: draft.reason.trim() }); }}>Add sample admin</button></div>
    </div> : <div className="space-y-5">
      <p className="text-sm text-[#526a60]">Only the Super admin preview can add an Admin sample member.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-semibold">Full name<input className={`${field} mt-2`} autoFocus value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="e.g. Ruwan Perera" /></label>
        <label className="text-sm font-semibold">Email<input className={`${field} mt-2`} type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} placeholder="name@example.test" aria-invalid={duplicate} />{duplicate && <span className="mt-1 block text-xs text-[#a82a1d]">A demo member already uses this email.</span>}</label>
      </div>
      <label className="block text-sm font-semibold">Assigned depot<select className={`${field} mt-2`} value={draft.depot} onChange={(event) => setDraft({ ...draft, depot: event.target.value })}><option value="">Choose a depot</option>{DEPOTS.map((depot) => <option key={depot} value={depot}>{depot}</option>)}</select></label>
      <label className="block text-sm font-semibold">Reason <span className="font-normal text-[#62766a]">(required)</span><textarea className={`${field} mt-2 min-h-24`} value={draft.reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} placeholder="Why is this admin needed?" /></label>
      <div className="flex flex-wrap justify-end gap-2"><button className={secondary} onClick={onClose}>Discard</button><button className={primary} disabled={!valid} onClick={() => setReviewing(true)}>Review admin</button></div>
    </div>}
  </Modal>;
}
