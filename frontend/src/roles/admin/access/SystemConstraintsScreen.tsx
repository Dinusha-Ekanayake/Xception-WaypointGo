"use client";

import { useEffect, useMemo, useState } from "react";
import { createPlanningRuleSet, fetchCalendarDay, fetchPlanningRules, setCalendarOperatingDay,
  type CalendarDay, type PlanningCatalogue, type PlanningParameter } from "../data/constraints";
import { field, primary, secondary } from "./components";
import { todayInColombo } from "./model";

type Category = "Planning" | "Ordering" | "Loading" | "Execution" | "Receipt and issues" |
  "Notifications" | "Intelligence" | "Integrations" | "Identity and security" |
  "Platform" | "Reference data" | "Fixed rules";

type OtherSetting = { category: Category; label: string; value: string; handling: string; status: string };

// These entries are a directory of where the rule lives. No local value is sent
// to the backend until its owning module has a versioned command and runtime read.
const OTHER: OtherSetting[] = [
  { category: "Ordering", label: "Order cutoff", value: "16:00", handling: "Time", status: "Code and scheduler, requires a shared runtime value" },
  { category: "Ordering", label: "Delivery date horizon", value: "60 days", handling: "Whole number", status: "Code, not yet editable" },
  { category: "Loading", label: "Docks per depot", value: "4", handling: "Whole number per depot", status: "Deployment setting, requires restart" },
  { category: "Loading", label: "Idle trip hold release", value: "30 min", handling: "Duration", status: "Code and loader screen, not yet editable" },
  { category: "Execution", label: "Proof retention", value: "400 days", handling: "Duration", status: "Deployment setting, requires restart" },
  { category: "Execution", label: "Maximum attachment size", value: "3 MB", handling: "File size", status: "Deployment and device code, not yet editable" },
  { category: "Execution", label: "Expected arrival update interval", value: "10 min", handling: "Duration", status: "Code, not yet editable" },
  { category: "Execution", label: "GPS accuracy threshold", value: "200 m", handling: "Distance", status: "Code, not yet editable" },
  { category: "Receipt and issues", label: "Receipt auto-close", value: "24 hours", handling: "Duration", status: "Effective-dated data, write command pending" },
  { category: "Receipt and issues", label: "Issue default severity", value: "By issue type", handling: "Severity choice", status: "Effective-dated data, write command pending" },
  { category: "Receipt and issues", label: "Issue escalation", value: "15 to 1440 min", handling: "Duration by severity", status: "Effective-dated data, write command pending" },
  { category: "Notifications", label: "Push delivery attempts", value: "6", handling: "Whole number", status: "Deployment setting, requires restart" },
  { category: "Notifications", label: "Push routing", value: "Routing version 2", handling: "Published version", status: "Data, publication command pending" },
  { category: "Intelligence", label: "Forecast horizon", value: "10 weeks", handling: "Whole number", status: "Code and screen, not yet editable" },
  { category: "Intelligence", label: "Model scoring timeout", value: "180 s", handling: "Duration", status: "Deployment setting, requires restart" },
  { category: "Integrations", label: "MCP access", value: "Off by default", handling: "On or off", status: "Deployment setting, requires restart" },
  { category: "Integrations", label: "Warehouse catalogue freshness", value: "2 hours", handling: "Duration", status: "Deployment setting, requires restart" },
  { category: "Identity and security", label: "Session absolute lifetime", value: "12 hours", handling: "Duration", status: "Deployment setting, requires restart" },
  { category: "Identity and security", label: "Login lockout", value: "8 failures per identity and address", handling: "Whole number", status: "Deployment setting, requires restart" },
  { category: "Platform", label: "Scheduler", value: "On", handling: "On or off", status: "Deployment setting, requires restart" },
  { category: "Platform", label: "Command receipt retention", value: "30 days", handling: "Duration", status: "Deployment setting, requires restart" },
  { category: "Reference data", label: "Vehicle capacities and weekly fuel", value: "Per vehicle", handling: "Reference version", status: "Change through reference data" },
  { category: "Reference data", label: "Outlet delivery window", value: "Per outlet", handling: "Time window", status: "Change through outlet details" },
  { category: "Fixed rules", label: "Placement safety checks", value: "13 active checks", handling: "Always enforced", status: "Locked: vehicle, depot, brand, temperature, access, whole order, capacity, trip count, time, window and fuel" },
  { category: "Fixed rules", label: "Policy evaluation order", value: "Deny wins", handling: "Always enforced", status: "Locked security rule" },
  { category: "Fixed rules", label: "Secure cookies and token size", value: "Required", handling: "Always enforced", status: "Locked security rule" },
];

