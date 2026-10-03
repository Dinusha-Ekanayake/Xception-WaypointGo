import { request } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import type { PlanView, TripView, StopView, PlanStatus } from "@shared/domain/planning";
import type { DeliveryOutcome } from "@shared/domain/execution";

export type AdminStop = {
  sequence: number;
  orderId: string;
  orderRef: string;
  outletId: string;
  outletName: string;
  district: string;
  plannedArrival: string;
  windowOpen: string;
  windowClose: string;
  serviceMinutes: number;
  weightKg: number;
  volumeM3: number;
  outcome?: DeliveryOutcome;
  arrivedAt?: string | null;
  completedAt?: string | null;
  delayMinutes?: number;
};

export type AdminTrip = {
  tripId: string;
  planId: string;
  vehicleId: string;
  vehicleType: "Van" | "Truck";
  driverId: string;
  driverName: string;
  driverPhone: string;
  tripNumber: 1 | 2;
  brand: "Fresh" | "Style" | "Tech";
  district: string;
  depot: string;
  temperature: "Ambient" | "Chilled (Reefer)";
  serviceDate: string;
  plannedDeparture: string;
  estimatedReturn: string;
  actualDeparture?: string | null;
  weightKg: number;
  weightCapKg: number;
  volumeM3: number;
  volumeCapM3: number;
  stops: AdminStop[];
  status: "PLANNED" | "LOADING" | "IN_TRANSIT" | "COMPLETED" | "DELAYED";
  currentStopSequence?: number;
  completedStopsCount?: number;
  totalStopsCount?: number;
};

export type AdminPlan = {
  planId: string;
  depot: string;
  serviceDate: string;
  planVersion: number;
  status: PlanStatus;
  publishedAt: string | null;
  referenceVersion: string;
  ruleSetVersion: string;
  priorityPolicyVersion: string;
  solverEngine: string;
  totalOrders: number;
  allocatedOrders: number;
  deferredOrders: number;
  totalWeightKg: number;
  totalVolumeM3: number;
  fuelEstimatedLitres: number;
  trips: AdminTrip[];
};

