"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, card, field, primary, secondary } from "./components";
import type { DemoState } from "./model";
import {
  fetchAdminOrders,
  fetchAdminOrderDetail,
  fetchAdminOrderTimeline,
  type AdminOrder,
  type AdminOrderDetail,
  type AdminOrderLine,
  type AdminStatusTimeline,
} from "../data/orders";

type SubTab = "today" | "deferred" | "all";

export function getOutletWindow(outletId: string, brand?: string) {
  if (brand?.toLowerCase() === "fresh") return { windowOpen: "05:00", windowClose: "07:30", label: "05:00 - 07:30", notes: "Early morning fresh produce slot." };
  if (brand?.toLowerCase() === "style") return { windowOpen: "10:00", windowClose: "13:00", label: "10:00 - 13:00", notes: "Midday retail fashion delivery." };
  if (brand?.toLowerCase() === "tech") return { windowOpen: "14:00", windowClose: "17:30", label: "14:00 - 17:30", notes: "Afternoon secure electronics bay." };
  return { windowOpen: "08:00", windowClose: "17:00", label: "08:00 - 17:00", notes: "Standard delivery window." };
}

export function matchesTimeSlot(windowOpen: string, windowClose: string, timeFilter: string): boolean {
  if (timeFilter === "all") return true;
  if (timeFilter === "early_morning") {
    return windowOpen < "08:00" || windowClose <= "08:30";
  }
  if (timeFilter === "morning") {
    return windowOpen >= "08:00" && windowOpen < "12:00";
  }
  if (timeFilter === "afternoon") {
    return (windowOpen >= "12:00" && windowOpen < "17:00") || (windowClose > "12:00" && windowClose <= "17:30" && windowOpen >= "10:00");
  }
  if (timeFilter === "evening") {
    return windowClose > "17:00" || windowOpen >= "16:00";
  }
  return true;
}

