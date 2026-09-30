import { defaultTemplates, type ConsoleState, type Person } from "./permissions.ts";
const person = (id: string, name: string, role: Person["role"], scope: string[], status: Person["status"] = "Active"): Person => ({ id, name, email: name.toLowerCase().replaceAll(" ", ".") + "@example.com", role, scope, status, overrides: {}, ...(role === "driver" ? { from: "2026-09-27", until: "2026-10-03" } : {}) });
export function initialDemo(): ConsoleState {
  return { schema: 1, templates: defaultTemplates(), people: [
    person("u1", "Amaya Perera", "admin", []),
    person("u2", "Ravindu Silva", "admin", []),
    person("u3", "Nethmi Fernando", "dispatcher", ["Peliyagoda"]),
    person("u4", "Kasun Jayawardena", "dispatcher", ["Kandy"]),
    person("u5", "Dinesh Kumara", "loader", ["Peliyagoda"]),
    person("u6", "Tharushi Silva", "loader", ["Kandy"]),
    person("u7", "Lahiru Bandara", "driver", ["VEH014"]),
    person("u8", "Sajith Perera", "driver", ["VEH022"]),
    person("u9", "Dilini Wijesinghe", "store_manager", ["OUT001", "OUT002"]),
    person("u10", "Chamari Dias", "store_manager", ["OUT083"]),
    person("u11", "Isuru De Silva", "driver", ["VEH031"], "Suspended"),
    person("u12", "Sanduni Fernando", "store_manager", ["OUT107"]),
  ], activity: [{ id: "a1", actor: "Super admin demo", action: "Demo workspace prepared", target: "Waypoint access console", reason: "Fictional accounts for UI review. No production data is connected.", at: "2026-09-27T08:30:00Z" }] };
}
