import type { DemoState, Member, Persona } from "./model";

export const DEMO_DEPOTS = ["PELIYAGODA", "KANDY"] as const;

const member = (id: string, name: string, persona: Persona, place: string, extra?: Persona): Member => ({
  id, name, email: `${name.toLowerCase().replaceAll(" ", ".")}@example.test`,
  personas: extra ? [persona, extra] : [persona], places: place ? [place] : [], active: true,
});

export const MEMBERS: Member[] = [
  member("d1", "Nimali Perera", "dispatcher", "PELIYAGODA"),
  member("d2", "Kavindu Silva", "dispatcher", "KANDY"),
  member("d3", "Tharushi Jayasinghe", "dispatcher", "PELIYAGODA"),
  member("d4", "Ruwani Fernando", "dispatcher", "PELIYAGODA"),
  member("d5", "Isuru Wijeratne", "dispatcher", "KANDY"),
  member("d6", "Sahani Dias", "dispatcher", "PELIYAGODA", "loader"),
  member("l1", "Ravi Fernando", "loader", "PELIYAGODA"),
  member("l2", "Kasun Bandara", "loader", "KANDY"),
  member("l3", "Dilmi Ranasinghe", "loader", "KANDY"),
  member("l4", "Akila Perera", "loader", "PELIYAGODA"),
  member("l5", "Nethmi Gunasekara", "loader", "KANDY"),
  member("l6", "Mihira Senanayake", "loader", ""),
  member("r1", "Amal Silva", "driver", "PELIYAGODA"),
  member("r2", "Fathima Rizwan", "driver", "KANDY"),
  member("r3", "Dinuka Samarakoon", "driver", "PELIYAGODA"),
  member("r4", "Shehan Mendis", "driver", "PELIYAGODA"),
  member("r5", "Maleesha Iqbal", "driver", "KANDY"),
  member("r6", "Nuwan Pathirana", "driver", "PELIYAGODA"),
  member("r7", "Amani Hassan", "driver", "KANDY"),
  member("r8", "Dinesh Kumara", "driver", "KANDY"),
  member("s1", "Ayesha Hassan", "store_manager", "OUT-SAMPLE-01"),
  member("s2", "Chamari Silva", "store_manager", "OUT-SAMPLE-02"),
  member("s3", "Gayan Wickramasinghe", "store_manager", "OUT-SAMPLE-03"),
  member("s4", "Meena Raj", "store_manager", "OUT-SAMPLE-04"),
  member("a1", "Devika Senanayake", "admin", "PELIYAGODA"),
  member("a2", "Mahesh de Alwis", "admin", "KANDY"),
  member("sa1", "Local Super Admin", "super_admin", "GLOBAL"),
];

export const INITIAL_STATE: DemoState = {
  members: MEMBERS,
  personaSettings: {},
  exceptions: [
    { memberId: "s2", action: "order:Cancel", decision: "deny", reason: "Cancellation requires the outlet lead", place: null, expires: null },
    { memberId: "d3", action: "calendar:Override", decision: "allow", reason: "Prior cover assignment", place: "PELIYAGODA", expires: "2025-12-31" },
  ],
  history: [
    { id: 1, at: "2026-09-30T10:15:00+05:30", actor: "Demo admin", target: "Chamari Silva", action: "order:Cancel", before: "Use persona access", after: "Block for this member", reason: "Cancellation requires the outlet lead", place: null, expires: null },
  ],
};

export function freshState(): DemoState {
  return { members: MEMBERS.map((item) => ({ ...item, personas: [...item.personas], places: [...item.places] })), personaSettings: {}, exceptions: INITIAL_STATE.exceptions.map((item) => ({ ...item })), history: INITIAL_STATE.history.map((item) => ({ ...item })) };
}