export function OrdersScreen({
  state,
  onNavigateTab,
}: {
  state?: DemoState;
  onNavigateTab?: (tab: "people" | "personas" | "vehicles" | "forecasts" | "depots" | "outlets" | "audit") => void;
}) {
  const [subTab, setSubTab] = useState<SubTab>("today");
  const [orders, setOrders] = useState<AdminOrder[]>([]);
  const windowFor = getOutletWindow;
  const [loading, setLoading] = useState(false);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);

  // Filters
  const [searchQuery, setSearchQuery] = useState("");
  const [dateFilter, setDateFilter] = useState("all");
  const [timeFilter, setTimeFilter] = useState("all");
  const [brandFilter, setBrandFilter] = useState("all");
  const [depotFilter, setDepotFilter] = useState("all");
  const [tempFilter, setTempFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");

  // Inspection Modal
  const [selectedOrder, setSelectedOrder] = useState<AdminOrder | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [orderLines, setOrderLines] = useState<AdminOrderLine[]>([]);
  const [timeline, setTimeline] = useState<AdminStatusTimeline[]>([]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchAdminOrders({ depot: depotFilter, limit: 200 }).then((page) => {
      if (cancelled) return;
      setOrders(page.items);
      setLiveConnected(true);
    }).catch(() => { if (!cancelled) setLiveConnected(false); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [depotFilter]);

  // Load detail and timeline when an order is selected
  useEffect(() => {
    if (!selectedOrder) {
      setOrderLines([]);
      setTimeline([]);
      return;
    }

    const currentOrder = selectedOrder;
    let cancelled = false;
    async function loadDetail() {
      setDetailLoading(true);
      try {
        const [detailRes, timelineRes] = await Promise.all([
          fetchAdminOrderDetail(currentOrder.orderId).catch(() => null),
          fetchAdminOrderTimeline(currentOrder.orderId).catch(() => null),
        ]);

        if (cancelled) return;

        if (detailRes?.lines) {
          setOrderLines(detailRes.lines);
        } else {
          setOrderLines([]);
        }

        if (timelineRes) {
          setTimeline(timelineRes);
        } else {
          setTimeline([]);
        }
      } catch {
        if (!cancelled) {
          setOrderLines([]);
          setTimeline([]);
        }
      } finally {
        if (!cancelled) setDetailLoading(false);
      }
    }

    void loadDetail();
    return () => {
      cancelled = true;
    };
  }, [selectedOrder]);

  const TODAY_DATE = "2026-10-03";

  // Unique dates available across all orders
  const availableDates = useMemo(() => {
    const set = new Set<string>();
    orders.forEach((o) => {
      if (o.deliveryDate) set.add(o.deliveryDate);
    });
    return Array.from(set).sort();
  }, [orders]);

  // Filter based on active subTab
  const subTabOrders = useMemo(() => {
    return orders.filter((o) => {
      if (subTab === "today") {
        return o.deliveryDate === TODAY_DATE && o.status !== "DEFERRED";
      }
      if (subTab === "deferred") {
        return o.status === "DEFERRED";
      }
      return true; // all orders
    });
  }, [orders, subTab, TODAY_DATE]);

  // Orders in view after subTab + date + time filter for metrics
  const currentViewOrders = useMemo(() => {
    return subTabOrders.filter((o) => {
      if (dateFilter !== "all" && dateFilter && o.deliveryDate !== dateFilter) return false;
      if (timeFilter !== "all" && timeFilter) {
        const win = windowFor(o.outletId, o.brand);
        if (!matchesTimeSlot(win.windowOpen, win.windowClose, timeFilter)) return false;
      }
      return true;
    });
  }, [subTabOrders, dateFilter, timeFilter]);

  // Filter based on user controls
  const filteredOrders = useMemo(() => {
    return subTabOrders.filter((order) => {
      if (dateFilter !== "all" && dateFilter && order.deliveryDate !== dateFilter) return false;
      if (timeFilter !== "all" && timeFilter) {
        const win = windowFor(order.outletId, order.brand);
        if (!matchesTimeSlot(win.windowOpen, win.windowClose, timeFilter)) return false;
      }
      if (brandFilter !== "all" && order.brand.toLowerCase() !== brandFilter.toLowerCase()) return false;
      if (depotFilter !== "all" && order.depot !== depotFilter) return false;
      if (tempFilter !== "all" && order.temperature.toLowerCase() !== tempFilter.toLowerCase()) return false;
      if (statusFilter !== "all" && order.status !== statusFilter) return false;

      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        const win = windowFor(order.outletId, order.brand);
        const searchable = `${order.orderRef} ${order.outletId} ${order.brand} ${order.depot} ${order.status} ${order.temperature} ${order.deliveryDate} ${win.label}`.toLowerCase();
        if (!searchable.includes(q)) return false;
      }

      return true;
    });
  }, [subTabOrders, dateFilter, timeFilter, brandFilter, depotFilter, tempFilter, statusFilter, searchQuery]);

  // Tab counts
  const todayCount = useMemo(() => orders.filter((o) => o.deliveryDate === TODAY_DATE && o.status !== "DEFERRED").length, [orders, TODAY_DATE]);
  const deferredCount = useMemo(() => orders.filter((o) => o.status === "DEFERRED").length, [orders]);
  const allCount = orders.length;

  // Brand Category Intake Metrics (based on current view)
  const freshStats = useMemo(() => {
    const list = currentViewOrders.filter((o) => o.brand.toLowerCase() === "fresh");
    const count = list.length;
    const weight = list.reduce((sum, o) => sum + (Number(o.weightKg) || 0), 0);
    const volume = list.reduce((sum, o) => sum + (Number(o.volumeM3) || 0), 0);
    const chilled = list.filter((o) => o.temperature.toLowerCase() === "chilled").length;
    const ambient = list.filter((o) => o.temperature.toLowerCase() === "ambient").length;
    return { count, weight, volume, chilled, ambient };
  }, [currentViewOrders]);

  const styleStats = useMemo(() => {
    const list = currentViewOrders.filter((o) => o.brand.toLowerCase() === "style");
    const count = list.length;
    const weight = list.reduce((sum, o) => sum + (Number(o.weightKg) || 0), 0);
    const volume = list.reduce((sum, o) => sum + (Number(o.volumeM3) || 0), 0);
    return { count, weight, volume };
  }, [currentViewOrders]);

  const techStats = useMemo(() => {
    const list = currentViewOrders.filter((o) => o.brand.toLowerCase() === "tech");
    const count = list.length;
    const weight = list.reduce((sum, o) => sum + (Number(o.weightKg) || 0), 0);
    const volume = list.reduce((sum, o) => sum + (Number(o.volumeM3) || 0), 0);
    return { count, weight, volume };
  }, [currentViewOrders]);

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "CONFIRMED":
        return <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-teal">CONFIRMED</span>;
      case "ALLOCATED":
        return <span className="rounded-md bg-[#e0f2fe] px-2 py-0.5 text-xs font-bold text-[#0284c7]">ALLOCATED</span>;
      case "LOADING":
        return <span className="rounded-md bg-[#f3e8ff] px-2 py-0.5 text-xs font-bold text-[#7e22ce]">LOADING</span>;
      case "IN_TRANSIT":
        return <span className="rounded-md bg-[#dbeafe] px-2 py-0.5 text-xs font-bold text-[#1d4ed8]">IN TRANSIT</span>;
      case "DELIVERED":
      case "RECEIVED":
        return <span className="rounded-md bg-[#dcfce7] px-2 py-0.5 text-xs font-bold text-[#15803d]">DELIVERED</span>;
      case "DEFERRED":
        return <span className="rounded-md bg-[#fef3c7] px-2 py-0.5 text-xs font-bold text-[#b45309]">DEFERRED</span>;
      case "CANCELLED":
      case "FAILED":
        return <span className="rounded-md bg-[#fee2e2] px-2 py-0.5 text-xs font-bold text-[#b91c1c]">{status}</span>;
      default:
        return <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-secondary">{status}</span>;
    }
  };

  const getBrandBadge = (brand: string) => {
    switch (brand) {
      case "Fresh":
        return <span className="rounded-md bg-[#dcfce7] px-2 py-0.5 text-xs font-bold text-[#15803d]">Fresh</span>;
      case "Style":
        return <span className="rounded-md bg-[#f3e8ff] px-2 py-0.5 text-xs font-bold text-[#7e22ce]">Style</span>;
      case "Tech":
        return <span className="rounded-md bg-[#e0f2fe] px-2 py-0.5 text-xs font-bold text-[#0284c7]">Tech</span>;
      default:
        return <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-bold text-go-secondary">{brand}</span>;
    }
  };

  return (
    <div className="space-y-6">
      {/* 3 Sub-tabs Navigation */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-go-rule pb-3">
        <div className="flex flex-wrap items-center gap-2">
          {/* Sub-tab 1: Today's Orders */}
          <button
            type="button"
            onClick={() => setSubTab("today")}
            className={`flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-bold transition-all ${
              subTab === "today"
                ? "bg-go-teal text-white shadow-sm"
                : "bg-white text-go-secondary border border-go-rule hover:bg-go-subtle"
            }`}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
              <line x1="16" y1="2" x2="16" y2="6" />
              <line x1="8" y1="2" x2="8" y2="6" />
              <line x1="3" y1="10" x2="21" y2="10" />
            </svg>
            <span>Today's orders</span>
            <span className={`rounded-full px-2 py-0.5 text-xs ${subTab === "today" ? "bg-white/20 text-white" : "bg-go-subtle text-go-teal"}`}>
              {todayCount}
            </span>
          </button>

          {/* Sub-tab 2: Deferred Ones */}
          <button
            type="button"
            onClick={() => setSubTab("deferred")}
            className={`flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-bold transition-all ${
              subTab === "deferred"
                ? "bg-[#b45309] text-white shadow-sm"
                : "bg-white text-go-secondary border border-go-rule hover:bg-[#fffbeb]"
            }`}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>Deferred ones</span>
            <span className={`rounded-full px-2 py-0.5 text-xs ${subTab === "deferred" ? "bg-white/20 text-white" : "bg-[#fef3c7] text-[#b45309]"}`}>
              {deferredCount}
            </span>
          </button>

          {/* Sub-tab 3: All Orders */}
          <button
            type="button"
            onClick={() => setSubTab("all")}
            className={`flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-bold transition-all ${
              subTab === "all"
                ? "bg-[#1e293b] text-white shadow-sm"
                : "bg-white text-go-secondary border border-go-rule hover:bg-[#f1f5f9]"
            }`}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <line x1="16" y1="13" x2="8" y2="13" />
              <line x1="16" y1="17" x2="8" y2="17" />
              <polyline points="10 9 9 9 8 9" />
            </svg>
            <span>All orders</span>
            <span className={`rounded-full px-2 py-0.5 text-xs ${subTab === "all" ? "bg-white/20 text-white" : "bg-[#f1f5f9] text-go-secondary"}`}>
              {allCount}
            </span>
          </button>
        </div>

        {liveConnected !== null && (
          <Badge tone={liveConnected ? "green" : "neutral"}>
            {liveConnected ? "Live API: GET /api/admin/orders" : "Connecting..."}
          </Badge>
        )}
      </div>

      {/* Three Category Cards: Waypoint Fresh, Waypoint Style, Waypoint Tech */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {/* Category 1: Waypoint Fresh */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Fresh" ? "all" : "Fresh")}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Fresh" ? "all" : "Fresh");
          }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter.toLowerCase() === "fresh"
              ? "border-2 border-go-teal bg-gradient-to-b from-[#e8f7f2] to-[#f5fbf8] shadow-md ring-2 ring-go-mint"
              : "hover:border-go-mint hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <span className="grid size-11 place-items-center rounded-2xl bg-[#dcfce7] text-[#15803d] shadow-2xs">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5.5" aria-hidden="true">
                  <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <path d="M16 10a4 4 0 0 1-8 0" />
                </svg>
              </span>
              <div>
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#15803d]">Category</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Fresh</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#dcfce7] px-2.5 py-1 text-sm font-extrabold text-[#15803d]">
              {freshStats.count} {freshStats.count === 1 ? "order" : "orders"}
            </span>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-2 border-t border-go-subtle pt-3 text-xs">
            <div>
              <p className="text-go-secondary">Total Weight</p>
              <p className="text-sm font-bold text-go-ink">
                {freshStats.weight.toLocaleString(undefined, { maximumFractionDigits: 1 })} kg
              </p>
            </div>
            <div>
              <p className="text-go-secondary">Total Volume</p>
              <p className="text-sm font-bold text-go-ink">{freshStats.volume.toFixed(1)} m³</p>
            </div>
          </div>

          <div className="mt-2.5 flex items-center justify-between border-t border-go-subtle/60 pt-2 text-[11px] text-go-secondary">
            <span>
              Split: <strong className="font-semibold text-go-ink">{freshStats.chilled} Chl · {freshStats.ambient} Amb</strong>
            </span>
            <span className="font-medium text-go-teal">Daily &lt; 08:00</span>
          </div>
        </div>

        {/* Category 2: Waypoint Style */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Style" ? "all" : "Style")}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Style" ? "all" : "Style");
          }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter.toLowerCase() === "style"
              ? "border-2 border-[#7e22ce] bg-gradient-to-b from-[#f9f5ff] to-[#fdfcff] shadow-md ring-2 ring-[#d8b4fe]"
              : "hover:border-[#d8b4fe] hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <span className="grid size-11 place-items-center rounded-2xl bg-[#f3e8ff] text-[#7e22ce] shadow-2xs">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5.5" aria-hidden="true">
                  <path d="M20.38 3.46L16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z" />
                </svg>
              </span>
              <div>
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#7e22ce]">Category</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Style</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#f3e8ff] px-2.5 py-1 text-sm font-extrabold text-[#7e22ce]">
              {styleStats.count} {styleStats.count === 1 ? "order" : "orders"}
            </span>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-2 border-t border-go-subtle pt-3 text-xs">
            <div>
              <p className="text-go-secondary">Total Weight</p>
              <p className="text-sm font-bold text-go-ink">
                {styleStats.weight.toLocaleString(undefined, { maximumFractionDigits: 1 })} kg
              </p>
            </div>
            <div>
              <p className="text-go-secondary">Total Volume</p>
              <p className="text-sm font-bold text-go-ink">{styleStats.volume.toFixed(1)} m³</p>
            </div>
          </div>

          <div className="mt-2.5 flex items-center justify-between border-t border-go-subtle/60 pt-2 text-[11px] text-go-secondary">
            <span>
              Type: <strong className="font-semibold text-go-ink">Apparel &amp; Cartons</strong>
            </span>
            <span className="font-medium text-[#7e22ce]">Midday windows</span>
          </div>
        </div>

        {/* Category 3: Waypoint Tech */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Tech" ? "all" : "Tech")}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Tech" ? "all" : "Tech");
          }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter.toLowerCase() === "tech"
              ? "border-2 border-[#0284c7] bg-gradient-to-b from-[#f0f9ff] to-[#f8fcff] shadow-md ring-2 ring-[#7dd3fc]"
              : "hover:border-[#7dd3fc] hover:bg-go-subtle"
          }`}
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <span className="grid size-11 place-items-center rounded-2xl bg-[#e0f2fe] text-[#0284c7] shadow-2xs">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5.5" aria-hidden="true">
                  <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                  <line x1="8" y1="21" x2="16" y2="21" />
                  <line x1="12" y1="17" x2="12" y2="21" />
                </svg>
              </span>
              <div>
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#0284c7]">Category</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Tech</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#e0f2fe] px-2.5 py-1 text-sm font-extrabold text-[#0284c7]">
              {techStats.count} {techStats.count === 1 ? "order" : "orders"}
            </span>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-2 border-t border-go-subtle pt-3 text-xs">
            <div>
              <p className="text-go-secondary">Total Weight</p>
              <p className="text-sm font-bold text-go-ink">
                {techStats.weight.toLocaleString(undefined, { maximumFractionDigits: 1 })} kg
              </p>
            </div>
            <div>
              <p className="text-go-secondary">Total Volume</p>
              <p className="text-sm font-bold text-go-ink">{techStats.volume.toFixed(1)} m³</p>
            </div>
          </div>

          <div className="mt-2.5 flex items-center justify-between border-t border-go-subtle/60 pt-2 text-[11px] text-go-secondary">
            <span>
              Type: <strong className="font-semibold text-go-ink">Consumer Electronics</strong>
            </span>
            <span className="font-medium text-[#0284c7]">Secure Bays</span>
          </div>
        </div>
      </div>

      {/* Filter Controls Bar */}
      <div className={`${card} space-y-4 p-5 sm:p-6`}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold uppercase tracking-wider text-go-teal">
            Filters &amp; Search ({filteredOrders.length} orders shown)
          </span>
          {(dateFilter !== "all" || timeFilter !== "all" || brandFilter !== "all" || depotFilter !== "all" || tempFilter !== "all" || statusFilter !== "all" || searchQuery) && (
            <button
              type="button"
              className="text-xs font-semibold text-go-teal hover:underline"
              onClick={() => {
                setDateFilter("all");
                setTimeFilter("all");
                setBrandFilter("all");
                setDepotFilter("all");
                setTempFilter("all");
                setStatusFilter("all");
                setSearchQuery("");
              }}
            >
              Reset all filters
            </button>
          )}
        </div>

        <div className="grid gap-3.5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-7">
          {/* Search */}
          <label className="text-xs font-medium text-go-ink">
            Search Orders
            <input
              type="search"
              placeholder="Ref, outlet, brand..."
              className={`${field} mt-1`}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </label>

          {/* Delivery Date Filter */}
          <label className="text-xs font-medium text-go-ink">
            Delivery Date
            <select
              className={`${field} mt-1`}
              value={dateFilter}
              onChange={(e) => setDateFilter(e.target.value)}
            >
              <option value="all">All Dates</option>
              {availableDates.map((d) => (
                <option key={d} value={d}>
                  {d === TODAY_DATE ? `Today (${d})` : d === "2026-10-04" ? `Tomorrow (${d})` : d === "2026-10-02" ? `Yesterday (${d})` : d}
                </option>
              ))}
            </select>
          </label>

          {/* Delivery Time Window Filter */}
          <label className="text-xs font-medium text-go-ink">
            Time Window
            <select
              className={`${field} mt-1`}
              value={timeFilter}
              onChange={(e) => setTimeFilter(e.target.value)}
            >
              <option value="all">All Time Windows</option>
              <option value="early_morning">Early Morning (&lt; 08:00)</option>
              <option value="morning">Morning (08:00 - 12:00)</option>
              <option value="afternoon">Afternoon (12:00 - 17:00)</option>
              <option value="evening">Evening (&gt; 17:00)</option>
            </select>
          </label>

          {/* Brand Filter */}
          <label className="text-xs font-medium text-go-ink">
            Brand
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

          {/* Depot Filter */}
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

          {/* Temperature Requirement */}
          <label className="text-xs font-medium text-go-ink">
            Temperature
            <select
              className={`${field} mt-1`}
              value={tempFilter}
              onChange={(e) => setTempFilter(e.target.value)}
            >
              <option value="all">All Temperatures</option>
              <option value="ambient">Ambient</option>
              <option value="chilled">Chilled (Refrigerated)</option>
            </select>
          </label>

          {/* Status Filter */}
          <label className="text-xs font-medium text-go-ink">
            Order Status
            <select
              className={`${field} mt-1`}
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="all">All Statuses</option>
              <option value="CONFIRMED">CONFIRMED</option>
              <option value="ALLOCATED">ALLOCATED</option>
              <option value="LOADING">LOADING</option>
              <option value="IN_TRANSIT">IN TRANSIT</option>
              <option value="DELIVERED">DELIVERED</option>
              <option value="DEFERRED">DEFERRED</option>
              <option value="CANCELLED">CANCELLED</option>
            </select>
          </label>
        </div>
      </div>

      {/* Orders Table / List */}
      {filteredOrders.length > 0 ? (
        <div className={`${card} divide-y divide-go-subtle overflow-hidden`}>
          <div className="hidden bg-go-subtle px-5 py-3 text-xs font-bold uppercase tracking-wider text-go-secondary md:grid md:grid-cols-12 md:gap-4">
            <span className="md:col-span-3">Order Ref &amp; Brand</span>
            <span className="md:col-span-2">Outlet &amp; Depot</span>
            <span className="md:col-span-2">Date &amp; Window</span>
            <span className="md:col-span-2">Authoritative Payload</span>
            <span className="md:col-span-2">Status &amp; Temp</span>
            <span className="md:col-span-1 text-right">Action</span>
          </div>

          {filteredOrders.map((order) => (
            <article
              key={order.orderId}
              className="flex flex-col gap-3 p-4 transition-colors hover:bg-go-subtle md:grid md:grid-cols-12 md:items-center md:gap-4 md:px-5 md:py-4"
            >
              {/* Order Ref & Brand */}
              <div className="md:col-span-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-bold text-go-ink">{order.orderRef}</span>
                  {getBrandBadge(order.brand)}
                </div>
                <p className="mt-0.5 text-xs text-go-secondary truncate">
                  ID: {order.orderId.slice(0, 8)}...
                </p>
              </div>

              {/* Outlet & Depot */}
              <div className="md:col-span-2">
                <span className="text-xs font-semibold text-go-ink">{order.outletId}</span>
                <p className="text-xs text-go-secondary">{order.depot} Depot</p>
              </div>

              {/* Delivery Date & Time Window */}
              <div className="md:col-span-2">
                <div className="flex items-center gap-1.5">
                  <span className="text-xs font-medium text-go-ink">{order.deliveryDate}</span>
                  {order.deliveryDate === TODAY_DATE && (
                    <span className="rounded-md bg-go-subtle px-1.5 py-0.2 text-[10px] font-bold text-go-teal">
                      TODAY
                    </span>
                  )}
                </div>
                <div className="mt-0.5 flex items-center gap-1 text-[11px] font-semibold text-go-teal">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-3 shrink-0" aria-hidden="true">
                    <circle cx="12" cy="12" r="10" />
                    <polyline points="12 6 12 12 16 14" />
                  </svg>
                  <span>{windowFor(order.outletId, order.brand).label}</span>
                </div>
                <p className="mt-0.5 text-[10px] text-go-secondary">{order.itemCount} items</p>
              </div>

              {/* Authoritative Payload (Weight & Volume) */}
              <div className="md:col-span-2">
                <div className="text-xs font-bold text-go-ink">
                  {Number(order.weightKg).toLocaleString()} kg
                </div>
                <div className="text-xs text-go-secondary">
                  {Number(order.volumeM3).toFixed(1)} m³ capacity
                </div>
              </div>

              {/* Status & Temp */}
              <div className="flex flex-wrap items-center gap-2 md:col-span-2">
                {getStatusBadge(order.status)}
                <span
                  className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                    order.temperature.toLowerCase() === "chilled"
                      ? "bg-[#e0f2fe] text-[#0284c7]"
                      : "bg-go-subtle text-go-secondary"
                  }`}
                >
                  {order.temperature.toLowerCase() === "chilled" ? "Chilled (Refrigerated)" : "Ambient"}
                </span>
              </div>

              {/* Action Button */}
              <div className="flex justify-end md:col-span-1">
                <button
                  type="button"
                  onClick={() => setSelectedOrder(order)}
                  className="flex items-center gap-1 rounded-xl border border-go-mint bg-go-subtle px-3 py-1.5 text-xs font-bold text-go-teal transition-colors hover:bg-go-mint"
                >
                  <span>Details</span>
                  <span aria-hidden="true">›</span>
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className={`${card} flex flex-col items-center justify-center p-10 text-center`}>
          <span className="grid size-14 place-items-center rounded-2xl bg-go-subtle text-go-secondary">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-7" aria-hidden="true">
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
          </span>
          <h3 className="mt-3 text-base font-semibold text-go-ink">No matching orders in {subTab === "today" ? "today's list" : subTab === "deferred" ? "deferred list" : "all orders"}</h3>
          <p className="mt-1 max-w-sm text-xs text-go-secondary">
            Try adjusting your search criteria or switching to the All orders tab.
          </p>
        </div>
      )}

      {/* Order Detail & Timeline Modal */}
      {selectedOrder && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="order-detail-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-start justify-between border-b border-go-subtle pb-4">
              <div>
                <div className="flex items-center gap-2.5">
                  <span className="text-xl font-bold text-go-ink">{selectedOrder.orderRef}</span>
                  {getBrandBadge(selectedOrder.brand)}
                  {getStatusBadge(selectedOrder.status)}
                </div>
                <p id="order-detail-title" className="mt-1 text-xs text-go-secondary">
                  Order UUID: <code className="font-mono text-[11px] text-go-ink">{selectedOrder.orderId}</code> · Version: {selectedOrder.rowVersion}
                </p>
              </div>

              <button
                type="button"
                className="grid size-9 place-items-center rounded-full text-go-secondary hover:bg-go-subtle text-lg"
                onClick={() => setSelectedOrder(null)}
                aria-label="Close"
              >
                ✕
              </button>
            </div>

            <div className="mt-5 space-y-5 text-sm">
              {/* Order Meta & Destination */}
              <div className="grid gap-3 sm:grid-cols-5 rounded-2xl border border-go-subtle bg-go-subtle p-4 text-xs">
                <div>
                  <span className="text-go-secondary">Outlet Destination</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">{selectedOrder.outletId}</p>
                </div>
                <div>
                  <span className="text-go-secondary">Servicing Depot</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">{selectedOrder.depot}</p>
                </div>
                <div>
                  <span className="text-go-secondary">Delivery Date</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5">{selectedOrder.deliveryDate}</p>
                </div>
                <div>
                  <span className="text-go-secondary">Receiving Window</span>
                  <p className="font-bold text-go-teal text-sm mt-0.5">
                    {windowFor(selectedOrder.outletId, selectedOrder.brand).label}
                  </p>
                </div>
                <div>
                  <span className="text-go-secondary">Temperature Zone</span>
                  <p className="font-bold text-go-ink text-sm mt-0.5 capitalize">{selectedOrder.temperature}</p>
                </div>
              </div>

              {/* Authoritative Order-Level Weight & Volume Box */}
              <div className="rounded-2xl border border-go-mint bg-go-subtle p-4.5 space-y-2">
                <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-go-teal">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-4" aria-hidden="true">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                  </svg>
                  <span>Authoritative Order-Level Capacity Metrics</span>
                </div>

                <div className="grid gap-4 sm:grid-cols-2 pt-1">
                  <div className="rounded-xl border border-go-rule bg-white p-3">
                    <span className="text-xs text-go-secondary">Authoritative Order Weight</span>
                    <p className="text-lg font-bold text-go-teal">
                      {Number(selectedOrder.weightKg).toLocaleString()} kg
                    </p>
                  </div>
                  <div className="rounded-xl border border-go-rule bg-white p-3">
                    <span className="text-xs text-go-secondary">Authoritative Order Volume</span>
                    <p className="text-lg font-bold text-go-teal">
                      {Number(selectedOrder.volumeM3).toFixed(2)} m³
                    </p>
                  </div>
                </div>

                <p className="text-[11px] text-go-secondary pt-1">
                  Note: Capacity planning and vehicle fit constraints read authoritative order totals returned by the warehouse. Individual SKU line items are descriptive.
                </p>
              </div>

              {/* Order Lines (GET /api/admin/orders/{id}) */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h4 className="text-xs font-bold uppercase tracking-wider text-go-ink">
                    Current Line Records ({orderLines.length} product lines)
                  </h4>
                  <span className="text-[11px] text-go-secondary">GET /api/admin/orders/{selectedOrder.orderId}</span>
                </div>

                {detailLoading ? (
                  <div className="p-4 text-center text-xs text-go-secondary">Loading line items...</div>
                ) : orderLines.length > 0 ? (
                  <div className="overflow-hidden rounded-xl border border-go-subtle">
                    <table className="w-full text-left text-xs">
                      <thead className="bg-go-subtle text-go-secondary border-b border-go-subtle">
                        <tr>
                          <th className="p-2.5 font-bold">Product ID / SKU</th>
                          <th className="p-2.5 font-bold">Item Description</th>
                          <th className="p-2.5 font-bold text-right">Quantity</th>
                          <th className="p-2.5 font-bold text-right">Revision</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-go-subtle">
                        {orderLines.map((line, idx) => (
                          <tr key={`${line.productId}-${idx}`} className="hover:bg-go-subtle">
                            <td className="p-2.5 font-mono font-medium text-go-ink">{line.productId}</td>
                            <td className="p-2.5 text-go-secondary">{line.productName || "Standard Catalog SKU"}</td>
                            <td className="p-2.5 text-right font-bold text-go-ink">{line.quantity}</td>
                            <td className="p-2.5 text-right text-go-secondary">Rev {line.revision}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="text-xs text-go-secondary italic">No line items available for this order record.</p>
                )}
              </div>

              {/* Status Timeline (GET /api/admin/orders/{id}/timeline) */}
              <div className="space-y-2 pt-2">
                <div className="flex items-center justify-between">
                  <h4 className="text-xs font-bold uppercase tracking-wider text-go-ink">
                    Recorded Status Changes &amp; Timeline
                  </h4>
                  <span className="text-[11px] text-go-secondary">GET /api/admin/orders/{selectedOrder.orderId}/timeline</span>
                </div>

                {detailLoading ? (
                  <div className="p-4 text-center text-xs text-go-secondary">Loading status timeline...</div>
                ) : timeline.length > 0 ? (
                  <div className="space-y-2.5">
                    {timeline.map((change, idx) => (
                      <div
                        key={idx}
                        className="flex items-start gap-3 rounded-xl border border-go-subtle bg-[#f9fbfb] p-3 text-xs"
                      >
                        <span className="grid size-6 shrink-0 place-items-center rounded-full bg-go-subtle text-go-teal text-[10px] font-bold">
                          {idx + 1}
                        </span>
                        <div className="space-y-1 min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            {change.from && (
                              <>
                                <span className="font-semibold text-go-secondary">{change.from}</span>
                                <span className="text-go-secondary">→</span>
                              </>
                            )}
                            <span className="font-bold text-go-teal">{change.to}</span>
                            <span className="text-go-secondary">·</span>
                            <span className="text-[11px] text-go-secondary">{new Date(change.at).toLocaleString()}</span>
                          </div>
                          <p className="text-go-ink font-medium">{change.reason}</p>
                          {change.actorName && (
                            <p className="text-[11px] text-go-secondary">Actor: {change.actorName}</p>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-go-secondary italic">No historical status transitions recorded.</p>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="mt-6 flex justify-end border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setSelectedOrder(null)}
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
