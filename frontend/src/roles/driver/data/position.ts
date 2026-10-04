import { useCallback, useEffect, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import { ExecutionCommandKind, type DemoView, type PositionPoint, type RecordPositionsPayload } from "@shared/domain/types";
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
  /**
   * Demo mode only (issue #231): drive this vehicle to a point, sending the
   * same RecordPositions commands a phone would, stamped with the demo clock.
   * Null when demo mode is off, so nothing of it exists in real use.
   */
  simulate: ((to: LatLon) => void) | null;
  simulating: boolean;
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

  const demoRead = useResource((signal: AbortSignal) => request<DemoView>("/api/demo", { signal }), "driver-demo", 30_000);
  const demo = demoRead.data?.enabled ? demoRead.data : null;
  const flushMs = demo ? Math.max(1_000, demo.positionFlushMs) : FLUSH_MS;
  const [simulating, setSimulating] = useState(false);
  const sim = useRef<number | null>(null);
  const hereRef = useRef<LatLon | null>(null);
  hereRef.current = here;

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
    const timer = window.setInterval(flush, flushMs);
    return () => {
      navigator.geolocation.clearWatch(watch);
      window.clearInterval(timer);
      // The run closed or the driver signed out: send what was kept, keep nothing more.
      flush();
      last.current = null;
    };
  }, [active, answer, vehicleId, flush, flushMs]);

  // The demo drive: straight legs at one point per demo interval, flushed on
  // the demo flush interval below. Ends at the target or when demo mode or the
  // run ends; a real GPS fix is never mixed in because the driver is not moving.
  useEffect(() => {
    if (demo && vehicleId) {
      const timer = window.setInterval(flush, flushMs);
      return () => window.clearInterval(timer);
    }
    return undefined;
  }, [demo, vehicleId, flush, flushMs]);

  useEffect(() => () => {
    if (sim.current !== null) window.clearInterval(sim.current);
  }, []);

  useEffect(() => {
    if ((!demo || !active) && sim.current !== null) {
      window.clearInterval(sim.current);
      sim.current = null;
      setSimulating(false);
    }
  }, [demo, active]);

  const simulate = useCallback(
    (to: LatLon) => {
      if (!demo) return;
      if (sim.current !== null) window.clearInterval(sim.current);
      const from = hereRef.current ?? { lat: to.lat - 0.02, lon: to.lon - 0.02 };
      const steps = 30;
      let i = 0;
      setSimulating(true);
      sim.current = window.setInterval(() => {
        i += 1;
        const t = Math.min(1, i / steps);
        const where = { lat: from.lat + (to.lat - from.lat) * t, lon: from.lon + (to.lon - from.lon) * t };
        const heading = ((Math.atan2(to.lon - from.lon, to.lat - from.lat) * 180) / Math.PI + 360) % 360;
        setState("on");
        setHere({ ...where, heading });
        setTrail((trail) => [...trail, where]);
        buffer.current.push(toPoint({ latitude: where.lat, longitude: where.lon, accuracy: 5, heading }, Date.now() + demo.offsetSeconds * 1000));
        if (t >= 1 && sim.current !== null) {
          window.clearInterval(sim.current);
          sim.current = null;
          setSimulating(false);
          flush();
        }
      }, Math.max(500, demo.simPointIntervalMs));
    },
    [demo, flush],
  );

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
    simulate: demo && active && vehicleId ? simulate : null,
    simulating,
  };
}
