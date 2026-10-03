"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge, card, field, primary, secondary } from "./components";
import type { DemoState } from "./model";
import { fetchAdminOutlets } from "../data/reference";

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

export const INITIAL_OUTLETS: OutletRecord[] = [
  // Peliyagoda Outlets
  {
    id: "OUT001",
    name: "Colombo Central Fresh Market",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "street",
    dockDetails: "Kerbside unload on Main St. Unloading restricted during peak traffic (07:30 - 09:00).",
    windowOpen: "05:00",
    windowClose: "07:30",
    windowNotes: "Early morning off-peak window before market open.",
    storeManager: "Ayesha Hassan",
    managerPhone: "+94 77 234 5601",
    managerEmail: "ayesha.hassan@waypoint.lk",
    address: "42 Main Street, Pettah, Colombo 11",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT002",
    name: "Kollupitiya Super Fresh",
    brand: "Fresh",
    tempZone: "Chilled (Refrigerated)",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "street",
    dockDetails: "Front bay parking with ramp access for chilled perishables.",
    windowOpen: "05:30",
    windowClose: "08:00",
    windowNotes: "Perishable produce requires immediate cold transfer upon arrival.",
    storeManager: "Chamari Silva",
    managerPhone: "+94 71 890 1202",
    managerEmail: "chamari.silva@waypoint.lk",
    address: "128 Galle Road, Kollupitiya, Colombo 03",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT005",
    name: "Borella Hypermarket Fresh",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "rear_dock",
    dockDetails: "Dedicated double-bay hydraulic rear dock. Accommodates heavy multi-ton trucks.",
    windowOpen: "04:00",
    windowClose: "07:45",
    windowNotes: "Early slot allocation for heavy grocery pallet offload.",
    storeManager: "Kasun Jayawardena",
    managerPhone: "+94 77 445 6705",
    managerEmail: "kasun.j@waypoint.lk",
    address: "88 Ward Place, Borella, Colombo 08",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT008",
    name: "Nugegoda Fresh Mart",
    brand: "Fresh",
    tempZone: "Chilled (Refrigerated)",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "rear_dock",
    dockDetails: "Dedicated rear dock with temperature-controlled air curtain barrier.",
    windowOpen: "05:00",
    windowClose: "07:30",
    windowNotes: "Refrigerated vehicle connection available for direct dairy unloading.",
    storeManager: "Nalaka Perera",
    managerPhone: "+94 77 556 7808",
    managerEmail: "nalaka.p@waypoint.lk",
    address: "210 High Level Road, Nugegoda",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT012",
    name: "Dehiwala Ocean View Fresh",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "rear_dock",
    dockDetails: "Single rear dock with hydraulic scissor lift for roll containers.",
    windowOpen: "05:30",
    windowClose: "08:00",
    windowNotes: "Produce crates unloaded first, followed by dry goods.",
    storeManager: "Saman Kumara",
    managerPhone: "+94 76 667 8912",
    managerEmail: "saman.k@waypoint.lk",
    address: "54 Galle Road, Dehiwala",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT015",
    name: "One Galle Face Mall Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Basement Level B2 loading dock. Strict security clearance and 3.2m vehicle height limit.",
    windowOpen: "09:00",
    windowClose: "11:00",
    windowNotes: "Mall management requires advance security pass registration for delivery driver.",
    storeManager: "Ruwan Wijeratne",
    managerPhone: "+94 77 778 9015",
    managerEmail: "ruwan.w@waypoint.lk",
    address: "1A Centre Road, Galle Face, Colombo 02",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT017",
    name: "Havelock City Mall Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Enclosed underground service bay 4. Freight elevator access directly to 2nd floor boutique.",
    windowOpen: "10:30",
    windowClose: "12:30",
    windowNotes: "Midday receiving window before retail shopping peak.",
    storeManager: "Dilani Senanayake",
    managerPhone: "+94 77 889 0117",
    managerEmail: "dilani.s@waypoint.lk",
    address: "324 Havelock Road, Colombo 05",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT019",
    name: "Bambalapitiya Flagship Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "rear_dock",
    dockDetails: "Rear yard receiving gate with roll cage ramp access.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "Flexible all-day receiving window for garment hanging racks.",
    storeManager: "Pradeep Bandara",
    managerPhone: "+94 71 990 1219",
    managerEmail: "pradeep.b@waypoint.lk",
    address: "410 Galle Road, Bambalapitiya, Colombo 04",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT021",
    name: "Crescat Boulevard Tech Experience",
    brand: "Tech",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Secured mall loading bay with CCTV escort for high-value electronics and gadgets.",
    windowOpen: "10:30",
    windowClose: "12:30",
    windowNotes: "High-value manifest verification required prior to seal breaking.",
    storeManager: "Tharindu Fernando",
    managerPhone: "+94 77 101 2321",
    managerEmail: "tharindu.f@waypoint.lk",
    address: "89 Galle Road, Kollupitiya, Colombo 03",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT022",
    name: "Colombo City Centre Tech Hub",
    brand: "Tech",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Dedicated electronics receiving bay with padded roller conveyor.",
    windowOpen: "10:00",
    windowClose: "12:00",
    windowNotes: "Electronics security scanner clearance at service gate.",
    storeManager: "Menaka Rathnayake",
    managerPhone: "+94 77 212 3422",
    managerEmail: "menaka.r@waypoint.lk",
    address: "137 Sir James Pieris Mawatha, Colombo 02",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT025",
    name: "Negombo Coastal Fresh",
    brand: "Fresh",
    tempZone: "Chilled (Refrigerated)",
    district: "Gampaha",
    depot: "PELIYAGODA",
    dockType: "rear_dock",
    dockDetails: "Elevated dock leveler supporting refrigerated van tailgates.",
    windowOpen: "05:30",
    windowClose: "08:00",
    windowNotes: "Early morning cold-chain delivery slot.",
    storeManager: "Anura Dissanayake",
    managerPhone: "+94 77 323 4525",
    managerEmail: "anura.d@waypoint.lk",
    address: "65 Main Street, Negombo",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT028",
    name: "Gampaha Town Fresh Express",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Gampaha",
    depot: "PELIYAGODA",
    dockType: "street",
    dockDetails: "Kerbside loading zone designated in front of store between 03:00 and 08:00.",
    windowOpen: "03:00",
    windowClose: "08:00",
    windowNotes: "Wide pre-dawn receiving window for bulk agricultural produce.",
    storeManager: "Sunil Shantha",
    managerPhone: "+94 77 434 5628",
    managerEmail: "sunil.s@waypoint.lk",
    address: "18 Colombo Road, Gampaha",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT035",
    name: "K-Zone Ja-Ela Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Gampaha",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Rear shopping complex bay with easy roller access.",
    windowOpen: "10:30",
    windowClose: "12:30",
    windowNotes: "Mid-morning delivery window for retail apparel restock.",
    storeManager: "Kavinda Mihiran",
    managerPhone: "+94 77 545 6735",
    managerEmail: "kavinda.m@waypoint.lk",
    address: "524 Negombo Road, Ja-Ela",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT038",
    name: "Kelaniya Electronics & Tech",
    brand: "Tech",
    tempZone: "Ambient Standard",
    district: "Gampaha",
    depot: "PELIYAGODA",
    dockType: "street",
    dockDetails: "Street frontage with secure side roller gate and hand-truck ramp.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "All-day tech accessories receiving window.",
    storeManager: "Sandun Gunawardena",
    managerPhone: "+94 77 656 7838",
    managerEmail: "sandun.g@waypoint.lk",
    address: "12 Waragoda Road, Kelaniya",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT-SAMPLE-01",
    name: "Pettah Demo Wholesale Fresh",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "street",
    dockDetails: "Pettah commercial unloading slot with priority offload bay.",
    windowOpen: "05:00",
    windowClose: "08:00",
    windowNotes: "Sample operational outlet linked to store manager Ayesha Hassan.",
    storeManager: "Ayesha Hassan",
    managerPhone: "+94 77 234 5601",
    managerEmail: "ayesha.hassan@waypoint.lk",
    address: "15 Keyzer Street, Pettah, Colombo 11",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT-SAMPLE-02",
    name: "Colombo 07 Boutique Style Demo",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Colombo",
    depot: "PELIYAGODA",
    dockType: "mall_bay",
    dockDetails: "Enclosed fashion gallery unloading bay.",
    windowOpen: "10:00",
    windowClose: "12:30",
    windowNotes: "Sample operational outlet linked to store manager Chamari Silva.",
    storeManager: "Chamari Silva",
    managerPhone: "+94 71 890 1202",
    managerEmail: "chamari.silva@waypoint.lk",
    address: "74 Gregory's Road, Cinnamon Gardens, Colombo 07",
    maxVehicleType: "Van Only",
  },

  // Kandy Outlets
  {
    id: "OUT076",
    name: "Kandy Market Fresh Pavilion",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Kandy",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Front kerbside bay on Market Square. Early hours permit strictly enforced.",
    windowOpen: "03:00",
    windowClose: "08:00",
    windowNotes: "Hill country fresh vegetables and wholesale produce intake.",
    storeManager: "Gayan Wickramasinghe",
    managerPhone: "+94 77 767 8976",
    managerEmail: "gayan.w@waypoint.lk",
    address: "10 Market Square, Kandy Central",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT077",
    name: "Peradeniya Road Super Fresh",
    brand: "Fresh",
    tempZone: "Chilled (Refrigerated)",
    district: "Kandy",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Wide roadside parking bay with covered ramp for dairy crates.",
    windowOpen: "05:00",
    windowClose: "07:30",
    windowNotes: "Refrigerated unit unloading for chilled food supplies.",
    storeManager: "Meena Raj",
    managerPhone: "+94 77 878 9077",
    managerEmail: "meena.r@waypoint.lk",
    address: "245 Peradeniya Road, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT080",
    name: "Katugastota Fresh Mart",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Kandy",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Kerbside staging space with rubber bumper dock matting.",
    windowOpen: "05:30",
    windowClose: "08:00",
    windowNotes: "Standard morning ambient food receiving.",
    storeManager: "Rohan Samarasinghe",
    managerPhone: "+94 77 989 0180",
    managerEmail: "rohan.s@waypoint.lk",
    address: "88 Kurunegala Road, Katugastota",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT084",
    name: "Kandy Lakeview Fresh Plaza",
    brand: "Fresh",
    tempZone: "Chilled (Refrigerated)",
    district: "Kandy",
    depot: "KANDY",
    dockType: "rear_dock",
    dockDetails: "Rear dock with sealed cold tunnel dock seal for temperature preservation.",
    windowOpen: "05:30",
    windowClose: "08:00",
    windowNotes: "Chilled cold chain intake for high-end groceries.",
    storeManager: "Dinesh Kumara",
    managerPhone: "+94 77 090 1284",
    managerEmail: "dinesh.k@waypoint.lk",
    address: "14 Sangharaja Mawatha, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT088",
    name: "Dalada Veediya Apparel Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Kandy",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Street frontage with side passage for garment roll cages.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "Daytime boutique replenishment window.",
    storeManager: "Nirosha Jayatillake",
    managerPhone: "+94 77 191 2388",
    managerEmail: "nirosha.j@waypoint.lk",
    address: "55 Dalada Veediya, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT089",
    name: "KCC Kandy City Centre Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Kandy",
    depot: "KANDY",
    dockType: "mall_bay",
    dockDetails: "KCC Basement delivery dock 2. Heavy security clearance required.",
    windowOpen: "10:30",
    windowClose: "12:30",
    windowNotes: "Strict shopping mall delivery cutoff at 12:30.",
    storeManager: "Ashan Weerasinghe",
    managerPhone: "+94 77 292 3489",
    managerEmail: "ashan.w@waypoint.lk",
    address: "5 Sri Dalada Veediya, KCC Mall, Kandy",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT092",
    name: "Kandy Outer Ring Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Kandy",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Dedicated side driveway for easy unloading.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "Standard apparel collection receiving.",
    storeManager: "Lasantha Dias",
    managerPhone: "+94 77 393 4592",
    managerEmail: "lasantha.d@waypoint.lk",
    address: "120 William Gopallawa Mawatha, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT098",
    name: "Kandy Tech Innovations",
    brand: "Tech",
    tempZone: "Ambient Standard",
    district: "Kandy",
    depot: "KANDY",
    dockType: "rear_dock",
    dockDetails: "Lockable rear security garage with camera monitoring.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "Electronics consignment receipt and serial check.",
    storeManager: "Harsha Ekanayake",
    managerPhone: "+94 77 494 5698",
    managerEmail: "harsha.e@waypoint.lk",
    address: "32 Yatinuwara Veediya, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT101",
    name: "Matale Central Fresh Mart",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Matale",
    depot: "KANDY",
    dockType: "rear_dock",
    dockDetails: "Rear covered warehouse platform with pallet jack accessibility.",
    windowOpen: "04:00",
    windowClose: "07:45",
    windowNotes: "Early morning grocery intake from Kandy depot transit.",
    storeManager: "Sampath Abeywickrama",
    managerPhone: "+94 77 595 6701",
    managerEmail: "sampath.a@waypoint.lk",
    address: "102 Main Street, Matale",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT105",
    name: "Matale Town Mall Style",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Matale",
    depot: "KANDY",
    dockType: "mall_bay",
    dockDetails: "Commercial plaza loading area with service lift.",
    windowOpen: "10:00",
    windowClose: "12:30",
    windowNotes: "Midday clothing delivery slot.",
    storeManager: "Priyanka Ranatunga",
    managerPhone: "+94 77 696 7805",
    managerEmail: "priyanka.r@waypoint.lk",
    address: "44 Trincomalee Street, Matale",
    maxVehicleType: "Van Only",
  },
  {
    id: "OUT112",
    name: "Nuwara Eliya Hill Tech Hub",
    brand: "Tech",
    tempZone: "Ambient Standard",
    district: "Nuwara Eliya",
    depot: "KANDY",
    dockType: "street",
    dockDetails: "Covered mountain street dock with anti-slip ramp for high-altitude weather.",
    windowOpen: "09:00",
    windowClose: "17:00",
    windowNotes: "Consignments dispatched via Kandy depot mountain trip.",
    storeManager: "Kithsiri Senewiratne",
    managerPhone: "+94 77 797 8912",
    managerEmail: "kithsiri.s@waypoint.lk",
    address: "18 Badulla Road, Nuwara Eliya",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT-SAMPLE-03",
    name: "Kandy Demo Fresh Flagship",
    brand: "Fresh",
    tempZone: "Ambient Fresh",
    district: "Kandy",
    depot: "KANDY",
    dockType: "rear_dock",
    dockDetails: "Dedicated dual-bay hydraulic rear dock with pallet staging.",
    windowOpen: "04:30",
    windowClose: "07:30",
    windowNotes: "Sample operational outlet linked to store manager Gayan Wickramasinghe.",
    storeManager: "Gayan Wickramasinghe",
    managerPhone: "+94 77 767 8976",
    managerEmail: "gayan.w@waypoint.lk",
    address: "78 Peradeniya Road, Kandy",
    maxVehicleType: "Van & Truck",
  },
  {
    id: "OUT-SAMPLE-04",
    name: "Kandy Demo Fashion Hub",
    brand: "Style",
    tempZone: "Ambient Standard",
    district: "Kandy",
    depot: "KANDY",
    dockType: "mall_bay",
    dockDetails: "Enclosed shopping center delivery bay.",
    windowOpen: "10:00",
    windowClose: "12:00",
    windowNotes: "Sample operational outlet linked to store manager Meena Raj.",
    storeManager: "Meena Raj",
    managerPhone: "+94 77 878 9077",
    managerEmail: "meena.r@waypoint.lk",
    address: "12 D.S. Senanayake Veediya, Kandy",
    maxVehicleType: "Van Only",
  },
];

