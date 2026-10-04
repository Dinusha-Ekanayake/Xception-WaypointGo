"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, Empty, VehicleTypeIcon, card, field, primary, secondary } from "./components";
import { todayInColombo } from "./model";
import { createAdminVehicle, fetchAdminVehicles, fetchAdminDepots, updateAdminVehicle } from "../data/reference";
import { request } from "@shared/api/client";
import type { ForecastOverviewView } from "@shared/domain/intelligence";


export type LastDriver = {
  id: string;
  name: string;
  phone: string;
  lastRunDate: string;
};

export type Vehicle = {
  id: string;
  brand: string;
  type: "Van" | "Truck";
  depot: string;
  weightCapKg: number;
  volumeCapM3: number;
  fuelType: string;
  weeklyFuelQuotaL: number;
  fuelEfficiencyKmPerL: number;
  temp: "Ambient" | "Chilled (Refrigerated)";
  status: "Available" | "On trip" | "Workshop" | "Unavailable";
  lastDriver?: LastDriver;
  rowVersion?: number;
};

export function VehiclesScreen() {
  const [vehiclesList, setVehiclesList] = useState<Vehicle[]>([]);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);
  const [isAddVehicleModalOpen, setIsAddVehicleModalOpen] = useState(false);
  const [editingVehicle, setEditingVehicle] = useState<Vehicle | null>(null);
  const [createError, setCreateError] = useState("");
  const [createNotice, setCreateNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [depotChoices, setDepotChoices] = useState<string[]>([]);

  // New Vehicle form draft
  const [newVehicleId, setNewVehicleId] = useState("");
  const [newVehicleType, setNewVehicleType] = useState<"Van" | "Truck">("Van");
  const [newVehicleDepot, setNewVehicleDepot] = useState<string>("");
  const [newVehicleTemp, setNewVehicleTemp] = useState<string>("Ambient Fresh");
  const [newVehicleWeightCap, setNewVehicleWeightCap] = useState("1200");
  const [newVehicleVolumeCap, setNewVehicleVolumeCap] = useState("8.5");
  const [newVehicleFuelType, setNewVehicleFuelType] = useState<"Diesel" | "Petrol">("Diesel");
  const [newVehicleFuelQuota, setNewVehicleFuelQuota] = useState("280");
  const [newVehicleEfficiency, setNewVehicleEfficiency] = useState("11.2");

  const [depot, setDepot] = useState("all");
  const [capacityFilter, setCapacityFilter] = useState("all");
  const [fuelQuotaFilter, setFuelQuotaFilter] = useState("all");
  const [status, setStatus] = useState("all");
  const [sortBy, setSortBy] = useState<"id" | "driver_name" | "driver_name_desc" | "driver_id">("id");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<string | null>(null);


  // Fetch live vehicles from backend API
  useEffect(() => {
    let cancelled = false;
    fetchAdminVehicles({
      date: todayInColombo(),
      depot: depot !== "all" ? depot : undefined,
      status: status !== "all" ? status.toLowerCase() : undefined,
      search: query.trim() || undefined,
      limit: 100,
    })
      .then((page) => {
        if (cancelled) return;
        {
          const records: Vehicle[] = page.items.map((v) => {
            const isTruck = v.type?.toLowerCase() === "truck";
            const tempVal =
              v.temperature?.toLowerCase().includes("reefer") || v.temperature?.toLowerCase().includes("chilled")
                ? "Chilled (Refrigerated)"
                : "Ambient";
            const statusVal =
              v.dayStatus === "in_workshop" || v.dayStatus === "workshop"
                ? "Workshop"
                : v.dayStatus === "unavailable" ? "Unavailable" : "Available";

            return {
              id: v.vehicleId,
              brand: "Make unavailable",
              type: isTruck ? "Truck" : "Van",
              depot: v.depot,
              weightCapKg: Number(v.weightCapKg),
              volumeCapM3: Number(v.volumeCapM3),
              fuelType: v.fuelType,
              weeklyFuelQuotaL: Number(v.weeklyFuelQuotaL),
              fuelEfficiencyKmPerL: Number(v.kmPerL),
              temp: tempVal,
              status: statusVal,
              rowVersion: v.rowVersion,
            };
          });
          setVehiclesList(records);
          setLiveConnected(true);
        }
      })
      .catch(() => {
        if (!cancelled) setLiveConnected(false);
      });
    return () => {
      cancelled = true;
    };
  }, [depot, status, query]);

  const handleVehicleTypeChange = (nextType: "Van" | "Truck") => {
    setNewVehicleType(nextType);
    if (nextType === "Truck") {
      setNewVehicleWeightCap("5510");
      setNewVehicleVolumeCap("26.4");
      setNewVehicleFuelQuota("480");
      setNewVehicleEfficiency("4.7");
    } else {
      setNewVehicleWeightCap("1200");
      setNewVehicleVolumeCap("8.5");
      setNewVehicleFuelQuota("280");
      setNewVehicleEfficiency("11.2");
    }
  };

  const handleCreateVehicle = async () => {
    setCreateError("");
    setCreating(true);
    let saved = false;
    try {
      const payload = {
        vehicleId: newVehicleId.trim().toUpperCase(), depotCode: newVehicleDepot,
        type: newVehicleType.toLowerCase(),
        temperature: newVehicleTemp === "Chilled (Refrigerated)" ? "reefer" : "ambient",
        weightCapKg: newVehicleWeightCap, volumeCapM3: newVehicleVolumeCap,
        fuelType: newVehicleFuelType.toLowerCase(), kmPerL: newVehicleEfficiency,
        weeklyFuelQuotaL: newVehicleFuelQuota,
      };
      if (editingVehicle) await updateAdminVehicle(payload, editingVehicle.rowVersion ?? 0);
      else await createAdminVehicle(payload);
      saved = true;
      const page = await fetchAdminVehicles({ limit: 100 });
      setVehiclesList(page.items.map((v) => ({
        id: v.vehicleId, brand: "Make unavailable",
        type: v.type.toLowerCase() === "truck" ? "Truck" : "Van", depot: v.depot,
        weightCapKg: Number(v.weightCapKg), volumeCapM3: Number(v.volumeCapM3),
        fuelType: v.fuelType, weeklyFuelQuotaL: Number(v.weeklyFuelQuotaL),
        fuelEfficiencyKmPerL: Number(v.kmPerL),
        temp: v.temperature === "reefer" ? "Chilled (Refrigerated)" : "Ambient",
        status: "Available",
        rowVersion: v.rowVersion,
      })));
      setIsAddVehicleModalOpen(false); setEditingVehicle(null);
    } catch (error) {
      if (saved) {
        setCreateNotice("Vehicle saved, but the directory could not refresh. Reload to see it.");
        setIsAddVehicleModalOpen(false);
      } else {
        setCreateError(error instanceof Error ? error.message : "Could not create vehicle.");
      }
    } finally {
      setCreating(false);
    }

    // Reset vehicle form
    if (!saved) return;
    setNewVehicleId("");
    setNewVehicleType("Van");
    setNewVehicleTemp("Ambient Fresh");
    setNewVehicleWeightCap("1200");
    setNewVehicleVolumeCap("8.5");
    setNewVehicleFuelType("Diesel");
    setNewVehicleFuelQuota("280");
    setNewVehicleEfficiency("11.2");
  };

  function openVehicleEditor(vehicle: Vehicle) {
    setEditingVehicle(vehicle); setNewVehicleId(vehicle.id); setNewVehicleType(vehicle.type);
    setNewVehicleDepot(vehicle.depot); setNewVehicleTemp(vehicle.temp);
    setNewVehicleWeightCap(String(vehicle.weightCapKg)); setNewVehicleVolumeCap(String(vehicle.volumeCapM3));
    setNewVehicleFuelType(vehicle.fuelType.toLowerCase() === "petrol" ? "Petrol" : "Diesel");
    setNewVehicleFuelQuota(String(vehicle.weeklyFuelQuotaL)); setNewVehicleEfficiency(String(vehicle.fuelEfficiencyKmPerL));
    setCreateError(""); setIsAddVehicleModalOpen(true);
  }

  const rows = useMemo(() => {
    const filtered = vehiclesList.filter((vehicle) => {
      if (depot !== "all" && vehicle.depot !== depot) return false;
      if (status !== "all" && vehicle.status !== status) return false;

      // Capacity filter
      if (capacityFilter === "light" && vehicle.weightCapKg >= 2000) return false;
      if (capacityFilter === "medium" && (vehicle.weightCapKg < 2000 || vehicle.weightCapKg > 5000)) return false;
      if (capacityFilter === "heavy" && vehicle.weightCapKg <= 5000) return false;

      // Fuel quota filter
      if (fuelQuotaFilter === "low" && vehicle.weeklyFuelQuotaL >= 350) return false;
      if (fuelQuotaFilter === "mid" && (vehicle.weeklyFuelQuotaL < 350 || vehicle.weeklyFuelQuotaL > 500)) return false;
      if (fuelQuotaFilter === "high" && vehicle.weeklyFuelQuotaL <= 500) return false;

      const q = query.trim().toLowerCase();
      if (!q) return true;
      const searchable = `${vehicle.id} ${vehicle.brand} ${vehicle.type} ${vehicle.depot} ${vehicle.temp} ${vehicle.fuelType} ${vehicle.lastDriver?.name || ""} ${vehicle.lastDriver?.id || ""}`.toLowerCase();
      return searchable.includes(q);
    });

    return [...filtered].sort((a, b) => {
      if (sortBy === "driver_name") {
        const nameA = a.lastDriver?.name || "";
        const nameB = b.lastDriver?.name || "";
        return nameA.localeCompare(nameB);
      }
      if (sortBy === "driver_name_desc") {
        const nameA = a.lastDriver?.name || "";
        const nameB = b.lastDriver?.name || "";
        return nameB.localeCompare(nameA);
      }
      if (sortBy === "driver_id") {
        const idA = a.lastDriver?.id || "";
        const idB = b.lastDriver?.id || "";
        return idA.localeCompare(idB);
      }
      return a.id.localeCompare(b.id);
    });
  }, [vehiclesList, depot, capacityFilter, fuelQuotaFilter, status, sortBy, query]);

  return (
    <div className="space-y-6">
      {createNotice && <p role="status" className="rounded-xl bg-go-subtle p-3 text-sm text-go-ink">{createNotice}</p>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          {liveConnected !== null && (
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live data" : "Vehicles unavailable"}
            </Badge>
          )}
        </div>
        <button
          type="button"
          className={`${primary} flex items-center gap-2`}
          onClick={() => {
            setCreateError("");
            setIsAddVehicleModalOpen(true);
            void fetchAdminDepots().then((rows) => {
              const codes = rows.map((row) => row.code).sort();
              setDepotChoices(codes);
              setNewVehicleDepot((current) => codes.includes(current) ? current : codes[0] ?? "");
            }).catch((error) => setCreateError(error instanceof Error ? error.message : "Depots unavailable."));
          }}
        >
          <span className="text-lg leading-none" aria-hidden="true">+</span>
          <span>Add vehicle</span>
        </button>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Summary value={vehiclesList.length} label="Total fleet vehicles" />
        <Summary value={vehiclesList.filter((item) => item.status === "Available").length} label="Available for dispatch" />
        <Summary value={vehiclesList.filter((item) => item.status === "On trip").length} label="Currently on trip" />
        <Summary value={vehiclesList.filter((item) => item.status === "Workshop").length} label="In workshop maintenance" />
      </div>

      {/* Filter Bar with Capacity, Fuel Quota, & Driver Sorting */}
      <div className={`${card} grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6`}>
        <label className="text-sm font-medium text-go-ink">
          Search vehicle / driver
          <input
            className={`${field} mt-1`}
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Vehicle ID or driver..."
          />
        </label>
        <label className="text-sm font-medium text-go-ink">
          Depot
          <select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}>
            <option value="all">All depots</option>
            {[...new Set(vehiclesList.map((item) => item.depot))].sort().map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium text-go-ink">
          Payload capacity
          <select
            className={`${field} mt-1`}
            value={capacityFilter}
            onChange={(event) => setCapacityFilter(event.target.value)}
          >
            <option value="all">All capacities</option>
            <option value="light">Under 2,000 kg (Light / Van)</option>
            <option value="medium">2,000 - 5,000 kg (Medium)</option>
            <option value="heavy">Over 5,000 kg (Heavy / Truck)</option>
          </select>
        </label>
        <label className="text-sm font-medium text-go-ink">
          Weekly fuel quota
          <select
            className={`${field} mt-1`}
            value={fuelQuotaFilter}
            onChange={(event) => setFuelQuotaFilter(event.target.value)}
          >
            <option value="all">All quotas</option>
            <option value="low">Under 350 L / week</option>
            <option value="mid">350 - 500 L / week</option>
            <option value="high">Over 500 L / week</option>
          </select>
        </label>
        <label className="text-sm font-medium text-go-ink">
          Status
          <select className={`${field} mt-1`} value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="all">All statuses</option>
            {["Available", "On trip", "Workshop"].map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium text-go-ink">
          Sort by
          <select className={`${field} mt-1`} value={sortBy} onChange={(event) => setSortBy(event.target.value as "id" | "driver_name" | "driver_name_desc" | "driver_id")}>
            <option value="id">Vehicle ID (Default)</option>
            <option value="driver_name">Driver name (A to Z)</option>
            <option value="driver_name_desc">Driver name (Z to A)</option>
            <option value="driver_id">Driver ID</option>
          </select>
        </label>
      </div>

      <p className="text-sm text-go-secondary">
        {rows.length} of {vehiclesList.length} vehicles match filters
      </p>

      {/* Output rows showing capacity, fuel quota, and structured details */}
      {rows.length ? (
        <div className={`${card} divide-y divide-go-subtle`}>
          {rows.map((item) => {
            const isSelected = selected === item.id;
            return (
              <article
                key={item.id}
                className="p-4 sm:p-5 transition-colors hover:bg-go-subtle"
              >
                <div className="flex flex-wrap items-center justify-between gap-4">
                  {/* Vehicle Identity & Assigned Driver */}
                  <div className="flex items-center gap-3.5 min-w-[200px]">
                    <div
                      className={`grid size-12 shrink-0 place-items-center rounded-2xl shadow-2xs ${
                        item.status === "Workshop"
                          ? "bg-go-warning-tint text-go-warning-text"
                          : item.status === "On trip"
                          ? "bg-go-mint text-go-teal"
                          : "bg-go-subtle text-go-teal"
                      }`}
                    >
                      <VehicleTypeIcon type={item.type} className="size-6.5" />
                    </div>
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-base font-semibold text-go-ink">
                          {item.id}
                        </span>
                        <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-semibold text-go-ink">
                          {item.type}
                        </span>
                        <span
                          className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                            item.temp.startsWith("Chilled")
                              ? "bg-[#e0f2fe] text-[#0369a1]"
                              : "bg-go-subtle text-[#486356]"
                          }`}
                        >
                          {item.temp}
                        </span>
                      </div>
                      <p className="mt-0.5 text-xs text-go-secondary">
                        <span>{item.depot} depot</span>
                      </p>
                    </div>
                  </div>

                  {/* Status & Actions */}
                  <div className="flex items-center gap-3">
                    <Badge tone={item.status === "Available" ? "green" : item.status === "Workshop" ? "amber" : "blue"}>
                      {item.status}
                    </Badge>
                    <button
                      type="button"
                      className={`${secondary} min-h-9 px-3 text-xs flex items-center gap-1.5`}
                      onClick={() => setSelected(isSelected ? null : item.id)}
                    >
                      <span>{isSelected ? "Hide info" : "More info"}</span>
                      <span aria-hidden="true">{isSelected ? "⌄" : "›"}</span>
                    </button>
                  </div>
                </div>

                {/* Expanded Details Panel: Last Driver & Structured Specification */}
                {isSelected && (
                  <div className="mt-4 rounded-2xl border border-go-rule bg-gradient-to-br from-[#f8fbf9] to-[#edf6f2] p-5 shadow-xs">
                    {/* Replaced Fleet specification record with Last Driver information */}
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-go-rule pb-3.5">
                      <div className="flex items-center gap-3">
                        <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-go-mint font-semibold text-xs text-go-teal shadow-2xs">
                          {item.lastDriver ? item.lastDriver.name.split(" ").map((p) => p[0]).slice(0, 2).join("") : "NA"}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-go-secondary">Last driver:</span>
                            <span className="text-sm font-semibold text-go-ink">
                              {item.lastDriver?.name || "Unassigned"}
                            </span>
                            {item.lastDriver && (
                              <span className="rounded-md bg-white px-2 py-0.5 font-mono text-xs text-go-teal border border-[#d8ebe1]">
                                {item.lastDriver.id}
                              </span>
                            )}
                          </div>
                          {item.lastDriver && (
                            <p className="mt-0.5 text-xs text-go-secondary">
                              <span>{item.lastDriver.phone}</span>
                              <span className="mx-1.5">·</span>
                              <span>Last run: {item.lastDriver.lastRunDate}</span>
                            </p>
                          )}
                        </div>
                      </div>
                      <span className="text-xs text-go-secondary">
                        Depot base: <strong className="font-semibold text-go-ink">{item.depot}</strong>
                      </span>
                    </div>

                    <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      {/* Metric 1: Certified payload weight */}
                      <div className="rounded-xl border border-go-rule bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-go-secondary">Certified weight payload</span>
                        <p className="mt-1 text-2xl font-semibold text-go-ink">
                          {item.weightCapKg.toLocaleString()} <span className="text-sm font-normal text-go-secondary">kg</span>
                        </p>
                        <div className="mt-2 w-full bg-go-subtle rounded-full h-1.5 overflow-hidden">
                          <div
                            className="bg-go-teal h-1.5 rounded-full"
                            style={{ width: `${Math.min(100, Math.round((item.weightCapKg / 7200) * 100))}%` }}
                          />
                        </div>
                        <p className="mt-1.5 text-xs text-go-secondary">Checked at dispatch publication gate</p>
                      </div>

                      {/* Metric 2: Cargo volume */}
                      <div className="rounded-xl border border-go-rule bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-go-secondary">Cargo volume capacity</span>
                        <p className="mt-1 text-2xl font-semibold text-go-ink">
                          {item.volumeCapM3} <span className="text-sm font-normal text-go-secondary">m³</span>
                        </p>
                        <div className="mt-2 w-full bg-go-subtle rounded-full h-1.5 overflow-hidden">
                          <div
                            className="bg-[#0284c7] h-1.5 rounded-full"
                            style={{ width: `${Math.min(100, Math.round((item.volumeCapM3 / 38) * 100))}%` }}
                          />
                        </div>
                        <p className="mt-1.5 text-xs text-go-secondary">Max volumetric load capacity</p>
                      </div>

                      {/* Metric 3: Weekly fuel quota */}
                      <div className="rounded-xl border border-[#f5e7cc] bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-[#8a5d00]">Weekly fuel allocation</span>
                        <p className="mt-1 text-2xl font-semibold text-go-ink">
                          {item.weeklyFuelQuotaL} <span className="text-sm font-normal text-[#8a5d00]">liters / wk</span>
                        </p>
                        <div className="mt-2 w-full bg-[#fbeed4] rounded-full h-1.5 overflow-hidden">
                          <div
                            className="bg-[#d97706] h-1.5 rounded-full"
                            style={{ width: `${Math.min(100, Math.round((item.weeklyFuelQuotaL / 650) * 100))}%` }}
                          />
                        </div>
                        <p className="mt-1.5 text-xs text-[#8a5d00]">Primary fuel: {item.fuelType}</p>
                      </div>

                      {/* Metric 4: Efficiency & temp zone */}
                      <div className="rounded-xl border border-go-rule bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-go-secondary">Fuel efficiency & cargo</span>
                        <p className="mt-1 text-2xl font-semibold text-go-ink">
                          {item.fuelEfficiencyKmPerL} <span className="text-sm font-normal text-go-secondary">km/L</span>
                        </p>
                        <p className="mt-2 text-xs font-medium text-go-teal">
                          {item.temp}
                        </p>
                        <p className="mt-1 text-xs text-go-secondary">Cold-chain compliant</p>
                      </div>
                    </div>

                    <div className="mt-3.5 flex flex-wrap items-center justify-between gap-3 text-xs text-go-secondary rounded-xl bg-white p-3 border border-[#e4efe8]">
                      <div>
                        <span className="font-semibold text-go-ink">System capacity constraint:</span> Capacity verification reads aggregate order weight and volume directly. 1% product reconstruction tolerance is not used for vehicle fit.
                      </div>
                      <div className="flex items-center gap-2 font-medium">
                        <span>Day dispatch status:</span>
                        <Badge tone={item.status === "Available" ? "green" : item.status === "Workshop" ? "amber" : "blue"}>
                          {item.status}
                        </Badge>
                      </div>
                    </div>
                    <div className="mt-3 flex justify-end"><button type="button" className={primary} onClick={() => openVehicleEditor(item)}>Edit vehicle</button></div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      ) : (
        <Empty>No vehicles match these filters.</Empty>
      )}

      {/* Add Vehicle Modal */}
      {isAddVehicleModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-vehicle-ops-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            <div className="flex items-center justify-between border-b border-go-subtle pb-4">
              <div>
                <h3 id="add-vehicle-ops-title" className="text-xl font-bold text-go-ink">{editingVehicle ? `Edit ${editingVehicle.id}` : "Add New Vehicle"}</h3>
                <p className="text-xs text-go-secondary">{editingVehicle ? "Update fleet details used by planning and capacity checks." : "Register a new vehicle with payload limits, certified temperature zone, and fuel quota."}</p>
              </div>
              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => { setIsAddVehicleModalOpen(false); setEditingVehicle(null); }}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            <div className="mt-5 space-y-4 text-sm">
              {/* Vehicle ID & Stationed Depot */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Vehicle ID / Plate *
                  <input
                    type="text"
                    className={`${field} mt-1 uppercase`}
                    placeholder="e.g. WP-3590"
                    value={newVehicleId}
                    disabled={!!editingVehicle}
                    onChange={(e) => setNewVehicleId(e.target.value)}
                  />
                </label>

                <label className="block font-medium text-go-ink">
                  Stationed Depot *
                  <select
                    className={`${field} mt-1`}
                    value={newVehicleDepot}
                    onChange={(e) => setNewVehicleDepot(e.target.value)}
                  >
                    {depotChoices.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <label className="block font-medium text-go-ink">
                Temperature capability *
                <select className={`${field} mt-1`} value={newVehicleTemp}
                  onChange={(e) => setNewVehicleTemp(e.target.value)}>
                  <option value="Ambient Fresh">Ambient</option>
                  <option value="Chilled (Refrigerated)">Refrigerated</option>
                </select>
              </label>

              {/* Vehicle type */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Vehicle Type *
                  <select
                    className={`${field} mt-1`}
                    value={newVehicleType}
                    onChange={(e) => handleVehicleTypeChange(e.target.value as "Van" | "Truck")}
                  >
                    <option value="Van">Van (Small / Medium fleet)</option>
                    <option value="Truck">Truck (Heavy / Multi-ton fleet)</option>
                  </select>
                </label>

              </div>

              {/* Capacity Specs */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Payload &amp; Capacity Specifications
                </span>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-go-ink">
                    Max Weight Capacity (kg) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleWeightCap}
                      onChange={(e) => setNewVehicleWeightCap(e.target.value)}
                    />
                  </label>
                  <label className="block font-medium text-go-ink">
                    Max Volume Capacity (m³) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleVolumeCap}
                      onChange={(e) => setNewVehicleVolumeCap(e.target.value)}
                    />
                  </label>
                </div>
              </div>

              {/* Fuel Specs */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Fuel &amp; Efficiency Configuration
                </span>
                <div className="grid gap-3 sm:grid-cols-3">
                  <label className="block font-medium text-go-ink">
                    Fuel Type *
                    <select
                      className={`${field} mt-1`}
                      value={newVehicleFuelType}
                      onChange={(e) => setNewVehicleFuelType(e.target.value as "Diesel" | "Petrol")}
                    >
                      <option value="Diesel">Diesel</option>
                      <option value="Petrol">Petrol</option>
                    </select>
                  </label>
                  <label className="block font-medium text-go-ink">
                    Weekly Quota (L) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleFuelQuota}
                      onChange={(e) => setNewVehicleFuelQuota(e.target.value)}
                    />
                  </label>
                  <label className="block font-medium text-go-ink">
                    Efficiency (km/L) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleEfficiency}
                      onChange={(e) => setNewVehicleEfficiency(e.target.value)}
                    />
                  </label>
                </div>
              </div>

              {createError && <p role="alert" className="text-sm text-go-danger">{createError}</p>}
            </div>

            <div className="mt-6 flex flex-wrap justify-end gap-3 border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => { setIsAddVehicleModalOpen(false); setEditingVehicle(null); }}
              >
                Cancel
              </button>
              <button
                type="button"
                className={primary}
                disabled={creating || !newVehicleId.trim() || !newVehicleDepot}
                onClick={handleCreateVehicle}
              >
                {creating ? "Saving..." : editingVehicle ? "Save changes" : "Create vehicle"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function ForecastsScreen() {
  const [depot, setDepot] = useState("all");
  const [overviews, setOverviews] = useState<ForecastOverviewView[]>([]);
  const [error, setError] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    void fetchAdminDepots({ signal: controller.signal })
      .then((depots) => Promise.all(depots.map((d) => request<ForecastOverviewView>(
        `/api/ml/forecast/overview?depot=${encodeURIComponent(d.code)}`, { signal: controller.signal }))))
      .then((rows) => { setOverviews(rows); setError(false); })
      .catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, []);
  const rows = overviews.filter((item) => depot === "all" || item.depotCode === depot);
  const nextWeek = rows.map((item) => item.weeks[0]).filter((week) => week != null);
  const totalVolume = nextWeek.reduce((sum, week) => sum + Number(week.totalM3), 0);
  const fleetVolume = nextWeek.reduce((sum, week) => sum + Number(week.capacity.fleetM3), 0);
  const vehicles = nextWeek.reduce((sum, week) => sum + week.capacity.vehicles, 0);
  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-semibold">Forecasts</h2>
        <p className="mt-1 text-sm text-go-secondary">
          Weekly demand volume and reference fleet capacity from the published forecast.
        </p>
      </div>
      {error && <Empty>Forecast data is unavailable. Reopen this screen to retry.</Empty>}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Summary value={Number(totalVolume.toFixed(1))} label="Next week forecast m³" />
        <Summary value={Number(fleetVolume.toFixed(1))} label="Next week fleet capacity m³" />
        <Summary value={vehicles} label="Reference fleet vehicles" />
        <Summary value={rows.filter((item) => item.degraded).length} label="Depots using fallback" />
      </div>
      <label className={`${card} block max-w-sm p-4 text-sm font-medium`}>
        Depot
        <select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}>
          <option value="all">All depots</option>
          {overviews.map((item) => <option key={item.depotCode}>{item.depotCode}</option>)}
        </select>
      </label>
      {!error && rows.length === 0 ? (
        <Empty>No forecast data published yet.</Empty>
      ) : !error && (
        <div className="grid gap-4 lg:grid-cols-3">
          {rows.map((item) => {
            const week = item.weeks[0];
            return (
              <article key={item.depotCode} className={`${card} p-5`}>
                <p className="text-xs font-bold uppercase tracking-wider text-go-secondary">{item.depotCode}</p>
                <div className="mt-4 flex items-end gap-2">
                  <strong className="text-4xl text-go-teal">{week ? Number(week.totalM3).toFixed(1) : "Unavailable"}</strong>
                  <span className="pb-1 text-sm text-go-secondary">m³ next week</span>
                </div>
                <p className="mt-2 text-sm text-go-secondary">{item.degraded ? "Deterministic fallback forecast" : item.modelLabel ?? "No model recorded"}</p>
                <div className="mt-5 border-t border-go-rule pt-4 text-sm">
                  <p>Fleet capacity: <strong>{week ? Number(week.capacity.fleetM3).toFixed(1) : "Unavailable"} m³</strong> · {week?.capacity.vehicles ?? 0} vehicles</p>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}

function Summary({ value, label }: { value: number; label: string }) { return <div className={`${card} p-5`}><p className="text-3xl font-semibold text-go-teal">{value}</p><p className="mt-1 text-sm text-go-secondary">{label}</p></div>; }
