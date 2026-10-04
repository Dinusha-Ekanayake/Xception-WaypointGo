"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { createPlanningRuleSet, fetchCalendarDay, fetchPlanningRules, setCalendarOperatingDay,
  type CalendarDay, type PlanningCatalogue, type PlanningParameter } from "../data/constraints";
import { field, primary, secondary } from "./components";
import { todayInColombo } from "./model";

type Category = "Planning" | "Ordering" | "Loading" | "Execution" | "Receipt and issues" |
  "Notifications" | "Intelligence" | "Integrations" | "Identity and security" |
  "Platform" | "Reference data" | "Fixed rules";
type ControlKind = "number" | "time" | "duration" | "toggle" | "select" | "range" | "reference" | "locked";
type Availability = "live" | "command" | "deployment" | "managed" | "locked";
type ConstraintItem = {
  id: string; category: Category; label: string; description: string; value: string;
  control: ControlKind; availability: Availability; unit?: string; minimum?: number; maximum?: number;
  options?: string[]; planning?: PlanningParameter;
};

const CATEGORIES: Category[] = ["Planning", "Ordering", "Loading", "Execution", "Receipt and issues",
  "Notifications", "Intelligence", "Integrations", "Identity and security", "Platform", "Reference data", "Fixed rules"];

const CONSTRAINTS: ConstraintItem[] = [
  { id: "ordering.cutoff", category: "Ordering", label: "Order cutoff", description: "Last time stores can place an order for the next eligible service day.", value: "16:00", control: "time", availability: "command" },
  { id: "ordering.horizon", category: "Ordering", label: "Delivery date horizon", description: "Furthest future service date available when placing an order.", value: "60 days", control: "number", availability: "command", unit: "days", minimum: 1, maximum: 365 },
  { id: "loading.docks", category: "Loading", label: "Docks per depot", description: "Concurrent loading positions available at each depot.", value: "4 docks", control: "number", availability: "deployment", unit: "docks", minimum: 1, maximum: 20 },
  { id: "loading.idle", category: "Loading", label: "Idle trip hold release", description: "Time before an inactive loading hold is released.", value: "30 min", control: "duration", availability: "command", unit: "minutes", minimum: 5, maximum: 240 },
  { id: "execution.proof-retention", category: "Execution", label: "Proof retention", description: "How long delivery proof remains available.", value: "400 days", control: "duration", availability: "deployment", unit: "days", minimum: 30, maximum: 2555 },
  { id: "execution.attachment", category: "Execution", label: "Maximum attachment size", description: "Largest proof or issue image accepted from a device.", value: "3 MB", control: "number", availability: "deployment", unit: "MB", minimum: 1, maximum: 20 },
  { id: "execution.arrival-update", category: "Execution", label: "Expected arrival update interval", description: "Minimum movement in expected arrival before stores are notified again.", value: "10 min", control: "duration", availability: "command", unit: "minutes", minimum: 1, maximum: 120 },
  { id: "execution.gps", category: "Execution", label: "GPS accuracy threshold", description: "Position readings above this uncertainty are treated as low quality.", value: "200 m", control: "number", availability: "command", unit: "metres", minimum: 25, maximum: 2000 },
  { id: "receipt.close", category: "Receipt and issues", label: "Receipt auto-close", description: "Time allowed before an open receipt closes automatically.", value: "24 hours", control: "duration", availability: "command", unit: "hours", minimum: 1, maximum: 168 },
  { id: "issues.severity", category: "Receipt and issues", label: "Default issue severity", description: "Starting severity applied for each issue type.", value: "High", control: "select", availability: "command", options: ["Low", "Medium", "High", "Critical"] },
  { id: "issues.escalation", category: "Receipt and issues", label: "Issue escalation times", description: "Minutes before each severity level is escalated.", value: "15 to 1440 min", control: "range", availability: "command", unit: "minutes", minimum: 5, maximum: 2880 },
  { id: "notification.attempts", category: "Notifications", label: "Push delivery attempts", description: "Maximum attempts before a push delivery is abandoned.", value: "6 attempts", control: "number", availability: "deployment", unit: "attempts", minimum: 1, maximum: 20 },
  { id: "notification.routing", category: "Notifications", label: "Push routing", description: "Published routing version that decides who receives each event.", value: "Routing version 2", control: "select", availability: "managed", options: ["Routing version 2"] },
  { id: "intelligence.horizon", category: "Intelligence", label: "Forecast horizon", description: "Number of future ISO weeks included in demand forecasts.", value: "10 weeks", control: "number", availability: "command", unit: "weeks", minimum: 2, maximum: 26 },
  { id: "intelligence.timeout", category: "Intelligence", label: "Model scoring timeout", description: "Maximum wait for one model scoring request.", value: "180 s", control: "duration", availability: "deployment", unit: "seconds", minimum: 10, maximum: 600 },
  { id: "integration.mcp", category: "Integrations", label: "Assistant access", description: "Allows configured assistant clients to connect.", value: "Off", control: "toggle", availability: "deployment" },
  { id: "integration.warehouse", category: "Integrations", label: "Warehouse catalogue freshness", description: "Maximum catalogue age before the warehouse connection is degraded.", value: "2 hours", control: "duration", availability: "deployment", unit: "hours", minimum: 1, maximum: 48 },
  { id: "identity.session", category: "Identity and security", label: "Session absolute lifetime", description: "Maximum session age even while the member stays active.", value: "12 hours", control: "duration", availability: "deployment", unit: "hours", minimum: 1, maximum: 72 },
  { id: "identity.lockout", category: "Identity and security", label: "Login lockout threshold", description: "Failed attempts allowed per identity and address.", value: "8 failures", control: "number", availability: "deployment", unit: "failures", minimum: 3, maximum: 20 },
  { id: "platform.scheduler", category: "Platform", label: "Scheduler", description: "Runs background jobs such as cutoff, retention and forecasting.", value: "On", control: "toggle", availability: "deployment" },
  { id: "platform.receipts", category: "Platform", label: "Command receipt retention", description: "How long idempotency receipts remain available.", value: "30 days", control: "duration", availability: "deployment", unit: "days", minimum: 7, maximum: 365 },
  { id: "reference.calendar", category: "Reference data", label: "Operating day override", description: "Sets whether the network operates on a selected date.", value: "Date specific", control: "toggle", availability: "live" },
  { id: "reference.vehicle", category: "Reference data", label: "Vehicle capacity and fuel", description: "Weight, volume, temperature and weekly fuel limits for a vehicle.", value: "Per vehicle", control: "reference", availability: "managed" },
  { id: "reference.outlet", category: "Reference data", label: "Outlet delivery window", description: "Opening and closing time used for delivery planning.", value: "Per outlet", control: "time", availability: "managed" },
  { id: "fixed.placement", category: "Fixed rules", label: "Placement safety checks", description: "Vehicle, depot, brand, temperature, access, capacity, time, window and fuel checks.", value: "13 checks", control: "locked", availability: "locked" },
  { id: "fixed.policy", category: "Fixed rules", label: "Policy evaluation order", description: "Default deny, matching deny wins, then allow.", value: "Deny wins", control: "locked", availability: "locked" },
  { id: "fixed.security", category: "Fixed rules", label: "Cookie and token security", description: "Secure cookies and cryptographic token sizes.", value: "Required", control: "locked", availability: "locked" },
];

