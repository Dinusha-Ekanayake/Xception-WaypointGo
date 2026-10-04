"use client";

import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DeliveryRecordView, OutletView, TrailPointView, VehiclePositionView } from "@shared/domain/types";
import { LiveMap, num, type MapLine, type MapMarker } from "@shared/ui/map";
import { clock } from "@shared/wording";
import { livePoll, useDemo } from "@shared/demo/useDemo";
import { Card, Chip, Muted } from "../ui.tsx";

// Figma "05 Delivery tracking" map card (11:113478): this vehicle only, the
// trail it has driven, a dashed leg to this store and the store's pin. Other
// stores on the trip are not drawn: their locations are not this store's to
// see (issue #161 PLAN). The server stops answering once this store's stop is
// done (R-EXE-20), and the card then says so.

const q = encodeURIComponent;

export default function LiveMapCard({ outlet, stop }: { outlet: OutletView | null; stop: DeliveryRecordView }): React.JSX.Element | null {
  const demo = useDemo();
  const positions = useResource(
    outlet ? (signal: AbortSignal) => request<VehiclePositionView[]>(`/api/execution/positions?outlet=${q(outlet.outletId)}&date=${q(stop.serviceDate)}`, { signal }) : null,
    `store-positions|${outlet?.outletId ?? ""}|${stop.serviceDate}`,
    livePoll(demo, 15_000),
  );
  const trail = useResource(
    (signal: AbortSignal) => request<{ items: TrailPointView[] }>(`/api/execution/trips/${q(stop.tripId)}/trail?limit=200`, { signal }),
    `store-trail|${stop.tripId}`,
    livePoll(demo, 15_000),
  );
  const position = positions.data?.find((p) => p.vehicleId === stop.vehicleId) ?? null;
  const lat = num(position?.latitude);
  const lon = num(position?.longitude);
  const here = lat !== null && lon !== null ? { lat, lon } : null;
  const storeLat = num(outlet?.location?.latitude);
  const storeLon = num(outlet?.location?.longitude);
  const store = storeLat !== null && storeLon !== null ? { lat: storeLat, lon: storeLon } : null;
  const approximate = outlet?.location?.precision === "district";
  const done = stop.completedAt !== null;

  const markers: MapMarker[] = [];
  if (store) {
    markers.push({
      id: "store", kind: "store", ...store, badge: String(stop.stopSequence).padStart(2, "0"),
      label: approximate ? `Your store · Approximate · ${outlet?.districtName}` : "Your store",
      ariaLabel: "Your store", selectable: false,
    });
  }
  if (here && position) {
    markers.push({
      id: stop.vehicleId, kind: "vehicle", ...here, heading: num(position.headingDeg), status: position.offline ? "offline" : "on-time",
      faded: position.offline, label: stop.vehicleId, ariaLabel: `${stop.vehicleId}, ${position.offline ? `last seen ${clock(position.recordedAt)}` : "live"}`, selectable: false,
    });
  }
  const lines: MapLine[] = [];
  const driven = (trail.data?.items ?? []).filter((p) => !p.lowQuality).map((p) => ({ lat: num(p.latitude) ?? 0, lon: num(p.longitude) ?? 0 }));
  if (driven.length > 1) lines.push({ id: "driven", points: driven, style: "driven" });
  if (here && store && !done) lines.push({ id: "leg", points: [here, store], style: "planned" });
  const fit = [here, store].filter((p): p is NonNullable<typeof p> => p !== null);

  const of = stop.tripStopCount ? ` of ${stop.tripStopCount}` : "";
  return (
    <Card label={`Live map · ${stop.vehicleId}`}>
      <h2 className="text-[17px] font-medium text-black">Live map · {stop.vehicleId}</h2>
      <div className="flex flex-wrap items-center gap-2">
        {position ? (
          position.offline ? <Chip outline>Last seen {clock(position.recordedAt)}</Chip> : <Chip tone="mint">Live</Chip>
        ) : (
          <Chip outline>{done ? "Delivered" : "No live location"}</Chip>
        )}
        <Muted>
          Stop {stop.stopSequence}{of}{position ? ` · updated ${clock(position.recordedAt)}` : ""}
        </Muted>
      </div>
      <div className="mt-3 h-[300px] w-full overflow-hidden rounded-[20px]">
        <LiveMap markers={markers} lines={lines} fit={fit} className="h-full w-full" background="#eef3ef" />
      </div>
      {!position && !done && <Muted>The driver&rsquo;s position appears here while the vehicle is on its way. Until then the times above are the plan.</Muted>}
      {positions.error && <Muted>Position not updated{positions.loadedAt ? ` since ${clock(positions.loadedAt)}` : ""}.</Muted>}
    </Card>
  );
}
