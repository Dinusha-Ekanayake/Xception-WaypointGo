/** UI permission proposals derived from booklet pp. 4–7 and 12.
 * These describe the intended product; they do not authorize server requests.
 */
export type Persona = "dispatcher" | "loader" | "driver" | "store_manager";
export type AdminRole = "admin" | "super_admin";
export type ManagedRole = Persona | "admin";
export const personas: { id: Persona; name: string; short: string; description: string; scope: string; icon: string }[] = [
  { id: "dispatcher", name: "Dispatcher", short: "Planning office", description: "Plan the day. Keep every delivery moving.", scope: "Assigned depots", icon: "route" },
  { id: "loader", name: "Loader", short: "Warehouse dock", description: "Load in sequence. Catch issues before departure.", scope: "One assigned depot", icon: "box" },
  { id: "driver", name: "Driver", short: "On the road", description: "Follow the route. Record every delivery.", scope: "Assigned vehicle and dates", icon: "truck" },
  { id: "store_manager", name: "Store manager", short: "Outlet counter", description: "Order goods. Track arrivals. Confirm receipt.", scope: "Assigned outlets", icon: "store" },
];
export const roleName = (role: ManagedRole | AdminRole) => role === "super_admin" ? "Super admin" : role === "admin" ? "Admin" : personas.find(p => p.id === role)!.name;
export type Permission = { id: string; label: string; detail: string; role: Persona; source: "Booklet" | "Product extension"; page?: string; required?: boolean };
export const permissions: Permission[] = [
  { id: "orders.read", label: "View confirmed orders", detail: "See the order queue for assigned depots.", role: "dispatcher", source: "Booklet", page: "4, 7", required: true },
  { id: "orders.close", label: "Close the order queue", detail: "Bring confirmed orders into the next planning run after the cutoff.", role: "dispatcher", source: "Booklet", page: "7", required: true },
  { id: "plans.allocate", label: "Build and adjust allocations", detail: "Assign orders to vehicles and trips within all operating constraints.", role: "dispatcher", source: "Booklet", page: "7, 12", required: true },
  { id: "plans.defer", label: "Defer orders with a reason", detail: "Record why an order moves to the next run and review previous skips.", role: "dispatcher", source: "Booklet", page: "5–6", required: true },
  { id: "delivery.monitor", label: "Monitor delivery progress", detail: "View progress, loading problems and delivery exceptions.", role: "dispatcher", source: "Booklet", page: "6", required: true },
  { id: "capacity.forecast", label: "View capacity forecasts", detail: "Plan future vehicles, drivers and refrigerated capacity.", role: "dispatcher", source: "Booklet", page: "7" },
  { id: "plans.publish", label: "Publish delivery plans", detail: "Release the approved plan to the loading dock and drivers.", role: "dispatcher", source: "Product extension" },
  { id: "fleet.status", label: "Change vehicle availability", detail: "Mark assigned-depot vehicles available or in the workshop.", role: "dispatcher", source: "Product extension" },
  { id: "loading.read", label: "View current loading lists", detail: "See the latest assigned-depot plan and planned stop sequence.", role: "loader", source: "Booklet", page: "6–7", required: true },
  { id: "loading.check", label: "Record loading checks", detail: "Check goods against the load and stop sequence.", role: "loader", source: "Booklet", page: "7", required: true },
  { id: "loading.shortfall", label: "Flag missing or damaged goods", detail: "Report shortfalls before the vehicle leaves the depot.", role: "loader", source: "Booklet", page: "6–7", required: true },
  { id: "loading.release", label: "Release a loaded vehicle", detail: "Confirm readiness for departure after loading checks.", role: "loader", source: "Product extension" },
  { id: "loading.interchange", label: "Request a vehicle change", detail: "Ask the dispatcher to review an interchange; does not approve one.", role: "loader", source: "Product extension" },
  { id: "route.read", label: "View assigned route and stops", detail: "See the run sheet and updated instructions for the assigned vehicle.", role: "driver", source: "Booklet", page: "6–7", required: true },
  { id: "delivery.record", label: "Record delivery outcomes", detail: "Record each stop’s result while safely stopped.", role: "driver", source: "Booklet", page: "6–7", required: true },
  { id: "delivery.proof", label: "Capture proof of delivery", detail: "Attach delivery evidence so disputes have a reliable record.", role: "driver", source: "Booklet", page: "6", required: true },
  { id: "delivery.issue", label: "Report delivery problems", detail: "Share problems encountered on the route with the dispatcher.", role: "driver", source: "Booklet", page: "4, 6", required: true },
  { id: "orders.place", label: "Place and confirm orders", detail: "Submit orders before 16:00; keep Fresh dry and chilled orders separate.", role: "store_manager", source: "Booklet", page: "4, 7", required: true },
  { id: "orders.track", label: "Track orders and arrival times", detail: "See confirmation, scheduling and expected arrival for assigned outlets.", role: "store_manager", source: "Booklet", page: "6", required: true },
  { id: "orders.deferral", label: "View deferral notices", detail: "See which orders moved to a later run and why.", role: "store_manager", source: "Booklet", page: "6", required: true },
  { id: "receipt.confirm", label: "Confirm receipt of goods", detail: "Confirm what arrived at the outlet.", role: "store_manager", source: "Booklet", page: "6–7", required: true },
  { id: "receipt.issue", label: "Report receipt issues", detail: "Report missing, damaged or disputed deliveries for the outlet.", role: "store_manager", source: "Booklet", page: "6–7", required: true },
  { id: "orders.amend", label: "Amend an order", detail: "Change an order only while its lifecycle permits it.", role: "store_manager", source: "Product extension" },
  { id: "orders.cancel", label: "Cancel an order", detail: "Cancel an eligible order with a recorded reason.", role: "store_manager", source: "Product extension" },
];
export type Templates = Record<Persona, string[]>;
export const defaultTemplates = (): Templates => Object.fromEntries(personas.map(p => [p.id, permissions.filter(x => x.role === p.id).map(x => x.id)])) as Templates;
export type Person = { id: string; name: string; email: string; role: ManagedRole; status: "Active" | "Suspended"; scope: string[]; from?: string; until?: string; overrides: Record<string, boolean> };
export type Activity = { id: string; actor: string; action: string; target: string; reason: string; at: string };
export type ConsoleState = { schema: 1; people: Person[]; templates: Templates; activity: Activity[] };
export function canManage(actor: AdminRole, target: ManagedRole): boolean {
  return (actor === "super_admin" && target === "admin") || ((actor === "admin" || actor === "super_admin") && personas.some(p => p.id === target));
}
export function effectivePermissions(person: Person, templates: Templates): string[] {
  if (person.status !== "Active") return [];
  if (person.role === "admin") return [];
  return permissions.filter(p => p.role === person.role && (person.overrides[p.id] ?? templates[person.role as Persona].includes(p.id))).map(p => p.id);
}
export function validatePerson(person: Person, people: Person[], actor: AdminRole): string | null {
  if (!canManage(actor, person.role)) return "Only a super admin can create or manage admins.";
  if (!person.name.trim()) return "Enter a full name.";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(person.email.trim())) return "Enter a valid email address.";
  if (people.some(p => p.id !== person.id && p.email.toLowerCase() === person.email.trim().toLowerCase())) return "An account already uses this email address.";
  if (person.role !== "admin" && !person.scope.length) return "Assign at least one location or vehicle.";
  if ((person.role === "loader" || person.role === "driver") && person.scope.length !== 1) return "Assign exactly one depot or vehicle.";
  if (person.role === "driver") {
    const validDate = (value?: string) => !!value && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
    if (!validDate(person.from) || !validDate(person.until) || person.from! > person.until!) return "Choose a valid assignment start and end date.";
    if (person.status === "Active" && people.some(p => p.id !== person.id && p.status === "Active" && p.role === "driver" && p.scope[0] === person.scope[0] && p.from! <= person.until! && p.until! >= person.from!)) return "This vehicle already has a driver during those dates.";
  }
  return null;
}
