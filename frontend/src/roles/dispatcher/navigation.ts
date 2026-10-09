"use client";

import { useCallback, useEffect, useState } from "react";
import { withTransition, type IconName } from "@shared/ui";

// The dispatcher's screens, in the sidebar order of the Figma shell. The view
// lives in the URL hash so reload and the back button keep the dispatcher where
// they were, without adding routes under app/.

export type ViewId = "overview" | "orders" | "plan" | "live" | "vehicles" | "forecast" | "issues" | "kpis";

export const VIEWS: Array<{ id: ViewId; label: string; icon: IconName }> = [
  { id: "overview", label: "Overview", icon: "grid" },
  { id: "orders", label: "Orders", icon: "box" },
  { id: "plan", label: "Plan", icon: "plan" },
  { id: "live", label: "Live", icon: "live" },
  { id: "vehicles", label: "Vehicles", icon: "truck" },
  { id: "forecast", label: "Forecast", icon: "chart-line" },
  { id: "issues", label: "Issues", icon: "alert" },
];

/**
 * Pages reached from another screen, not from the sidebar: the pilot KPIs open
 * from their card on Overview (a weekly task during the pilot, not daily work).
 */
const LINKED: ViewId[] = ["kpis"];

function fromHash(): ViewId {
  const id = window.location.hash.replace(/^#\/?/, "");
  return VIEWS.some((view) => view.id === id) || LINKED.includes(id as ViewId) ? (id as ViewId) : "overview";
}

export function useView(): [ViewId, (view: ViewId) => void] {
  const [view, setView] = useState<ViewId>("overview");

  useEffect(() => {
    setView(fromHash());
    // A hash change (a sidebar link, or the browser's back and forward) crossfades.
    const sync = () => withTransition(() => setView(fromHash()), "tab");
    window.addEventListener("hashchange", sync);
    return () => window.removeEventListener("hashchange", sync);
  }, []);

  const go = useCallback((next: ViewId) => {
    window.location.hash = `/${next}`;
  }, []);

  return [view, go];
}
