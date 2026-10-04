"use client";

import { useCallback, useRef, useState } from "react";
import { newCommand, send, type Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import {
  VehicleCommandKind,
  type SetVehicleDayStatus,
  type SetVehicleDayStatusResult,
} from "@shared/domain/types";

// vehicle:SetDayStatus through POST /api/commands. A retry after a network
// failure resends the same command id, so the server answers from its receipt
// instead of applying the change twice. A changed payload is a new command.

export type SubmitState =
  | { phase: "idle" }
  | { phase: "sending" }
  | { phase: "failed"; error: ApiError | Error }
  | { phase: "done"; result: SetVehicleDayStatusResult; replayed: boolean };

function samePayload(a: SetVehicleDayStatus, b: SetVehicleDayStatus): boolean {
  return a.vehicleId === b.vehicleId && a.status === b.status && a.serviceDate === b.serviceDate && a.reason === b.reason;
}

export function useSetDayStatus(): {
  state: SubmitState;
  submit: (payload: SetVehicleDayStatus) => Promise<boolean>;
  reset: () => void;
} {
  const [state, setState] = useState<SubmitState>({ phase: "idle" });
  const pending = useRef<Command<SetVehicleDayStatus> | null>(null);

  const submit = useCallback(async (payload: SetVehicleDayStatus) => {
    const previous = pending.current;
    const command =
      previous && samePayload(previous.payload, payload)
        ? previous
        : newCommand(VehicleCommandKind.setDayStatus, payload, null);
    pending.current = command;
    setState({ phase: "sending" });
    try {
      const ack = await send<SetVehicleDayStatusResult>(command);
      pending.current = null;
      setState({ phase: "done", result: ack.result, replayed: ack.replayed });
      return true;
    } catch (failure) {
      // A rule rejection will not change on retry; forget the command so a
      // corrected payload goes out under a fresh id.
      if (failure instanceof ApiError && !failure.isRetryable) pending.current = null;
      setState({ phase: "failed", error: failure instanceof Error ? failure : new Error(String(failure)) });
      return false;
    }
  }, []);

  const reset = useCallback(() => {
    pending.current = null;
    setState({ phase: "idle" });
  }, []);

  return { state, submit, reset };
}
