"use client";

import { useCallback, useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import { NotificationCommandKind, type PushConfigView } from "@shared/domain/notification";
import { deviceId } from "@shared/offline";
import { base64url, keyBytes } from "./pushKeys.ts";

// Phone and desktop push for a role's notifications (issue #118): data only,
// each role places its own switch. The server says whether it can push at all
// (GET /api/notifications/push-config); when it cannot, the switch says why
// instead of failing. The service worker shows each push and opens the app
// when it is tapped (scripts/build-sw.mjs).

export type PushState =
  | { kind: "checking" }
  /** This browser cannot receive push (no service worker or Push API, or not installed on iOS). */
  | { kind: "unsupported" }
  /** The server has no push keys configured. */
  | { kind: "server-off"; reason: string }
  /** The person blocked notifications for this site; only the browser's settings can undo it. */
  | { kind: "blocked" }
  | { kind: "off" }
  | { kind: "on" };

export type Push = {
  state: PushState;
  busy: boolean;
  error: string | null;
  turnOn: () => Promise<void>;
  turnOff: () => Promise<void>;
};

export function pushSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window
  );
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  const existing = await navigator.serviceWorker.getRegistration();
  return existing ?? null;
}

export function usePush(enabled = true): Push {
  const [state, setState] = useState<PushState>({ kind: "checking" });
  const [config, setConfig] = useState<PushConfigView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!enabled) return;
    if (!pushSupported()) {
      setState({ kind: "unsupported" });
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const answer = await request<PushConfigView>("/api/notifications/push-config");
        if (cancelled) return;
        setConfig(answer);
        if (!answer.enabled || !answer.publicKey) {
          setState({ kind: "server-off", reason: answer.reason ?? "Push is not set up on this server." });
          return;
        }
        if (Notification.permission === "denied") {
          setState({ kind: "blocked" });
          return;
        }
        const reg = await registration();
        const sub = reg ? await reg.pushManager.getSubscription() : null;
        if (!cancelled) setState({ kind: sub ? "on" : "off" });
      } catch {
        if (!cancelled) setState({ kind: "server-off", reason: "Could not ask the server about push." });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [enabled]);

  const turnOn = useCallback(async () => {
    if (!config?.publicKey) return;
    setBusy(true);
    setError(null);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        setState({ kind: permission === "denied" ? "blocked" : "off" });
        return;
      }
      const reg = (await registration()) ?? (await navigator.serviceWorker.ready);
      const sub =
        (await reg.pushManager.getSubscription()) ??
        (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(config.publicKey) }));
      await send(
        newCommand(NotificationCommandKind.subscribe, {
          deviceId: deviceId(),
          endpoint: sub.endpoint,
          p256dhKey: base64url(sub.getKey("p256dh")),
          authSecret: base64url(sub.getKey("auth")),
        }),
      );
      setState({ kind: "on" });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not turn on notifications.");
    } finally {
      setBusy(false);
    }
  }, [config]);

  const turnOff = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const reg = await registration();
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (sub) {
        await send(newCommand(NotificationCommandKind.unsubscribe, { endpoint: sub.endpoint }));
        await sub.unsubscribe();
      }
      setState({ kind: "off" });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not turn off notifications.");
    } finally {
      setBusy(false);
    }
  }, []);

  return { state, busy, error, turnOn, turnOff };
}
