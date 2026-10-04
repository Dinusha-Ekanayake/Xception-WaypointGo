"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, card, field, primary, secondary } from "./components";
import type { DemoState } from "./model";
import { createAdminOutlet, fetchAdminOutletDetails, fetchAdminOutlets, updateAdminOutletDetails } from "../data/reference";
import type { OutletDetailsView } from "@shared/domain/types";

export type OutletRecord = {
  id: string;
  name?: string;
  brand: "Fresh" | "Style" | "Tech";
  tempZone?: "Ambient Fresh" | "Chilled (Refrigerated)" | "Ambient Standard";
  district: string;
  depot: string;
  dockType: "rear_dock" | "street" | "mall_bay";
  dockDetails?: string;
  windowOpen: string;
  windowClose: string;
  windowNotes?: string;
  storeManager?: string;
  managerPhone?: string;
  managerEmail?: string;
  address?: string;
  maxVehicleType?: "Van & Truck" | "Van Only" | "Medium Rigid Truck Only";
};

export function OutletsScreen({
  state,
  onNavigateTab,
}: {
  state?: DemoState;
  onNavigateTab?: (tab: "people" | "personas" | "vehicles" | "forecasts" | "depots" | "audit") => void;
}) {
  const [outletsList, setOutletsList] = useState<OutletRecord[]>([]);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [brandFilter, setBrandFilter] = useState<string>("all");
  const [districtFilter, setDistrictFilter] = useState<string>("all");
  const [depotFilter, setDepotFilter] = useState<string>("all");
  const [dockFilter, setDockFilter] = useState<string>("all");

  // Detail modal state
  const [selectedOutlet, setSelectedOutlet] = useState<OutletRecord | null>(null);
  const [editingOutlet, setEditingOutlet] = useState<OutletRecord | null>(null);
  const [outletDetails, setOutletDetails] = useState<OutletDetailsView | null>(null);
  const [editOpen, setEditOpen] = useState("");
  const [editClose, setEditClose] = useState("");
  const [editDock, setEditDock] = useState("street");
  const [editContact, setEditContact] = useState("");
  const [editPhone, setEditPhone] = useState("");
  const [editNotes, setEditNotes] = useState("");
  const [editError, setEditError] = useState("");

  // Add Outlet modal state
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [createError, setCreateError] = useState("");
  const [createNotice, setCreateNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [districtDepots, setDistrictDepots] = useState<Record<string, string>>({});
  const [newOutletId, setNewOutletId] = useState("");
  const [newOutletBrand, setNewOutletBrand] = useState<"Fresh" | "Style" | "Tech">("Fresh");
  const [newOutletDistrict, setNewOutletDistrict] = useState("Colombo");
  const [newOutletDockType, setNewOutletDockType] = useState<"rear_dock" | "street" | "mall_bay">("rear_dock");
  const [newOutletWindowOpen, setNewOutletWindowOpen] = useState("05:00");
  const [newOutletWindowClose, setNewOutletWindowClose] = useState("08:00");
  const [newOutletMaxVehicleType, setNewOutletMaxVehicleType] = useState<"Van & Truck" | "Van Only" | "Medium Rigid Truck Only">("Van & Truck");

  // Dynamic unique districts
  const districts = useMemo(() => {
    const set = new Set<string>();
    outletsList.forEach((o) => set.add(o.district));
    return Array.from(set).sort();
  }, [outletsList]);

  // Dynamic unique depots
  const depots = useMemo(() => {
    const set = new Set<string>();
    outletsList.forEach((o) => set.add(o.depot));
    return Array.from(set).sort();
  }, [outletsList]);

  // Fetch live outlets from backend API
  useEffect(() => {
    let cancelled = false;
    fetchAdminOutlets({
      depot: depotFilter,
      brand: brandFilter,
      district: districtFilter,
      dockType: dockFilter,
      search: searchQuery.trim() || undefined,
      limit: 200,
    })
      .then((page) => {
        if (cancelled) return;
        {
          const records: OutletRecord[] = page.items.map((o) => {
            const brand = (o.brand === "Fresh" || o.brand === "Style" || o.brand === "Tech" ? o.brand : "Fresh") as "Fresh" | "Style" | "Tech";
            const dockType = (o.dockType === "rear_dock" || o.dockType === "street" || o.dockType === "mall_bay" ? o.dockType : "street") as "rear_dock" | "street" | "mall_bay";
            const maxVehicleType = o.parking === "van_only" ? "Van Only" : "Van & Truck";
            const manager = state?.members.find((member) => member.active && member.personas.includes("store_manager") &&
              [...(member.outlets ?? []), ...member.places].includes(o.outletId));
            return {
              id: o.outletId,
              name: `Outlet ${o.outletId}`,
              brand,
              district: o.district,
              depot: o.depot,
              dockType,
              dockDetails: `Unloading capability: ${dockType}`,
              windowOpen: o.windowOpen,
              windowClose: o.windowClose,
              windowNotes: `Operational window: ${o.windowOpen} - ${o.windowClose}`,
              maxVehicleType,
              storeManager: manager?.name,
              managerEmail: manager?.email,
            };
          });
          setOutletsList(records);
          setLiveConnected(true);
        }
      })
      .catch(() => {
        if (!cancelled) setLiveConnected(false);
      });
    return () => {
      cancelled = true;
    };
  }, [depotFilter, brandFilter, districtFilter, dockFilter, searchQuery, state?.members]);

  async function openOutletEditor(outlet: OutletRecord) {
    setSelectedOutlet(null); setEditingOutlet(outlet); setEditError("");
    setEditOpen(outlet.windowOpen.slice(0, 5)); setEditClose(outlet.windowClose.slice(0, 5)); setEditDock(outlet.dockType);
    try {
      const details = await fetchAdminOutletDetails(outlet.id); setOutletDetails(details);
      setEditContact(details.contactName ?? ""); setEditPhone(details.contactPhone ?? ""); setEditNotes(details.receivingNotes ?? "");
    } catch (failure) { setEditError(failure instanceof Error ? failure.message : "Could not load outlet details."); }
  }

  async function saveOutlet() {
    if (!editingOutlet || !outletDetails) return;
    if (!editOpen || !editClose || editOpen >= editClose) return setEditError("The opening time must be before the closing time.");
    setCreating(true); setEditError("");
    try {
      await updateAdminOutletDetails({ outletId: editingOutlet.id, windowOpen: editOpen, windowClose: editClose,
        dockType: editingOutlet.dockType === "mall_bay" ? null : editDock,
        contactName: editContact.trim() || null, contactPhone: editPhone.trim() || null,
        receivingNotes: editNotes.trim() || null }, outletDetails.rowVersion);
      setOutletsList((items) => items.map((item) => item.id === editingOutlet.id ? { ...item,
        windowOpen: editOpen, windowClose: editClose,
        dockType: (editingOutlet.dockType === "mall_bay" ? "mall_bay" : editDock) as OutletRecord["dockType"] } : item));
      setEditingOutlet(null); setOutletDetails(null); setCreateNotice(`${editingOutlet.id} was updated.`);
    } catch (failure) { setEditError(failure instanceof Error ? failure.message : "Could not update outlet."); }
    finally { setCreating(false); }
  }

  // Filtered outlets
  const filteredOutlets = useMemo(() => {
    return outletsList.filter((outlet) => {
      // Brand filter
      if (brandFilter !== "all") {
        if (brandFilter === "Fresh") {
          if (outlet.brand !== "Fresh") return false;
        } else if (brandFilter === "Fresh-Ambient") {
          if (outlet.brand !== "Fresh" || outlet.tempZone !== "Ambient Fresh") return false;
        } else if (brandFilter === "Fresh-Chilled") {
          if (outlet.brand !== "Fresh" || outlet.tempZone !== "Chilled (Refrigerated)") return false;
        } else if (brandFilter === "Style") {
          if (outlet.brand !== "Style") return false;
        } else if (brandFilter === "Tech") {
          if (outlet.brand !== "Tech") return false;
        }
      }

      // District filter
      if (districtFilter !== "all" && outlet.district !== districtFilter) return false;

      // Depot filter
      if (depotFilter !== "all" && outlet.depot !== depotFilter) return false;

      // Dock type filter
      if (dockFilter !== "all" && outlet.dockType !== dockFilter) return false;

      // Search query
      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        const fullSearchable = `${outlet.id} ${outlet.name || ""} ${outlet.brand} ${outlet.tempZone || ""} ${outlet.district} ${outlet.depot} ${outlet.dockType} ${outlet.storeManager || ""} ${outlet.address || ""}`.toLowerCase();
        if (!fullSearchable.includes(q)) return false;
      }

      return true;
    });
  }, [outletsList, brandFilter, districtFilter, depotFilter, dockFilter, searchQuery]);

  const handleCreateOutlet = async () => {
    setCreateError("");
    setCreating(true);
    let saved = false;
    try {
      const depotCode = districtDepots[newOutletDistrict];
      if (!depotCode) throw new Error("Choose a published district.");
      await createAdminOutlet({
        outletId: newOutletId.trim().toUpperCase(), brand: newOutletBrand,
        district: newOutletDistrict, depotCode,
        dockType: newOutletDockType,
        parking: newOutletDockType === "mall_bay" ? "mall_dock"
          : newOutletMaxVehicleType === "Van Only" ? "van_only" : "normal",
        windowOpen: newOutletWindowOpen, windowClose: newOutletWindowClose,
        ...(newOutletDockType === "mall_bay"
          ? { mallOpen: newOutletWindowOpen, mallClose: newOutletWindowClose } : {}),
      });
      saved = true;
      const page = await fetchAdminOutlets({ limit: 200 });
      setOutletsList(page.items.map((o) => ({
        id: o.outletId, name: `Outlet ${o.outletId}`,
        brand: (o.brand === "Style" || o.brand === "Tech" ? o.brand : "Fresh") as "Fresh" | "Style" | "Tech",
        district: o.district, depot: o.depot,
        dockType: (o.dockType === "rear_dock" || o.dockType === "mall_bay" ? o.dockType : "street") as "rear_dock" | "street" | "mall_bay",
        windowOpen: o.windowOpen, windowClose: o.windowClose,
        maxVehicleType: o.parking === "van_only" ? "Van Only" : "Van & Truck",
      })));
      setIsAddModalOpen(false);
    } catch (error) {
      if (saved) {
        setCreateNotice("Outlet saved, but the directory could not refresh. Reload to see it.");
        setIsAddModalOpen(false);
      } else {
        setCreateError(error instanceof Error ? error.message : "Could not create outlet.");
      }
    } finally {
      setCreating(false);
    }

    // Reset form
    if (!saved) return;
    setNewOutletId("");
    setNewOutletBrand("Fresh");
    setNewOutletDistrict("Colombo");
    setNewOutletDockType("rear_dock");
    setNewOutletWindowOpen("05:00");
    setNewOutletWindowClose("08:00");
    setNewOutletMaxVehicleType("Van & Truck");
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
        return "bg-go-subtle text-go-teal border border-[#a7d9ca]";
      case "Style":
        return "bg-[#f5e8ff] text-[#7e22ce] border border-[#e9d5ff]";
      case "Tech":
        return "bg-[#e0f2fe] text-[#0369a1] border border-[#bae6fd]";
    }
  };

  // Counts summary
  const freshCount = outletsList.filter((o) => o.brand === "Fresh").length;
  const styleCount = outletsList.filter((o) => o.brand === "Style").length;
  const techCount = outletsList.filter((o) => o.brand === "Tech").length;
  const rearDockCount = outletsList.filter((o) => o.dockType === "rear_dock").length;

  return (
    <div className="space-y-6">
      {createNotice && <p role="status" className="rounded-xl bg-go-subtle p-3 text-sm text-go-ink">{createNotice}</p>}
      {/* Top action bar: Add outlet */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          {liveConnected !== null && (
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live data" : "Outlets unavailable"}
            </Badge>
          )}
        </div>
        <button
          type="button"
          className={`${primary} flex items-center gap-2`}
          onClick={() => {
            setCreateError("");
            setIsAddModalOpen(true);
            void fetchAdminOutlets().then((page) => {
              const choices = Object.fromEntries(page.items.map((item) => [item.district, item.depot]));
              setDistrictDepots(choices);
              setNewOutletDistrict((current) => choices[current] ? current : Object.keys(choices).sort()[0] ?? "");
            }).catch((error) => setCreateError(error instanceof Error ? error.message : "Districts unavailable."));
          }}
        >
          <span className="text-lg leading-none" aria-hidden="true">+</span>
          <span>Add outlet</span>
        </button>
      </div>

      {/* 3 Brand Overview Cards (Waypoint Fresh, Waypoint Style, Waypoint Tech) */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {/* Brand 1: Waypoint Fresh */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Fresh" ? "all" : "Fresh")}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Fresh" ? "all" : "Fresh"); }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter === "Fresh" || brandFilter === "Fresh-Ambient" || brandFilter === "Fresh-Chilled"
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
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#15803d]">Retail Brand</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Fresh</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#dcfce7] px-2.5 py-1 text-sm font-extrabold text-[#15803d]">
              {freshCount}
            </span>
          </div>

          <div className="mt-4 space-y-1.5 border-t border-go-subtle pt-3 text-xs text-go-secondary">
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Goods:</strong> Groceries, chilled &amp; frozen items
            </p>
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Schedule:</strong> Daily before 8:00 AM
            </p>
          </div>
        </div>

        {/* Brand 2: Waypoint Style */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Style" ? "all" : "Style")}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Style" ? "all" : "Style"); }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter === "Style"
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
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#7e22ce]">Retail Brand</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Style</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#f3e8ff] px-2.5 py-1 text-sm font-extrabold text-[#7e22ce]">
              {styleCount}
            </span>
          </div>

          <div className="mt-4 space-y-1.5 border-t border-go-subtle pt-3 text-xs text-go-secondary">
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Goods:</strong> Hanging garments &amp; apparel cartons
            </p>
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Schedule:</strong> Weekly, with seasonal peaks
            </p>
          </div>
        </div>

        {/* Brand 3: Waypoint Tech */}
        <div
          role="button"
          tabIndex={0}
          onClick={() => setBrandFilter(brandFilter === "Tech" ? "all" : "Tech")}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setBrandFilter(brandFilter === "Tech" ? "all" : "Tech"); }}
          className={`${card} flex flex-col justify-between p-5 cursor-pointer transition-all ${
            brandFilter === "Tech"
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
                <span className="text-[11px] font-bold uppercase tracking-wider text-[#0284c7]">Retail Brand</span>
                <h3 className="text-base font-bold text-go-ink">Waypoint Tech</h3>
              </div>
            </div>
            <span className="rounded-xl bg-[#e0f2fe] px-2.5 py-1 text-sm font-extrabold text-[#0284c7]">
              {techCount}
            </span>
          </div>

          <div className="mt-4 space-y-1.5 border-t border-go-subtle pt-3 text-xs text-go-secondary">
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Goods:</strong> Appliances &amp; consumer electronics
            </p>
            <p className="line-clamp-1">
              <strong className="font-semibold text-go-ink">Schedule:</strong> As needed; fragile high-value
            </p>
          </div>
        </div>
      </div>

      {/* Structured Filter Bar: Brand/Zone, District, Depot, Dock Type, Search */}
      <div className={`${card} space-y-4 p-5 sm:p-6`}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold uppercase tracking-wider text-go-teal">
            Filters &amp; Search
          </span>
          {(brandFilter !== "all" || districtFilter !== "all" || depotFilter !== "all" || dockFilter !== "all" || searchQuery) && (
            <button
              type="button"
              className="text-xs font-semibold text-go-teal hover:underline"
              onClick={() => {
                setBrandFilter("all");
                setDistrictFilter("all");
                setDepotFilter("all");
                setDockFilter("all");
                setSearchQuery("");
              }}
            >
              Reset all filters
            </button>
          )}
        </div>

        <div className="grid gap-3.5 sm:grid-cols-2 lg:grid-cols-5">
          {/* Search */}
          <label className="text-xs font-medium text-go-ink lg:col-span-1">
            Search
            <input
              type="search"
              placeholder="ID, name, manager..."
              className={`${field} mt-1`}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </label>

          {/* Filter 1: Brand & Ambient/Chilled Requirement */}
          <label className="text-xs font-medium text-go-ink">
            Brand &amp; Temp Zone
            <select
              className={`${field} mt-1`}
              value={brandFilter}
              onChange={(e) => setBrandFilter(e.target.value)}
            >
              <option value="all">All Brands &amp; Zones</option>
              <option value="Fresh">Fresh (All)</option>
              <option value="Style">Style (Apparel)</option>
              <option value="Tech">Tech (Electronics)</option>
            </select>
          </label>

          {/* Filter 2: District */}
          <label className="text-xs font-medium text-go-ink">
            District
            <select
              className={`${field} mt-1`}
              value={districtFilter}
              onChange={(e) => setDistrictFilter(e.target.value)}
            >
              <option value="all">All Districts</option>
              {districts.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>

          {/* Filter 3: Assigned Depot */}
          <label className="text-xs font-medium text-go-ink">
            Assigned Depot
            <select
              className={`${field} mt-1`}
              value={depotFilter}
              onChange={(e) => setDepotFilter(e.target.value)}
            >
              <option value="all">All Depots</option>
              {depots.map((d) => (
                <option key={d} value={d}>
                  {d === "PELIYAGODA" ? "Peliyagoda (Western)" : d === "KANDY" ? "Kandy (Central)" : d}
                </option>
              ))}
            </select>
          </label>

          {/* Filter 4: Dock Type */}
          <label className="text-xs font-medium text-go-ink">
            Dock Availability
            <select
              className={`${field} mt-1`}
              value={dockFilter}
              onChange={(e) => setDockFilter(e.target.value)}
            >
              <option value="all">All Dock Types</option>
              <option value="rear_dock">Rear loading dock</option>
              <option value="street">Street kerbside</option>
              <option value="mall_bay">Mall enclosed bay</option>
            </select>
          </label>
        </div>

        <div className="flex items-center justify-between border-t border-go-subtle pt-3 text-xs text-go-secondary">
          <span>
            Showing <strong>{filteredOutlets.length}</strong> of {outletsList.length} registered retail outlets
          </span>
          <span className="hidden sm:inline">
            Each outlet specifies receiving opening/closing time, dock constraints, and assigned store manager.
          </span>
        </div>
      </div>

      {/* Outlets Grid */}
      {filteredOutlets.length > 0 ? (
        <div className="grid gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
          {filteredOutlets.map((outlet) => (
            <article
              key={outlet.id}
              className={`${card} flex items-center justify-between gap-3 p-4 transition-all hover:border-go-mint hover:shadow-md sm:p-5`}
            >
              <div className="flex items-center gap-3.5 min-w-0">
                <div className="grid size-11 shrink-0 place-items-center rounded-2xl bg-go-subtle text-go-teal">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                    <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
                    <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
                    <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
                    <path d="M2 7h20" />
                    <path d="M22 7v3a2 2 0 0 1-2 2 2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12v0a2 2 0 0 1-2-2V7" />
                  </svg>
                </div>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-base font-bold text-go-ink">{outlet.id}</span>
                    <span className={`rounded-md px-2 py-0.5 text-xs font-bold ${getBrandBadgeColor(outlet.brand)}`}>
                      {outlet.brand}
                    </span>
                    <span className="rounded-md bg-go-subtle px-2 py-0.5 text-xs font-medium text-go-secondary">
                      {outlet.district}
                    </span>
                  </div>
                  <h3 className="mt-1 text-xs font-medium text-go-secondary truncate">
                    {outlet.name || `${outlet.district} ${outlet.brand} Store`}
                  </h3>
                </div>
              </div>

              <button
                type="button"
                onClick={() => setSelectedOutlet(outlet)}
                className="shrink-0 flex items-center gap-1 rounded-xl border border-go-mint bg-go-subtle px-3 py-1.5 text-xs font-bold text-go-teal transition-colors hover:bg-go-mint hover:border-go-mint"
              >
                <span>More info</span>
                <span aria-hidden="true">›</span>
              </button>
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
          <h3 className="mt-3 text-base font-semibold text-go-ink">No matching outlets found</h3>
          <p className="mt-1 max-w-sm text-xs text-go-secondary">
            Try adjusting your brand, district, depot, or dock availability filters.
          </p>
          <button
            type="button"
            className={`${secondary} mt-4 text-xs`}
            onClick={() => {
              setBrandFilter("all");
              setDistrictFilter("all");
              setDepotFilter("all");
              setDockFilter("all");
              setSearchQuery("");
            }}
          >
            Clear all filters
          </button>
        </div>
      )}

      {/* More Info Modal */}
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

            {/* Exactly the 3 requested details */}
            <div className="mt-5 space-y-3.5 text-sm">
              {/* 1. Store Manager */}
              <div className="flex items-center justify-between rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-center gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal">
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
                  </div>
                </div>
              </div>

              {/* 2. Dock Type */}
              <div className="flex items-center justify-between rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-center gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
                      <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Dock Type</span>
                    <div className="mt-0.5">
                      <span className={`inline-block rounded-md px-2.5 py-0.5 text-xs font-bold ${getDockBadgeColor(selectedOutlet.dockType)}`}>
                        {getDockLabel(selectedOutlet.dockType)}
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {/* 3. Open Window */}
              <div className="flex items-center justify-between rounded-2xl border border-go-rule bg-go-subtle p-4">
                <div className="flex items-center gap-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-go-mint text-go-teal">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-5" aria-hidden="true">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="12 6 12 12 16 14" />
                    </svg>
                  </span>
                  <div>
                    <span className="text-xs font-bold uppercase tracking-wider text-go-teal">Open Window</span>
                    <p className="text-base font-bold text-go-ink">
                      {selectedOutlet.windowOpen} - {selectedOutlet.windowClose}
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="mt-6 flex justify-end gap-3 border-t border-go-subtle pt-4">
              <button
                type="button"
                className={secondary}
                onClick={() => setSelectedOutlet(null)}
              >
                Close
              </button>
              <button type="button" className={primary} onClick={() => void openOutletEditor(selectedOutlet)}>Edit outlet</button>
            </div>
          </div>
        </div>
      )}

      {editingOutlet && (
        <div role="dialog" aria-modal="true" aria-labelledby="edit-outlet-title" className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-xs">
          <div className="w-full max-w-2xl rounded-3xl border border-go-rule bg-white p-6 shadow-2xl sm:p-7">
            <div className="flex items-start justify-between border-b border-go-subtle pb-4"><div><h3 id="edit-outlet-title" className="text-xl font-bold">Edit {editingOutlet.id}</h3><p className="text-sm text-go-secondary">Update receiving details used by the next plan.</p></div><button type="button" aria-label="Close" className="grid size-9 place-items-center rounded-full hover:bg-go-subtle" onClick={() => setEditingOutlet(null)}>✕</button></div>
            {!outletDetails ? <p className="py-6 text-sm text-go-secondary">Loading current details...</p> : <div className="mt-5 space-y-4 text-sm">
              <div className="grid gap-3 sm:grid-cols-2"><label className="font-medium">Window opens<input type="time" className={`${field} mt-1`} value={editOpen} onChange={(e) => setEditOpen(e.target.value)} /></label><label className="font-medium">Window closes<input type="time" className={`${field} mt-1`} value={editClose} onChange={(e) => setEditClose(e.target.value)} /></label></div>
              <label className="block font-medium">Dock type<select className={`${field} mt-1`} value={editDock} disabled={editingOutlet.dockType === "mall_bay"} onChange={(e) => setEditDock(e.target.value)}><option value="rear_dock">Rear loading dock</option><option value="street">Street kerbside</option>{editingOutlet.dockType === "mall_bay" && <option value="mall_bay">Mall enclosed bay</option>}</select></label>
              <div className="grid gap-3 sm:grid-cols-2"><label className="font-medium">Contact person<input className={`${field} mt-1`} value={editContact} maxLength={80} onChange={(e) => setEditContact(e.target.value)} /></label><label className="font-medium">Contact phone<input className={`${field} mt-1`} type="tel" value={editPhone} onChange={(e) => setEditPhone(e.target.value)} /></label></div>
              <label className="block font-medium">Driver notes<textarea className={`${field} mt-1 min-h-24`} value={editNotes} maxLength={300} onChange={(e) => setEditNotes(e.target.value)} /></label>
            </div>}
            {editError && <p role="alert" className="mt-4 text-sm text-go-danger">{editError}</p>}
            <div className="mt-6 flex justify-end gap-3 border-t border-go-subtle pt-4"><button type="button" className={secondary} onClick={() => setEditingOutlet(null)}>Cancel</button><button type="button" className={primary} disabled={!outletDetails || creating} onClick={() => void saveOutlet()}>{creating ? "Saving..." : "Save changes"}</button></div>
          </div>
        </div>
      )}

      {/* Add Outlet Modal */}
      {isAddModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-outlet-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-xs"
        >
          <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-3xl bg-white p-6 sm:p-7 shadow-2xl border border-go-rule animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-go-subtle pb-4">
              <div>
                <h3 id="add-outlet-title" className="text-xl font-bold text-go-ink">Add New Retail Outlet</h3>
                <p className="text-xs text-go-secondary">
                  Register a new receiving store location, brand merchandise zone, dock type, and receiving window.
                </p>
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

            {/* Form */}
            <div className="mt-5 space-y-4 text-sm">
              <label className="block font-medium text-go-ink">
                Outlet code *
                <input type="text" className={`${field} mt-1 uppercase`}
                  value={newOutletId} onChange={(e) => setNewOutletId(e.target.value)} />
              </label>
              <label className="block font-medium text-go-ink">
                Brand *
                <select className={`${field} mt-1`} value={newOutletBrand}
                  onChange={(e) => setNewOutletBrand(e.target.value as "Fresh" | "Style" | "Tech")}>
                  <option value="Fresh">Fresh</option>
                  <option value="Style">Style</option>
                  <option value="Tech">Tech</option>
                </select>
              </label>
              <label className="block font-medium text-go-ink">
                District *
                <select className={`${field} mt-1`} value={newOutletDistrict}
                  onChange={(e) => setNewOutletDistrict(e.target.value)}>
                  {Object.keys(districtDepots).sort().map((district) => <option key={district} value={district}>{district}</option>)}
                </select>
              </label>
              <p className="text-xs text-go-secondary">
                The servicing depot comes from the published district assignment.
              </p>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Dock type *
                  <select className={`${field} mt-1`} value={newOutletDockType}
                    onChange={(e) => setNewOutletDockType(e.target.value as "rear_dock" | "street" | "mall_bay")}>
                    <option value="rear_dock">Rear loading dock</option>
                    <option value="street">Street kerbside</option>
                    <option value="mall_bay">Mall enclosed bay</option>
                  </select>
                </label>
                <label className="block font-medium text-go-ink">
                  Vehicle access *
                  <select className={`${field} mt-1`} value={newOutletMaxVehicleType}
                    onChange={(e) => setNewOutletMaxVehicleType(e.target.value as "Van & Truck" | "Van Only" | "Medium Rigid Truck Only")}>
                    <option value="Van & Truck">Van and truck</option>
                    <option value="Van Only">Van only</option>
                  </select>
                </label>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Window opens *
                  <input type="time" className={`${field} mt-1`} value={newOutletWindowOpen}
                    onChange={(e) => setNewOutletWindowOpen(e.target.value)} />
                </label>
                <label className="block font-medium text-go-ink">
                  Window closes *
                  <input type="time" className={`${field} mt-1`} value={newOutletWindowClose}
                    onChange={(e) => setNewOutletWindowClose(e.target.value)} />
                </label>
              </div>
              {newOutletDockType === "mall_bay" &&
                <p className="text-xs text-go-secondary">The mall access window will match the delivery window.</p>}
              {createError && <p role="alert" className="text-sm text-go-danger">{createError}</p>}
            </div>
            {/* Modal Actions */}
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
                disabled={creating || !newOutletId.trim() || !districtDepots[newOutletDistrict]}
                onClick={handleCreateOutlet}
              >
                {creating ? "Creating..." : "Create outlet"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
