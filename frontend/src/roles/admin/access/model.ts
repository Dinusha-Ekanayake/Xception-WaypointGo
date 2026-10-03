export type Persona = "dispatcher" | "loader" | "driver" | "store_manager" | "admin" | "super_admin";
export type Decision = "inherit" | "allow" | "omit" | "deny";
export type Module = "Orders" | "Vehicles" | "Reference" | "Offline work" | "Calendar" | "Planning" | "Issues" | "Live deliveries" | "Forecasts" | "Loading" | "Delivery" | "Receipts" | "Catalogue" | "People & access";

export type Capability = {
  action: string;
  label: string;
  description: string;
  module: Module;
  implemented: boolean;
  relevant: Persona[];
  optionalFor?: Persona[];
};
export type Member = {
  id: string;
  name: string;
  email: string;
  personas: Persona[];
  places: string[];
  active: boolean;
  vehicleType?: "van" | "truck";
  rowVersion?: number;
  depots?: string[];
  outlets?: string[];
  source?: "live" | "demo";
  roleCodes?: string[];
};
export type Exception = { memberId: string; action: string; decision: "allow" | "deny"; reason: string; place: string | null; expires: string | null };
export type Change = { id: number; at: string; actor: string; target: string; action: string; before: string; after: string; reason: string; place: string | null; expires: string | null };
export type DemoState = { members: Member[]; personaSettings: Record<string, Decision>; exceptions: Exception[]; history: Change[] };

export const PERSONAS: { id: Persona; label: string; description: string }[] = [
  { id: "dispatcher", label: "Dispatcher", description: "Plans the day and keeps deliveries moving." },
  { id: "loader", label: "Loader", description: "Checks loads and releases trips from the dock." },
  { id: "driver", label: "Driver", description: "Completes assigned stops and reports road outcomes." },
  { id: "store_manager", label: "Store manager", description: "Places orders and confirms what arrived." },
  { id: "admin", label: "Admin", description: "Manages people, reference data and operations." },
  { id: "super_admin", label: "Super admin", description: "Governs protected access and accounts." },
];
const OPS: Persona[] = ["dispatcher", "loader", "driver", "store_manager"];
const ADMIN: Persona[] = ["admin", "super_admin"];
const ALL: Persona[] = [...OPS, ...ADMIN];
const c = (action: string, label: string, description: string, module: Module, implemented: boolean, relevant: Persona[], optionalFor?: Persona[]): Capability => ({ action, label, description, module, implemented, relevant, optionalFor });