export async function fetchAdminPlans(options: {
  depot?: string;
  date?: string;
  status?: string;
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<AdminPlan>> {
  const params = new URLSearchParams();
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.date && options.date !== "all") params.set("date", options.date);
  if (options.status && options.status !== "all") params.set("status", options.status);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();

  return request<Page<AdminPlan>>(`/api/admin/plans${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

export async function fetchAdminPlanDetail(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminPlan> {
  const res = await request<AdminPlan | { plan: AdminPlan }>(`/api/admin/plans/${id}`, {
    signal: options?.signal,
  });
  if (res && "plan" in res && res.plan) {
    return res.plan;
  }
  return res as AdminPlan;
}

const TODAY = "2026-10-03";
const YESTERDAY = "2026-10-02";
const TOMORROW = "2026-10-04";

export const FALLBACK_ADMIN_PLANS: AdminPlan[] = [
  // 1. Peliyagoda Today's Master Plan
  {
    planId: "p1000001-0000-0000-0000-000000000001",
    depot: "PELIYAGODA",
    serviceDate: TODAY,
    planVersion: 2,
    status: "PUBLISHED",
    publishedAt: "2026-10-03T04:15:00+05:30",
    referenceVersion: "REF-2026-W40-V1",
    ruleSetVersion: "RULES-2026-V3.2",
    priorityPolicyVersion: "POL-ALLOC-V2",
    solverEngine: "Waypoint Dispatch V2 (Constraint Exact)",
    totalOrders: 28,
    allocatedOrders: 26,
    deferredOrders: 2,
    totalWeightKg: 14850,
    totalVolumeM3: 94.2,
    fuelEstimatedLitres: 142.5,
    trips: [
      {
        tripId: "TRP-20261003-001",
        planId: "p1000001-0000-0000-0000-000000000001",
        vehicleId: "WP-1042",
        vehicleType: "Van",
        driverId: "DRV-001",
        driverName: "Amal Silva",
        driverPhone: "+94 77 123 4567",
        tripNumber: 1,
        brand: "Fresh",
        district: "Colombo",
        depot: "PELIYAGODA",
        temperature: "Ambient",
        serviceDate: TODAY,
        plannedDeparture: "05:00",
        estimatedReturn: "08:15",
        actualDeparture: "05:02",
        weightKg: 1120,
        weightCapKg: 1200,
        volumeM3: 7.8,
        volumeCapM3: 8.5,
        status: "COMPLETED",
        currentStopSequence: 3,
        completedStopsCount: 3,
        totalStopsCount: 3,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000001",
            orderRef: "ORD-20261003-001",
            outletId: "OUT001",
            outletName: "Colombo Central Fresh Market",
            district: "Colombo",
            plannedArrival: "05:25",
            windowOpen: "05:00",
            windowClose: "07:30",
            serviceMinutes: 20,
            weightKg: 420.5,
            volumeM3: 2.8,
            outcome: "DELIVERED",
            arrivedAt: "05:22",
            completedAt: "05:44",
            delayMinutes: 0,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000003",
            orderRef: "ORD-20261003-003",
            outletId: "OUT005",
            outletName: "Borella Hypermarket Fresh",
            district: "Colombo",
            plannedArrival: "06:10",
            windowOpen: "04:00",
            windowClose: "07:45",
            serviceMinutes: 30,
            weightKg: 410.0,
            volumeM3: 2.9,
            outcome: "DELIVERED",
            arrivedAt: "06:08",
            completedAt: "06:39",
            delayMinutes: 0,
          },
          {
            sequence: 3,
            orderId: "a1000001-0000-0000-0000-000000000008",
            orderRef: "ORD-20261003-008",
            outletId: "OUT008",
            outletName: "Nugegoda Fresh Mart",
            district: "Colombo",
            plannedArrival: "07:05",
            windowOpen: "05:00",
            windowClose: "07:30",
            serviceMinutes: 25,
            weightKg: 289.5,
            volumeM3: 2.1,
            outcome: "DELIVERED",
            arrivedAt: "07:02",
            completedAt: "07:28",
            delayMinutes: 0,
          },
        ],
      },
      {
        tripId: "TRP-20261003-002",
        planId: "p1000001-0000-0000-0000-000000000001",
        vehicleId: "WP-2088",
        vehicleType: "Truck",
        driverId: "DRV-003",
        driverName: "Dinuka Samarakoon",
        driverPhone: "+94 77 345 6789",
        tripNumber: 1,
        brand: "Fresh",
        district: "Colombo",
        depot: "PELIYAGODA",
        temperature: "Chilled (Reefer)",
        serviceDate: TODAY,
        plannedDeparture: "05:15",
        estimatedReturn: "09:00",
        actualDeparture: "05:20",
        weightKg: 4950,
        weightCapKg: 5510,
        volumeM3: 23.8,
        volumeCapM3: 26.4,
        status: "IN_TRANSIT",
        currentStopSequence: 2,
        completedStopsCount: 1,
        totalStopsCount: 3,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000002",
            orderRef: "ORD-20261003-002",
            outletId: "OUT002",
            outletName: "Kollupitiya Super Fresh",
            district: "Colombo",
            plannedArrival: "05:45",
            windowOpen: "05:30",
            windowClose: "08:00",
            serviceMinutes: 30,
            weightKg: 1680.0,
            volumeM3: 8.2,
            outcome: "DELIVERED",
            arrivedAt: "05:48",
            completedAt: "06:19",
            delayMinutes: 3,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000012",
            orderRef: "ORD-20261003-012",
            outletId: "OUT012",
            outletName: "Dehiwala Ocean View Fresh",
            district: "Colombo",
            plannedArrival: "06:50",
            windowOpen: "05:30",
            windowClose: "08:00",
            serviceMinutes: 35,
            weightKg: 1840.0,
            volumeM3: 8.9,
            outcome: "ARRIVED",
            arrivedAt: "06:53",
            delayMinutes: 3,
          },
          {
            sequence: 3,
            orderId: "a1000001-0000-0000-0000-000000000015",
            orderRef: "ORD-20261003-015",
            outletId: "OUT-SAMPLE-01",
            outletName: "Mount Lavinia Coast Fresh",
            district: "Colombo",
            plannedArrival: "07:45",
            windowOpen: "06:00",
            windowClose: "08:30",
            serviceMinutes: 25,
            weightKg: 1430.0,
            volumeM3: 6.7,
            outcome: "PENDING",
            delayMinutes: 0,
          },
        ],
      },
      {
        tripId: "TRP-20261003-003",
        planId: "p1000001-0000-0000-0000-000000000001",
        vehicleId: "WP-1223",
        vehicleType: "Van",
        driverId: "DRV-004",
        driverName: "Shehan Mendis",
        driverPhone: "+94 76 456 7890",
        tripNumber: 1,
        brand: "Style",
        district: "Colombo",
        depot: "PELIYAGODA",
        temperature: "Ambient",
        serviceDate: TODAY,
        plannedDeparture: "08:30",
        estimatedReturn: "12:45",
        actualDeparture: "08:35",
        weightKg: 1080,
        weightCapKg: 1200,
        volumeM3: 8.1,
        volumeCapM3: 8.5,
        status: "IN_TRANSIT",
        currentStopSequence: 1,
        completedStopsCount: 0,
        totalStopsCount: 3,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000004",
            orderRef: "ORD-20261003-004",
            outletId: "OUT015",
            outletName: "One Galle Face Mall Style",
            district: "Colombo",
            plannedArrival: "09:15",
            windowOpen: "09:00",
            windowClose: "11:00",
            serviceMinutes: 40,
            weightKg: 310.0,
            volumeM3: 2.6,
            outcome: "ARRIVED",
            arrivedAt: "09:18",
            delayMinutes: 3,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000017",
            orderRef: "ORD-20261003-017",
            outletId: "OUT017",
            outletName: "Havelock City Mall Style",
            district: "Colombo",
            plannedArrival: "10:40",
            windowOpen: "10:30",
            windowClose: "12:30",
            serviceMinutes: 35,
            weightKg: 420.0,
            volumeM3: 3.0,
            outcome: "PENDING",
            delayMinutes: 0,
          },
          {
            sequence: 3,
            orderId: "a1000001-0000-0000-0000-000000000019",
            orderRef: "ORD-20261003-019",
            outletId: "OUT019",
            outletName: "Bambalapitiya Flagship Style",
            district: "Colombo",
            plannedArrival: "11:45",
            windowOpen: "11:00",
            windowClose: "13:00",
            serviceMinutes: 30,
            weightKg: 350.0,
            volumeM3: 2.5,
            outcome: "PENDING",
            delayMinutes: 0,
          },
        ],
      },
      {
        tripId: "TRP-20261003-004",
        planId: "p1000001-0000-0000-0000-000000000001",
        vehicleId: "WP-1288",
        vehicleType: "Van",
        driverId: "DRV-007",
        driverName: "Kavinda Mihiran",
        driverPhone: "+94 77 789 0123",
        tripNumber: 1,
        brand: "Tech",
        district: "Colombo",
        depot: "PELIYAGODA",
        temperature: "Ambient",
        serviceDate: TODAY,
        plannedDeparture: "13:00",
        estimatedReturn: "17:30",
        actualDeparture: null,
        weightKg: 980,
        weightCapKg: 1200,
        volumeM3: 7.2,
        volumeCapM3: 8.5,
        status: "PLANNED",
        currentStopSequence: 1,
        completedStopsCount: 0,
        totalStopsCount: 3,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000005",
            orderRef: "ORD-20261003-005",
            outletId: "OUT021",
            outletName: "Majestic City Tech Hub",
            district: "Colombo",
            plannedArrival: "13:45",
            windowOpen: "13:30",
            windowClose: "15:30",
            serviceMinutes: 35,
            weightKg: 520.0,
            volumeM3: 3.4,
            outcome: "PENDING",
            delayMinutes: 0,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000022",
            orderRef: "ORD-20261003-022",
            outletId: "OUT022",
            outletName: "Liberty Plaza Gadgets",
            district: "Colombo",
            plannedArrival: "14:45",
            windowOpen: "14:00",
            windowClose: "16:30",
            serviceMinutes: 30,
            weightKg: 260.0,
            volumeM3: 2.1,
            outcome: "PENDING",
            delayMinutes: 0,
          },
          {
            sequence: 3,
            orderId: "a1000001-0000-0000-0000-000000000025",
            orderRef: "ORD-20261003-025",
            outletId: "OUT025",
            outletName: "Union Place Audio Lounge",
            district: "Colombo",
            plannedArrival: "15:45",
            windowOpen: "15:00",
            windowClose: "17:30",
            serviceMinutes: 25,
            weightKg: 200.0,
            volumeM3: 1.7,
            outcome: "PENDING",
            delayMinutes: 0,
          },
        ],
      },
    ],
  },

  // 2. Kandy Regional Hub Today's Master Plan
  {
    planId: "p1000001-0000-0000-0000-000000000002",
    depot: "KANDY",
    serviceDate: TODAY,
    planVersion: 1,
    status: "PUBLISHED",
    publishedAt: "2026-10-03T04:30:00+05:30",
    referenceVersion: "REF-2026-W40-V1",
    ruleSetVersion: "RULES-2026-V3.2",
    priorityPolicyVersion: "POL-ALLOC-V2",
    solverEngine: "Waypoint Dispatch V2 (Constraint Exact)",
    totalOrders: 18,
    allocatedOrders: 18,
    deferredOrders: 0,
    totalWeightKg: 9640,
    totalVolumeM3: 61.5,
    fuelEstimatedLitres: 98.0,
    trips: [
      {
        tripId: "TRP-20261003-010",
        planId: "p1000001-0000-0000-0000-000000000002",
        vehicleId: "WP-1176",
        vehicleType: "Van",
        driverId: "DRV-002",
        driverName: "Fathima Rizwan",
        driverPhone: "+94 71 234 5678",
        tripNumber: 1,
        brand: "Fresh",
        district: "Kandy",
        depot: "KANDY",
        temperature: "Ambient",
        serviceDate: TODAY,
        plannedDeparture: "04:45",
        estimatedReturn: "08:30",
        actualDeparture: "04:48",
        weightKg: 1310,
        weightCapKg: 1400,
        volumeM3: 9.1,
        volumeCapM3: 9.8,
        status: "COMPLETED",
        currentStopSequence: 3,
        completedStopsCount: 3,
        totalStopsCount: 3,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000006",
            orderRef: "ORD-20261003-006",
            outletId: "OUT076",
            outletName: "Kandy City Centre Fresh Market",
            district: "Kandy",
            plannedArrival: "05:15",
            windowOpen: "04:30",
            windowClose: "07:00",
            serviceMinutes: 30,
            weightKg: 890.0,
            volumeM3: 5.1,
            outcome: "DELIVERED",
            arrivedAt: "05:12",
            completedAt: "05:44",
            delayMinutes: 0,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000080",
            orderRef: "ORD-20261003-080",
            outletId: "OUT080",
            outletName: "Peradeniya Botanical Fresh",
            district: "Kandy",
            plannedArrival: "06:15",
            windowOpen: "05:30",
            windowClose: "08:00",
            serviceMinutes: 25,
            weightKg: 420.0,
            volumeM3: 4.0,
            outcome: "DELIVERED",
            arrivedAt: "06:18",
            completedAt: "06:45",
            delayMinutes: 3,
          },
        ],
      },
      {
        tripId: "TRP-20261003-011",
        planId: "p1000001-0000-0000-0000-000000000002",
        vehicleId: "WP-2150",
        vehicleType: "Truck",
        driverId: "DRV-005",
        driverName: "Tharindu Bandara",
        driverPhone: "+94 77 567 8901",
        tripNumber: 1,
        brand: "Fresh",
        district: "Kandy",
        depot: "KANDY",
        temperature: "Chilled (Reefer)",
        serviceDate: TODAY,
        plannedDeparture: "05:00",
        estimatedReturn: "08:45",
        actualDeparture: "05:06",
        weightKg: 4620,
        weightCapKg: 5510,
        volumeM3: 22.1,
        volumeCapM3: 26.4,
        status: "IN_TRANSIT",
        currentStopSequence: 1,
        completedStopsCount: 1,
        totalStopsCount: 2,
        stops: [
          {
            sequence: 1,
            orderId: "a1000001-0000-0000-0000-000000000007",
            orderRef: "ORD-20261003-007",
            outletId: "OUT077",
            outletName: "Katugastota Valley Fresh",
            district: "Kandy",
            plannedArrival: "05:35",
            windowOpen: "05:00",
            windowClose: "07:30",
            serviceMinutes: 35,
            weightKg: 740.0,
            volumeM3: 4.8,
            outcome: "DELIVERED",
            arrivedAt: "05:38",
            completedAt: "06:14",
            delayMinutes: 3,
          },
          {
            sequence: 2,
            orderId: "a1000001-0000-0000-0000-000000000088",
            orderRef: "ORD-20261003-088",
            outletId: "OUT088",
            outletName: "Gampola Grand Fresh",
            district: "Kandy",
            plannedArrival: "07:15",
            windowOpen: "06:00",
            windowClose: "08:30",
            serviceMinutes: 30,
            weightKg: 3880.0,
            volumeM3: 17.3,
            outcome: "ARRIVED",
            arrivedAt: "07:19",
            delayMinutes: 4,
          },
        ],
      },
    ],
  },
];
