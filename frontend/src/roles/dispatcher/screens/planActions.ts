// What the Plan screen's parts ask the container to do. Each is one command on
// the open draft (or a read of what a command would do); the container sends it
// with the version it saw, reads the day again whatever the answer, says what
// happened, and answers whether it worked. The parts hold no draft id and no version.

export type Place = { orderId: string; vehicleId: string; tripNumber: 1 | 2; reason: string };

export type PlanActions = {
  /** A command is in flight: every control that sends one waits. */
  busy: boolean;
  place: (target: Place) => Promise<boolean>;
  defer: (orderId: string, reason: string) => Promise<boolean>;
  /** With `orderIds`, the trip's stop order after the swap, sent in the same command. */
  swap: (outOrderId: string, inOrderId: string, reason: string, orderIds?: string[]) => Promise<boolean>;
  keepDeferred: (orderIds: string[], reason: string) => Promise<boolean>;
  lock: (orderId: string, locked: boolean) => Promise<boolean>;
  reorder: (tripId: string, orderIds: string[], reason: string) => Promise<boolean>;
  /** The trip holds exactly these orders in this order; an empty list removes it (plan:EditTrip). */
  editTrip: (tripId: string, orderIds: string[], reason: string) => Promise<boolean>;
  moveTrip: (tripId: string, vehicleId: string, reason: string) => Promise<boolean>;
  contactStore: (orderId: string, message: string) => Promise<boolean>;
};
