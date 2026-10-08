"use client";

import { useMemo } from "react";
import type { DeliveryRecordView, OutletView } from "@shared/domain/types";
import { usePositionStream } from "@shared/live/usePositionStream";
import { useTripTrail } from "@shared/live/useTripTrail";
import { LiveMap, num, withLive, type MapLine, type MapMarker } from "@shared/ui/map";
import { clock } from "@shared/wording";
import { livePoll, useDemo } from "@shared/demo/useDemo";
import { Card, Chip, Muted } from "../ui.tsx";
import { useT } from "../i18n.tsx";

// Figma "05 Delivery tracking" map card (11:113478): this vehicle only, the
// trail it has driven, a dashed leg to this store and the store's pin. Other
// stores on the trip are not drawn: their locations are not this store's to
// see (issue #161 PLAN). The server stops answering once this store's stop is
// done (R-EXE-20), and the card then says so.

export default function LiveMapCard({ outlet, stop }: { outlet: OutletView | null; stop: DeliveryRecordView }): React.JSX.Element | null {
  const t = useT();
  const demo = useDemo();
  // Pushed as each fix lands (R-EXE-23), polled when the push goes quiet.
  const positions = usePositionStream({ kind: "outlet", codes: outlet ? [outlet.outletId] : [] }, stop.serviceDate);
  const done = stop.completedAt !== null;
  // Once this store's stop is done the server stops answering (R-EXE-20), so the trail is not asked for again.
  const trail = useTripTrail(done ? null : stop.tripId, livePoll(demo, 15_000));
  const position = positions.data?.find((p) => p.vehicleId === stop.vehicleId) ?? null;
  const lat = num(position?.latitude);
  const lon = num(position?.longitude);
  const here = lat !== null && lon !== null ? { lat, lon } : null;
  const storeLat = num(outlet?.location?.latitude);
  const storeLon = num(outlet?.location?.longitude);
  const store = storeLat !== null && storeLon !== null ? { lat: storeLat, lon: storeLon } : null;
  const approximate = outlet?.location?.precision === "district";

  const markers: MapMarker[] = [];
  if (store) {
    markers.push({
      id: "store", kind: "store", ...store, badge: String(stop.stopSequence).padStart(2, "0"),
      label: approximate ? t("Your store · Approximate · {place}", { place: outlet?.districtName ?? "" }) : t("Your store"),
      ariaLabel: t("Your store"), selectable: false,
    });
  }
  if (here && position) {
    markers.push({
      id: stop.vehicleId, kind: "vehicle", ...here, heading: num(position.headingDeg), status: position.offline ? "offline" : "on-time",
      faded: position.offline, label: stop.vehicleId, ariaLabel: position.offline ? t("{vehicle}, last seen {time}", { vehicle: stop.vehicleId, time: clock(position.recordedAt) }) : t("{vehicle}, live", { vehicle: stop.vehicleId }), selectable: false,
    });
  }
  const lines: MapLine[] = [];
  const driven = withLive(trail.points ?? [], here && position ? { ...here, at: Date.parse(position.recordedAt) } : null);
  if (driven.length > 1) lines.push({ id: "driven", points: driven, style: "driven" });
  if (here && store && !done) lines.push({ id: "leg", points: [here, store], style: "planned" });
  // Fit once to the store and the vehicle's first position, not on every move:
  // a re-fit each poll would undo the manager's own pan and zoom.
  const hasVehicle = here !== null;
  const fit = useMemo(
    () => [here, store].filter((p): p is NonNullable<typeof p> => p !== null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [hasVehicle, store?.lat, store?.lon],
  );

  const where = stop.tripStopCount
    ? t("Stop {n} of {total}", { n: stop.stopSequence, total: stop.tripStopCount })
    : t("Stop {n}", { n: stop.stopSequence });
  return (
    <Card label={t("Live map · {vehicle}", { vehicle: stop.vehicleId })}>
      <h2 className="text-[17px] font-medium text-black">{t("Live map · {vehicle}", { vehicle: stop.vehicleId })}</h2>
      <div className="flex flex-wrap items-center gap-2">
        {position ? (
          position.offline ? <Chip outline>{t("Last seen {time}", { time: clock(position.recordedAt) })}</Chip> : <Chip tone="mint">{t("Live")}</Chip>
        ) : (
          <Chip outline>{t(done ? "Delivered" : "No live location")}</Chip>
        )}
        <Muted>
          {where}
          {position ? t(" · updated {time}", { time: clock(position.recordedAt) }) : ""}
        </Muted>
      </div>
      <div className="mt-3 h-[300px] w-full overflow-hidden rounded-[20px]">
        <LiveMap markers={markers} lines={lines} fit={fit} className="h-full w-full" background="#eef3ef" />
      </div>
      {!position && !done && <Muted>{t("The driver’s position appears here while the vehicle is on its way. Until then the times above are the plan.")}</Muted>}
      {positions.error && (
        <p role="status" className="text-[13px] font-light text-go-muted">
          {positions.loadedAt ? t("Position not updated since {time}", { time: clock(positions.loadedAt) }) : t("Position not updated")} ·{" "}
          <button type="button" className="underline" onClick={positions.refresh}>
            {t("Retry")}
          </button>
        </p>
      )}
    </Card>
  );
}
