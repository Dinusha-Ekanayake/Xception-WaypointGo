"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, card, field, secondary } from "./components";
import type { DemoState } from "./model";
import { todayInColombo } from "./model";
import {
  fetchAdminPlans,
  fetchAdminPlanDetail,
  type AdminPlan,
  type AdminTrip,
  type AdminStop,
} from "../data/plans";

export type TripsSubTab = "planned" | "live";

export function TripsScreen({
  state,
  activeSubTab,
  onNavigateTab,
}: {
  state?: DemoState;
  activeSubTab?: TripsSubTab;
  onNavigateTab?: (tab: "people" | "personas" | "vehicles" | "forecasts" | "depots" | "outlets" | "orders" | "trips" | "audit") => void;
}) {
  const subTab = activeSubTab || "planned";
  const [plans, setPlans] = useState<AdminPlan[]>([]);
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
        setPlans(page.items);
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
          setSelectedPlanDetail(null);
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

  const TODAY = todayInColombo();

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
  const measuredTrips = allTrips.filter((t) => t.weightCapKg != null && t.weightCapKg > 0);
  const fleetWeightUse = measuredTrips.length
    ? Math.round(100 * measuredTrips.reduce((sum, t) => sum + t.weightKg, 0) /
        measuredTrips.reduce((sum, t) => sum + (t.weightCapKg ?? 0), 0))
    : null;

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
        return <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-teal">PLANNED</span>;
      case "DELAYED":
        return <span className="rounded-md bg-[#fee2e2] px-2 py-0.5 text-xs font-bold text-[#b91c1c]">DELAYED</span>;
      default:
        return <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-secondary">{status}</span>;
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
      {liveConnected !== null && (
        <div className="flex justify-end">
          <Badge tone={liveConnected ? "green" : "neutral"}>
            {liveConnected ? "Live data" : "Plans unavailable"}
          </Badge>
        </div>
      )}

      {/* KPI Overview Cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {/* Metric 1 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-go-secondary">Total Trips</span>
            <span className="grid size-9 place-items-center rounded-xl bg-go-subtle text-go-teal">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <rect x="1" y="3" width="15" height="13" rx="1" />
                <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
                <circle cx="5.5" cy="18.5" r="2.5" />
                <circle cx="18.5" cy="18.5" r="2.5" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-go-ink">{totalTripsCount}</p>
          <p className="mt-0.5 text-xs text-go-secondary">{plannedOnlyCount} awaiting departure</p>
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
          <p className="mt-2 text-2xl font-bold text-go-ink">{liveTripsCount}</p>
          <p className="mt-0.5 text-xs text-go-secondary">Active road deliveries</p>
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
          <p className="mt-2 text-2xl font-bold text-go-ink">{completedTripsCount}</p>
          <p className="mt-0.5 text-xs text-go-secondary">Returned to depot</p>
        </div>

        {/* Metric 4 */}
        <div className={`${card} p-5`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Fleet Utilization</span>
            <span className="grid size-9 place-items-center rounded-xl bg-go-mint text-go-teal">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                <path d="M21.21 15.89A10 10 0 1 1 8 2.83" />
                <path d="M22 12A10 10 0 0 0 12 2v10z" />
              </svg>
            </span>
          </div>
          <p className="mt-2 text-2xl font-bold text-go-ink">{fleetWeightUse == null ? "Unavailable" : `${fleetWeightUse}%`}</p>
          <p className="mt-0.5 text-xs text-go-secondary">Authoritative weight fit</p>
        </div>
      </div>

      {/* Planning run summary */}
      {currentPlan && (
        <div className="rounded-2xl border border-go-mint bg-go-subtle p-5 sm:p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-go-rule pb-3">
            <div className="flex items-center gap-3">
              <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                </svg>
              </span>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-base font-bold text-go-ink">
                    Planning Run Summary · {currentPlan.depot} Hub
                  </span>
                  <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-teal">
                    v{currentPlan.planVersion} {currentPlan.status}
                  </span>
                </div>
                <p className="text-xs text-go-secondary">
                  Plan ID: {currentPlan.planId} · Service Date: {currentPlan.serviceDate}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 text-xs">
              <span className="rounded-lg bg-white border border-go-rule px-3 py-1 font-semibold text-go-ink">
                Solver: {currentPlan.solverEngine}
              </span>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 text-xs">
            <div className="rounded-xl border border-go-rule bg-white p-3">
              <span className="text-go-secondary">Orders Allocated</span>
              <p className="mt-0.5 text-base font-bold text-go-ink">
                {currentPlan.allocatedOrders} / {currentPlan.totalOrders} Orders
              </p>
              <span className="text-[11px] font-semibold text-go-teal">
                {currentPlan.deferredOrders === 0 ? "100% Demand Served" : `${currentPlan.deferredOrders} Deferred`}
              </span>
            </div>

            <div className="rounded-xl border border-go-rule bg-white p-3">
              <span className="text-go-secondary">Total Payload Load</span>
              <p className="mt-0.5 text-base font-bold text-go-ink">
                {currentPlan.totalWeightKg.toLocaleString()} kg
              </p>
              <span className="text-[11px] text-go-secondary">
                {currentPlan.totalVolumeM3.toFixed(1)} m³ total volume
              </span>
            </div>

            <div className="rounded-xl border border-go-rule bg-white p-3">
              <span className="text-go-secondary">Estimated Fuel</span>
              <p className="mt-0.5 text-base font-bold text-go-ink">
                {currentPlan.fuelEstimatedLitres == null ? "Unavailable" : `${currentPlan.fuelEstimatedLitres} litres`}
              </p>
              <span className="text-[11px] text-go-secondary">Across all trip legs</span>
            </div>

            <div className="rounded-xl border border-go-rule bg-white p-3">
              <span className="text-go-secondary">Governance Reference</span>
              <p className="mt-0.5 text-xs font-bold text-go-ink truncate">
                {currentPlan.referenceVersion}
              </p>
              <span className="text-[11px] text-go-secondary">
                Rules: {currentPlan.ruleSetVersion}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Filter Controls Bar */}
      <div className={`${card} space-y-4 p-5 sm:p-6`}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold uppercase tracking-wider text-go-teal">
            Filters &amp; Search ({filteredTrips.length} trips shown)
          </span>
          {(dateFilter !== "all" || depotFilter !== "all" || brandFilter !== "all" || statusFilter !== "all" || searchQuery) && (
            <button
              type="button"
              className="text-xs font-semibold text-go-teal hover:underline"
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
          <label className="text-xs font-medium text-go-ink">
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
          <label className="text-xs font-medium text-go-ink">
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
          <label className="text-xs font-medium text-go-ink">
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
          <label className="text-xs font-medium text-go-ink">
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
          <label className="text-xs font-medium text-go-ink">
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

      {/* Compact trip list. The inspection dialog holds the full trip details. */}
      {filteredTrips.length > 0 ? (
        <div className="space-y-2">
          {filteredTrips.map((trip) => (
            <article key={trip.tripId} className={`${card} flex flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3 sm:px-5`}>
              <div className="min-w-0 flex-1 basis-full sm:basis-56">
                <p className="font-semibold text-go-ink">Trip {trip.tripNumber} <span className="ml-1 text-xs font-normal text-go-secondary">ID {trip.tripId.slice(-8)}</span></p>
                <p className="mt-0.5 truncate text-sm text-go-secondary">{trip.depot} Depot · {trip.district} District</p>
              </div>
              <div className="min-w-24 text-sm">
                <p className="text-xs text-go-secondary">Vehicle</p>
                <p className="font-semibold text-go-ink">{trip.vehicleId}</p>
              </div>
              <div className="min-w-24 text-sm">
                <p className="text-xs text-go-secondary">Departure</p>
                <p className="font-semibold text-go-ink">{trip.plannedDeparture}</p>
              </div>
              <div className="min-w-20 text-sm">
                <p className="text-xs text-go-secondary">Stops</p>
                <p className="font-semibold text-go-ink">{trip.completedStopsCount || 0} / {trip.stops.length} done</p>
              </div>
              <div className="flex min-w-28 items-center">{getStatusBadge(trip.status)}</div>
              <button
                type="button"
                onClick={() => setSelectedTrip(trip)}
                className="min-h-11 rounded-xl border border-go-mint bg-go-subtle px-4 text-sm font-semibold text-go-teal hover:bg-go-mint"
                aria-label={`More info for trip ${trip.tripNumber}, ${trip.tripId}`}
              >
                More info <span aria-hidden="true">›</span>
              </button>
            </article>
          ))}
        </div>
      ) : (
        <div className={`${card} flex flex-col items-center justify-center p-10 text-center`}>
          <span className="grid size-14 place-items-center rounded-2xl bg-go-subtle text-go-secondary">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-7" aria-hidden="true">
              <rect x="1" y="3" width="15" height="13" rx="1" />
              <polygon points="16 8 20 8 23 11 23 16 16 16 16 8" />
              <circle cx="5.5" cy="18.5" r="2.5" />
              <circle cx="18.5" cy="18.5" r="2.5" />
            </svg>
          </span>
          <h3 className="mt-3 text-base font-semibold text-go-ink">
            No matching trips in {subTab === "planned" ? "planned view" : "live execution view"}
          </h3>
          <p className="mt-1 max-w-sm text-xs text-go-secondary">
            Try adjusting your search criteria or changing the active filters.
          </p>
        </div>
      )}

      {/* Trip inspection details */}
      {selectedTrip && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="trip-detail-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-start justify-between border-b border-go-subtle pb-4">
              <div>
                <div className="flex items-center gap-2.5">
                  <span className="text-xl font-bold text-go-ink">{selectedTrip.tripId}</span>
                  <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-semibold text-go-ink">
                    Trip {selectedTrip.tripNumber}
                  </span>
                  {getStatusBadge(selectedTrip.status)}
                </div>
                <h3 id="trip-detail-title" className="mt-1 text-sm font-semibold text-go-ink">
                  {selectedTrip.depot} Hub · {selectedTrip.district} Stop Sequence
                </h3>
              </div>

              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => setSelectedTrip(null)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            {/* Trip Details Body */}
            <div className="mt-5 space-y-5 text-sm">
              {/* Vehicle, Driver, Timings */}
              <div className="grid gap-3 sm:grid-cols-4 rounded-2xl border border-go-subtle bg-go-subtle p-4 text-xs">
                <div>
                  <span className="text-go-secondary">Assigned Vehicle</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">
                    {selectedTrip.vehicleId} ({selectedTrip.vehicleType})
                  </p>
                </div>
                <div>
                  <span className="text-go-secondary">Driver</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">{selectedTrip.driverName || "Driver unavailable"}</p>
                  <p className="text-go-secondary text-[11px]">{selectedTrip.driverPhone || "Phone unavailable"}</p>
                </div>
                <div>
                  <span className="text-go-secondary">Planned Departure</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">{selectedTrip.plannedDeparture}</p>
                  <p className="text-go-secondary text-[11px]">Return: {selectedTrip.estimatedReturn}</p>
                </div>
                <div>
                  <span className="text-go-secondary">Load Specifications</span>
                  <p className="mt-0.5 font-bold text-go-teal text-sm">
                    {selectedTrip.weightKg.toLocaleString()} / {selectedTrip.weightCapKg == null ? "Unavailable" : selectedTrip.weightCapKg.toLocaleString()} kg
                  </p>
                  <p className="text-go-secondary text-[11px]">
                    {selectedTrip.volumeM3.toFixed(1)} / {selectedTrip.volumeCapM3 == null ? "Unavailable" : selectedTrip.volumeCapM3.toFixed(1)} m³
                  </p>
                  <div className="mt-2 flex flex-wrap gap-1">{getBrandBadge(selectedTrip.brand)}<span className="text-go-secondary">{selectedTrip.temperature}</span></div>
                </div>
              </div>

              {/* Stop Sequence Timeline */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <h4 className="text-xs font-bold uppercase tracking-wider text-go-ink">
                    Stop Sequence Timeline ({selectedTrip.stops.length} Retail Outlets)
                  </h4>
                </div>

                <div className="space-y-2.5">
                  {selectedTrip.stops.map((stop) => (
                    <div
                      key={stop.sequence}
                      className="flex flex-col gap-2 rounded-2xl border border-go-subtle bg-go-subtle p-3.5 text-xs sm:flex-row sm:items-center sm:justify-between hover:bg-white"
                    >
                      <div className="flex items-center gap-3">
                        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-go-mint font-mono text-xs font-bold text-go-teal">
                          #{stop.sequence}
                        </span>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-go-ink">{stop.outletId}</span>
                            <span className="text-xs font-medium text-go-secondary">{stop.outletName || ""}</span>
                          </div>
                          <p className="text-go-secondary text-[11px]">
                            Order ID: {stop.orderId} · {stop.weightKg == null ? "Weight unavailable" : `${stop.weightKg} kg`} ({stop.volumeM3 == null ? "volume unavailable" : `${stop.volumeM3} m³`})
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-3 self-end sm:self-auto">
                        <div className="text-right">
                          <p className="font-bold text-go-ink">
                            Planned: {stop.plannedArrival}
                          </p>
                          <p className="text-[11px] text-go-secondary">
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

            <div className="mt-6 flex justify-end border-t border-go-subtle pt-4">
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
