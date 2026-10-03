"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, Empty, VehicleTypeIcon, card, field, primary, secondary } from "./components";
import { DEMO_DEPOTS } from "./fixtures";
import { fetchAdminVehicles } from "../data/reference";


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
  fuelType: "Diesel" | "Petrol";
  weeklyFuelQuotaL: number;
  fuelEfficiencyKmPerL: number;
  temp: "Ambient" | "Chilled (Refrigerated)";
  status: "Available" | "On trip" | "Workshop";
  lastDriver?: LastDriver;
};

export const VEHICLES: Vehicle[] = [
  {
    id: "WP-1042",
    brand: "Toyota",
    type: "Van",
    depot: "PELIYAGODA",
    weightCapKg: 1200,
    volumeCapM3: 8.5,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 280,
    fuelEfficiencyKmPerL: 11.2,
    temp: "Ambient",
    status: "Available",
    lastDriver: { id: "DRV-001", name: "Amal Silva", phone: "+94 77 123 4567", lastRunDate: "Today, 06:15" },
  },
  {
    id: "WP-2088",
    brand: "Isuzu",
    type: "Truck",
    depot: "PELIYAGODA",
    weightCapKg: 5510,
    volumeCapM3: 26.4,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 480,
    fuelEfficiencyKmPerL: 4.7,
    temp: "Chilled (Refrigerated)",
    status: "On trip",
    lastDriver: { id: "DRV-003", name: "Dinuka Samarakoon", phone: "+94 77 345 6789", lastRunDate: "Today, 05:40" },
  },
  {
    id: "WP-1176",
    brand: "Toyota",
    type: "Van",
    depot: "KANDY",
    weightCapKg: 1400,
    volumeCapM3: 9.8,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 310,
    fuelEfficiencyKmPerL: 10.5,
    temp: "Ambient",
    status: "Available",
    lastDriver: { id: "DRV-002", name: "Fathima Rizwan", phone: "+94 71 234 5678", lastRunDate: "Yesterday, 18:20" },
  },
  {
    id: "WP-3041",
    brand: "Mitsubishi",
    type: "Truck",
    depot: "KANDY",
    weightCapKg: 6840,
    volumeCapM3: 33.4,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 430,
    fuelEfficiencyKmPerL: 4.4,
    temp: "Chilled (Refrigerated)",
    status: "Workshop",
    lastDriver: { id: "DRV-008", name: "Dinesh Kumara", phone: "+94 72 890 1234", lastRunDate: "2 days ago, 14:10" },
  },
  {
    id: "WP-1223",
    brand: "Nissan",
    type: "Van",
    depot: "PELIYAGODA",
    weightCapKg: 1200,
    volumeCapM3: 8.5,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 280,
    fuelEfficiencyKmPerL: 11.2,
    temp: "Ambient",
    status: "On trip",
    lastDriver: { id: "DRV-004", name: "Shehan Mendis", phone: "+94 76 456 7890", lastRunDate: "Today, 07:00" },
  },
  {
    id: "WP-3095",
    brand: "Isuzu",
    type: "Truck",
    depot: "KANDY",
    weightCapKg: 3990,
    volumeCapM3: 21.1,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 610,
    fuelEfficiencyKmPerL: 6.1,
    temp: "Chilled (Refrigerated)",
    status: "Available",
    lastDriver: { id: "DRV-005", name: "Maleesha Iqbal", phone: "+94 75 567 8901", lastRunDate: "Yesterday, 16:45" },
  },
  {
    id: "WP-2104",
    brand: "Tata",
    type: "Truck",
    depot: "PELIYAGODA",
    weightCapKg: 7200,
    volumeCapM3: 38.0,
    fuelType: "Diesel",
    weeklyFuelQuotaL: 540,
    fuelEfficiencyKmPerL: 4.9,
    temp: "Ambient",
    status: "Available",
    lastDriver: { id: "DRV-006", name: "Nuwan Pathirana", phone: "+94 78 678 9012", lastRunDate: "Yesterday, 19:30" },
  },
  {
    id: "WP-1330",
    brand: "Toyota",
    type: "Van",
    depot: "KANDY",
    weightCapKg: 1200,
    volumeCapM3: 8.5,
    fuelType: "Petrol",
    weeklyFuelQuotaL: 350,
    fuelEfficiencyKmPerL: 9.8,
    temp: "Ambient",
    status: "Available",
    lastDriver: { id: "DRV-007", name: "Amani Hassan", phone: "+94 70 789 0123", lastRunDate: "Yesterday, 15:10" },
  },
];

