"use client";

import { createContext, useContext, type ReactNode } from "react";
import { Segmented } from "@shared/ui";
import type { DepotFilter } from "./data/scope.ts";

// The one depot scope of the dispatcher's screens. The shell holds it; the
// page header of every screen shows the switch (Both · Kandy · Peliyagoda),
// always in the same place, so no screen grows a depot control of its own.

type DepotScope = { scope: string[]; filter: DepotFilter; onFilter: (filter: DepotFilter) => void };

const Context = createContext<DepotScope | null>(null);

export function DepotScopeProvider({ value, children }: { value: DepotScope; children: ReactNode }): React.JSX.Element {
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function depotOptions(depots: string[]): Array<{ value: string; label: string }> {
  return [
    ...(depots.length > 1 ? [{ value: "all", label: depots.length === 2 ? "Both" : "All" }] : []),
    ...depots.map((depot) => ({ value: depot, label: depot })),
  ];
}

/** The header's depot switch; nothing when the session sees one depot. */
export function DepotSwitch(): React.JSX.Element | null {
  const scope = useContext(Context);
  if (!scope || scope.scope.length < 2) return null;
  return <Segmented size="md" label="Depots" value={scope.filter} onChange={scope.onFilter} options={depotOptions(scope.scope)} />;
}