/** Every row represents one catalogue action. Planned rows are visible but never editable. */
export const CAPABILITIES: Capability[] = [
  c("order:Read", "View orders", "See orders in assigned places.", "Orders", true, ["dispatcher", "store_manager", ...ADMIN]),
  c("order:CloseForDay", "Close ordering", "Close a depot's ordering window for the day.", "Orders", true, ["dispatcher"]),
  c("order:Place", "Place orders", "Create an order for an assigned outlet.", "Orders", true, ["store_manager"]),
  c("order:Amend", "Amend orders", "Change an order before allocation.", "Orders", true, ["store_manager"]),
  c("order:Cancel", "Cancel orders", "Cancel an eligible outlet order with a reason.", "Orders", true, ["store_manager"]),
  c("reference:Read", "Read reference data", "See outlets, vehicles, travel and calendar data.", "Reference", true, ["dispatcher", "loader", "driver", ...ADMIN]),
  c("vehicle:SetDayStatus", "Set vehicle day status", "Mark a vehicle available, unavailable or in the workshop.", "Vehicles", true, ["dispatcher", ...ADMIN]),
  c("calendar:Override", "Override calendar day", "Change an operating-day decision with a reason.", "Calendar", true, ["dispatcher", ...ADMIN], ["dispatcher"]),
  c("sync:Read", "View sync status", "See own pending operations and conflicts.", "Offline work", true, ALL),
  c("sync:Submit", "Send offline work", "Submit work saved on a device; each action is checked separately.", "Offline work", true, ["loader", "driver", "store_manager"]),
  c("sync:Acknowledge", "Acknowledge synced work", "Mark an own operation as synchronized.", "Offline work", true, ["loader", "driver", "store_manager"]),
  c("plan:Read", "View plans", "See assigned plans and deferrals.", "Planning", false, ["dispatcher", "loader", "driver"]),
  c("plan:Generate", "Generate draft plan", "Build a draft allocation for a depot.", "Planning", false, ["dispatcher"]),
  c("plan:Override", "Override plan decision", "Change a draft allocation with a reason.", "Planning", false, ["dispatcher"]),
  c("plan:Publish", "Publish plan", "Release a reviewed plan to field teams.", "Planning", false, ["dispatcher"]),
  c("plan:Defer", "Defer order", "Move an order out of a draft plan with a reason.", "Planning", false, ["dispatcher"]),
  c("plan:Replan", "Replan affected trips", "Rebuild affected work after a vehicle change.", "Planning", false, ["dispatcher"]),
  c("plan:Revise", "Revise published plan", "Create a new version from a published plan.", "Planning", false, ["dispatcher"]),
  c("issue:Read", "View issues", "Read linked operational issues.", "Issues", false, OPS),
  c("issue:Raise", "Raise issue", "Report an operational exception.", "Issues", false, OPS),
  c("issue:Assign", "Assign issue", "Give an issue to the right person.", "Issues", false, ["dispatcher"]),
  c("issue:Resolve", "Resolve issue", "Record how an issue was resolved.", "Issues", false, ["dispatcher"]),
  c("delivery:Read", "Monitor deliveries", "See run sheets and delivery progress.", "Live deliveries", false, ["dispatcher", "store_manager", "driver"]),
  c("ml:Read", "View forecasts", "See predictions and operational forecasts.", "Forecasts", false, ["dispatcher"]),
  c("loading:Read", "View trips and manifests", "See ready trips and loading sheets.", "Loading", false, ["loader", "dispatcher"]),
  c("loading:Start", "Start loading", "Begin a loading session.", "Loading", false, ["loader"]),
  c("loading:Check", "Record loading checks", "Check an order on the load sheet.", "Loading", false, ["loader"]),
  c("loading:Shortfall", "Record shortfall", "Report missing or damaged goods.", "Loading", false, ["loader"]),
  c("loading:Release", "Release trip", "Release a checked trip for departure.", "Loading", false, ["loader"]),
  c("loading:Handover", "Hand over trip", "Transfer a loading session to another loader.", "Loading", false, ["loader"]),
  c("loading:RequestInterchange", "Request vehicle interchange", "Ask for a substitute vehicle.", "Loading", false, ["loader"]),
  c("delivery:Start", "Start stop", "Begin an assigned delivery stop.", "Delivery", false, ["driver"]),
  c("delivery:RecordArrival", "Record arrival", "Mark arrival at a stop.", "Delivery", false, ["driver"]),
  c("delivery:Record", "Record delivery outcome", "Record what happened at a stop.", "Delivery", false, ["driver"]),
  c("delivery:CaptureProof", "Capture delivery proof", "Save proof of delivery.", "Delivery", false, ["driver"]),
  c("delivery:ReportFault", "Report road fault", "Report a road or vehicle fault.", "Delivery", false, ["driver"]),
  c("delivery:ReportVehicleStatus", "Report vehicle status", "Update the vehicle's status from the road.", "Delivery", false, ["driver"]),
  c("receipt:Read", "View receipts", "See pending and recorded receipts.", "Receipts", false, ["store_manager"]),
  c("receipt:Confirm", "Confirm full receipt", "Confirm everything arrived.", "Receipts", false, ["store_manager"]),
  c("receipt:ConfirmPartial", "Confirm partial receipt", "Record quantities that arrived.", "Receipts", false, ["store_manager"]),
  c("receipt:Dispute", "Dispute delivery", "Record a discrepancy with a reason.", "Receipts", false, ["store_manager"]),
  c("warehouse:ReadCatalogue", "View product catalogue", "Read cached product information.", "Catalogue", false, ["store_manager", "dispatcher"]),
  c("reference:Import", "Publish reference data", "Stage and publish a reference version.", "Reference", true, ADMIN),
  c("iam:AssignDriver", "Assign driver", "Assign a driver to a vehicle for a period.", "People & access", true, ADMIN),
  c("iam:AttachPolicy", "Attach policy", "Attach a policy to a member or persona.", "People & access", true, ADMIN),
  c("iam:CreatePolicy", "Create policy", "Author a policy document.", "People & access", true, ADMIN),
  c("iam:CreateUser", "Create member", "Create an account.", "People & access", true, ADMIN),
  c("iam:DisableUser", "Disable member", "Disable an account and end its sessions.", "People & access", true, ADMIN),
  c("iam:GrantScope", "Grant place access", "Add a depot or outlet assignment.", "People & access", true, ADMIN),
  c("iam:ManagePermission", "Manage capability", "Set a persona or member permission decision.", "People & access", true, ADMIN),
  c("iam:ReadPolicy", "View policies", "Read policies and attachments.", "People & access", true, ADMIN),
  c("iam:ResetPassword", "Reset password", "Set a new password and end old sessions.", "People & access", true, ADMIN),
  c("iam:RevokeScope", "Revoke place access", "Remove a depot or outlet assignment.", "People & access", true, ADMIN),
  c("iam:UpdateUser", "Update member", "Change account details.", "People & access", true, ADMIN),
  c("audit:Read", "Read audit history", "Review recorded operational decisions.", "People & access", false, ADMIN),
  c("iam:ChangeRole", "Change member role", "Change a member's persona and rotate sessions.", "People & access", false, ADMIN),
  c("iam:DetachPolicy", "Detach policy", "Remove a policy attachment.", "People & access", false, ADMIN),
  c("iam:RegisterDevice", "Register device", "Register a member device.", "People & access", false, ADMIN),
  c("iam:RetireDevice", "Retire device", "Retire a member device.", "People & access", false, ADMIN),
  c("issue:Cancel", "Cancel issue", "Cancel an issue raised in error.", "Issues", false, ["dispatcher"]),
  c("issue:Close", "Close issue", "Close a resolved issue.", "Issues", false, ["dispatcher"]),
  c("issue:RecordReplacement", "Record replacement", "Record a replacement for a shortfall.", "Issues", false, ["dispatcher"]),
  c("issue:ScheduleRedelivery", "Schedule redelivery", "Request a redelivery.", "Issues", false, ["dispatcher"]),
  c("ml:ActivateModel", "Activate forecast model", "Activate a model version.", "Forecasts", false, ADMIN),
  c("ml:RegisterModel", "Register forecast model", "Register a model version.", "Forecasts", false, ADMIN),
  c("ml:RetireModel", "Retire forecast model", "Retire a model version.", "Forecasts", false, ADMIN),
  c("notification:MarkRead", "Mark notification read", "Mark own notifications as read.", "Offline work", false, OPS),
  c("notification:Read", "Read notifications", "Read own inbox.", "Offline work", false, OPS),
  c("notification:Subscribe", "Subscribe to notifications", "Subscribe own device to notifications.", "Offline work", false, OPS),
  c("notification:Unsubscribe", "Unsubscribe notifications", "Unsubscribe own device.", "Offline work", false, OPS),
  c("platform:ReplayEvent", "Replay failed event", "Replay a failed platform event.", "People & access", false, ADMIN),
  c("sync:Discard", "Discard sync conflict", "Discard a conflicting operation with a reason.", "Offline work", false, ["dispatcher"]),
  c("sync:Resolve", "Resolve sync conflict", "Reapply an operation after conflict review.", "Offline work", false, ["dispatcher"]),
  c("warehouse:DiscardInbound", "Discard inbound event", "Discard a quarantined warehouse event.", "Catalogue", false, ADMIN),
  c("warehouse:Reconcile", "Reconcile warehouse", "Reconcile orders with warehouse state.", "Catalogue", false, ADMIN),
  c("warehouse:ReplayInbound", "Replay inbound event", "Replay a quarantined warehouse event.", "Catalogue", false, ADMIN),
];

