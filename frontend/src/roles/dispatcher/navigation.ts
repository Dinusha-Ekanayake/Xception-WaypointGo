"use client";

import { useCallback, useEffect, useState } from "react";
import type { IconName } from "@shared/ui";

// The dispatcher's screens, in the sidebar order of the Figma shell. The view
// lives in the URL hash so reload and the back button keep the dispatcher where
// they were, without adding routes under app/.

export type ViewId = "overview" | "orders" | "plan" | "live" | "vehicles" | "forecast" | "issues";

export const VIEWS: Array<{ id: ViewId; label: string; icon: IconName }> = [
  { id: "overview", label: "Overview", icon: "grid" },
  { id: "orders", label: "Orders", icon: "box" },
  { id: "plan", label: "Plan", icon: "plan" },
  { id: "live", label: "Live", icon: "live" },
  { id: "vehicles", label: "Vehicles", icon: "truck" },
  { id: "forecast", label: "Forecast", icon: "chart-line" },
  { id: "issues", label: "Issues", icon: "alert" },
];

function fromHash(): ViewId {
  const id = window.location.hash.replace(/^#\/?/, "");
  return VIEWS.some((view) => view.id === id) ? (id as ViewId) : "overview";
}

export function useView(): [ViewId, (view: ViewId) => void] {
  const [view, setView] = useState<ViewId>("overview");

  useEffect(() => {
    const sync = () => setView(fromHash());
    sync();
    window.addEventListener("hashchange", sync);
    return () => window.removeEventListener("hashchange", sync);
  }, []);

  const go = useCallback((next: ViewId) => {
    window.location.hash = `/${next}`;
  }, []);

  return [view, go];
}
