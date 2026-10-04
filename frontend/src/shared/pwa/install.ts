"use client";

import { useEffect, useState } from "react";

// Installing a role's app from the browser (issue #201). Chrome and Edge offer
// it through `beforeinstallprompt`, fired once and early, so it is caught when
// the shell starts and held until a person asks from settings. Safari on iPhone
// and iPad has no prompt: it installs from Share, Add to Home Screen, and the
// settings row says so. Installed, the row is gone.

/** What a settings row can offer on this device. */
export type InstallKind = "installed" | "prompt" | "ios" | "none";

type PromptEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: "accepted" | "dismissed" }> };

let held: PromptEvent | null = null;
let installed = false;
let watching = false;
const listeners = new Set<() => void>();
const changed = () => listeners.forEach((l) => l());

/** Starts listening for the browser's offer. Called once by the app shell; safe to call again. */
export function watchInstall(): void {
  if (typeof window === "undefined" || watching) return;
  watching = true;
  window.addEventListener("beforeinstallprompt", (e) => {
    // Held for the settings row rather than shown as the browser's own banner.
    e.preventDefault();
    held = e as PromptEvent;
    changed();
  });
  window.addEventListener("appinstalled", () => {
    held = null;
    installed = true;
    changed();
  });
}

/** Decided from what the browser reports; kept apart from it so it can be tested. */
export function installKind(device: { standalone: boolean; prompt: boolean; userAgent: string; touchPoints: number }): InstallKind {
  if (device.standalone) return "installed";
  if (device.prompt) return "prompt";
  // iPadOS reports a Mac; a Mac has no touch points.
  const apple = /iPhone|iPad|iPod/.test(device.userAgent) || (/Macintosh/.test(device.userAgent) && device.touchPoints > 1);
  // Safari is the browser that installs on iOS; other iOS browsers name themselves.
  const safari = /Safari/.test(device.userAgent) && !/CriOS|FxiOS|EdgiOS/.test(device.userAgent);
  return apple && safari ? "ios" : "none";
}

function current(): InstallKind {
  const standalone =
    installed ||
    window.matchMedia("(display-mode: standalone)").matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true;
  return installKind({ standalone, prompt: held !== null, userAgent: navigator.userAgent, touchPoints: navigator.maxTouchPoints });
}

/** What this device offers, and the browser's prompt when it has one. */
export function useInstall(): { kind: InstallKind; install: () => Promise<void> } {
  const [kind, setKind] = useState<InstallKind>("none");
  useEffect(() => {
    const update = () => setKind(current());
    update();
    listeners.add(update);
    return () => void listeners.delete(update);
  }, []);
  const install = async () => {
    const offer = held;
    if (!offer) return;
    held = null;
    await offer.prompt();
    await offer.userChoice;
    changed();
  };
  return { kind, install };
}