export function OutletsScreen({
  state,
  onNavigateTab,
}: {
  state?: DemoState;
  onNavigateTab?: (tab: "people" | "personas" | "vehicles" | "forecasts" | "depots" | "audit") => void;
}) {
  const [outletsList, setOutletsList] = useState<OutletRecord[]>(INITIAL_OUTLETS);
  const [liveConnected, setLiveConnected] = useState<boolean | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [brandFilter, setBrandFilter] = useState<string>("all");
  const [districtFilter, setDistrictFilter] = useState<string>("all");
  const [depotFilter, setDepotFilter] = useState<string>("all");
  const [dockFilter, setDockFilter] = useState<string>("all");

  // Detail modal state
  const [selectedOutlet, setSelectedOutlet] = useState<OutletRecord | null>(null);

  // Add Outlet modal state
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [newOutletId, setNewOutletId] = useState("");
  const [newOutletName, setNewOutletName] = useState("");
  const [newOutletBrand, setNewOutletBrand] = useState<"Fresh" | "Style" | "Tech">("Fresh");
  const [newOutletTempZone, setNewOutletTempZone] = useState<"Ambient Fresh" | "Chilled (Refrigerated)" | "Ambient Standard">("Ambient Fresh");
  const [newOutletDistrict, setNewOutletDistrict] = useState("Colombo");
  const [newOutletDepot, setNewOutletDepot] = useState("PELIYAGODA");
  const [newOutletDockType, setNewOutletDockType] = useState<"rear_dock" | "street" | "mall_bay">("rear_dock");
  const [newOutletDockDetails, setNewOutletDockDetails] = useState("");
  const [newOutletWindowOpen, setNewOutletWindowOpen] = useState("05:00");
  const [newOutletWindowClose, setNewOutletWindowClose] = useState("08:00");
  const [newOutletWindowNotes, setNewOutletWindowNotes] = useState("");
  const [newOutletStoreManager, setNewOutletStoreManager] = useState("");
  const [newOutletManagerPhone, setNewOutletManagerPhone] = useState("");
  const [newOutletManagerEmail, setNewOutletManagerEmail] = useState("");
  const [newOutletAddress, setNewOutletAddress] = useState("");
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
        if (page.items && page.items.length > 0) {
          const records: OutletRecord[] = page.items.map((o) => {
            const fallback = INITIAL_OUTLETS.find((init) => init.id === o.outletId);
            return {
              id: o.outletId,
              name: fallback?.name || `${o.brand} Store ${o.outletId}`,
              brand: (o.brand === "Fresh" || o.brand === "Style" || o.brand === "Tech" ? o.brand : "Fresh") as "Fresh" | "Style" | "Tech",
              tempZone: fallback?.tempZone || (o.brand === "Fresh" ? "Ambient Fresh" : "Ambient Standard"),
              district: o.district || fallback?.district || "Colombo",
              depot: o.depot || fallback?.depot || "PELIYAGODA",
              dockType: (o.dockType === "rear_dock" || o.dockType === "street" || o.dockType === "mall_bay" ? o.dockType : "street") as any,
              dockDetails: fallback?.dockDetails || `Unloading capability: ${o.dockType || "standard"}`,
              windowOpen: o.windowOpen || fallback?.windowOpen || "08:00",
              windowClose: o.windowClose || fallback?.windowClose || "17:00",
              windowNotes: fallback?.windowNotes || `Standard time window: ${o.windowOpen || "08:00"} - ${o.windowClose || "17:00"}`,
              storeManager: fallback?.storeManager || "Assigned Manager",
              managerPhone: fallback?.managerPhone || "+94 77 000 0000",
              managerEmail: fallback?.managerEmail || `store.${o.outletId.toLowerCase()}@waypoint.lk`,
              address: fallback?.address || `${o.district || "Commercial District"}, Sri Lanka`,
              maxVehicleType: fallback?.maxVehicleType || (o.dockType === "street" ? "Van Only" : "Van & Truck"),
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
  }, [depotFilter, brandFilter, districtFilter, dockFilter, searchQuery]);

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

  // Brand change handler for modal
  const handleBrandChange = (brand: "Fresh" | "Style" | "Tech") => {
    setNewOutletBrand(brand);
    if (brand === "Fresh") {
      setNewOutletTempZone("Ambient Fresh");
      setNewOutletWindowOpen("05:00");
      setNewOutletWindowClose("08:00");
      setNewOutletMaxVehicleType("Van & Truck");
    } else if (brand === "Style") {
      setNewOutletTempZone("Ambient Standard");
      setNewOutletWindowOpen("10:00");
      setNewOutletWindowClose("12:30");
      setNewOutletMaxVehicleType("Van Only");
    } else {
      setNewOutletTempZone("Ambient Standard");
      setNewOutletWindowOpen("09:00");
      setNewOutletWindowClose("17:00");
      setNewOutletMaxVehicleType("Van & Truck");
    }
  };

  const handleCreateOutlet = () => {
    const generatedId = newOutletId.trim().toUpperCase() || `OUT${Math.floor(100 + Math.random() * 900)}`;
    const created: OutletRecord = {
      id: generatedId,
      name: newOutletName.trim() || `${newOutletDistrict} ${newOutletBrand} Store`,
      brand: newOutletBrand,
      tempZone: newOutletTempZone,
      district: newOutletDistrict,
      depot: newOutletDepot,
      dockType: newOutletDockType,
      dockDetails: newOutletDockDetails.trim() || (newOutletDockType === "rear_dock" ? "Standard rear loading dock with ramp." : newOutletDockType === "mall_bay" ? "Enclosed mall loading bay." : "Street kerbside unloading bay."),
      windowOpen: newOutletWindowOpen || "06:00",
      windowClose: newOutletWindowClose || "09:00",
      windowNotes: newOutletWindowNotes.trim() || undefined,
      storeManager: newOutletStoreManager.trim() || undefined,
      managerPhone: newOutletManagerPhone.trim() || undefined,
      managerEmail: newOutletManagerEmail.trim() || undefined,
      address: newOutletAddress.trim() || `${newOutletDistrict} Commercial District`,
      maxVehicleType: newOutletMaxVehicleType,
    };

    setOutletsList((prev) => [created, ...prev]);
    setIsAddModalOpen(false);

    // Reset form
    setNewOutletId("");
    setNewOutletName("");
    setNewOutletBrand("Fresh");
    setNewOutletTempZone("Ambient Fresh");
    setNewOutletDistrict("Colombo");
    setNewOutletDepot("PELIYAGODA");
    setNewOutletDockType("rear_dock");
    setNewOutletDockDetails("");
    setNewOutletWindowOpen("05:00");
    setNewOutletWindowClose("08:00");
    setNewOutletWindowNotes("");
    setNewOutletStoreManager("");
    setNewOutletManagerPhone("");
    setNewOutletManagerEmail("");
    setNewOutletAddress("");
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
      {/* Top action bar: Add outlet */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          {liveConnected !== null && (
            <Badge tone={liveConnected ? "green" : "neutral"}>
              {liveConnected ? "Live API: GET /api/admin/outlets" : "Sample outlets"}
            </Badge>
          )}
        </div>
        <button
          type="button"
          className={`${primary} flex items-center gap-2`}
          onClick={() => setIsAddModalOpen(true)}
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
              <option value="Fresh-Ambient">Fresh (Ambient)</option>
              <option value="Fresh-Chilled">Fresh (Chilled Refrigerated)</option>
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
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
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
              {/* Outlet Code & Store Name */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  Outlet Code / ID *
                  <input
                    type="text"
                    className={`${field} mt-1 uppercase`}
                    placeholder="e.g. OUT045"
                    value={newOutletId}
                    onChange={(e) => setNewOutletId(e.target.value)}
                  />
                </label>

                <label className="block font-medium text-go-ink">
                  Store Facility Name
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. Maharagama Central Mart"
                    value={newOutletName}
                    onChange={(e) => setNewOutletName(e.target.value)}
                  />
                </label>
              </div>

              {/* Brand & Temperature Zone Requirement (Brand asked first, conditional sub-options for Fresh) */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Brand &amp; Temperature Profile
                </span>

                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-go-ink">
                    Which Brand is this Outlet for? *
                    <select
                      className={`${field} mt-1`}
                      value={newOutletBrand}
                      onChange={(e) => handleBrandChange(e.target.value as "Fresh" | "Style" | "Tech")}
                    >
                      <option value="Fresh">Fresh (Food, Dairy &amp; Groceries)</option>
                      <option value="Style">Style (Apparel &amp; Fashion)</option>
                      <option value="Tech">Tech (Electronics &amp; Appliances)</option>
                    </select>
                  </label>

                  {newOutletBrand === "Fresh" ? (
                    <label className="block font-medium text-go-ink">
                      Fresh Sub-Category / Temperature *
                      <select
                        className={`${field} mt-1`}
                        value={newOutletTempZone}
                        onChange={(e) => setNewOutletTempZone(e.target.value as "Ambient Fresh" | "Chilled (Refrigerated)")}
                      >
                        <option value="Ambient Fresh">Ambient Fresh (Dry &amp; packaged foods)</option>
                        <option value="Chilled (Refrigerated)">Chilled Refrigerated (Cold chain &amp; perishables)</option>
                      </select>
                    </label>
                  ) : (
                    <div>
                      <span className="block text-xs font-medium text-go-secondary">
                        Temperature Zone Requirement
                      </span>
                      <div className="mt-1 flex items-center gap-2 rounded-xl border border-[#e1ece5] bg-white px-3 py-2 text-sm text-[#3b5246]">
                        <span className="size-2 rounded-full bg-go-teal"></span>
                        <span>Standard Ambient ({newOutletBrand} merchandise)</span>
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* District & Assigned Depot */}
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block font-medium text-go-ink">
                  District *
                  <select
                    className={`${field} mt-1`}
                    value={newOutletDistrict}
                    onChange={(e) => setNewOutletDistrict(e.target.value)}
                  >
                    <option value="Colombo">Colombo</option>
                    <option value="Gampaha">Gampaha</option>
                    <option value="Kalutara">Kalutara</option>
                    <option value="Kandy">Kandy</option>
                    <option value="Matale">Matale</option>
                    <option value="Nuwara Eliya">Nuwara Eliya</option>
                    <option value="Kurunegala">Kurunegala</option>
                  </select>
                </label>

                <label className="block font-medium text-go-ink">
                  Assigned Servicing Depot *
                  <select
                    className={`${field} mt-1`}
                    value={newOutletDepot}
                    onChange={(e) => setNewOutletDepot(e.target.value)}
                  >
                    <option value="PELIYAGODA">Peliyagoda (Western)</option>
                    <option value="KANDY">Kandy (Central)</option>
                  </select>
                </label>
              </div>

              {/* Dock Infrastructure */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Dock Availability &amp; Vehicle Access
                </span>

                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-go-ink">
                    Dock Type *
                    <select
                      className={`${field} mt-1`}
                      value={newOutletDockType}
                      onChange={(e) => {
                        const dt = e.target.value as "rear_dock" | "street" | "mall_bay";
                        setNewOutletDockType(dt);
                        if (dt === "mall_bay") {
                          setNewOutletMaxVehicleType("Van Only");
                        } else {
                          setNewOutletMaxVehicleType("Van & Truck");
                        }
                      }}
                    >
                      <option value="rear_dock">Rear loading dock</option>
                      <option value="street">Street kerbside unload</option>
                      <option value="mall_bay">Mall enclosed bay</option>
                    </select>
                  </label>

                  <label className="block font-medium text-go-ink">
                    Permitted Transport Vehicle *
                    <select
                      className={`${field} mt-1`}
                      value={newOutletMaxVehicleType}
                      onChange={(e) => setNewOutletMaxVehicleType(e.target.value as "Van & Truck" | "Van Only" | "Medium Rigid Truck Only")}
                    >
                      <option value="Van & Truck">Van &amp; Heavy Truck Allowed</option>
                      <option value="Van Only">Van Only (Height / Access Restricted)</option>
                      <option value="Medium Rigid Truck Only">Medium Rigid Truck Only</option>
                    </select>
                  </label>
                </div>

                <label className="block font-medium text-go-ink">
                  Dock &amp; Ramp Facility Description
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. Hydraulic dock leveler with pallet ramp access."
                    value={newOutletDockDetails}
                    onChange={(e) => setNewOutletDockDetails(e.target.value)}
                  />
                </label>
              </div>

              {/* Delivery Window Open & Close */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Delivery Time Window (Receiving Hours)
                </span>

                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block font-medium text-go-ink">
                    Window Opening Time *
                    <input
                      type="time"
                      className={`${field} mt-1`}
                      value={newOutletWindowOpen}
                      onChange={(e) => setNewOutletWindowOpen(e.target.value)}
                    />
                  </label>

                  <label className="block font-medium text-go-ink">
                    Window Closing Time *
                    <input
                      type="time"
                      className={`${field} mt-1`}
                      value={newOutletWindowClose}
                      onChange={(e) => setNewOutletWindowClose(e.target.value)}
                    />
                  </label>
                </div>

                <label className="block font-medium text-go-ink">
                  Window Notes / Receiving Gate Instructions
                  <input
                    type="text"
                    className={`${field} mt-1`}
                    placeholder="e.g. Early morning slot before retail opening at 08:30."
                    value={newOutletWindowNotes}
                    onChange={(e) => setNewOutletWindowNotes(e.target.value)}
                  />
                </label>
              </div>

              {/* Store Manager Details */}
              <div className="rounded-2xl border border-go-rule bg-go-subtle p-4 space-y-3">
                <span className="block text-xs font-bold uppercase tracking-wider text-go-teal">
                  Assigned Store Manager
                </span>

                <div className="grid gap-3 sm:grid-cols-3">
                  <label className="block font-medium text-go-ink">
                    Manager Name
                    <input
                      type="text"
                      className={`${field} mt-1`}
                      placeholder="e.g. Kasun Fernando"
                      value={newOutletStoreManager}
                      onChange={(e) => setNewOutletStoreManager(e.target.value)}
                    />
                  </label>

                  <label className="block font-medium text-go-ink">
                    Manager Phone
                    <input
                      type="text"
                      className={`${field} mt-1`}
                      placeholder="e.g. +94 77 123 4567"
                      value={newOutletManagerPhone}
                      onChange={(e) => setNewOutletManagerPhone(e.target.value)}
                    />
                  </label>

                  <label className="block font-medium text-go-ink">
                    Manager Email
                    <input
                      type="email"
                      className={`${field} mt-1`}
                      placeholder="e.g. kasun.f@waypoint.lk"
                      value={newOutletManagerEmail}
                      onChange={(e) => setNewOutletManagerEmail(e.target.value)}
                    />
                  </label>
                </div>
              </div>

              {/* Address */}
              <label className="block font-medium text-go-ink">
                Store Physical Address
                <input
                  type="text"
                  className={`${field} mt-1`}
                  placeholder="e.g. 104 Main Street, Maharagama"
                  value={newOutletAddress}
                  onChange={(e) => setNewOutletAddress(e.target.value)}
                />
              </label>
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
                disabled={!newOutletId.trim() && !newOutletName.trim()}
                onClick={handleCreateOutlet}
              >
                Create outlet
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
