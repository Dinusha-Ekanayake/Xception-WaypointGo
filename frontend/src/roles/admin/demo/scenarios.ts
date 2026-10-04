// The scenario deck (issue #231): every demo scenario as data. Each one is
// demonstrated through the real screens and commands; "setup" names the control
// room actions that prepare it, and "needs" says honestly what it depends on.
// Register ids point at EDGE-CASES.md.

export type SetupAction = "before-cutoff" | "after-cutoff" | "early-morning" | "start-vehicles";

export type Scenario = {
  key: string;
  title: string;
  situation: string;
  roles: string[];
  steps: string[];
  expected: string;
  setup: SetupAction[];
  needs?: string;
  register?: string;
};

export const SCENARIOS: Scenario[] = [
  {
    key: "normal-delivery",
    title: "Normal delivery, order to receipt",
    situation: "A Fresh store orders for tomorrow before the 16:00 cutoff. The day runs end to end.",
    roles: ["Store manager", "Dispatcher", "Loader", "Driver"],
    steps: [
      "Store: place a chilled order and see it confirmed with its weight and volume",
      "Clock: after cutoff; Dispatcher: close orders, generate and publish the plan",
      "Loader: check the manifest and release the trip",
      "Start the vehicle; Store: watch it on Track with the arrival time",
      "Driver: record the delivery with a photo; Store: confirm receipt",
    ],
    expected: "Every role sees the same order move from placed to received, with an audit trail.",
    setup: ["before-cutoff", "after-cutoff", "start-vehicles"],
  },
  {
    key: "peak-day-deferrals",
    title: "Demand exceeds capacity",
    situation: "The prepared peak day has 85 orders at Peliyagoda, more than the fleet can carry.",
    roles: ["Dispatcher", "Store manager"],
    steps: [
      "Prepare demo day, then clock: after cutoff",
      "Dispatcher: close orders and generate the plan",
      "Dispatcher: open the deferred orders and read each reason",
      "Store: the deferred store sees the notice and its new day",
    ],
    expected: "Deferred orders carry a reason, an actor and a time; the store is told, never left guessing.",
    setup: ["after-cutoff"],
    register: "Task 2B",
  },
  {
    key: "late-arrival",
    title: "Late delivery and mall window",
    situation: "A vehicle is on the road and a mall store's window is about to close.",
    roles: ["Dispatcher", "Driver", "Store manager"],
    steps: [
      "Start the vehicle, then move the clock past the mall's window",
      "Dispatcher: the live board puts the late stop first",
      "Store: the arrival time moves and the store is told",
      "Driver: a late mall arrival is recorded as failed, not delivered",
    ],
    expected: "Lateness is visible to all three roles; a mall that missed its window is not marked delivered.",
    setup: ["start-vehicles"],
    register: "R-PLN-14",
  },
  {
    key: "loader-shortfall",
    title: "Loader shortfall",
    situation: "One item is short at the dock.",
    roles: ["Loader", "Store manager"],
    steps: ["Loader: mark one line short and release", "Store: Home shows how many units are short before the vehicle arrives", "Store: report the short item at receipt"],
    expected: "The store knows about the shortfall before the vehicle arrives.",
    setup: ["early-morning"],
  },
  {
    key: "cancel",
    title: "Cancel before planning, refused after loading",
    situation: "A store changes its mind.",
    roles: ["Store manager"],
    steps: ["Store: cancel a confirmed order before the cutoff: it is cancelled", "Store: try to cancel an order that is already loaded: refused with the reason", "Store: raise an issue instead"],
    expected: "Cancellation follows the physical world; a loaded order cannot silently disappear.",
    setup: ["before-cutoff"],
    register: "ORD-10",
  },
  {
    key: "order-after-cutoff",
    title: "Order after the cutoff and on a holiday",
    situation: "A store orders late, or for a day the depot does not operate.",
    roles: ["Store manager"],
    steps: ["Clock: after cutoff; Store: place an order: it goes to the next run", "Store: choose a holiday: the delivery date rolls to the next operating day before confirming"],
    expected: "The store sees the real delivery day before confirming.",
    setup: ["after-cutoff"],
    register: "ORD-01, ORD-12",
  },
  {
    key: "out-of-stock",
    title: "Out of stock",
    situation: "The warehouse cannot cover a line.",
    roles: ["Store manager"],
    steps: ["Store: order more than the warehouse holds", "Store: see the available quantity per line and change the order"],
    expected: "Nothing is saved until the store decides; no stock is invented.",
    setup: ["before-cutoff"],
    needs: "The live warehouse must be reachable.",
    register: "STK-01",
  },
  {
    key: "driver-offline",
    title: "Driver offline",
    situation: "The driver loses signal in a valley.",
    roles: ["Driver", "Dispatcher"],
    steps: ["Driver: switch the phone to offline and record a stop", "Driver: reload: the work is still there", "Driver: back online: it syncs; Dispatcher: the stop appears"],
    expected: "No work is lost offline; nothing is merged silently.",
    setup: ["start-vehicles"],
    register: "Offline tier",
  },
  {
    key: "unauthorized",
    title: "Access outside your scope",
    situation: "A store manager tries to read another store's orders.",
    roles: ["Store manager", "Administrator"],
    steps: ["Store: open another outlet's data: refused", "Admin: the audit console shows the denied attempt"],
    expected: "Denied with 403 and audited, never an empty screen.",
    setup: [],
    register: "ORD-09",
  },
];