export const BASELINE: Record<Persona, string[]> = {
  dispatcher: ["order:Read", "order:CloseForDay", "reference:Read", "vehicle:SetDayStatus", "sync:Read"],
  loader: ["reference:Read", "sync:Submit", "sync:Acknowledge", "sync:Read"],
  driver: ["reference:Read", "sync:Submit", "sync:Acknowledge", "sync:Read"],
  store_manager: ["order:Place", "order:Amend", "order:Cancel", "order:Read", "sync:Submit", "sync:Acknowledge", "sync:Read"],
  admin: ["calendar:Override", "iam:AssignDriver", "iam:AttachPolicy", "iam:CreatePolicy", "iam:CreateUser", "iam:DisableUser", "iam:GrantScope", "iam:ManagePermission", "iam:ReadPolicy", "iam:ResetPassword", "iam:RevokeScope", "iam:UpdateUser", "reference:Import", "reference:Read", "vehicle:SetDayStatus", "order:Read", "sync:Read"],
  super_admin: ["calendar:Override", "iam:AssignDriver", "iam:AttachPolicy", "iam:CreatePolicy", "iam:CreateUser", "iam:DisableUser", "iam:GrantScope", "iam:ManagePermission", "iam:ReadPolicy", "iam:ResetPassword", "iam:RevokeScope", "iam:UpdateUser", "reference:Import", "reference:Read", "vehicle:SetDayStatus", "order:Read", "sync:Read"],
};

