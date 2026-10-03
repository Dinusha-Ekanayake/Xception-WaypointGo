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
