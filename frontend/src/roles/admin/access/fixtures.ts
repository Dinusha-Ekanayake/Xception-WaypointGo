import type { DemoState, Member } from "./model";

export const DEMO_DEPOTS = ["PELIYAGODA", "KANDY"] as const;

export const MEMBERS: Member[] = [];

export const INITIAL_STATE: DemoState = {
  members: [],
  personaSettings: {},
  exceptions: [],
  history: [],
};

export function freshState(): DemoState {
  return {
    members: [],
    personaSettings: {},
    exceptions: [],
    history: [],
  };
}
