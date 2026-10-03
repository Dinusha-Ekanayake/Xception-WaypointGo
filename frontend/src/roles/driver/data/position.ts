import { useCallback, useEffect, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ExecutionCommandKind, type PositionPoint, type RecordPositionsPayload } from "@shared/domain/types";
import { keepFix, type LatLon } from "@shared/ui/map/geo";
import { batches, toPoint } from "./points.ts";
import type { DriverGateway } from "./gateway.ts";

// The phone's position while a run is open (issue #161, D2). Foreground only:
// a closed or backgrounded app records nothing, and the dispatcher sees "Last
// seen". Points go through the same durable queue as every driver write, so a
// trail recorded with no signal survives a reload and arrives in order.
// Nothing here is logged: a position is personal data.

export type LocationState = "off" | "on" | "denied" | "unsupported";

const FLUSH_MS = 60_000;
const FLUSH_POINTS = 20;
const CONSENT_KEY = "waypoint.driver.location";

function consented(): boolean | null {
  try {
    const v = window.localStorage.getItem(CONSENT_KEY);
    return v === null ? null : v === "yes";
  } catch {
    return null;
  }
}

function remember(yes: boolean): void {
  try {
    window.localStorage.setItem(CONSENT_KEY, yes ? "yes" : "no");
  } catch {
    // Private mode: ask again next time.
  }
}

export type PositionRecorder = {
  state: LocationState;
  /** True until the driver has answered once. */
  needsConsent: boolean;
  here: (LatLon & { heading: number | null }) | null;
  trail: LatLon[];
  allow: () => void;
  decline: () => void;
};

export function usePositionRecorder(gateway: DriverGateway, vehicleId: string | null, tripId: string | null, active: boolean): PositionRecorder {
  const [state, setState] = useState<LocationState>("off");
  const [answer, setAnswer] = useState<boolean | null>(null);
  const [here, setHere] = useState<PositionRecorder["here"]>(null);
  const [trail, setTrail] = useState<LatLon[]>([]);
  const buffer = useRef<PositionPoint[]>([]);
  const last = useRef<{ at: number; where: LatLon } | null>(null);
  const target = useRef({ vehicleId, tripId });
  target.current = { vehicleId, tripId };

  useEffect(() => setAnswer(consented()), []);

  const flush = useCallback(() => {
    const points = buffer.current;
    const { vehicleId: vehicle, tripId: trip } = target.current;
    if (points.length === 0 || !vehicle) return;
    buffer.current = [];
    for (const batch of batches(points)) {
      const payload: RecordPositionsPayload = { vehicleId: vehicle, ...(trip ? { tripId: trip } : {}), points: batch };
      void gateway.queue(newCommand(ExecutionCommandKind.recordPositions, payload));
    }
  }, [gateway]);

  useEffect(() => {
    if (!active || answer !== true || !vehicleId) {
      setState(answer === false ? "denied" : "off");
      return;
    }
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setState("unsupported");
      return;
    }
    const watch = navigator.geolocation.watchPosition(
      (pos) => {
        setState("on");
        const where = { lat: pos.coords.latitude, lon: pos.coords.longitude };
        setHere({ ...where, heading: pos.coords.heading ?? null });
        if (!keepFix(last.current, pos.timestamp, where)) return;
        last.current = { at: pos.timestamp, where };
        buffer.current.push(toPoint(pos.coords, pos.timestamp));
        setTrail((t) => [...t, where]);
        if (buffer.current.length >= FLUSH_POINTS) flush();
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          remember(false);
          setAnswer(false);
          setState("denied");
        }
      },
      { enableHighAccuracy: true, maximumAge: 10_000 },
    );
    const timer = window.setInterval(flush, FLUSH_MS);
    return () => {
      navigator.geolocation.clearWatch(watch);
      window.clearInterval(timer);
      // The run closed or the driver signed out: send what was kept, keep nothing more.
      flush();
      last.current = null;
    };
  }, [active, answer, vehicleId, flush]);

  return {
    state,
    needsConsent: active && answer === null,
    here,
    trail,
    allow: () => {
      remember(true);
      setAnswer(true);
    },
    decline: () => {
      remember(false);
      setAnswer(false);
    },
  };
}
