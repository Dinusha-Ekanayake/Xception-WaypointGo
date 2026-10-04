import { useCallback, useEffect, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import { ExecutionCommandKind, type DemoView, type PositionPoint, type RecordPositionsPayload } from "@shared/domain/types";
import { SAMPLE_MS, takeSample, travelBearing, type LatLon } from "@shared/ui/map/geo";
import { batches, toPoint } from "./points.ts";
import type { DriverGateway } from "./gateway.ts";
import type { TrailPoint } from "@shared/ui/map/trail";

// The phone's position while a trip is open (issue #161, R-EXE-23): one fix
// every five seconds from Start run until the vehicle is back at the depot
// (see recording.ts for when). Foreground only: a closed or backgrounded app
// records nothing, and the dispatcher sees "Last seen"; while recording, the
// screen is kept awake so the phone does not sleep in its cradle. Points go
// through the same durable queue as every driver write, sent every five seconds
// so the dispatcher's map moves as the vehicle does; a trail recorded with no
// signal survives a reload and arrives in order.
// Nothing here is logged: a position is personal data.

export type LocationState = "off" | "on" | "denied" | "unsupported";

const FLUSH_MS = SAMPLE_MS;
const FLUSH_POINTS = 20;

type Fix = { coords: GeolocationCoordinates; at: number };
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

/**
 * Keeps the screen on while recording, where the browser allows it (Screen Wake
 * Lock), and takes it again when the driver comes back to the app, since the
 * browser drops it whenever the page is hidden. @return what lets the screen sleep
 */
function keepAwake(): () => void {
  type Sentinel = { release: () => Promise<void> };
  const wake = (navigator as Navigator & { wakeLock?: { request: (type: "screen") => Promise<Sentinel> } }).wakeLock;
  if (!wake) return () => undefined;
  let lock: Sentinel | null = null;
  let done = false;
  const take = () => {
    if (done || document.visibilityState !== "visible") return;
    wake.request("screen").then(
      (l) => {
        if (done) void l.release().catch(() => undefined);
        else lock = l;
      },
      () => undefined,
    );
  };
  take();
  document.addEventListener("visibilitychange", take);
  return () => {
    done = true;
    document.removeEventListener("visibilitychange", take);
    if (lock) void lock.release().catch(() => undefined);
  };
}

export type PositionRecorder = {
  state: LocationState;
  /** True until the driver has answered once. */
  needsConsent: boolean;
  here: (LatLon & { heading: number | null }) | null;
  /** What this phone kept for the current trip, stamped as it was sent. */
  trail: TrailPoint[];
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
  const [trail, setTrail] = useState<TrailPoint[]>([]);
  const buffer = useRef<PositionPoint[]>([]);
  const lastKept = useRef<number | null>(null);
  const latestFix = useRef<Fix | null>(null);
  const recent = useRef<LatLon[]>([]);
  const target = useRef({ vehicleId, tripId });
  target.current = { vehicleId, tripId };

  useEffect(() => setAnswer(consented()), []);

  // A new trip starts a new trail: the last trip's line is never drawn on this one.
  useEffect(() => {
    setTrail([]);
    setHere(null);
  }, [tripId]);

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
    // While the demo drives this vehicle, the phone's real fixes would pull it back.
    if (simulating) return;
    if (!active || answer !== true || !vehicleId) {
      setState(answer === false ? "denied" : "off");
      return;
    }
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setState("unsupported");
      return;
    }
    // The watch only remembers the newest fix; the five-second tick below decides what is kept.
    const heard = (pos: GeolocationPosition) => {
      setState("on");
      if (latestFix.current && pos.timestamp <= latestFix.current.at) return;
      latestFix.current = { coords: pos.coords, at: pos.timestamp };
      const where = { lat: pos.coords.latitude, lon: pos.coords.longitude };
      setHere((before) => ({ ...where, heading: pos.coords.heading ?? before?.heading ?? null }));
    };
    const failed = (err: GeolocationPositionError) => {
      if (err.code === err.PERMISSION_DENIED) {
        remember(false);
        setAnswer(false);
        setState("denied");
      }
    };
    const watch = navigator.geolocation.watchPosition(heard, failed, { enableHighAccuracy: true, maximumAge: 0 });
    const sample = (ask = true) => {
      // A phone standing still may stop reporting through the watch: ask it outright,
      // so a vehicle waiting at a store still sends a fix every five seconds.
      if (ask && (!latestFix.current || Date.now() - latestFix.current.at > SAMPLE_MS)) {
        navigator.geolocation.getCurrentPosition(heard, failed, { enableHighAccuracy: true, maximumAge: 0, timeout: SAMPLE_MS - 500 });
      }
      const fix = latestFix.current;
      if (!fix || !takeSample(lastKept.current, fix.at, Date.now())) return;
      lastKept.current = fix.at;
      const where = { lat: fix.coords.latitude, lon: fix.coords.longitude };
      buffer.current.push(toPoint(fix.coords, fix.at));
      setTrail((t) => [...t, { ...where, at: fix.at }]);
      recent.current = [...recent.current.slice(-11), where];
      // The phone's compass is often missing in a moving car; the way it moved is not (R-EXE-22).
      const moved = travelBearing(recent.current) ?? fix.coords.heading ?? null;
      if (moved !== null) setHere((h) => (h ? { ...h, heading: moved } : h));
      if (buffer.current.length >= FLUSH_POINTS) flush();
    };
    const sampler = window.setInterval(() => sample(), SAMPLE_MS);
    const timer = window.setInterval(flush, flushMs);
    const awake = keepAwake();
    return () => {
      navigator.geolocation.clearWatch(watch);
      window.clearInterval(sampler);
      window.clearInterval(timer);
      awake();
      // The trip ended or the driver signed out: send what was kept, keep nothing more.
      sample(false);
      flush();
      lastKept.current = null;
      latestFix.current = null;
      recent.current = [];
    };
  }, [active, answer, vehicleId, flush, flushMs, simulating]);

  // The demo drive: straight legs at one point per demo interval, flushed on
  // the demo flush interval below. Ends at the target or when demo mode or the
  // run ends; the GPS watch above is paused meanwhile, so no real fix is mixed in.
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
        const east = (to.lon - from.lon) * Math.cos((from.lat * Math.PI) / 180);
        const heading = ((Math.atan2(east, to.lat - from.lat) * 180) / Math.PI + 360) % 360;
        const at = Date.now() + demo.offsetSeconds * 1000;
        setState("on");
        setHere({ ...where, heading });
        setTrail((trail) => [...trail, { ...where, at }]);
        buffer.current.push(toPoint({ latitude: where.lat, longitude: where.lon, accuracy: 5, heading }, at));
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