const CATEGORIES: Category[] = ["Planning", "Ordering", "Loading", "Execution", "Receipt and issues",
  "Notifications", "Intelligence", "Integrations", "Identity and security", "Platform", "Reference data", "Fixed rules"];

function minutesToTime(value: number) {
  return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}

function timeToMinutes(value: string) {
  const [hours, minutes] = value.split(":").map(Number);
  return String(hours * 60 + minutes);
}

export function SystemConstraintsScreen() {
  const [category, setCategory] = useState<Category>("Planning");
  const [catalogue, setCatalogue] = useState<PlanningCatalogue | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [editing, setEditing] = useState<PlanningParameter | null>(null);
  const [value, setValue] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState(todayInColombo());
  const [reason, setReason] = useState("");
  const [reviewing, setReviewing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [calendarDate, setCalendarDate] = useState(todayInColombo());
  const [calendarDay, setCalendarDay] = useState<CalendarDay | null>(null);
  const [calendarOperating, setCalendarOperating] = useState(false);
  const [calendarReason, setCalendarReason] = useState("");
  const [calendarLoading, setCalendarLoading] = useState(false);

  async function reload() {
    setLoading(true);
    try { setCatalogue(await fetchPlanningRules()); setError(""); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "Planning rules are unavailable."); }
    finally { setLoading(false); }
  }

  useEffect(() => { void reload(); }, []);

  const base = catalogue?.scheduled ?? catalogue?.active;
  const earliestDate = base && base.effectiveFrom >= todayInColombo()
    ? new Date(Date.parse(`${base.effectiveFrom}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10)
    : todayInColombo();
  const groups = useMemo(() => {
    const all = base?.parameters ?? [];
    return Array.from(new Set(all.map((item) => item.category)))
      .map((name) => ({ name, items: all.filter((item) => item.category === name) }));
  }, [base]);

  function begin(item: PlanningParameter) {
    setEditing(item);
    setValue(item.control === "time" ? minutesToTime(item.value) : String(item.value));
    setEffectiveFrom(earliestDate);
    setReason("");
    setReviewing(false);
    setMessage("");
  }

  const numericValue = editing?.control === "time" ? timeToMinutes(value) : value;
  const changed = editing && numericValue !== String(editing.value);
  const valid = changed && reason.trim().length >= 10 && effectiveFrom >= todayInColombo() &&
    !!base && effectiveFrom > base.effectiveFrom && numericValue !== "" &&
    Number.isInteger(Number(numericValue)) && !!editing &&
    Number(numericValue) >= editing.minimum && Number(numericValue) <= editing.maximum;

  async function save() {
    if (!editing || !base || !valid) return;
    setSaving(true);
    setError("");
    try {
      await createPlanningRuleSet({ key: editing.key, value: numericValue,
        effectiveFrom, reason: reason.trim(), expectedVersion: base.rowVersion });
      setEditing(null);
      setReviewing(false);
      setMessage(`${editing.label} scheduled from ${effectiveFrom}. New plans will use the new rule set.`);
      await reload();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the rule set.");
      setReviewing(false);
    } finally { setSaving(false); }
  }

  async function loadCalendar(date: string) {
    setCalendarDate(date);
    setCalendarDay(null);
    if (!date) return;
    setCalendarLoading(true);
    try {
      const day = await fetchCalendarDay(date);
      setCalendarDay(day);
      setCalendarOperating(day.operating);
      setError("");
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Calendar day is unavailable."); }
    finally { setCalendarLoading(false); }
  }

  async function saveCalendar() {
    if (!calendarDay || calendarReason.trim().length < 3 || calendarOperating === calendarDay.operating) return;
    setSaving(true);
    try {
      await setCalendarOperatingDay(calendarDate, calendarOperating, calendarReason.trim());
      setMessage(`${calendarDate} is now ${calendarOperating ? "operating" : "not operating"}.`);
      setCalendarReason("");
      await loadCalendar(calendarDate);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Calendar change failed."); }
    finally { setSaving(false); }
  }

  return <div className="space-y-5">
    <p className="text-sm text-go-secondary">Rules are grouped by where they apply. Planning values below create a new effective-dated rule set. Fixed safety checks stay enforced.</p>
    <div role="tablist" aria-label="Constraint categories" className="flex flex-wrap gap-2">
      {CATEGORIES.map((item) => <button key={item} type="button" role="tab" aria-selected={category === item}
        onClick={() => { setCategory(item); setEditing(null); }}
        className={`rounded-full px-4 py-2 text-sm ${category === item ? "bg-go-teal font-semibold text-white" : "bg-white text-go-ink"}`}>{item}</button>)}
    </div>
    {message && <div role="status" className="rounded-2xl bg-go-mint p-4 text-sm">{message}</div>}
    {error && <div role="alert" className="rounded-2xl border border-go-warning-text bg-white p-4 text-sm">{error}</div>}
    {category === "Planning" ? <>
      {loading && <p className="text-go-secondary">Loading planning rules...</p>}
      {!loading && !base && <p className="rounded-2xl bg-white p-5">No planning rule set is available. Planning cannot use a missing value.</p>}
      {base && <div className="rounded-2xl bg-white p-5 text-sm">
        <strong>Current rule set</strong>: {catalogue?.active?.effectiveFrom ?? "Not yet effective"}
        {catalogue?.scheduled && <p className="mt-1 text-go-secondary">A successor is scheduled from {catalogue.scheduled.effectiveFrom}. Changes below build on that scheduled version.</p>}
        <p className="mt-1 text-go-secondary">New and regenerated plans use the rule set effective on their service date. Published plans retain their stamped rule set until revised.</p>
        <p className="mt-1 text-go-secondary">The scored baseline caps trips and time budgets. Operational changes may tighten those limits, but this screen cannot raise them above the booklet values.</p>
      </div>}
      {groups.map((group) => <section key={group.name} className="rounded-2xl bg-white p-5" aria-label={group.name}>
        <h2 className="mb-3 text-lg font-semibold">{group.name}</h2>
        <div className="divide-y divide-go-rule">{group.items.map((item) => <div key={item.key} className="flex flex-wrap items-center gap-3 py-3 text-sm">
          <div className="min-w-0 flex-1"><strong>{item.label}</strong><p className="text-go-secondary">{item.key === "trips.max.per.vehicle" ? "One vehicle, one service day" : item.unit}</p></div>
          <span className="font-semibold">{item.control === "time" ? minutesToTime(item.value) : item.value} {item.control === "time" ? "" : item.unit}</span>
          {item.editable ? <button type="button" className={secondary} onClick={() => begin(item)}>Change</button> : <span className="rounded-full bg-go-subtle px-3 py-1 text-go-secondary">Locked</span>}
        </div>)}</div>
      </section>)}
      <section className="rounded-2xl bg-white p-5" aria-label="Deferral priority">
        <h2 className="text-lg font-semibold">Deferral priority</h2>
        <p className="mt-1 text-sm text-go-secondary">The priority order is an effective-dated planning policy. Editing its order needs a separate versioned command and is not available in this screen yet.</p>
      </section>
      {editing && <section className="rounded-2xl border border-go-teal bg-white p-5" aria-label="Change planning rule">
        <h2 className="text-lg font-semibold">{reviewing ? "Review rule change" : `Change ${editing.label}`}</h2>
        {!reviewing ? <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <label className="text-sm font-medium">New value
            <input className={`${field} mt-2`} type={editing.control === "time" ? "time" : "number"} step="1"
              min={editing.control === "time" ? undefined : editing.minimum}
              max={editing.control === "time" ? undefined : editing.maximum}
              value={value} onChange={(event) => setValue(event.target.value)} /></label>
          <label className="text-sm font-medium">Effective from (Asia/Colombo)
            <input className={`${field} mt-2`} type="date" min={earliestDate} value={effectiveFrom}
              onChange={(event) => setEffectiveFrom(event.target.value)} /></label>
          <label className="text-sm font-medium sm:col-span-2">Reason
            <textarea className={`${field} mt-2 min-h-24`} maxLength={500} value={reason}
              onChange={(event) => setReason(event.target.value)} placeholder="Why should this rule change?" /></label>
          <p className="text-xs text-go-secondary sm:col-span-2">Allowed: {editing.minimum} to {editing.maximum} {editing.unit}. The new value affects plans with service dates from the effective date. Existing published plans keep their original version.</p>
        </div> : <div className="mt-4 space-y-2 text-sm">
          <p><strong>Before:</strong> {editing.control === "time" ? minutesToTime(editing.value) : editing.value} {editing.unit}</p>
          <p><strong>After:</strong> {value} {editing.control === "time" ? "" : editing.unit}</p>
          <p><strong>Effective:</strong> {effectiveFrom}</p><p><strong>Reason:</strong> {reason}</p>
        </div>}
        <div className="mt-5 flex justify-end gap-2"><button className={secondary} onClick={() => reviewing ? setReviewing(false) : setEditing(null)}>{reviewing ? "Back" : "Cancel"}</button>
          <button className={primary} disabled={!valid || saving} onClick={() => reviewing ? void save() : setReviewing(true)}>{saving ? "Saving..." : reviewing ? "Save rule set" : "Review change"}</button></div>
      </section>}
    </> : <section className="rounded-2xl bg-white p-5" aria-label={category}>
      <h2 className="text-lg font-semibold">{category}</h2>
      <p className="mt-1 text-sm text-go-secondary">Documented baseline values are shown for orientation. The deployed value may differ. Settings without a live command remain read-only.</p>
      {category === "Reference data" && <div className="mt-4 rounded-2xl border border-go-rule bg-go-subtle p-4 text-sm">
        <h3 className="font-semibold">Operating day override</h3>
        <p className="mt-1 text-go-secondary">Choose a date, then set whether the network operates on that day. Planning and order scheduling read the calendar decision. Other server replicas may continue using their cached calendar until they reload reference data.</p>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <label className="font-medium">Date<input className={`${field} mt-1`} type="date" min={todayInColombo()} value={calendarDate} onChange={(event) => void loadCalendar(event.target.value)} /></label>
          <button className={secondary} onClick={() => void loadCalendar(calendarDate)} disabled={calendarLoading}>{calendarLoading ? "Loading..." : "Load day"}</button>
        </div>
        {calendarDay && <div className="mt-4 space-y-3">
          <p>Current: <strong>{calendarDay.operating ? "Operating" : "Not operating"}</strong>{calendarDay.day.generated ? " (generated calendar day)" : ""}</p>
          <label className="flex items-center gap-3 font-medium"><input type="checkbox" checked={calendarOperating} onChange={(event) => setCalendarOperating(event.target.checked)} />Operating day</label>
          <label className="block font-medium">Reason<input className={`${field} mt-1`} value={calendarReason} onChange={(event) => setCalendarReason(event.target.value)} placeholder="Why is this day changing?" maxLength={500} /></label>
          <button className={primary} disabled={saving || calendarReason.trim().length < 3 || calendarOperating === calendarDay.operating} onClick={() => void saveCalendar()}>{saving ? "Saving..." : "Apply calendar override"}</button>
        </div>}
      </div>}
      <div className="mt-4 divide-y divide-go-rule">{OTHER.filter((item) => item.category === category).map((item) => <div key={item.label} className="grid gap-2 py-4 text-sm sm:grid-cols-[1fr_auto]">
        <div><strong>{item.label}</strong><p className="text-go-secondary">{item.status}</p></div>
        <div className="text-left sm:text-right"><span className="font-semibold">{item.value}</span><p className="text-go-secondary">{item.handling}</p></div>
      </div>)}</div>
    </section>}
  </div>;
}
