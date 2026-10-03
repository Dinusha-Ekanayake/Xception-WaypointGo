export type RouteStop = {
  id: string;
  /** The live delivery this stop draws; absent on the design's sample stops. */
  deliveryId?: string;
  stopNumber: string;
  stopIndex: number;
  totalStops: number;
  name: string;
  address: string;
  dockTag: string;
  eta: string;
  etaDistanceTime: string;
  window: string;
  windowStatus: string;
  cargoText: string;
  instructions: string;
  mapButtonType: "split" | "wide";
  expectedUnits: number;
  deliveredItems: Array<{ code: string; category: string; qty: number }>;
};

export const ROUTE_STOPS: RouteStop[] = [
  {
    id: "stop-01",
    stopNumber: "01",
    stopIndex: 0,
    totalStops: 7,
    name: "Peradeniya",
    address: "12 • Peradeniya rd",
    dockTag: "Side gate",
    eta: "04:48",
    etaDistanceTime: "4.2 Km • 14 min",
    window: "06:30",
    windowStatus: "On Time",
    cargoText: "6 ambient units · 4 chilled units",
    instructions: "Side gate • Call the store manager on arrival, the gate stays locked before 05:00",
    mapButtonType: "split",
    expectedUnits: 18,
    deliveredItems: [
      { code: "FR-AMB-000058", category: "Ambient", qty: 5 },
      { code: "FR-AMB-000061", category: "Ambient", qty: 3 },
      { code: "FR-AMB-000063", category: "Ambient", qty: 3 },
      { code: "FR-CHL-000147", category: "Chilled", qty: 4 },
      { code: "FR-CHL-000149", category: "Chilled", qty: 2 },
      { code: "FR-CHL-000154", category: "Chilled", qty: 1 },
    ],
  },
  {
    id: "stop-02",
    stopNumber: "02",
    stopIndex: 1,
    totalStops: 7,
    name: "Pilimathalawa",
    address: "08 • Colombo - Kandy rd",
    dockTag: "Front dock",
    eta: "05:16",
    etaDistanceTime: "5.4 Km • 11 min",
    window: "07:00",
    windowStatus: "On Time",
    cargoText: "5 ambient units · 3 chilled units",
    instructions: "Front dock • Park on the service lane, chilled crates go in first",
    mapButtonType: "split",
    expectedUnits: 8,
    deliveredItems: [
      { code: "FR-AMB-000064", category: "Ambient", qty: 3 },
      { code: "FR-AMB-000067", category: "Ambient", qty: 2 },
      { code: "FR-CHL-000156", category: "Chilled", qty: 2 },
      { code: "FR-CHL-000158", category: "Chilled", qty: 1 },
    ],
  },
  {
    id: "stop-03",
    stopNumber: "03",
    stopIndex: 2,
    totalStops: 7,
    name: "Kadugannawa",
    address: "01 • Colombo - Kandy rd",
    dockTag: "Rear dock",
    eta: "05:44",
    etaDistanceTime: "6.1 Km • 12 min",
    window: "07:30",
    windowStatus: "On Time",
    cargoText: "8 ambient units · 5 chilled units",
    instructions: "Rear dock • Ring twice, receiver is in the cold rooms",
    mapButtonType: "split",
    expectedUnits: 13,
    deliveredItems: [
      { code: "FR-AMB-000070", category: "Ambient", qty: 5 },
      { code: "FR-AMB-000072", category: "Ambient", qty: 3 },
      { code: "FR-CHL-000160", category: "Chilled", qty: 3 },
      { code: "FR-CHL-000163", category: "Chilled", qty: 2 },
    ],
  },
  {
    id: "stop-04",
    stopNumber: "04",
    stopIndex: 3,
    totalStops: 7,
    name: "Mawanella",
    address: "15 • Kandy - Colombo rd",
    dockTag: "Side ramp",
    eta: "06:12",
    etaDistanceTime: "8.5 Km • 15 min",
    window: "08:00",
    windowStatus: "On Time",
    cargoText: "7 ambient units · 3 chilled units",
    instructions: "Side ramp • Security check at main gate before entering delivery bay",
    mapButtonType: "split",
    expectedUnits: 10,
    deliveredItems: [
      { code: "FR-AMB-000075", category: "Ambient", qty: 4 },
      { code: "FR-AMB-000078", category: "Ambient", qty: 3 },
      { code: "FR-CHL-000165", category: "Chilled", qty: 2 },
      { code: "FR-CHL-000168", category: "Chilled", qty: 1 },
    ],
  },
  {
    id: "stop-05",
    stopNumber: "05",
    stopIndex: 4,
    totalStops: 7,
    name: "Kegalle",
    address: "42 • Main Street",
    dockTag: "Dock 1",
    eta: "06:38",
    etaDistanceTime: "12.3 Km • 20 min",
    window: "08:30",
    windowStatus: "On Time",
    cargoText: "9 ambient units · 6 chilled units",
    instructions: "Dock 1 • Unload chilled crates first to preserve cold chain log",
    mapButtonType: "split",
    expectedUnits: 15,
    deliveredItems: [
      { code: "FR-AMB-000082", category: "Ambient", qty: 5 },
      { code: "FR-AMB-000085", category: "Ambient", qty: 4 },
      { code: "FR-CHL-000170", category: "Chilled", qty: 3 },
      { code: "FR-CHL-000174", category: "Chilled", qty: 3 },
    ],
  },
  {
    id: "stop-06",
    stopNumber: "06",
    stopIndex: 5,
    totalStops: 7,
    name: "Warakapola",
    address: "88 • Colombo - Kandy rd",
    dockTag: "Front dock",
    eta: "07:05",
    etaDistanceTime: "14.1 Km • 22 min",
    window: "09:00",
    windowStatus: "On Time",
    cargoText: "4 ambient units · 4 chilled units",
    instructions: "Front dock • Store supervisor verifies delivery seal on arrival",
    mapButtonType: "split",
    expectedUnits: 8,
    deliveredItems: [
      { code: "FR-AMB-000089", category: "Ambient", qty: 2 },
      { code: "FR-AMB-000091", category: "Ambient", qty: 2 },
      { code: "FR-CHL-000177", category: "Chilled", qty: 2 },
      { code: "FR-CHL-000180", category: "Chilled", qty: 2 },
    ],
  },
  {
    id: "stop-07",
    stopNumber: "07",
    stopIndex: 6,
    totalStops: 7,
    name: "Ambepussa",
    address: "04 • Kurunegala rd",
    dockTag: "Bay 3",
    eta: "07:32",
    etaDistanceTime: "11.8 Km • 18 min",
    window: "09:30",
    windowStatus: "On Time",
    cargoText: "6 ambient units · 2 chilled units",
    instructions: "Bay 3 • Final run stop, return empties and pallets to depot",
    mapButtonType: "wide",
    expectedUnits: 8,
    deliveredItems: [
      { code: "FR-AMB-000095", category: "Ambient", qty: 4 },
      { code: "FR-AMB-000097", category: "Ambient", qty: 2 },
      { code: "FR-CHL-000185", category: "Chilled", qty: 2 },
    ],
  },
];
