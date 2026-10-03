import { request } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import type { OrderStatus } from "@shared/domain/ordering";

export type AdminOrder = {
  orderId: string;
  orderRef: string;
  outletId: string;
  depot: string;
  brand: "Fresh" | "Style" | "Tech" | string;
  deliveryDate: string;
  status: OrderStatus | string;
  temperature: "ambient" | "chilled" | string;
  weightKg: number;
  volumeM3: number;
  itemCount: number;
  rowVersion: number;
};

export type AdminOrderLine = {
  productId: string;
  productName?: string;
  quantity: number;
  revision: number;
};

export type AdminOrderDetail = {
  order: AdminOrder;
  lines: AdminOrderLine[];
};

export type AdminStatusTimeline = {
  from: string | null;
  to: string;
  reason: string;
  actorId: string | null;
  actorName?: string;
  at: string;
};

export async function fetchAdminOrders(options: {
  depot?: string;
  date?: string;
  after?: string;
  limit?: number;
  status?: string;
  brand?: string;
  outlet?: string;
  temperature?: string;
  search?: string;
  signal?: AbortSignal;
} = {}): Promise<Page<AdminOrder>> {
  const params = new URLSearchParams();
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.date && options.date !== "all") params.set("date", options.date);
  if (options.status && options.status !== "all") params.set("status", options.status);
  if (options.brand && options.brand !== "all") params.set("brand", options.brand);
  if (options.outlet && options.outlet !== "all") params.set("outlet", options.outlet);
  if (options.temperature && options.temperature !== "all") params.set("temperature", options.temperature);
  if (options.search) params.set("search", options.search);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();

  return request<Page<AdminOrder>>(`/api/admin/orders${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

export async function fetchAdminOrderDetail(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminOrderDetail> {
  return request<AdminOrderDetail>(`/api/admin/orders/${id}`, {
    signal: options?.signal,
  });
}

export async function fetchAdminOrderTimeline(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminStatusTimeline[]> {
  return request<AdminStatusTimeline[]>(`/api/admin/orders/${id}/timeline`, {
    signal: options?.signal,
  });
}

const TODAY_DATE = "2026-10-03";
const YESTERDAY_DATE = "2026-10-02";
const TOMORROW_DATE = "2026-10-04";

export const FALLBACK_ADMIN_ORDERS: AdminOrder[] = [
  // Today's Active Orders
  {
    orderId: "a1000001-0000-0000-0000-000000000001",
    orderRef: "ORD-20261003-001",
    outletId: "OUT001",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "CONFIRMED",
    temperature: "ambient",
    weightKg: 420.5,
    volumeM3: 2.8,
    itemCount: 34,
    rowVersion: 1,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000002",
    orderRef: "ORD-20261003-002",
    outletId: "OUT002",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "IN_TRANSIT",
    temperature: "chilled",
    weightKg: 680.0,
    volumeM3: 4.2,
    itemCount: 48,
    rowVersion: 2,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000003",
    orderRef: "ORD-20261003-003",
    outletId: "OUT005",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "ALLOCATED",
    temperature: "ambient",
    weightKg: 1250.0,
    volumeM3: 7.5,
    itemCount: 82,
    rowVersion: 1,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000004",
    orderRef: "ORD-20261003-004",
    outletId: "OUT015",
    depot: "PELIYAGODA",
    brand: "Style",
    deliveryDate: TODAY_DATE,
    status: "LOADING",
    temperature: "ambient",
    weightKg: 310.0,
    volumeM3: 5.6,
    itemCount: 120,
    rowVersion: 3,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000005",
    orderRef: "ORD-20261003-005",
    outletId: "OUT021",
    depot: "PELIYAGODA",
    brand: "Tech",
    deliveryDate: TODAY_DATE,
    status: "CONFIRMED",
    temperature: "ambient",
    weightKg: 520.0,
    volumeM3: 3.4,
    itemCount: 18,
    rowVersion: 1,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000006",
    orderRef: "ORD-20261003-006",
    outletId: "OUT076",
    depot: "KANDY",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "DELIVERED",
    temperature: "ambient",
    weightKg: 890.0,
    volumeM3: 5.1,
    itemCount: 65,
    rowVersion: 4,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000007",
    orderRef: "ORD-20261003-007",
    outletId: "OUT077",
    depot: "KANDY",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "IN_TRANSIT",
    temperature: "chilled",
    weightKg: 740.0,
    volumeM3: 4.8,
    itemCount: 52,
    rowVersion: 2,
  },
  {
    orderId: "a1000001-0000-0000-0000-000000000008",
    orderRef: "ORD-20261003-008",
    outletId: "OUT089",
    depot: "KANDY",
    brand: "Style",
    deliveryDate: TODAY_DATE,
    status: "ALLOCATED",
    temperature: "ambient",
    weightKg: 280.0,
    volumeM3: 4.9,
    itemCount: 95,
    rowVersion: 1,
  },

  // Deferred Orders
  {
    orderId: "a1000002-0000-0000-0000-000000000001",
    orderRef: "ORD-20261002-014",
    outletId: "OUT008",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "DEFERRED",
    temperature: "chilled",
    weightKg: 920.0,
    volumeM3: 5.8,
    itemCount: 58,
    rowVersion: 2,
  },
  {
    orderId: "a1000002-0000-0000-0000-000000000002",
    orderRef: "ORD-20261002-019",
    outletId: "OUT017",
    depot: "PELIYAGODA",
    brand: "Style",
    deliveryDate: TODAY_DATE,
    status: "DEFERRED",
    temperature: "ambient",
    weightKg: 450.0,
    volumeM3: 6.2,
    itemCount: 140,
    rowVersion: 2,
  },
  {
    orderId: "a1000002-0000-0000-0000-000000000003",
    orderRef: "ORD-20261002-022",
    outletId: "OUT022",
    depot: "PELIYAGODA",
    brand: "Tech",
    deliveryDate: TODAY_DATE,
    status: "DEFERRED",
    temperature: "ambient",
    weightKg: 610.0,
    volumeM3: 4.1,
    itemCount: 22,
    rowVersion: 2,
  },
  {
    orderId: "a1000002-0000-0000-0000-000000000004",
    orderRef: "ORD-20261003-011",
    outletId: "OUT084",
    depot: "KANDY",
    brand: "Fresh",
    deliveryDate: TODAY_DATE,
    status: "DEFERRED",
    temperature: "chilled",
    weightKg: 850.0,
    volumeM3: 5.4,
    itemCount: 60,
    rowVersion: 2,
  },
  {
    orderId: "a1000002-0000-0000-0000-000000000005",
    orderRef: "ORD-20261003-015",
    outletId: "OUT098",
    depot: "KANDY",
    brand: "Tech",
    deliveryDate: TODAY_DATE,
    status: "DEFERRED",
    temperature: "ambient",
    weightKg: 780.0,
    volumeM3: 4.5,
    itemCount: 26,
    rowVersion: 2,
  },

  // Historical & Future Orders (for All Orders tab)
  {
    orderId: "a1000003-0000-0000-0000-000000000001",
    orderRef: "ORD-20261002-001",
    outletId: "OUT012",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: YESTERDAY_DATE,
    status: "DELIVERED",
    temperature: "ambient",
    weightKg: 540.0,
    volumeM3: 3.6,
    itemCount: 42,
    rowVersion: 4,
  },
  {
    orderId: "a1000003-0000-0000-0000-000000000002",
    orderRef: "ORD-20261002-004",
    outletId: "OUT019",
    depot: "PELIYAGODA",
    brand: "Style",
    deliveryDate: YESTERDAY_DATE,
    status: "DELIVERED",
    temperature: "ambient",
    weightKg: 380.0,
    volumeM3: 5.2,
    itemCount: 110,
    rowVersion: 4,
  },
  {
    orderId: "a1000003-0000-0000-0000-000000000003",
    orderRef: "ORD-20261002-008",
    outletId: "OUT101",
    depot: "KANDY",
    brand: "Fresh",
    deliveryDate: YESTERDAY_DATE,
    status: "DELIVERED",
    temperature: "ambient",
    weightKg: 910.0,
    volumeM3: 5.9,
    itemCount: 72,
    rowVersion: 4,
  },
  {
    orderId: "a1000003-0000-0000-0000-000000000004",
    orderRef: "ORD-20261004-001",
    outletId: "OUT025",
    depot: "PELIYAGODA",
    brand: "Fresh",
    deliveryDate: TOMORROW_DATE,
    status: "CONFIRMED",
    temperature: "chilled",
    weightKg: 640.0,
    volumeM3: 4.0,
    itemCount: 45,
    rowVersion: 1,
  },
  {
    orderId: "a1000003-0000-0000-0000-000000000005",
    orderRef: "ORD-20261004-002",
    outletId: "OUT035",
    depot: "PELIYAGODA",
    brand: "Style",
    deliveryDate: TOMORROW_DATE,
    status: "CONFIRMED",
    temperature: "ambient",
    weightKg: 290.0,
    volumeM3: 4.8,
    itemCount: 88,
    rowVersion: 1,
  },
  {
    orderId: "a1000003-0000-0000-0000-000000000006",
    orderRef: "ORD-20261004-003",
    outletId: "OUT105",
    depot: "KANDY",
    brand: "Style",
    deliveryDate: TOMORROW_DATE,
    status: "CONFIRMED",
    temperature: "ambient",
    weightKg: 330.0,
    volumeM3: 5.0,
    itemCount: 92,
    rowVersion: 1,
  },
];

export const FALLBACK_ORDER_LINES: Record<string, AdminOrderLine[]> = {
  "ORD-20261003-001": [
    { productId: "PROD-VEG-001", productName: "Fresh Vegetables Box (10kg)", quantity: 15, revision: 1 },
    { productId: "PROD-DRY-012", productName: "Highland Rice 5kg Pack", quantity: 20, revision: 1 },
    { productId: "PROD-DRY-034", productName: "Ceylon Spices Pack", quantity: 12, revision: 1 },
  ],
  "ORD-20261003-002": [
    { productId: "PROD-CHL-002", productName: "Fresh Pasteurized Milk 1L Crate", quantity: 24, revision: 1 },
    { productId: "PROD-CHL-008", productName: "Highland Butter 200g Pack", quantity: 30, revision: 1 },
    { productId: "PROD-CHL-015", productName: "Yogurt Cups 80-pack Tray", quantity: 10, revision: 1 },
  ],
  "ORD-20261003-003": [
    { productId: "PROD-GRC-101", productName: "Sugar Refined 25kg Sack", quantity: 20, revision: 1 },
    { productId: "PROD-GRC-104", productName: "Wheat Flour 25kg Sack", quantity: 15, revision: 1 },
    { productId: "PROD-OIL-022", productName: "Cooking Oil 5L Can", quantity: 35, revision: 1 },
  ],
  "ORD-20261003-004": [
    { productId: "PROD-STY-201", productName: "Men's Casual Linen Shirts (Assorted)", quantity: 45, revision: 1 },
    { productId: "PROD-STY-205", productName: "Women's Summer Dresses Pack", quantity: 35, revision: 1 },
    { productId: "PROD-STY-210", productName: "Denim Jeans Stack Pack", quantity: 40, revision: 1 },
  ],
  "ORD-20261003-005": [
    { productId: "PROD-TCH-301", productName: "Smart LED TV 43-inch", quantity: 4, revision: 1 },
    { productId: "PROD-TCH-308", productName: "Microwave Oven 20L", quantity: 6, revision: 1 },
    { productId: "PROD-TCH-312", productName: "Soundbar Audio System", quantity: 8, revision: 1 },
  ],
  "ORD-20261002-014": [
    { productId: "PROD-CHL-004", productName: "Cottage Cheese 500g Tub", quantity: 18, revision: 1 },
    { productId: "PROD-CHL-011", productName: "Ice Cream Tub 2L (Vanilla/Choc)", quantity: 25, revision: 1 },
    { productId: "PROD-CHL-020", productName: "Frozen Meat Cuts Assorted", quantity: 15, revision: 1 },
  ],
};

export const FALLBACK_TIMELINE: Record<string, AdminStatusTimeline[]> = {
  "ORD-20261003-001": [
    { from: null, to: "CONFIRMED", reason: "Store order placed and stock reserved by warehouse API", actorId: "s1", actorName: "Ayesha Hassan (Store Mgr)", at: "2026-10-02T15:40:00+05:30" },
  ],
  "ORD-20261003-002": [
    { from: null, to: "CONFIRMED", reason: "Store order placed and verified", actorId: "s2", actorName: "Chamari Silva (Store Mgr)", at: "2026-10-02T15:30:00+05:30" },
    { from: "CONFIRMED", to: "ALLOCATED", reason: "Assigned to refrigerated truck VEH014 (Trip 1)", actorId: "d1", actorName: "Nimali Perera (Dispatcher)", at: "2026-10-02T16:30:00+05:30" },
    { from: "ALLOCATED", to: "IN_TRANSIT", reason: "Driver departed Peliyagoda dock", actorId: "r1", actorName: "Amal Silva (Driver)", at: "2026-10-03T05:15:00+05:30" },
  ],
  "ORD-20261002-014": [
    { from: null, to: "CONFIRMED", reason: "Order placed for Oct 03 delivery", actorId: "s1", actorName: "Nalaka Perera (Store Mgr)", at: "2026-10-02T14:20:00+05:30" },
    { from: "CONFIRMED", to: "DEFERRED", reason: "Capacity exceeded on Peliyagoda chilled fleet for morning cutoff", actorId: "d1", actorName: "Nimali Perera (Dispatcher)", at: "2026-10-02T16:45:00+05:30" },
  ],
  "ORD-20261002-019": [
    { from: null, to: "CONFIRMED", reason: "Order placed for Mall Style delivery", actorId: "s2", actorName: "Dilani Senanayake (Store Mgr)", at: "2026-10-02T15:00:00+05:30" },
    { from: "CONFIRMED", to: "DEFERRED", reason: "Mall bay delivery window constraint conflict with earlier run", actorId: "d1", actorName: "Nimali Perera (Dispatcher)", at: "2026-10-02T16:50:00+05:30" },
  ],
};