const availabilityLabel: Record<Availability, string> = {
  live: "Live", command: "Needs runtime command", deployment: "Deployment", managed: "Managed elsewhere", locked: "Locked",
};

function minutesToTime(value: number) {
  return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}
function timeToMinutes(value: string) {
  const [hours, minutes] = value.split(":").map(Number);
  return String(hours * 60 + minutes);
}

export function SystemConstraintsScreen() {
  const [category, setCategory] = useState<Category>("Planning");
  const [query, setQuery] = useState("");
  const [catalogue, setCatalogue] = useState<PlanningCatalogue | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [editing, setEditing] = useState<ConstraintItem | null>(null);
  const [value, setValue] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState(todayInColombo());
  const [reason, setReason] = useState("");
  const [reviewing, setReviewing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [calendarDate, setCalendarDate] = useState(todayInColombo());
  const [calendarDay, setCalendarDay] = useState<CalendarDay | null>(null);
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
  const planningItems: ConstraintItem[] = (base?.parameters ?? []).map((item) => ({
    id: item.key, category: "Planning", label: item.label,
    description: item.key === "trips.max.per.vehicle" ? "Maximum trips assigned to one vehicle on one service day." : item.category,
    value: `${item.control === "time" ? minutesToTime(item.value) : item.value}${item.control === "time" ? "" : ` ${item.unit}`}`,
    control: item.control === "locked" ? "locked" : item.control,
    availability: item.editable ? "live" : "locked", unit: item.unit,
    minimum: item.minimum, maximum: item.maximum, planning: item,
  }));
  const allItems = [...planningItems, ...CONSTRAINTS];
  const visible = useMemo(() => allItems.filter((item) => item.category === category &&
    `${item.label} ${item.description} ${item.value}`.toLowerCase().includes(query.trim().toLowerCase())), [allItems, category, query]);

  async function loadCalendar(date: string) {
    setCalendarDate(date); setCalendarDay(null); setCalendarLoading(true);
    try { const day = await fetchCalendarDay(date); setCalendarDay(day); setValue(String(day.operating)); setError(""); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "Calendar day is unavailable."); }
    finally { setCalendarLoading(false); }
  }
  function begin(item: ConstraintItem) {
    setEditing(item);
    const raw = item.planning?.value;
    setValue(item.control === "time" && raw !== undefined ? minutesToTime(raw) : raw !== undefined ? String(raw) : item.control === "toggle" ? (item.value === "On" ? "true" : "false") : item.value.match(/[\d.]+/)?.[0] ?? item.options?.[0] ?? "");
    setEffectiveFrom(earliestDate); setReason(""); setReviewing(false); setMessage("");
    if (item.id === "reference.calendar") void loadCalendar(calendarDate);
  }

  const planning = editing?.planning;
  const numericValue = planning?.control === "time" ? timeToMinutes(value) : value;
  const validPlanning = !!planning && !!base && numericValue !== String(planning.value) && reason.trim().length >= 10 &&
    effectiveFrom > base.effectiveFrom && Number.isInteger(Number(numericValue)) &&
    Number(numericValue) >= planning.minimum && Number(numericValue) <= planning.maximum;
  const calendarChanged = !!(editing?.id === "reference.calendar" && calendarDay &&
    (value === "true") !== calendarDay.operating && reason.trim().length >= 3);

  async function savePlanning() {
    if (!planning || !base || !validPlanning) return;
    setSaving(true);
    try {
      await createPlanningRuleSet({ key: planning.key, value: numericValue, effectiveFrom, reason: reason.trim(), expectedVersion: base.rowVersion });
      setEditing(null); setMessage(`${planning.label} scheduled from ${effectiveFrom}.`); await reload();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Could not save the rule set."); setReviewing(false); }
    finally { setSaving(false); }
  }
  async function saveCalendar() {
    if (!calendarChanged) return;
    setSaving(true);
    try {
      await setCalendarOperatingDay(calendarDate, value === "true", reason.trim());
      setMessage(`${calendarDate} is now ${value === "true" ? "operating" : "not operating"}.`); setEditing(null);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Calendar change failed."); }
    finally { setSaving(false); }
  }

  const counts = CATEGORIES.reduce((result, name) => ({ ...result, [name]: allItems.filter((item) => item.category === name).length }), {} as Record<Category, number>);

  return <div className="space-y-5">
    <p className="text-sm text-go-secondary">Find a constraint by business area, review its current value, then open a focused change panel. Changes that need new runtime support are identified before you edit.</p>
    {message && <div role="status" className="rounded-2xl bg-go-mint p-4 text-sm">{message}</div>}
    {error && <div role="alert" className="rounded-2xl border border-go-warning-text bg-white p-4 text-sm">{error}</div>}
    <div className="grid gap-5 lg:grid-cols-[250px_minmax(0,1fr)]">
      <nav aria-label="Constraint categories" className="grid h-fit grid-cols-2 gap-1 rounded-2xl bg-white p-2 sm:grid-cols-3 lg:block">
        {CATEGORIES.map((name) => <button key={name} type="button" onClick={() => { setCategory(name); setQuery(""); }}
          className={`flex min-h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-sm ${category === name ? "bg-go-mint font-semibold text-go-ink" : "text-go-secondary hover:bg-go-subtle"}`}>
          <span className={`h-2 w-2 rounded-full ${category === name ? "bg-go-teal" : "bg-go-rule"}`} />
          <span className="min-w-0 flex-1">{name}</span><span className="text-xs tabular-nums">{counts[name]}</span>
        </button>)}
      </nav>
      <section className="min-w-0 overflow-hidden rounded-2xl bg-white" aria-labelledby="constraint-category-title">
        <div className="border-b border-go-rule p-5">
          <div className="flex flex-wrap items-start justify-between gap-3"><div><h2 id="constraint-category-title" className="text-xl font-semibold">{category}</h2>
            <p className="mt-1 text-sm text-go-secondary">{category === "Planning" ? "Effective-dated rules used by new and regenerated plans." : "Operational limits and settings owned by this business area."}</p></div>
            <label className="min-w-56 text-sm"><span className="sr-only">Search constraints</span><input className={field} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search this category" /></label>
          </div>
          {category === "Planning" && base && <p className="mt-3 text-xs text-go-secondary">Active from {catalogue?.active?.effectiveFrom ?? "not yet effective"}{catalogue?.scheduled ? ` · Next version ${catalogue.scheduled.effectiveFrom}` : ""}</p>}
        </div>
        {category === "Planning" && loading ? <p className="p-5 text-sm text-go-secondary">Loading planning rules...</p> : <div className="divide-y divide-go-rule">
          <div className="hidden grid-cols-[minmax(0,1fr)_150px_150px_110px] gap-4 bg-go-subtle px-5 py-2 text-xs font-semibold uppercase tracking-wide text-go-secondary md:grid"><span>Constraint</span><span>Current value</span><span>Management</span><span className="text-right">Action</span></div>
          {visible.map((item) => <div key={item.id} className="grid gap-3 px-5 py-4 text-sm md:grid-cols-[minmax(0,1fr)_150px_150px_110px] md:items-center md:gap-4">
            <div className="min-w-0"><h3 className="font-semibold">{item.label}</h3><p className="mt-0.5 text-go-secondary">{item.description}</p></div>
            <div><span className="text-xs text-go-secondary md:hidden">Current value · </span><strong>{item.value}</strong></div>
            <div><span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${item.availability === "live" ? "bg-go-mint text-go-ink" : "bg-go-subtle text-go-secondary"}`}>{availabilityLabel[item.availability]}</span></div>
            <div className="md:text-right"><button type="button" className={secondary} onClick={() => begin(item)}>{item.availability === "locked" ? "View" : item.availability === "managed" ? "Open" : "Change"}</button></div>
          </div>)}
          {!visible.length && <p className="p-8 text-center text-sm text-go-secondary">No constraints match this search.</p>}
        </div>}
      </section>
    </div>
    {editing && <ConstraintPanel item={editing} value={value} setValue={setValue} effectiveFrom={effectiveFrom} setEffectiveFrom={setEffectiveFrom}
      earliestDate={earliestDate} reason={reason} setReason={setReason} reviewing={reviewing} setReviewing={setReviewing}
      saving={saving} validPlanning={validPlanning} calendarDate={calendarDate} calendarDay={calendarDay}
      calendarLoading={calendarLoading} calendarChanged={calendarChanged} loadCalendar={loadCalendar}
      close={() => setEditing(null)} savePlanning={savePlanning} saveCalendar={saveCalendar} />}
  </div>;
}

type PanelProps = {
  item: ConstraintItem; value: string; setValue: (value: string) => void;
  effectiveFrom: string; setEffectiveFrom: (value: string) => void; earliestDate: string;
  reason: string; setReason: (value: string) => void; reviewing: boolean; setReviewing: (value: boolean) => void;
  saving: boolean; validPlanning: boolean; calendarDate: string; calendarDay: CalendarDay | null;
  calendarLoading: boolean; calendarChanged: boolean; loadCalendar: (date: string) => Promise<void>;
  close: () => void; savePlanning: () => Promise<void>; saveCalendar: () => Promise<void>;
};

function ConstraintPanel(props: PanelProps) {
  const { item } = props;
  const canApply = item.planning ? props.validPlanning : item.id === "reference.calendar" ? props.calendarChanged : false;
  return <div className="fixed inset-0 z-50 flex justify-end bg-[#001f1780]" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) props.close(); }}>
    <aside role="dialog" aria-modal="true" aria-labelledby="constraint-editor-title" className="flex h-full w-full max-w-xl flex-col bg-white shadow-2xl">
      <header className="flex items-start gap-4 border-b border-go-rule px-6 py-5"><div className="min-w-0 flex-1"><p className="text-xs font-semibold uppercase tracking-wide text-go-teal">{item.category}</p>
        <h2 id="constraint-editor-title" className="mt-1 text-xl font-semibold">{props.reviewing ? "Review change" : item.label}</h2><p className="mt-1 text-sm text-go-secondary">{item.description}</p></div>
        <button type="button" aria-label="Close constraint editor" className="min-h-11 min-w-11 rounded-full text-xl hover:bg-go-subtle" onClick={props.close}>×</button></header>
      <div className="min-h-0 flex-1 overflow-y-auto p-6">
        {item.availability === "locked" && <Notice title="This rule is locked">This safety or security rule cannot be changed from the admin console.</Notice>}
        {item.availability === "managed" && <Notice title="Managed in its own record">Open the related vehicle, outlet, model or routing version to change this value with its full context.</Notice>}
        {(item.availability === "command" || item.availability === "deployment") && <Notice title={item.availability === "deployment" ? "Requires a deployment" : "Runtime command required"}>The control below shows the correct input for this constraint. Applying it is unavailable until its owner reads a versioned runtime setting.</Notice>}
        {item.id === "reference.calendar" ? <CalendarControl {...props} /> : item.control !== "locked" && item.control !== "reference" && !props.reviewing ? <div className="space-y-5">
          <ConstraintControl item={item} value={props.value} setValue={props.setValue} disabled={item.availability !== "live"} />
          {item.planning && <label className="block text-sm font-medium">Effective from (Asia/Colombo)<input className={`${field} mt-2`} type="date" min={props.earliestDate} value={props.effectiveFrom} onChange={(event) => props.setEffectiveFrom(event.target.value)} /></label>}
          {item.availability === "live" && <label className="block text-sm font-medium">Reason<textarea className={`${field} mt-2 min-h-28`} maxLength={500} value={props.reason} onChange={(event) => props.setReason(event.target.value)} placeholder="Explain why this constraint should change" /></label>}
        </div> : props.reviewing && <div className="space-y-3 text-sm"><p><strong>Current:</strong> {item.value}</p><p><strong>New:</strong> {props.value} {item.control === "time" ? "" : item.unit}</p><p><strong>Effective:</strong> {props.effectiveFrom}</p><p><strong>Reason:</strong> {props.reason}</p></div>}
      </div>
      <footer className="flex items-center justify-between gap-3 border-t border-go-rule bg-white px-6 py-4"><button type="button" className={secondary} onClick={() => props.reviewing ? props.setReviewing(false) : props.close()}>{props.reviewing ? "Back" : "Cancel"}</button>
        {item.availability === "live" ? <button type="button" className={primary} disabled={!canApply || props.saving || (item.id === "reference.calendar" && props.calendarLoading)} onClick={() => item.id === "reference.calendar" ? void props.saveCalendar() : props.reviewing ? void props.savePlanning() : props.setReviewing(true)}>{props.saving ? "Saving..." : item.id === "reference.calendar" ? "Apply override" : props.reviewing ? "Save rule set" : "Review change"}</button> : <span className="text-right text-xs text-go-secondary">{item.availability === "locked" ? "No changes allowed" : item.availability === "managed" ? "Use the related admin page" : item.availability === "deployment" ? "Deployment setting" : "Backend command required"}</span>}
      </footer>
    </aside>
  </div>;
}

function Notice({ title, children }: { title: string; children: ReactNode }) {
  return <div className="mb-5 rounded-2xl bg-go-subtle p-4 text-sm"><h3 className="font-semibold">{title}</h3><p className="mt-1 text-go-secondary">{children}</p></div>;
}

function ConstraintControl({ item, value, setValue, disabled }: { item: ConstraintItem; value: string; setValue: (value: string) => void; disabled: boolean }) {
  if (item.control === "toggle") return <fieldset disabled={disabled}><legend className="text-sm font-medium">New value</legend><div className="mt-2 grid grid-cols-2 gap-2">{["true", "false"].map((option) => <button key={option} type="button" aria-pressed={value === option} onClick={() => setValue(option)} className={`min-h-12 rounded-xl border px-4 ${value === option ? "border-go-teal bg-go-mint font-semibold" : "border-go-rule"}`}>{option === "true" ? "On" : "Off"}</button>)}</div></fieldset>;
  if (item.control === "select") return <label className="block text-sm font-medium">New value<select className={`${field} mt-2`} disabled={disabled} value={value} onChange={(event) => setValue(event.target.value)}>{item.options?.map((option) => <option key={option}>{option}</option>)}</select></label>;
  if (item.control === "range") return <div><label className="block text-sm font-medium">Escalation time<input className={`${field} mt-2`} disabled={disabled} type="number" min={item.minimum} max={item.maximum} value={value} onChange={(event) => setValue(event.target.value)} /></label><p className="mt-2 text-xs text-go-secondary">Set each severity separately when the runtime command is available.</p></div>;
  return <label className="block text-sm font-medium">New value<div className="mt-2 flex items-center gap-2"><input className={field} disabled={disabled} type={item.control === "time" ? "time" : "number"} step="1" min={item.minimum} max={item.maximum} value={value} onChange={(event) => setValue(event.target.value)} />{item.unit && item.control !== "time" && <span className="min-w-20 text-sm text-go-secondary">{item.unit}</span>}</div></label>;
}

function CalendarControl(props: PanelProps) {
  return <div className="space-y-5"><label className="block text-sm font-medium">Date<input className={`${field} mt-2`} type="date" min={todayInColombo()} value={props.calendarDate} onChange={(event) => void props.loadCalendar(event.target.value)} /></label>
    {props.calendarLoading && <p className="text-sm text-go-secondary">Loading calendar day...</p>}
    {props.calendarDay && <><ConstraintControl item={props.item} value={props.value} setValue={props.setValue} disabled={false} /><p className="text-xs text-go-secondary">Current value: {props.calendarDay.operating ? "Operating" : "Not operating"}{props.calendarDay.day.generated ? " · Generated calendar day" : ""}</p><label className="block text-sm font-medium">Reason<textarea className={`${field} mt-2 min-h-28`} maxLength={500} value={props.reason} onChange={(event) => props.setReason(event.target.value)} placeholder="Explain why this date should change" /></label></>}
  </div>;
}
