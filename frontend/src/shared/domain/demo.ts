// Mirror of the demo module contract (issue #231): DemoView and the demo:*
// command kinds. Demo mode is off by default and, when off, changes nothing.

export type DemoView = {
  enabled: boolean;
  now: string;
  offsetSeconds: number;
  banner: boolean;
  simPointIntervalMs: number;
  positionFlushMs: number;
  speed: number;
  rowVersion: number;
};

/** A demo action from the run log; the server returns its column names. */
export type DemoRunView = {
  id: string;
  scenario_key: string;
  actor: string;
  reason: string;
  outcome: string;
  started_at: string;
};

export const DemoCommandKind = {
  Enable: "demo:Enable",
  Disable: "demo:Disable",
  SetClock: "demo:SetClock",
  UpdateSettings: "demo:UpdateSettings",
  ResetDay: "demo:ResetDay",
  StartSimulation: "demo:StartSimulation",
  ControlSimulations: "demo:ControlSimulations",
} as const;

/** A simulated vehicle; the server returns its column names. */
export type DemoSimulationView = {
  id: string;
  vehicle_id: string;
  service_date: string;
  trip_id: string | null;
  tick: number;
  waypoints: number;
  status: "running" | "paused" | "stopped" | "finished" | "failed";
  failure: string | null;
  started_at: string;
};
