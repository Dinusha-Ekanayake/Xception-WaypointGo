"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, PersonaIcon, VehicleTypeIcon, card, field, primary, secondary } from "./components";
import type { DemoState, Member, Persona } from "./model";
import type { Vehicle } from "./operations";
import type { OutletRecord } from "./OutletsScreen";
import { fetchAdminDepots, fetchAdminOutlets, fetchAdminVehicles } from "../data/reference";

export type DepotRecord = {
  id: string;
  name: string;
  badge: string;
  region: string;
  lat?: number;
  lng?: number;
  outlets?: string[];
};



export function DepotsScreen({
  state,
  onNavigateTab,
  onSelectMember,
}: {
  state: DemoState;
  onNavigateTab: (tab: "people" | "personas" | "vehicles" | "forecasts" | "audit") => void;
  onSelectMember: (id: string) => void;
}) {
  const [depots, setDepots] = useState<DepotRecord[]>([]);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);
  const [selectedDepotId, setSelectedDepotId] = useState<string>("PELIYAGODA");
  const [activeComponent, setActiveComponent] = useState<"people" | "vehicles" | "stores">("people");
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [selectedOutlet, setSelectedOutlet] = useState<OutletRecord | null>(null);
  const [selectedVehicle, setSelectedVehicle] = useState<Vehicle | null>(null);

  // Dynamic vehicles & outlets lists
  const [vehiclesList, setVehiclesList] = useState<Vehicle[]>([]);
  const [outletsList, setOutletsList] = useState<OutletRecord[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetchAdminDepots()
      .then((liveDepots) => {
        if (cancelled) return;
        if (liveDepots && liveDepots.length > 0) {
          const records: DepotRecord[] = liveDepots.map((d) => ({
            id: d.code,
            name: d.name || `${d.code} Hub`,
            badge: "Distribution Hub",
            region: `${d.code}, Sri Lanka`,
            outlets: [],
          }));
          setDepots(records);
          setSelectedDepotId((prev) => (records.some((r) => r.id === prev) ? prev : records[0].id));
          setLiveConnected(true);
        }
      })
      .catch(() => {
        if (!cancelled) setLiveConnected(false);
      });

    fetchAdminVehicles({ limit: 100 })
      .then((page) => {
        if (cancelled) return;
        if (page.items) {
          setVehiclesList(
            page.items.map((v) => ({
              id: v.vehicleId,
              brand: "Make unavailable",
              type: v.type?.toLowerCase() === "truck" ? "Truck" : "Van",
              depot: v.depot,
              weightCapKg: Number(v.weightCapKg),
              volumeCapM3: Number(v.volumeCapM3),
              fuelType: v.fuelType,
              weeklyFuelQuotaL: Number(v.weeklyFuelQuotaL),
              fuelEfficiencyKmPerL: Number(v.kmPerL),
              temp: v.temperature?.toLowerCase().includes("chilled") ? "Chilled (Refrigerated)" : "Ambient",
              status: v.dayStatus === "in_workshop" ? "Workshop" : v.dayStatus === "unavailable" ? "Unavailable" : "Available",
            }))
          );
        }
      })
      .catch(() => {});

    fetchAdminOutlets({ limit: 200 })
      .then((page) => {
        if (cancelled) return;
        if (page.items) {
          setOutletsList(
            page.items.map((o) => ({
              id: o.outletId,
              name: `Outlet ${o.outletId}`,
              brand: (o.brand === "Style" || o.brand === "Tech" ? o.brand : "Fresh") as "Fresh" | "Style" | "Tech",
              district: o.district || "",
              depot: o.depot,
              dockType: (o.dockType === "rear_dock" || o.dockType === "mall_bay" ? o.dockType : "street") as "rear_dock" | "street" | "mall_bay",
              dockDetails: `Unloading capability: ${o.dockType || "standard"}`,
              windowOpen: o.windowOpen,
              windowClose: o.windowClose,
              windowNotes: `Time window: ${o.windowOpen} - ${o.windowClose}`,
              maxVehicleType: o.parking === "van_only" ? "Van Only" : "Van & Truck",
            }))
          );
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, []);

  // Filters inside People component
  const [peopleSearch, setPeopleSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState<"all" | "driver" | "store_manager">("all");

  // Filters inside Vehicles component
  const [vehicleSearch, setVehicleSearch] = useState("");
  const [vehicleBrandFilter, setVehicleBrandFilter] = useState("all");
  const [vehicleTypeFilter, setVehicleTypeFilter] = useState("all");

  // Filters inside Stores / Outlets component
  const [outletSearch, setOutletSearch] = useState("");
  const [outletBrandFilter, setOutletBrandFilter] = useState("all");
  const [outletDockFilter, setOutletDockFilter] = useState("all");

  // New Depot form draft
  const [newDepotName, setNewDepotName] = useState("");
  const [newDepotCode, setNewDepotCode] = useState("");
  const [newDepotRegion, setNewDepotRegion] = useState("");
  const [newDepotLat, setNewDepotLat] = useState("");
  const [newDepotLng, setNewDepotLng] = useState("");
  const [newDepotOutletsInput, setNewDepotOutletsInput] = useState("");

  const currentDepot = useMemo(() => {
    return depots.find((d) => d.id === selectedDepotId) || depots[0];
  }, [depots, selectedDepotId]);

  // People belonging to this depot (Only Drivers & Store Managers are bound to a depot)
  const depotPeople = useMemo(() => {
    if (!currentDepot) return [];
    return state.members.filter((m) => {
      const isDriver = m.personas.includes("driver");
      const isStoreMgr = m.personas.includes("store_manager");
      if (!isDriver && !isStoreMgr) return false;

      // Driver mapping
      if (isDriver) {
        if (m.places.includes(currentDepot.id)) return true;
        if (m.depots && m.depots.includes(currentDepot.id)) return true;
      }

      // Store manager mapping
      if (isStoreMgr) {
        if (m.places.includes(currentDepot.id)) return true;
        if (m.depots && m.depots.includes(currentDepot.id)) return true;
        const managerPlaces = [...(m.outlets ?? []), ...m.places];
        const belongsToDepot = outletsList.some((o) => o.depot === currentDepot.id && managerPlaces.includes(o.id));
        if (belongsToDepot) return true;
      }

      return false;
    });
  }, [state.members, currentDepot, outletsList]);

  // Vehicles stationed at this depot
  const depotVehicles = useMemo(() => {
    if (!currentDepot) return [];
    return vehiclesList.filter((v) => v.depot === currentDepot.id);
  }, [vehiclesList, currentDepot]);

  // Outlets connected to this depot
  const depotOutlets = useMemo(() => {
    if (!currentDepot) return [];
    return outletsList.filter((o) => o.depot === currentDepot.id || (currentDepot.outlets && currentDepot.outlets.includes(o.id)));
  }, [outletsList, currentDepot]);

  // Count summaries for the main buttons
  const driversCount = depotPeople.filter((p) => p.personas.includes("driver")).length;
  const storeManagersCount = depotPeople.filter((p) => p.personas.includes("store_manager")).length;

  // Filtered people
  const filteredPeople = useMemo(() => {
    return depotPeople.filter((member) => {
      if (roleFilter !== "all" && !member.personas.includes(roleFilter)) return false;
      const q = peopleSearch.trim().toLowerCase();
      if (!q) return true;
      return `${member.name} ${member.email} ${member.personas.join(" ")}`.toLowerCase().includes(q);
    });
  }, [depotPeople, roleFilter, peopleSearch]);

  // Available vehicle brands
  const vehicleBrands = useMemo(() => {
    const set = new Set<string>();
    depotVehicles.forEach((v) => set.add(v.brand));
    return Array.from(set).sort();
  }, [depotVehicles]);

  // Filtered vehicles
  const filteredVehicles = useMemo(() => {
    return depotVehicles.filter((v) => {
      if (vehicleBrandFilter !== "all" && v.brand !== vehicleBrandFilter) return false;
      if (vehicleTypeFilter !== "all" && v.type !== vehicleTypeFilter) return false;
      const q = vehicleSearch.trim().toLowerCase();
      if (!q) return true;
      return `${v.id} ${v.brand} ${v.type} ${v.fuelType} ${v.lastDriver?.name || ""}`.toLowerCase().includes(q);
    });
  }, [depotVehicles, vehicleBrandFilter, vehicleTypeFilter, vehicleSearch]);

  // Available outlet brands
  const outletBrands = useMemo(() => {
    const set = new Set<string>();
    depotOutlets.forEach((o) => set.add(o.brand));
    return Array.from(set).sort();
  }, [depotOutlets]);

  // Filtered outlets
  const filteredOutlets = useMemo(() => {
    return depotOutlets.filter((o) => {
      if (outletBrandFilter !== "all" && o.brand !== outletBrandFilter) return false;
      if (outletDockFilter !== "all" && o.dockType !== outletDockFilter) return false;
      const q = outletSearch.trim().toLowerCase();
      if (!q) return true;
      return `${o.id} ${o.name || ""} ${o.brand} ${o.district} ${o.dockType} ${o.storeManager || ""}`.toLowerCase().includes(q);
    });
  }, [depotOutlets, outletBrandFilter, outletDockFilter, outletSearch]);

  const handleCreateDepot = () => {
    const code = newDepotCode.trim().toUpperCase() || `DEPOT-${Date.now().toString().slice(-4)}`;
    const name = newDepotName.trim() || `${code} Hub`;
    const region = newDepotRegion.trim() || "Sri Lanka";
    const lat = parseFloat(newDepotLat) || 6.9271;
    const lng = parseFloat(newDepotLng) || 79.8612;

    const parsedOutlets = newDepotOutletsInput
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);

    const newDepot: DepotRecord = {
      id: code,
      name,
      badge: "Regional Logistics Hub",
      region,
      lat,
      lng,
      outlets: parsedOutlets.length > 0 ? parsedOutlets : [`OUT-${code}-01`, `OUT-${code}-02`],
    };

    setDepots((prev) => [...prev, newDepot]);
    setSelectedDepotId(code);
    setIsAddModalOpen(false);

    // Reset form
    setNewDepotName("");
    setNewDepotCode("");
    setNewDepotRegion("");
    setNewDepotLat("");
    setNewDepotLng("");
    setNewDepotOutletsInput("");
  };

  const getDockLabel = (dockType: "rear_dock" | "street" | "mall_bay") => {
    switch (dockType) {
      case "rear_dock":
        return "Rear loading dock";
      case "street":
        return "Street kerbside";
      case "mall_bay":
        return "Mall enclosed bay";
    }
  };

  const getDockBadgeColor = (dockType: "rear_dock" | "street" | "mall_bay") => {
    switch (dockType) {
      case "rear_dock":
        return "bg-go-mint text-go-teal border border-go-mint";
      case "street":
        return "bg-go-warning-tint text-[#b45309] border border-[#fed7aa]";
      case "mall_bay":
        return "bg-[#e0e7ff] text-[#4338ca] border border-[#c7d2fe]";
    }
  };

  const getBrandBadgeColor = (brand: "Fresh" | "Style" | "Tech") => {
    switch (brand) {
      case "Fresh":
        return "bg-go-mint text-[#006b47]";
      case "Style":
        return "bg-[#f3e8ff] text-[#6b21a8]";
      case "Tech":
        return "bg-[#e0f2fe] text-[#0369a1]";
    }
  };

  if (!currentDepot) {
    return <div className={`${card} p-6 text-go-secondary`} role="status">
      {liveConnected === false ? "Depots could not be loaded. Try refreshing the page." : "Loading depots..."}
    </div>;
  }

  return (
    <div className="space-y-6">
      {/* Top Bar: Single Depot Selection & Actions */}
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-go-rule pb-5">
        <div className="flex flex-wrap items-center gap-3">
          <label className="text-sm font-semibold text-go-ink">
            Operating Depot:
            <select
              className="ml-2.5 min-h-11 rounded-xl border border-go-rule bg-white px-3.5 py-2 text-base font-semibold text-go-teal shadow-2xs outline-none focus:border-go-teal focus:ring-2 focus:ring-go-mint"
              value={currentDepot.id}
              onChange={(e) => setSelectedDepotId(e.target.value)}
            >
              {depots.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} ({d.id})
                </option>
              ))}
            </select>
          </label>
          {liveConnected !== null && (
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live data" : "Depots unavailable"}
            </Badge>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <button
            type="button"
            className={`${primary} flex items-center gap-2`}
            disabled
            title="Adding a depot requires a reference create command that is not available yet"
          >
            <span className="text-lg leading-none" aria-hidden="true">+</span>
            <span>Add depot</span>
          </button>
        </div>
      </div>

      {/* The 3 Main Component Selector Buttons */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {/* Button 1: People */}
        <button
          type="button"
          onClick={() => setActiveComponent("people")}
          className={`flex h-full flex-col justify-between rounded-2xl p-5 text-left transition-all ${
            activeComponent === "people"
              ? "border-2 border-go-teal bg-gradient-to-b from-[#e8f7f2] to-[#f5fbf8] shadow-md ring-2 ring-go-mint"
              : "border border-go-rule bg-white hover:border-go-rule hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shadow-2xs">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            </span>
            <span
              className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                activeComponent === "people" ? "bg-go-teal text-white" : "bg-go-subtle text-go-secondary"
              }`}
            >
              {activeComponent === "people" ? "Active View" : "View"}
            </span>
          </div>
          <div className="mt-4">
            <span className="text-xs font-bold uppercase tracking-wider text-go-secondary">Depot Personnel</span>
            <p className="mt-1 text-2xl font-bold text-go-ink">{depotPeople.length} People</p>
            <p className="mt-1 text-xs text-go-secondary">
              Drivers &amp; store managers
            </p>
          </div>
        </button>

        {/* Button 2: Vehicles */}
        <button
          type="button"
          onClick={() => setActiveComponent("vehicles")}
          className={`flex h-full flex-col justify-between rounded-2xl p-5 text-left transition-all ${
            activeComponent === "vehicles"
              ? "border-2 border-go-teal bg-gradient-to-b from-[#e8f7f2] to-[#f5fbf8] shadow-md ring-2 ring-go-mint"
              : "border border-go-rule bg-white hover:border-go-rule hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shadow-2xs">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <rect x="2" y="5" width="12" height="11" rx="1" />
                <path d="M14 8h4.5a1 1 0 0 1 .8.4l2.4 3.2a1 1 0 0 1 .3.6V16h-3" />
                <circle cx="6" cy="18" r="2" />
                <circle cx="18" cy="18" r="2" />
                <path d="M8 18h8" />
              </svg>
            </span>
            <span
              className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                activeComponent === "vehicles" ? "bg-go-teal text-white" : "bg-go-subtle text-go-secondary"
              }`}
            >
              {activeComponent === "vehicles" ? "Active View" : "View"}
            </span>
          </div>
          <div className="mt-4">
            <span className="text-xs font-bold uppercase tracking-wider text-go-secondary">Stationed Fleet</span>
            <p className="mt-1 text-2xl font-bold text-go-ink">{depotVehicles.length} Vehicles</p>
            <p className="mt-1 text-xs text-go-secondary">
              Vans &amp; trucks fleet
            </p>
          </div>
        </button>

        {/* Button 3: Connected Stores */}
        <button
          type="button"
          onClick={() => setActiveComponent("stores")}
          className={`flex h-full flex-col justify-between rounded-2xl p-5 text-left transition-all ${
            activeComponent === "stores"
              ? "border-2 border-go-teal bg-gradient-to-b from-[#e8f7f2] to-[#f5fbf8] shadow-md ring-2 ring-go-mint"
              : "border border-go-rule bg-white hover:border-go-rule hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shadow-2xs">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <path d="M3 9l1.5-6h15L21 9" />
                <path d="M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0" />
                <path d="M4 9v11a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V9" />
                <path d="M9 21v-7a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v7" />
              </svg>
            </span>
            <span
              className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                activeComponent === "stores" ? "bg-go-teal text-white" : "bg-go-subtle text-go-secondary"
              }`}
            >
              {activeComponent === "stores" ? "Active View" : "View"}
            </span>
          </div>
          <div className="mt-4">
            <span className="text-xs font-bold uppercase tracking-wider text-go-secondary">Connected Stores</span>
            <p className="mt-1 text-2xl font-bold text-go-ink">{depotOutlets.length} Outlets</p>
            <p className="mt-1 text-xs text-go-secondary">
              Fresh, Style &amp; Tech retail
            </p>
          </div>
        </button>
      </div>

      {/* Component Details Container */}
      <div className="mt-6">
        {/* COMPONENT 1: PEOPLE */}
        {activeComponent === "people" && (
          <div className="space-y-4">
            {/* Filter Bar with Store Managers vs Drivers separation */}
            <div className={`${card} flex flex-wrap items-center justify-between gap-3 p-4`}>
              <div className="w-full sm:w-72">
                <input
                  type="search"
                  className={field}
                  value={peopleSearch}
                  onChange={(e) => setPeopleSearch(e.target.value)}
                  placeholder="Search staff by name..."
                />
              </div>

              {/* Role filter pills */}
              <div className="flex flex-wrap items-center gap-1.5 rounded-xl bg-go-subtle p-1 text-xs">
                <button
                  type="button"
                  onClick={() => setRoleFilter("all")}
                  className={`rounded-lg px-3 py-1.5 font-semibold transition ${
                    roleFilter === "all" ? "bg-white text-go-teal shadow-2xs" : "text-go-secondary hover:text-go-ink"
                  }`}
                >
                  All ({depotPeople.length})
                </button>
                <button
                  type="button"
                  onClick={() => setRoleFilter("driver")}
                  className={`rounded-lg px-3 py-1.5 font-semibold transition ${
                    roleFilter === "driver" ? "bg-white text-go-teal shadow-2xs" : "text-go-secondary hover:text-go-ink"
                  }`}
                >
                  Drivers ({driversCount})
                </button>
                <button
                  type="button"
                  onClick={() => setRoleFilter("store_manager")}
                  className={`rounded-lg px-3 py-1.5 font-semibold transition ${
                    roleFilter === "store_manager" ? "bg-white text-go-teal shadow-2xs" : "text-go-secondary hover:text-go-ink"
                  }`}
                >
                  Store managers ({storeManagersCount})
                </button>
              </div>
            </div>

            <p className="text-xs text-go-secondary">
              Showing {filteredPeople.length} of {depotPeople.length} personnel in {currentDepot.name}
            </p>

            {/* People List */}
            {filteredPeople.length ? (
              <div className={`${card} divide-y divide-go-subtle`}>
                {filteredPeople.map((person) => {
                  const isDriver = person.personas.includes("driver");
                  const isStoreMgr = person.personas.includes("store_manager");

                  return (
                    <article
                      key={person.id}
                      className="flex flex-wrap items-center justify-between gap-4 p-4 transition-colors hover:bg-go-subtle sm:px-5"
                    >
                      <div className="flex items-center gap-3.5">
                        <div
                          className={`grid size-11 shrink-0 place-items-center rounded-full text-xs font-bold ${
                            isDriver
                              ? "bg-go-mint text-[#006b47]"
                              : isStoreMgr
                              ? "bg-[#e0f2fe] text-[#0369a1]"
                              : "bg-go-subtle text-[#3e5648]"
                          }`}
                        >
                          {person.name
                            .split(" ")
                            .map((p) => p[0])
                            .slice(0, 2)
                            .join("")}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-semibold text-go-ink">{person.name}</span>
                            {isDriver && (
                              <span className="rounded-md bg-go-mint px-2 py-0.5 text-xs font-semibold text-[#006b47]">
                                Driver {person.vehicleType ? `· ${person.vehicleType}` : ""}
                              </span>
                            )}
                            {isStoreMgr && (
                              <span className="rounded-md bg-[#e0f2fe] px-2 py-0.5 text-xs font-semibold text-[#0369a1]">
                                Store manager
                              </span>
                            )}
                          </div>
                        </div>
                      </div>

                      <div className="flex items-center gap-3">
                        <Badge tone="green">Active</Badge>
                        <button
                          type="button"
                          className={`${secondary} min-h-9 px-3.5 text-xs`}
                          onClick={() => onSelectMember(person.id)}
                        >
                          View access
                        </button>
                      </div>
                    </article>
                  );
                })}
              </div>
            ) : (
              <div className={`${card} p-8 text-center text-sm text-go-secondary`}>
                No personnel match the selected role or search filter.
              </div>
            )}
          </div>
        )}

        {/* COMPONENT 2: VEHICLES */}
        {activeComponent === "vehicles" && (
          <div className="space-y-4">
            {/* Filter Bar with Vehicle Brand filter */}
            <div className={`${card} grid gap-3 p-4 sm:grid-cols-3`}>
              <label className="text-sm font-medium text-go-ink">
                Search vehicle
                <input
                  type="search"
                  className={`${field} mt-1`}
                  value={vehicleSearch}
                  onChange={(e) => setVehicleSearch(e.target.value)}
                  placeholder="ID, type, driver..."
                />
              </label>

              {/* Brand Filter */}
              <label className="text-sm font-medium text-go-ink">
                Filter by Brand
                <select
                  className={`${field} mt-1`}
                  value={vehicleBrandFilter}
                  onChange={(e) => setVehicleBrandFilter(e.target.value)}
                >
                  <option value="all">All brands</option>
                  {vehicleBrands.map((b) => (
                    <option key={b} value={b}>
                      {b}
                    </option>
                  ))}
                </select>
              </label>

              <label className="text-sm font-medium text-go-ink">
                Vehicle type
                <select
                  className={`${field} mt-1`}
                  value={vehicleTypeFilter}
                  onChange={(e) => setVehicleTypeFilter(e.target.value)}
                >
                  <option value="all">All types</option>
                  <option value="Van">Van</option>
                  <option value="Truck">Truck</option>
                </select>
              </label>
            </div>

            <p className="text-xs text-go-secondary">
              Showing {filteredVehicles.length} of {depotVehicles.length} vehicles stationed at {currentDepot.name}
            </p>

            {/* Vehicles List */}
            {filteredVehicles.length ? (
              <div className={`${card} divide-y divide-go-subtle`}>
                {filteredVehicles.map((vehicle) => (
                  <article
                    key={vehicle.id}
                    className="flex flex-wrap items-center justify-between gap-4 p-4 transition-colors hover:bg-go-subtle sm:px-5"
                  >
                    <div className="flex items-center gap-3.5">
                      <div className="grid size-12 shrink-0 place-items-center rounded-2xl bg-go-subtle text-go-teal shadow-2xs">
                        <VehicleTypeIcon type={vehicle.type} className="size-6" />
                      </div>
                      <div>
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-base font-semibold text-go-ink">{vehicle.id}</span>
                          <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-semibold text-go-ink">
                            {vehicle.type}
                          </span>
                          <span
                            className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                              vehicle.temp.startsWith("Chilled") ? "bg-[#e0f2fe] text-[#0369a1]" : "bg-go-subtle text-go-secondary"
                            }`}
                          >
                            {vehicle.temp}
                          </span>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <Badge tone={vehicle.status === "Available" ? "green" : vehicle.status === "Workshop" ? "amber" : "blue"}>
                        {vehicle.status}
                      </Badge>
                      <button
                        type="button"
                        className="flex items-center gap-1 rounded-xl border border-go-mint bg-go-subtle px-3.5 py-1.5 text-xs font-bold text-go-teal transition-colors hover:bg-go-mint hover:border-go-mint"
                        onClick={() => setSelectedVehicle(vehicle)}
                      >
                        <span>More info</span>
                        <span aria-hidden="true">›</span>
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            ) : (
              <div className={`${card} p-8 text-center text-sm text-go-secondary`}>
                No vehicles match the selected brand or filter.
              </div>
            )}
          </div>
        )}

        {/* COMPONENT 3: STORES / OUTLETS (Clean view without inline additional info) */}
        {activeComponent === "stores" && (
          <div className="space-y-4">
            {/* Filter Bar */}
            <div className={`${card} grid gap-3 p-4 sm:grid-cols-3`}>
              <label className="text-sm font-medium text-go-ink">
                Search outlet
                <input
                  type="search"
                  className={`${field} mt-1`}
                  value={outletSearch}
                  onChange={(e) => setOutletSearch(e.target.value)}
                  placeholder="ID, name, district..."
                />
              </label>

              {/* Brand Filter */}
              <label className="text-sm font-medium text-go-ink">
                Filter by Brand
                <select
                  className={`${field} mt-1`}
                  value={outletBrandFilter}
                  onChange={(e) => setOutletBrandFilter(e.target.value)}
                >
                  <option value="all">All brands</option>
                  {outletBrands.map((b) => (
                    <option key={b} value={b}>
                      {b} Stores
                    </option>
                  ))}
                </select>
              </label>

              <label className="text-sm font-medium text-go-ink">
                Dock access type
                <select
                  className={`${field} mt-1`}
                  value={outletDockFilter}
                  onChange={(e) => setOutletDockFilter(e.target.value)}
                >
                  <option value="all">All dock types</option>
                  <option value="rear_dock">Rear loading dock</option>
                  <option value="street">Street access bay</option>
                  <option value="mall_bay">Mall delivery bay</option>
                </select>
              </label>
            </div>

            <p className="text-xs text-go-secondary">
              Showing {filteredOutlets.length} of {depotOutlets.length} connected stores supplied by {currentDepot.name}
            </p>

            {/* Outlets List (Clean rows without additional info paragraph, details available via More info button) */}
            {filteredOutlets.length ? (
              <div className={`${card} divide-y divide-go-subtle`}>
                {filteredOutlets.map((outlet) => (
                  <article
                    key={outlet.id}
                    className="flex flex-wrap items-center justify-between gap-4 p-4 transition-colors hover:bg-go-subtle sm:px-5"
                  >
                    <div className="flex items-center gap-3.5 min-w-0">
                      <div className="grid size-11 shrink-0 place-items-center rounded-2xl bg-[#f0f9f5] text-go-teal shadow-2xs font-mono text-xs font-bold">
                        {outlet.id.slice(-3)}
                      </div>
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-base font-semibold text-go-ink">{outlet.id}</span>
                          <span className={`rounded-md px-2 py-0.5 text-xs font-bold ${getBrandBadgeColor(outlet.brand)}`}>
                            {outlet.brand}
                          </span>
                          <span className="rounded-md bg-[#f1f5f2] px-2 py-0.5 text-xs font-medium text-[#4b6055]">
                            {outlet.district} District
                          </span>
                        </div>
                        {outlet.name && (
                          <h3 className="mt-0.5 text-xs font-medium text-go-secondary truncate">
                            {outlet.name}
                          </h3>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <Badge tone="blue">Connected</Badge>
                      <button
                        type="button"
                        className="flex items-center gap-1 rounded-xl border border-go-mint bg-go-subtle px-3.5 py-1.5 text-xs font-bold text-go-teal transition-colors hover:bg-go-mint hover:border-go-mint"
                        onClick={() => setSelectedOutlet(outlet)}
                      >
                        <span>More info</span>
                        <span aria-hidden="true">›</span>
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            ) : (
              <div className={`${card} p-8 text-center text-sm text-go-secondary`}>
                No outlets match the selected brand or filter.
              </div>
            )}
          </div>
        )}
      </div>

      {/* Outlet Details Modal (Opens via More info button) */}
      {selectedOutlet && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="outlet-details-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-start justify-between border-b border-go-subtle pb-4">
              <div>
                <div className="flex items-center gap-2.5">
                  <span className="text-xl font-bold text-go-ink">{selectedOutlet.id}</span>
                  <span className={`rounded-md px-2.5 py-0.5 text-xs font-bold ${getBrandBadgeColor(selectedOutlet.brand)}`}>
                    {selectedOutlet.brand}
                  </span>
                  <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-medium text-go-secondary">
                    {selectedOutlet.district} District
                  </span>
                </div>
                <h3 id="outlet-details-title" className="mt-1 text-base font-semibold text-go-ink">
                  {selectedOutlet.name || `${selectedOutlet.district} ${selectedOutlet.brand} Outlet`}
                </h3>
              </div>

              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => setSelectedOutlet(null)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            {/* Outlet Details */}
            <div className="mt-5 space-y-3.5 text-sm">
              {/* 1. Store Manager */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-start gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shrink-0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Store Manager</span>
                    <p className="text-base font-semibold text-go-ink">
                      {selectedOutlet.storeManager || "Unassigned"}
                    </p>
                    {(selectedOutlet.managerPhone || selectedOutlet.managerEmail) && (
                      <p className="mt-1 text-xs text-go-secondary">
                        {selectedOutlet.managerPhone && <span>Phone: {selectedOutlet.managerPhone}</span>}
                        {selectedOutlet.managerPhone && selectedOutlet.managerEmail && <span className="mx-2">·</span>}
                        {selectedOutlet.managerEmail && <span>Email: {selectedOutlet.managerEmail}</span>}
                      </p>
                    )}
                  </div>
                </div>
              </div>

              {/* 2. Dock Type & Details */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-start gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shrink-0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
                      <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Unloading Dock Type</span>
                    <div className="mt-1">
                      <span className={`inline-block rounded-md px-2.5 py-0.5 text-xs font-bold ${getDockBadgeColor(selectedOutlet.dockType)}`}>
                        {getDockLabel(selectedOutlet.dockType)}
                      </span>
                    </div>
                    {selectedOutlet.dockDetails && (
                      <p className="mt-1.5 text-xs text-go-secondary">{selectedOutlet.dockDetails}</p>
                    )}
                  </div>
                </div>
              </div>

              {/* 3. Delivery Time Window */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-start gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shrink-0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="12 6 12 12 16 14" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Receiving Time Window</span>
                    <p className="text-base font-bold text-go-ink">
                      {selectedOutlet.windowOpen} - {selectedOutlet.windowClose}
                    </p>
                    {selectedOutlet.windowNotes && (
                      <p className="mt-1 text-xs text-go-secondary">
                        <strong className="font-semibold text-go-ink">Gate Notes:</strong> {selectedOutlet.windowNotes}
                      </p>
                    )}
                  </div>
                </div>
              </div>

              {/* 4. Location & Vehicle constraints */}
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="rounded-2xl border border-go-subtle bg-go-subtle p-3.5">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Address</span>
                  <p className="mt-0.5 text-xs font-medium text-go-ink">
                    {selectedOutlet.address || `${selectedOutlet.district} Commercial Zone`}
                  </p>
                </div>
                <div className="rounded-2xl border border-go-subtle bg-go-subtle p-3.5">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Vehicle Limit</span>
                  <p className="mt-0.5 text-xs font-medium text-go-ink">
                    {selectedOutlet.maxVehicleType || "Van & Truck"}
                  </p>
                </div>
              </div>
            </div>

            <div className="mt-6 flex justify-end border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setSelectedOutlet(null)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Vehicle Details Modal (Opens via More info button) */}
      {selectedVehicle && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="vehicle-details-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-start justify-between border-b border-go-subtle pb-4">
              <div className="flex items-center gap-3.5">
                <div className="grid size-12 shrink-0 place-items-center rounded-2xl bg-go-subtle text-go-teal shadow-2xs">
                  <VehicleTypeIcon type={selectedVehicle.type} className="size-6" />
                </div>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xl font-bold text-go-ink">{selectedVehicle.id}</span>
                    <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-semibold text-go-ink">
                      {selectedVehicle.brand} {selectedVehicle.type}
                    </span>
                    <Badge tone={selectedVehicle.status === "Available" ? "green" : selectedVehicle.status === "Workshop" ? "amber" : "blue"}>
                      {selectedVehicle.status}
                    </Badge>
                  </div>
                  <h3 id="vehicle-details-title" className="mt-1 text-xs font-medium text-go-secondary">
                    Stationed at {currentDepot.name} ({currentDepot.id})
                  </h3>
                </div>
              </div>

              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => setSelectedVehicle(null)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            {/* Vehicle Details */}
            <div className="mt-5 space-y-3.5 text-sm">
              {/* 1. Last Assigned Driver */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-start gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal shrink-0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                      <circle cx="9" cy="7" r="4" />
                      <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
                      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Last Assigned Driver</span>
                    {selectedVehicle.lastDriver ? (
                      <>
                        <p className="text-base font-semibold text-go-ink">
                          {selectedVehicle.lastDriver.name}
                        </p>
                        <p className="mt-1 text-xs text-go-secondary">
                          <span>ID: {selectedVehicle.lastDriver.id}</span>
                          <span className="mx-2">·</span>
                          <span>Phone: {selectedVehicle.lastDriver.phone}</span>
                          <span className="mx-2">·</span>
                          <span>Last run: {selectedVehicle.lastDriver.lastRunDate}</span>
                        </p>
                      </>
                    ) : (
                      <p className="mt-1 text-xs text-go-secondary">No driver currently assigned</p>
                    )}
                  </div>
                </div>
              </div>

              {/* 2. Payload & Volume Capacity */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal mb-2.5">
                  Payload &amp; Volume Capacity
                </span>
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Max Weight</span>
                    <p className="mt-0.5 text-base font-bold text-go-ink">
                      {selectedVehicle.weightCapKg.toLocaleString()} kg
                    </p>
                  </div>
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Max Volume</span>
                    <p className="mt-0.5 text-base font-bold text-go-ink">
                      {selectedVehicle.volumeCapM3} m³
                    </p>
                  </div>
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Temp Zone</span>
                    <p className="mt-0.5 text-sm font-bold text-go-teal">
                      {selectedVehicle.temp}
                    </p>
                  </div>
                </div>
              </div>

              {/* 3. Fuel Quota & Efficiency */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal mb-2.5">
                  Fuel Quota &amp; Efficiency
                </span>
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Fuel Type</span>
                    <p className="mt-0.5 text-sm font-bold text-go-ink">
                      {selectedVehicle.fuelType}
                    </p>
                  </div>
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Weekly Quota</span>
                    <p className="mt-0.5 text-base font-bold text-go-ink">
                      {selectedVehicle.weeklyFuelQuotaL} L
                    </p>
                  </div>
                  <div className="rounded-xl border border-go-subtle bg-white p-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-go-secondary">Efficiency</span>
                    <p className="mt-0.5 text-base font-bold text-go-ink">
                      {selectedVehicle.fuelEfficiencyKmPerL} km/L
                    </p>
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-6 flex justify-end border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setSelectedVehicle(null)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add Depot Modal */}
      {isAddModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-depot-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-xl rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            <div className="flex items-center justify-between border-b border-go-subtle pb-4">
              <div>
                <h3 id="add-depot-title" className="text-xl font-bold text-go-ink">Add New Depot</h3>
                <p className="text-xs text-go-secondary">Create an operational distribution hub with geo-location coordinates.</p>
              </div>
              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => setIsAddModalOpen(false)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            <div className="mt-5 space-y-4 text-sm">
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Depot Name *
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. Galle Southern Hub"
                    value={newDepotName}
                    onChange={(e) => setNewDepotName(e.target.value)}
                  />
                </label>
                <label className="block font-medium text-go-ink">
                  Depot Code / ID *
                  <input
                    type="text"
                    className={`${field} mt-1 uppercase`}
                    placeholder="e.g. GALLE"
                    value={newDepotCode}
                    onChange={(e) => setNewDepotCode(e.target.value)}
                  />
                </label>
              </div>

              <label className="block font-medium text-go-ink">
                Region / District Description *
                <input
                  type="text"
                  className={`${field} mt-1`}
                  placeholder="e.g. Galle, Southern Province"
                  value={newDepotRegion}
                  onChange={(e) => setNewDepotRegion(e.target.value)}
                />
              </label>

              {/* Geo-location coordinates */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Geo-Location Coordinates
                </span>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-go-ink">
                    Latitude (°N) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      placeholder="e.g. 6.0535"
                      value={newDepotLat}
                      onChange={(e) => setNewDepotLat(e.target.value)}
                    />
                  </label>
                  <label className="block font-medium text-go-ink">
                    Longitude (°E) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      placeholder="e.g. 80.2210"
                      value={newDepotLng}
                      onChange={(e) => setNewDepotLng(e.target.value)}
                    />
                  </label>
                </div>
              </div>

              {/* Related Outlets */}
              <label className="block font-medium text-go-ink">
                Related Outlets (comma-separated codes)
                <input
                  type="text"
                  className={`${field} mt-1`}
                  placeholder="e.g. OUT-GAL-01, OUT-GAL-02, OUT045"
                  value={newDepotOutletsInput}
                  onChange={(e) => setNewDepotOutletsInput(e.target.value)}
                />
                <span className="mt-1 block text-xs text-go-secondary">
                  Leave empty to generate initial starter outlets for this depot automatically.
                </span>
              </label>
            </div>

            <div className="mt-6 flex flex-wrap justify-end gap-3 border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setIsAddModalOpen(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className={primary}
                disabled={!newDepotName.trim() && !newDepotCode.trim()}
                onClick={handleCreateDepot}
              >
                Create depot
              </button>
            </div>
          </div>
        </div>
      )}


    </div>
  );
}