const FORECASTS = [
  { depot: "PELIYAGODA", orders: 128, previous: 112, vans: 7, trucks: 4 },
  { depot: "KANDY", orders: 94, previous: 88, vans: 5, trucks: 3 },
] as const;

export function VehiclesScreen() {
  const [vehiclesList, setVehiclesList] = useState<Vehicle[]>(VEHICLES);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);
  const [isAddVehicleModalOpen, setIsAddVehicleModalOpen] = useState(false);

  // New Vehicle form draft
  const [newVehicleId, setNewVehicleId] = useState("");
  const [newVehicleBrand, setNewVehicleBrand] = useState("Toyota");
  const [newVehicleTargetBrand, setNewVehicleTargetBrand] = useState<"Fresh" | "Style" | "Tech" | "General">("Fresh");
  const [newVehicleType, setNewVehicleType] = useState<"Van" | "Truck">("Van");
  const [newVehicleDepot, setNewVehicleDepot] = useState<string>("PELIYAGODA");
  const [newVehicleTemp, setNewVehicleTemp] = useState<string>("Ambient Fresh");
  const [newVehicleWeightCap, setNewVehicleWeightCap] = useState("1200");
  const [newVehicleVolumeCap, setNewVehicleVolumeCap] = useState("8.5");
  const [newVehicleFuelType, setNewVehicleFuelType] = useState<"Diesel" | "Petrol">("Diesel");
  const [newVehicleFuelQuota, setNewVehicleFuelQuota] = useState("280");
  const [newVehicleEfficiency, setNewVehicleEfficiency] = useState("11.2");
  const [newVehicleStatus, setNewVehicleStatus] = useState<"Available" | "On trip" | "Workshop">("Available");
  const [newVehicleDriverName, setNewVehicleDriverName] = useState("");
  const [newVehicleDriverPhone, setNewVehicleDriverPhone] = useState("");

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
      depot: depot !== "all" ? depot : undefined,
      status: status !== "all" ? status.toLowerCase() : undefined,
      search: query.trim() || undefined,
      limit: 100,
    })
      .then((page) => {
        if (cancelled) return;
        if (page.items && page.items.length > 0) {
          const records: Vehicle[] = page.items.map((v) => {
            const fallback = VEHICLES.find((item) => item.id === v.vehicleId);
            const isTruck = v.type?.toLowerCase() === "truck";
            const tempVal =
              v.temperature?.toLowerCase().includes("reefer") || v.temperature?.toLowerCase().includes("chilled")
                ? "Chilled (Refrigerated)"
                : "Ambient";
            const statusVal =
              v.dayStatus === "in_workshop" || v.dayStatus === "workshop"
                ? "Workshop"
                : v.dayStatus === "on_route"
                ? "On trip"
                : "Available";

            return {
              id: v.vehicleId,
              brand: fallback?.brand || "Toyota",
              type: isTruck ? "Truck" : "Van",
              depot: v.depot || fallback?.depot || "PELIYAGODA",
              weightCapKg: Number(v.weightCapKg) || (isTruck ? 5510 : 1200),
              volumeCapM3: Number(v.volumeCapM3) || (isTruck ? 26.4 : 8.5),
              fuelType: (v.fuelType === "Petrol" || v.fuelType === "Diesel" ? v.fuelType : fallback?.fuelType || "Diesel") as "Diesel" | "Petrol",
              weeklyFuelQuotaL: Number(v.weeklyFuelQuotaL) || (isTruck ? 480 : 280),
              fuelEfficiencyKmPerL: Number(v.kmPerL) || (isTruck ? 4.7 : 11.2),
              temp: tempVal,
              status: fallback?.status || statusVal,
              lastDriver: fallback?.lastDriver || {
                id: `DRV-${v.vehicleId.slice(-3)}`,
                name: "Assigned Driver",
                phone: "+94 77 123 4567",
                lastRunDate: "Today",
              },
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

  const handleTargetBrandChange = (brand: "Fresh" | "Style" | "Tech" | "General") => {
    setNewVehicleTargetBrand(brand);
    if (brand === "Fresh") {
      setNewVehicleTemp("Ambient Fresh");
    } else {
      setNewVehicleTemp("Ambient");
    }
  };

  const handleCreateVehicle = () => {
    const vId = newVehicleId.trim().toUpperCase() || `WP-${Math.floor(1000 + Math.random() * 9000)}`;
    const weight = parseFloat(newVehicleWeightCap) || (newVehicleType === "Van" ? 1200 : 5510);
    const volume = parseFloat(newVehicleVolumeCap) || (newVehicleType === "Van" ? 8.5 : 26.4);
    const quota = parseFloat(newVehicleFuelQuota) || (newVehicleType === "Van" ? 280 : 480);
    const efficiency = parseFloat(newVehicleEfficiency) || (newVehicleType === "Van" ? 11.2 : 4.7);

    const newVeh: Vehicle = {
      id: vId,
      brand: newVehicleBrand.trim() || "Toyota",
      type: newVehicleType,
      depot: newVehicleDepot,
      weightCapKg: weight,
      volumeCapM3: volume,
      fuelType: newVehicleFuelType,
      weeklyFuelQuotaL: quota,
      fuelEfficiencyKmPerL: efficiency,
      temp: newVehicleTemp as "Ambient" | "Chilled (Refrigerated)",
      status: newVehicleStatus,
      lastDriver: newVehicleDriverName.trim()
        ? {
            id: `DRV-${Date.now().toString().slice(-3)}`,
            name: newVehicleDriverName.trim(),
            phone: newVehicleDriverPhone.trim() || "+94 77 000 0000",
            lastRunDate: "Just registered",
          }
        : undefined,
    };

    setVehiclesList((prev) => [newVeh, ...prev]);
    setIsAddVehicleModalOpen(false);

    // Reset vehicle form
    setNewVehicleId("");
    setNewVehicleBrand("Toyota");
    setNewVehicleTargetBrand("Fresh");
    setNewVehicleType("Van");
    setNewVehicleDepot("PELIYAGODA");
    setNewVehicleTemp("Ambient Fresh");
    setNewVehicleWeightCap("1200");
    setNewVehicleVolumeCap("8.5");
    setNewVehicleFuelType("Diesel");
    setNewVehicleFuelQuota("280");
    setNewVehicleEfficiency("11.2");
    setNewVehicleStatus("Available");
    setNewVehicleDriverName("");
    setNewVehicleDriverPhone("");
  };

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
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          {liveConnected !== null && (
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live API: GET /api/admin/vehicles" : "Sample vehicles"}
            </Badge>
          )}
        </div>
        <button
          type="button"
          className={`${primary} flex items-center gap-2`}
          onClick={() => setIsAddVehicleModalOpen(true)}
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
        <label className="text-sm font-medium text-[#10251e]">
          Search vehicle / driver
          <input
            className={`${field} mt-1`}
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Vehicle ID or driver..."
          />
        </label>
        <label className="text-sm font-medium text-[#10251e]">
          Depot
          <select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}>
            <option value="all">All depots</option>
            {DEMO_DEPOTS.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium text-[#10251e]">
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
        <label className="text-sm font-medium text-[#10251e]">
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
        <label className="text-sm font-medium text-[#10251e]">
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
        <label className="text-sm font-medium text-[#10251e]">
          Sort by
          <select className={`${field} mt-1`} value={sortBy} onChange={(event) => setSortBy(event.target.value as "id" | "driver_name" | "driver_name_desc" | "driver_id")}>
            <option value="id">Vehicle ID (Default)</option>
            <option value="driver_name">Driver name (A to Z)</option>
            <option value="driver_name_desc">Driver name (Z to A)</option>
            <option value="driver_id">Driver ID</option>
          </select>
        </label>
      </div>

      <p className="text-sm text-[#58685f]">
        {rows.length} of {VEHICLES.length} vehicles match filters
      </p>

      {/* Output rows showing capacity, fuel quota, and structured details */}
      {rows.length ? (
        <div className={`${card} divide-y divide-[#edf3ef]`}>
          {rows.map((item) => {
            const isSelected = selected === item.id;
            return (
              <article
                key={item.id}
                className="p-4 sm:p-5 transition-colors hover:bg-[#fafcfb]"
              >
                <div className="flex flex-wrap items-center justify-between gap-4">
                  {/* Vehicle Identity & Assigned Driver */}
                  <div className="flex items-center gap-3.5 min-w-[200px]">
                    <div
                      className={`grid size-12 shrink-0 place-items-center rounded-2xl shadow-2xs ${
                        item.status === "Workshop"
                          ? "bg-[#fff2d8] text-[#835500]"
                          : item.status === "On trip"
                          ? "bg-[#d8f5ee] text-[#006b57]"
                          : "bg-[#e5f4ef] text-[#006b57]"
                      }`}
                    >
                      <VehicleTypeIcon type={item.type} className="size-6.5" />
                    </div>
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-base font-semibold text-[#10251e]">
                          {item.id}
                        </span>
                        <span className="rounded-md bg-[#eef5f1] px-2 py-0.5 text-xs font-semibold text-[#2c4c3e]">
                          {item.type}
                        </span>
                        <span
                          className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                            item.temp.startsWith("Chilled")
                              ? "bg-[#e0f2fe] text-[#0369a1]"
                              : "bg-[#edf4f0] text-[#486356]"
                          }`}
                        >
                          {item.temp}
                        </span>
                      </div>
                      <p className="mt-0.5 text-xs text-[#58685f]">
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
                  <div className="mt-4 rounded-2xl border border-[#d6ebe0] bg-gradient-to-br from-[#f8fbf9] to-[#edf6f2] p-5 shadow-xs">
                    {/* Replaced Fleet specification record with Last Driver information */}
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#e1eee6] pb-3.5">
                      <div className="flex items-center gap-3">
                        <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-[#ddefec] font-semibold text-xs text-[#0a6b63] shadow-2xs">
                          {item.lastDriver ? item.lastDriver.name.split(" ").map((p) => p[0]).slice(0, 2).join("") : "NA"}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-[#58685f]">Last driver:</span>
                            <span className="text-sm font-semibold text-[#10251e]">
                              {item.lastDriver?.name || "Unassigned"}
                            </span>
                            {item.lastDriver && (
                              <span className="rounded-md bg-white px-2 py-0.5 font-mono text-xs text-[#0a6b63] border border-[#d8ebe1]">
                                {item.lastDriver.id}
                              </span>
                            )}
                          </div>
                          {item.lastDriver && (
                            <p className="mt-0.5 text-xs text-[#58685f]">
                              <span>{item.lastDriver.phone}</span>
                              <span className="mx-1.5">·</span>
                              <span>Last run: {item.lastDriver.lastRunDate}</span>
                            </p>
                          )}
                        </div>
                      </div>
                      <span className="text-xs text-[#58685f]">
                        Depot base: <strong className="font-semibold text-[#10251e]">{item.depot}</strong>
                      </span>
                    </div>

                    <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      {/* Metric 1: Certified payload weight */}
                      <div className="rounded-xl border border-[#e1eee6] bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-[#58685f]">Certified weight payload</span>
                        <p className="mt-1 text-2xl font-semibold text-[#10251e]">
                          {item.weightCapKg.toLocaleString()} <span className="text-sm font-normal text-[#58685f]">kg</span>
                        </p>
                        <div className="mt-2 w-full bg-[#eef4f1] rounded-full h-1.5 overflow-hidden">
                          <div
                            className="bg-[#00896d] h-1.5 rounded-full"
                            style={{ width: `${Math.min(100, Math.round((item.weightCapKg / 7200) * 100))}%` }}
                          />
                        </div>
                        <p className="mt-1.5 text-xs text-[#58685f]">Checked at dispatch publication gate</p>
                      </div>

                      {/* Metric 2: Cargo volume */}
                      <div className="rounded-xl border border-[#e1eee6] bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-[#58685f]">Cargo volume capacity</span>
                        <p className="mt-1 text-2xl font-semibold text-[#10251e]">
                          {item.volumeCapM3} <span className="text-sm font-normal text-[#58685f]">m³</span>
                        </p>
                        <div className="mt-2 w-full bg-[#eef4f1] rounded-full h-1.5 overflow-hidden">
                          <div
                            className="bg-[#0284c7] h-1.5 rounded-full"
                            style={{ width: `${Math.min(100, Math.round((item.volumeCapM3 / 38) * 100))}%` }}
                          />
                        </div>
                        <p className="mt-1.5 text-xs text-[#58685f]">Max volumetric load capacity</p>
                      </div>

                      {/* Metric 3: Weekly fuel quota */}
                      <div className="rounded-xl border border-[#f5e7cc] bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-[#8a5d00]">Weekly fuel allocation</span>
                        <p className="mt-1 text-2xl font-semibold text-[#10251e]">
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
                      <div className="rounded-xl border border-[#e1eee6] bg-white p-3.5 shadow-2xs">
                        <span className="text-xs font-medium uppercase tracking-wider text-[#58685f]">Fuel efficiency & cargo</span>
                        <p className="mt-1 text-2xl font-semibold text-[#10251e]">
                          {item.fuelEfficiencyKmPerL} <span className="text-sm font-normal text-[#58685f]">km/L</span>
                        </p>
                        <p className="mt-2 text-xs font-medium text-[#006b57]">
                          {item.temp}
                        </p>
                        <p className="mt-1 text-xs text-[#58685f]">Cold-chain compliant</p>
                      </div>
                    </div>

                    <div className="mt-3.5 flex flex-wrap items-center justify-between gap-3 text-xs text-[#58685f] rounded-xl bg-white p-3 border border-[#e4efe8]">
                      <div>
                        <span className="font-semibold text-[#10251e]">System capacity constraint:</span> Capacity verification reads aggregate order weight and volume directly. 1% product reconstruction tolerance is not used for vehicle fit.
                      </div>
                      <div className="flex items-center gap-2 font-medium">
                        <span>Day dispatch status:</span>
                        <Badge tone={item.status === "Available" ? "green" : item.status === "Workshop" ? "amber" : "blue"}>
                          {item.status}
                        </Badge>
                      </div>
                    </div>
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
          <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-[#d6e7df] animate-in fade-in duration-200">
            <div className="flex items-center justify-between border-b border-[#edf4f0] pb-4">
              <div>
                <h3 id="add-vehicle-ops-title" className="text-xl font-bold text-[#10251e]">Add New Vehicle</h3>
                <p className="text-xs text-[#58685f]">Register a new vehicle with payload limits, certified temperature zone, and fuel quota.</p>
              </div>
              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-[#58685f] hover:bg-[#f0f4f2] text-lg"
                onClick={() => setIsAddVehicleModalOpen(false)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            <div className="mt-5 space-y-4 text-sm">
              {/* Vehicle ID & Stationed Depot */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-[#10251e]">
                  Vehicle ID / Plate *
                  <input
                    type="text"
                    className={`${field} mt-1 uppercase`}
                    placeholder="e.g. WP-3590"
                    value={newVehicleId}
                    onChange={(e) => setNewVehicleId(e.target.value)}
                  />
                </label>

                <label className="block font-medium text-[#10251e]">
                  Stationed Depot *
                  <select
                    className={`${field} mt-1`}
                    value={newVehicleDepot}
                    onChange={(e) => setNewVehicleDepot(e.target.value)}
                  >
                    {DEMO_DEPOTS.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              {/* Brand & Category Configuration (Asks brand first, then conditional sub-options for Fresh) */}
              <div className="rounded-2xl border border-[#d6ebe0] bg-[#f8fbf9] p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-[#006b57]">
                  Brand &amp; Merchandise Category
                </span>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-[#10251e]">
                    Which Brand is this vehicle for? *
                    <select
                      className={`${field} mt-1`}
                      value={newVehicleTargetBrand}
                      onChange={(e) => handleTargetBrandChange(e.target.value as "Fresh" | "Style" | "Tech" | "General")}
                    >
                      <option value="Fresh">Fresh (Food, Dairy &amp; Groceries)</option>
                      <option value="Style">Style (Apparel &amp; Fashion)</option>
                      <option value="Tech">Tech (Electronics &amp; Appliances)</option>
                      <option value="General">General Fleet (Multi-brand network)</option>
                    </select>
                  </label>

                  {newVehicleTargetBrand === "Fresh" ? (
                    <label className="block font-medium text-[#10251e]">
                      Fresh Sub-Category / Temperature *
                      <select
                        className={`${field} mt-1`}
                        value={newVehicleTemp}
                        onChange={(e) => setNewVehicleTemp(e.target.value)}
                      >
                        <option value="Ambient Fresh">Ambient Fresh (Dry &amp; packaged foods)</option>
                        <option value="Chilled (Refrigerated)">Chilled Refrigerated (Cold chain &amp; perishables)</option>
                      </select>
                    </label>
                  ) : (
                    <div>
                      <span className="block text-xs font-medium text-[#58685f]">
                        Temperature Zone Requirement
                      </span>
                      <div className="mt-1 flex items-center gap-2 rounded-xl border border-[#e1ece5] bg-white px-3 py-2 text-sm text-[#3b5246]">
                        <span className="size-2 rounded-full bg-[#00896d]"></span>
                        <span>Standard Ambient ({newVehicleTargetBrand} does not require refrigerated)</span>
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* Vehicle Type & Initial Status */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-[#10251e]">
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

                <label className="block font-medium text-[#10251e]">
                  Initial Status *
                  <select
                    className={`${field} mt-1`}
                    value={newVehicleStatus}
                    onChange={(e) => setNewVehicleStatus(e.target.value as "Available" | "On trip" | "Workshop")}
                  >
                    <option value="Available">Available</option>
                    <option value="On trip">On trip</option>
                    <option value="Workshop">Workshop</option>
                  </select>
                </label>
              </div>

              {/* Capacity Specs */}
              <div className="rounded-2xl border border-[#d6ebe0] bg-[#f8fbf9] p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-[#006b57]">
                  Payload &amp; Capacity Specifications
                </span>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-[#10251e]">
                    Max Weight Capacity (kg) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleWeightCap}
                      onChange={(e) => setNewVehicleWeightCap(e.target.value)}
                    />
                  </label>
                  <label className="block font-medium text-[#10251e]">
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
              <div className="rounded-2xl border border-[#d6ebe0] bg-[#f8fbf9] p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-[#006b57]">
                  Fuel &amp; Efficiency Configuration
                </span>
                <div className="grid gap-3 sm:grid-cols-3">
                  <label className="block font-medium text-[#10251e]">
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
                  <label className="block font-medium text-[#10251e]">
                    Weekly Quota (L) *
                    <input
                      type="number"
                      step="any"
                      className={`${field} mt-1`}
                      value={newVehicleFuelQuota}
                      onChange={(e) => setNewVehicleFuelQuota(e.target.value)}
                    />
                  </label>
                  <label className="block font-medium text-[#10251e]">
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

              {/* Assigned Driver (Optional) */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-[#10251e]">
                  Assigned Driver Name (Optional)
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. Kasun Fernando"
                    value={newVehicleDriverName}
                    onChange={(e) => setNewVehicleDriverName(e.target.value)}
                  />
                </label>
                <label className="block font-medium text-[#10251e]">
                  Driver Phone (Optional)
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. +94 77 123 4567"
                    value={newVehicleDriverPhone}
                    onChange={(e) => setNewVehicleDriverPhone(e.target.value)}
                  />
                </label>
              </div>
            </div>

            <div className="mt-6 flex flex-wrap justify-end gap-3 border-t border-[#edf4f0] pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setIsAddVehicleModalOpen(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className={primary}
                disabled={!newVehicleId.trim()}
                onClick={handleCreateVehicle}
              >
                Create vehicle
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
  const rows = FORECASTS.filter((item) => depot === "all" || item.depot === depot);
  const totalOrders = rows.reduce((sum, item) => sum + item.orders, 0);
  const totalVehicles = rows.reduce((sum, item) => sum + item.vans + item.trucks, 0);
  const vehiclesInView = VEHICLES.filter((vehicle) => depot === "all" || vehicle.depot === depot);
  return <div className="space-y-5"><div><h2 className="text-2xl font-semibold">Forecasts</h2><p className="mt-1 text-sm text-[#58685f]">Illustrative order demand and vehicle needs for the next operating day.</p></div>
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><Summary value={totalOrders} label="Forecast orders"/><Summary value={totalVehicles} label="Suggested vehicles"/><Summary value={vehiclesInView.filter((vehicle) => vehicle.status === "Available").length} label="Available vehicles"/><Summary value={vehiclesInView.filter((vehicle) => vehicle.status === "Workshop").length} label="Workshop vehicles"/></div>
    <label className={`${card} block max-w-sm p-4 text-sm font-medium`}>Depot<select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}><option value="all">All depots</option>{FORECASTS.map((item) => <option key={item.depot}>{item.depot}</option>)}</select></label>
    <div className="grid gap-4 lg:grid-cols-3">{rows.map((item) => { const change = Math.round((item.orders - item.previous) / item.previous * 100); return <article key={item.depot} className={`${card} p-5`}><p className="text-xs font-bold uppercase tracking-wider text-[#64776b]">{item.depot}</p><div className="mt-4 flex items-end gap-2"><strong className="text-4xl text-[#075c4b]">{item.orders}</strong><span className="pb-1 text-sm text-[#58685f]">orders</span></div><p className="mt-2 text-sm text-[#58685f]">{change >= 0 ? "+" : ""}{change}% against the comparison day</p><div className="mt-5 border-t border-[#e8efea] pt-4 text-sm"><p>Suggested: <strong>{item.vans} vans</strong> · <strong>{item.trucks} trucks</strong></p></div></article>; })}</div>
  </div>;
}

function Summary({ value, label }: { value: number; label: string }) { return <div className={`${card} p-5`}><p className="text-3xl font-semibold text-[#0a6b63]">{value}</p><p className="mt-1 text-sm text-[#58685f]">{label}</p></div>; }
