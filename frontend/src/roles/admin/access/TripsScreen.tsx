"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, VehicleTypeIcon, card, field, primary, secondary } from "./components";
import type { DemoState } from "./model";
import {
  fetchAdminPlans,
  fetchAdminPlanDetail,
  FALLBACK_ADMIN_PLANS,
  type AdminPlan,
  type AdminTrip,
  type AdminStop,
} from "../data/plans";

export type TripsSubTab = "planned" | "live";

export function TripsScreen({
  state,
  activeSubTab,
  onSubTabChange,
  onNavigateTab,
}: {
  state?: DemoState;
  activeSubTab?: TripsSubTab;
  onSubTabChange?: (tab: TripsSubTab) => void;
  onNavigateTab?: (tab: "people" | "personas" | "vehicles" | "forecasts" | "depots" | "outlets" | "orders" | "trips" | "audit") => void;
}) {
  const [internalSubTab, setInternalSubTab] = useState<TripsSubTab>("planned");
  const subTab = activeSubTab || internalSubTab;
  const setSubTab = (t: TripsSubTab) => {
    setInternalSubTab(t);
    onSubTabChange?.(t);
  };
  const [plans, setPlans] = useState<AdminPlan[]>(FALLBACK_ADMIN_PLANS);
  const [loading, setLoading] = useState(false);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);

  // Filters
  const [searchQuery, setSearchQuery] = useState("");
  const [dateFilter, setDateFilter] = useState("all");
  const [depotFilter, setDepotFilter] = useState("all");
  const [brandFilter, setBrandFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");

  // Selected Trip & Plan Modal
  const [selectedTrip, setSelectedTrip] = useState<AdminTrip | null>(null);
  const [selectedPlanDetail, setSelectedPlanDetail] = useState<AdminPlan | null>(null);
  const [modalLoading, setModalLoading] = useState(false);

  // Fetch plans on mount or filters change
  useEffect(() => {
    let cancelled = false;
    async function loadPlans() {
      setLoading(true);
      try {
        const page = await fetchAdminPlans({
          depot: depotFilter,
          date: dateFilter !== "all" ? dateFilter : undefined,
          status: statusFilter !== "all" ? statusFilter.toLowerCase() : undefined,
          limit: 200,
        });
        if (cancelled) return;
        if (page.items && page.items.length > 0) {
          setPlans(page.items);
        }
        setLiveConnected(true);
      } catch {
        if (!cancelled) setLiveConnected(false);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void loadPlans();
    return () => {
      cancelled = true;
    };
  }, [depotFilter, dateFilter, statusFilter]);

  // Load detailed plan when a trip is inspected
  useEffect(() => {
    if (!selectedTrip) {
      setSelectedPlanDetail(null);
      return;
    }
    const currentTrip = selectedTrip;
    let cancelled = false;
    async function loadDetail() {
      setModalLoading(true);
      try {
        const detail = await fetchAdminPlanDetail(currentTrip.planId);
        if (cancelled) return;
        setSelectedPlanDetail(detail);
      } catch (error) {
        if (!cancelled) {
          setSelectedPlanDetail(plans.find((p) => p.planId === currentTrip.planId) || plans[0]);
        }
      } finally {
        if (!cancelled) setModalLoading(false);
      }
    }
    void loadDetail();
    return () => {
      cancelled = true;
    };
  }, [selectedTrip, plans]);

  const TODAY = "2026-10-03";

  // Flatten all trips across plans
  const allTrips = useMemo(() => {
    return plans.flatMap((p) => p.trips);
  }, [plans]);

  // Available unique dates across all plans
  const availableDates = useMemo(() => {
    const set = new Set<string>();
    plans.forEach((p) => {
      if (p.serviceDate) set.add(p.serviceDate);
    });
    return Array.from(set).sort();
  }, [plans]);

  // Trips in current subTab
  const subTabTrips = useMemo(() => {
    return allTrips.filter((t) => {
      if (subTab === "planned") {
        return true; // All planned trips
      }
      if (subTab === "live") {
        return t.status === "IN_TRANSIT" || t.status === "LOADING" || t.status === "DELAYED";
      }
      return true;
    });
  }, [allTrips, subTab]);

  // Filtered trips
  const filteredTrips = useMemo(() => {
    return subTabTrips.filter((trip) => {
      if (dateFilter !== "all" && dateFilter && trip.serviceDate !== dateFilter) return false;
      if (depotFilter !== "all" && trip.depot !== depotFilter) return false;
      if (brandFilter !== "all" && trip.brand.toLowerCase() !== brandFilter.toLowerCase()) return false;
      if (statusFilter !== "all" && trip.status !== statusFilter) return false;

      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        const searchable = `${trip.tripId} ${trip.vehicleId} ${trip.driverName} ${trip.driverPhone} ${trip.district} ${trip.depot} ${trip.brand} ${trip.stops.map((s) => `${s.outletId} ${s.outletName}`).join(" ")}`.toLowerCase();
        if (!searchable.includes(q)) return false;
      }

      return true;
    });
  }, [subTabTrips, dateFilter, depotFilter, brandFilter, statusFilter, searchQuery]);

  // Metrics
  const totalTripsCount = allTrips.length;
  const liveTripsCount = allTrips.filter((t) => t.status === "IN_TRANSIT" || t.status === "LOADING" || t.status === "DELAYED").length;
  const completedTripsCount = allTrips.filter((t) => t.status === "COMPLETED").length;
  const plannedOnlyCount = allTrips.filter((t) => t.status === "PLANNED").length;

  const currentPlan = useMemo(() => {
    if (depotFilter !== "all") {
      return plans.find((p) => p.depot === depotFilter) || plans[0];
    }
    return plans[0];
  }, [plans, depotFilter]);

  const getStatusBadge = (status: AdminTrip["status"]) => {
    switch (status) {
      case "COMPLETED":
        return <span className="rounded-md bg-[#dcfce7] px-2 py-0.5 text-xs font-bold text-[#15803d]">COMPLETED</span>;
      case "IN_TRANSIT":
        return <span className="rounded-md bg-[#dbeafe] px-2 py-0.5 text-xs font-bold text-[#1d4ed8] animate-pulse">IN TRANSIT</span>;
      case "LOADING":
        return <span className="rounded-md bg-[#f3e8ff] px-2 py-0.5 text-xs font-bold text-[#7e22ce]">LOADING</span>;
      case "PLANNED":
        return <span className="rounded-md bg-[#e5f4ef] px-2 py-0.5 text-xs font-bold text-[#006b57]">PLANNED</span>;
      case "DELAYED":
        return <span className="rounded-md bg-[#fee2e2] px-2 py-0.5 text-xs font-bold text-[#b91c1c]">DELAYED</span>;
      default:
        return <span className="rounded-md bg-[#f0f4f2] px-2 py-0.5 text-xs font-bold text-[#4d6356]">{status}</span>;
    }
  };

  const getBrandBadge = (brand: AdminTrip["brand"]) => {
    switch (brand) {
      case "Fresh":
        return <span className="rounded-md bg-[#dcfce7] px-2 py-0.5 text-xs font-bold text-[#15803d]">Waypoint Fresh</span>;
      case "Style":
        return <span className="rounded-md bg-[#f3e8ff] px-2 py-0.5 text-xs font-bold text-[#7e22ce]">Waypoint Style</span>;
      case "Tech":
        return <span className="rounded-md bg-[#e0f2fe] px-2 py-0.5 text-xs font-bold text-[#0284c7]">Waypoint Tech</span>;
    }
  };

  return (
    <div className="space-y-6">
      {/* 2 Sub-tabs Navigation: Planned vs Live */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#dce9e5] pb-3">
        <div className="flex flex-wrap items-center gap-2">
          {/* Sub-tab 1: Planned Trips */}
          <button
            type="button"
            onClick={() => setSubTab("planned")}
            className={`flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-bold transition-all ${
              subTab === "planned"
                ? "bg-[#006b57] text-white shadow-sm"
                : "bg-white text-[#344940] border border-[#dce8e2] hover:bg-[#edf8f5]"
            }`}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <line x1="16" y1="13" x2="8" y2="13" />
              <line x1="16" y1="17" x2="8" y2="17" />
              <polyline points="10 9 9 9 8 9" />
            </svg>
            <span>Planned</span>
            <span className={`rounded-full px-2 py-0.5 text-xs ${subTab === "planned" ? "bg-white/20 text-white" : "bg-[#edf4f0] text-[#006b57]"}`}>
              {totalTripsCount}
            </span>
          </button>

          {/* Sub-tab 2: Live Trips */}
          <button
            type="button"
            onClick={() => setSubTab("live")}
            className={`flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-bold transition-all ${
              subTab === "live"
                ? "bg-[#1d4ed8] text-white shadow-sm"
                : "bg-white text-[#344940] border border-[#dce8e2] hover:bg-[#eff6ff]"
            }`}
          >
            <span className="size-2.5 rounded-full bg-[#3b82f6] animate-ping" aria-hidden="true"></span>
            <span>Live</span>
            <span className={`rounded-full px-2 py-0.5 text-xs ${subTab === "live" ? "bg-white/20 text-white" : "bg-[#dbeafe] text-[#1d4ed8]"}`}>
              {liveTripsCount}
            </span>
          </button>
        </div>

        {liveConnected !== null && (
          <Badge tone={liveConnected ? "green" : "neutral"}>
            {liveConnected ? "Live API: GET /api/admin/plans" : "Sample plans"}
          </Badge>
        )}
      </div>

      {/* KPI Overview Cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {/* Metric 1 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[#58685f]">Total Trips</span>
            <span className="grid size-9 place-items-center rounded-xl bg-[#e5f4ef] text-[#006b57]">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <rect x="1" y="3" width="15" height="13" rx="1" />
                <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
                <circle cx="5.5" cy="18.5" r="2.5" />
                <circle cx="18.5" cy="18.5" r="2.5" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-[#10251e]">{totalTripsCount}</p>
          <p className="mt-0.5 text-xs text-[#58685f]">{plannedOnlyCount} awaiting departure</p>
        </div>

        {/* Metric 2 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[#1d4ed8]">Live In-Transit</span>
            <span className="grid size-9 place-items-center rounded-xl bg-[#dbeafe] text-[#1d4ed8]">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-[#10251e]">{liveTripsCount}</p>
          <p className="mt-0.5 text-xs text-[#58685f]">Active road deliveries</p>
        </div>

        {/* Metric 3 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[#15803d]">Completed</span>
            <span className="grid size-9 place-items-center rounded-xl bg-[#dcfce7] text-[#15803d]">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-[#10251e]">{completedTripsCount}</p>
          <p className="mt-0.5 text-xs text-[#58685f]">Returned to depot</p>
        </div>

        {/* Metric 4 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[#006b57]">Fleet Utilization</span>
            <span className="grid size-9 place-items-center rounded-xl bg-[#d8f5ee] text-[#006b57]">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <path d="M21.21 15.89A10 10 0 1 1 8 2.83" />
                <path d="M22 12A10 10 0 0 0 12 2v10z" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-[#10251e]">91.4%</p>
          <p className="mt-0.5 text-xs text-[#58685f]">Authoritative weight fit</p>
        </div>
      </div>

      {/* Planning Run Summary Box (GET /api/admin/plans) */}
      {currentPlan && (
        <div className="rounded-2xl border border-[#b8e5d9] bg-[#f0fbf7] p-5 sm:p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#d6ebe0] pb-3">
            <div className="flex items-center gap-3">
              <span className="grid size-10 place-items-center rounded-xl bg-[#d8f5ee] text-[#006b57]">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                </svg>
              </span>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-base font-bold text-[#10251e]">
                    Planning Run Summary · {currentPlan.depot} Hub
                  </span>
                  <span className="rounded-md bg-[#e5f4ef] px-2 py-0.5 text-xs font-bold text-[#006b57]">
                    v{currentPlan.planVersion} {currentPlan.status}
                  </span>
                </div>
                <p className="text-xs text-[#58685f]">
                  Plan ID: {currentPlan.planId} · Service Date: {currentPlan.serviceDate}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 text-xs">
              <span className="rounded-lg bg-white border border-[#cbded5] px-3 py-1 font-semibold text-[#10251e]">
                Solver: {currentPlan.solverEngine}
              </span>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 text-xs">
            <div className="rounded-xl border border-[#d6ebe0] bg-white p-3">
              <span className="text-[#58685f]">Orders Allocated</span>
              <p className="mt-0.5 text-base font-bold text-[#10251e]">
                {currentPlan.allocatedOrders} / {currentPlan.totalOrders} Orders
              </p>
              <span className="text-[11px] font-semibold text-[#006b57]">
                {currentPlan.deferredOrders === 0 ? "100% Demand Served" : `${currentPlan.deferredOrders} Deferred`}
              </span>
            </div>

            <div className="rounded-xl border border-[#d6ebe0] bg-white p-3">
              <span className="text-[#58685f]">Total Payload Load</span>
              <p className="mt-0.5 text-base font-bold text-[#10251e]">
                {currentPlan.totalWeightKg.toLocaleString()} kg
              </p>
              <span className="text-[11px] text-[#58685f]">
                {currentPlan.totalVolumeM3.toFixed(1)} m³ total volume
              </span>
            </div>

            <div className="rounded-xl border border-[#d6ebe0] bg-white p-3">
              <span className="text-[#58685f]">Estimated Fuel</span>
              <p className="mt-0.5 text-base font-bold text-[#10251e]">
                {currentPlan.fuelEstimatedLitres} Litres
              </p>
              <span className="text-[11px] text-[#58685f]">Across all route legs</span>
            </div>

            <div className="rounded-xl border border-[#d6ebe0] bg-white p-3">
              <span className="text-[#58685f]">Governance Reference</span>
              <p className="mt-0.5 text-xs font-bold text-[#10251e] truncate">
                {currentPlan.referenceVersion}
              </p>
              <span className="text-[11px] text-[#58685f]">
                Rules: {currentPlan.ruleSetVersion}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Filter Controls Bar */}
      <div className={`${card} space-y-4 p-5 sm:p-6`}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold uppercase tracking-wider text-[#006b57]">
            Filters &amp; Search ({filteredTrips.length} trips shown)
          </span>
          {(dateFilter !== "all" || depotFilter !== "all" || brandFilter !== "all" || statusFilter !== "all" || searchQuery) && (
            <button
              type="button"
              className="text-xs font-semibold text-[#006b57] hover:underline"
              onClick={() => {
                setDateFilter("all");
                setDepotFilter("all");
                setBrandFilter("all");
                setStatusFilter("all");
                setSearchQuery("");
              }}
            >
              Reset all filters
            </button>
          )}
        </div>

        <div className="grid gap-3.5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5">
          {/* Search */}
          <label className="text-xs font-medium text-[#10251e]">
            Search Trips
            <input
              type="search"
              placeholder="Trip, vehicle, driver, outlet..."
              className={`${field} mt-1`}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </label>

          {/* Service Date */}
          <label className="text-xs font-medium text-[#10251e]">
            Service Date
            <select
              className={`${field} mt-1`}
              value={dateFilter}
              onChange={(e) => setDateFilter(e.target.value)}
            >
              <option value="all">All Dates</option>
              {availableDates.map((d) => (
                <option key={d} value={d}>
                  {d === TODAY ? `Today (${d})` : d}
                </option>
              ))}
            </select>
          </label>

          {/* Depot Hub */}
          <label className="text-xs font-medium text-[#10251e]">
            Depot Hub
            <select
              className={`${field} mt-1`}
              value={depotFilter}
              onChange={(e) => setDepotFilter(e.target.value)}
            >
              <option value="all">All Depots</option>
              <option value="PELIYAGODA">Peliyagoda</option>
              <option value="KANDY">Kandy</option>
            </select>
          </label>

          {/* Brand Category */}
          <label className="text-xs font-medium text-[#10251e]">
            Brand Category
            <select
              className={`${field} mt-1`}
              value={brandFilter}
              onChange={(e) => setBrandFilter(e.target.value)}
            >
              <option value="all">All Brands</option>
              <option value="Fresh">Waypoint Fresh</option>
              <option value="Style">Waypoint Style</option>
              <option value="Tech">Waypoint Tech</option>
            </select>
          </label>

          {/* Status */}
          <label className="text-xs font-medium text-[#10251e]">
            Trip Status
            <select
              className={`${field} mt-1`}
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="all">All Statuses</option>
              <option value="PLANNED">PLANNED</option>
              <option value="LOADING">LOADING</option>
              <option value="IN_TRANSIT">IN TRANSIT</option>
              <option value="COMPLETED">COMPLETED</option>
              <option value="DELAYED">DELAYED</option>
            </select>
          </label>
        </div>
      </div>

      {/* Trips Cards / List */}
      {filteredTrips.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filteredTrips.map((trip) => {
            const weightPct = trip.weightCapKg ? Math.round((trip.weightKg / trip.weightCapKg) * 100) : 0;
            const volumePct = Math.round((trip.volumeM3 / trip.volumeCapM3) * 100);

            return (
              <article
                key={trip.tripId}
                className={`${card} flex flex-col justify-between p-5 transition-all hover:border-[#b8e5d9] hover:shadow-md`}
              >
                <div>
                  {/* Top: Trip ID & Status */}
                  <div className="flex items-start justify-between gap-2 border-b border-[#edf4f0] pb-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-base font-bold text-[#10251e]">{trip.tripId}</span>
                        <span className="rounded-md bg-[#eef5f1] px-2 py-0.5 text-xs font-semibold text-[#2c4c3e]">
                          Trip {trip.tripNumber}
                        </span>
                      </div>
                      <p className="mt-0.5 text-xs text-[#58685f]">
                        {trip.depot} Depot · {trip.district} District
                      </p>
                    </div>
                    {getStatusBadge(trip.status)}
                  </div>

                  {/* Vehicle & Driver Info */}
                  <div className="mt-3 flex items-center justify-between gap-3 text-xs">
                    <div className="flex items-center gap-2">
                      <span className="grid size-8 place-items-center rounded-lg bg-[#e5f4ef] text-[#006b57]">
                        <VehicleTypeIcon type={trip.vehicleType} className="size-4" />
                      </span>
                      <div>
                        <span className="font-bold text-[#10251e]">{trip.vehicleId}</span>
                        <p className="text-[#58685f]">{trip.vehicleType}</p>
                      </div>
                    </div>

                    <div className="text-right">
                      <span className="font-semibold text-[#10251e]">{trip.driverName}</span>
                      <p className="text-[#58685f]">{trip.driverPhone || "Phone unavailable"}</p>
                    </div>
                  </div>

                  {/* Brand & Temperature */}
                  <div className="mt-3 flex items-center gap-2">
                    {getBrandBadge(trip.brand)}
                    <span
                      className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                        trip.temperature.startsWith("Chilled") ? "bg-[#e0f2fe] text-[#0284c7]" : "bg-[#f0f4f2] text-[#4d6356]"
                      }`}
                    >
                      {trip.temperature}
                    </span>
                  </div>

                  {/* Schedule Timings */}
                  <div className="mt-3.5 grid grid-cols-2 gap-2 rounded-xl border border-[#edf3ef] bg-[#fafcfb] p-2.5 text-xs">
                    <div>
                      <span className="text-[#58685f]">Departure</span>
                      <p className="font-bold text-[#10251e]">{trip.plannedDeparture}</p>
                    </div>
                    <div>
                      <span className="text-[#58685f]">Est. Return</span>
                      <p className="font-bold text-[#10251e]">{trip.estimatedReturn}</p>
                    </div>
                  </div>

                  {/* Load Utilization Gauges */}
                  <div className="mt-3.5 space-y-2 text-xs">
                    <div>
                      <div className="flex justify-between text-[11px]">
                        <span className="text-[#58685f]">Weight Load ({weightPct}%)</span>
                        <span className="font-bold text-[#10251e]">
                          {trip.weightKg.toLocaleString()} / {trip.weightCapKg.toLocaleString()} kg
                        </span>
                      </div>
                      <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-[#eef4f1]">
                        <div
                          className={`h-full rounded-full ${weightPct > 95 ? "bg-[#b45309]" : "bg-[#00896d]"}`}
                          style={{ width: `${Math.min(weightPct, 100)}%` }}
                        ></div>
                      </div>
                    </div>

                    <div>
                      <div className="flex justify-between text-[11px]">
                        <span className="text-[#58685f]">Volume Load ({volumePct}%)</span>
                        <span className="font-bold text-[#10251e]">
                          {trip.volumeM3.toFixed(1)} / {trip.volumeCapM3.toFixed(1)} m³
                        </span>
                      </div>
                      <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-[#eef4f1]">
                        <div
                          className="h-full rounded-full bg-[#0284c7]"
                          style={{ width: `${Math.min(volumePct, 100)}%` }}
                        ></div>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Footer: Stops count & Inspect Button */}
                <div className="mt-4 flex items-center justify-between border-t border-[#edf4f0] pt-3 text-xs">
                  <span className="font-semibold text-[#58685f]">
                    {trip.stops.length} Stops ({trip.completedStopsCount || 0} done)
                  </span>

                  <button
                    type="button"
                    onClick={() => setSelectedTrip(trip)}
                    className="flex items-center gap-1 rounded-xl border border-[#b8e5d9] bg-[#f0fbf7] px-3 py-1.5 font-bold text-[#006b57] transition-colors hover:bg-[#d8f5ee]"
                  >
                    <span>Inspect Trip</span>
                    <span aria-hidden="true">›</span>
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      ) : (
        <div className={`${card} flex flex-col items-center justify-center p-10 text-center`}>
          <span className="grid size-14 place-items-center rounded-2xl bg-[#edf3ef] text-[#58685f]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-7" aria-hidden="true">
              <rect x="1" y="3" width="15" height="13" rx="1" />
              <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
              <circle cx="5.5" cy="18.5" r="2.5" />
              <circle cx="18.5" cy="18.5" r="2.5" />
            </svg>
          </span>
          <h3 className="mt-3 text-base font-semibold text-[#10251e]">
            No matching trips in {subTab === "planned" ? "planned view" : "live execution view"}
          </h3>
          <p className="mt-1 max-w-sm text-xs text-[#58685f]">
            Try adjusting your search criteria or changing the active filters.
          </p>
        </div>
      )}

      {/* Trip Inspection Modal (GET /api/admin/plans/{id}) */}
      {selectedTrip && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="trip-detail-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-[#d6e7df] animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-start justify-between border-b border-[#edf4f0] pb-4">
              <div>
                <div className="flex items-center gap-2.5">
                  <span className="text-xl font-bold text-[#10251e]">{selectedTrip.tripId}</span>
                  <span className="rounded-md bg-[#eef5f1] px-2 py-0.5 text-xs font-semibold text-[#2c4c3e]">
                    Trip {selectedTrip.tripNumber}
                  </span>
                  {getStatusBadge(selectedTrip.status)}
                </div>
                <h3 id="trip-detail-title" className="mt-1 text-sm font-semibold text-[#10251e]">
                  {selectedTrip.depot} Hub · {selectedTrip.district} Route Sequence
                </h3>
              </div>

              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-[#58685f] hover:bg-[#f0f4f2] text-lg"
                onClick={() => setSelectedTrip(null)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            {/* Trip Details Body */}
            <div className="mt-5 space-y-5 text-sm">
              {/* Vehicle, Driver, Timings */}
              <div className="grid gap-3 sm:grid-cols-4 rounded-2xl border border-[#edf3ef] bg-[#f8faf9] p-4 text-xs">
                <div>
                  <span className="text-[#58685f]">Assigned Vehicle</span>
                  <p className="font-bold text-[#10251e] text-sm mt-0.5">
                    {selectedTrip.vehicleId} ({selectedTrip.vehicleType})
                  </p>
                </div>
                <div>
                  <span className="text-[#58685f]">Driver</span>
                  <p className="font-bold text-[#10251e] text-sm mt-0.5">{selectedTrip.driverName || "Driver unavailable"}</p>
                  <p className="text-[#58685f] text-[11px]">{selectedTrip.driverPhone || "Phone unavailable"}</p>
                </div>
                <div>
                  <span className="text-[#58685f]">Planned Departure</span>
                  <p className="font-bold text-[#10251e] text-sm mt-0.5">{selectedTrip.plannedDeparture}</p>
                  <p className="text-[#58685f] text-[11px]">Return: {selectedTrip.estimatedReturn}</p>
                </div>
                <div>
                  <span className="text-[#58685f]">Load Specifications</span>
                  <p className="font-bold text-[#006b57] text-sm mt-0.5">
                    {selectedTrip.weightKg} kg · {selectedTrip.volumeM3} m³
                  </p>
                  <p className="text-[#58685f] text-[11px]">{selectedTrip.temperature}</p>
                </div>
              </div>

              {/* Stop Sequence Timeline */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <h4 className="text-xs font-bold uppercase tracking-wider text-[#10251e]">
                    Stop Sequence Timeline ({selectedTrip.stops.length} Retail Outlets)
                  </h4>
                  <span className="text-[11px] text-[#58685f]">GET /api/admin/plans/{selectedTrip.planId}</span>
                </div>

                <div className="space-y-2.5">
                  {selectedTrip.stops.map((stop) => (
                    <div
                      key={stop.sequence}
                      className="flex flex-col gap-2 rounded-2xl border border-[#edf3ef] bg-[#fafcfb] p-3.5 text-xs sm:flex-row sm:items-center sm:justify-between hover:bg-white"
                    >
                      <div className="flex items-center gap-3">
                        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-[#d8f5ee] font-mono text-xs font-bold text-[#006b57]">
                          #{stop.sequence}
                        </span>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-[#10251e]">{stop.outletId}</span>
                            <span className="text-xs font-medium text-[#344940]">{stop.outletName || ""}</span>
                          </div>
                          <p className="text-[#58685f] text-[11px]">
                            Order Ref: {stop.orderRef} · {stop.weightKg} kg ({stop.volumeM3} m³)
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-3 self-end sm:self-auto">
                        <div className="text-right">
                          <p className="font-bold text-[#10251e]">
                            Planned: {stop.plannedArrival}
                          </p>
                          <p className="text-[11px] text-[#58685f]">
                            Window: {stop.windowOpen} - {stop.windowClose}
                          </p>
                        </div>

                        {stop.outcome && (
                          <span
                            className={`rounded-md px-2 py-0.5 text-xs font-bold ${
                              stop.outcome === "DELIVERED"
                                ? "bg-[#dcfce7] text-[#15803d]"
                                : stop.outcome === "ARRIVED"
                                ? "bg-[#dbeafe] text-[#1d4ed8]"
                                : "bg-[#fef3c7] text-[#b45309]"
                            }`}
                          >
                            {stop.outcome}
                          </span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <div className="mt-6 flex justify-end border-t border-[#edf4f0] pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setSelectedTrip(null)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