export function personaChoice(state: DemoState, persona: Persona, action: string): Decision {
  return state.personaSettings[`${persona}:${action}`] ?? "inherit";
}
export function personaAllows(state: DemoState, persona: Persona, action: string): boolean {
  const choice = personaChoice(state, persona, action);
  return choice === "allow" || (choice === "inherit" && BASELINE[persona].includes(action));
}
export function activeException(state: DemoState, memberId: string, action: string): Exception | undefined {
  const value = state.exceptions.find((item) => item.memberId === memberId && item.action === action);
  return value && (!value.expires || value.expires >= todayInColombo()) ? value : undefined;
}
export function todayInColombo(): string { return new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Colombo" }); }
export function effective(state: DemoState, member: Member, capability: Capability): { allowed: boolean; places: string[]; source: string } {
  if (!capability.implemented) return { allowed: false, places: [], source: "Coming later" };
  const deniedBy = member.personas.find((persona) => personaChoice(state, persona, capability.action) === "deny");
  if (deniedBy) return { allowed: false, places: [], source: `Blocked by ${labelFor(deniedBy)}` };
  if (!member.places.length) return { allowed: false, places: [], source: "No assigned place" };
  const exception = activeException(state, member.id, capability.action);
  const grantingPersonas = member.personas.filter((persona) => personaAllows(state, persona, capability.action));
  const places = member.places.filter((place) => {
    if (exception?.decision === "deny" && (!exception.place || exception.place === place)) return false;
    return grantingPersonas.length > 0 || exception?.decision === "allow" && (!exception.place || exception.place === place);
  });
  if (!places.length) return { allowed: false, places, source: exception?.decision === "deny" ? "Blocked for this member" : "No grant" };
  const scoped = places.length < member.places.length ? ` in ${places.join(", ")}` : "";
  const source = grantingPersonas.length > 0 ? `From ${grantingPersonas.map(labelFor).join(" + ")}${scoped}` : `Allowed for this member${scoped || (exception?.place ? ` in ${exception.place}` : "")}`;
  return { allowed: true, places, source };
}
export function labelFor(persona: Persona): string { return PERSONAS.find((item) => item.id === persona)!.label; }
export function personaCounts(state: DemoState, persona: Persona) {
  const relevant = CAPABILITIES.filter((item) => item.relevant.includes(persona));
  return {
    active: relevant.filter((item) => item.implemented && personaAllows(state, persona, item.action) && personaChoice(state, persona, item.action) !== "deny").length,
    optional: relevant.filter((item) => item.implemented && !personaAllows(state, persona, item.action) && item.optionalFor?.includes(persona) && personaChoice(state, persona, item.action) !== "deny").length,
    restricted: relevant.filter((item) => item.implemented && personaChoice(state, persona, item.action) === "deny").length,
    later: relevant.filter((item) => !item.implemented).length,
  };
}
