"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import type { DeliveryRecordView, IssueView, OrderView, OutletView, PendingReceiptView } from "@shared/domain/types";
import { Icon, Notice, Pending, SkeletonRows, cx } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { ORDER_STATUS, clock, depotToday, hhmm, longDay, onTheWay, temperatureLabel } from "../data/format.ts";
import { ISSUE_TYPE, isOpenIssue } from "../data/issues.ts";
import { BackButton, Button, Card, Chip, Muted } from "../ui.tsx";
import LiveMapCard from "./LiveMapCard.tsx";
import NextStop from "./NextStop.tsx";
import { Progress } from "./Home.tsx";
import { useT, useCounts } from "../i18n.tsx";

// Figma "05 Delivery tracking": one vehicle at a time, what it carries for this
// outlet, how far it has got and, on the live map card (issue #161), where it
// is. The call buttons are not here: nothing serves a phone number.

export default function Track({
  gateway,
  deliveries,
  orders,
  issues,
  outlet,
  toReceive,
  vehicleId,
  onVehicle,
  onReceive,
  onOpen,
  onBack,
}: {
  gateway: StoreGateway;
  deliveries: DeliveryRecordView[];
  orders: OrderView[];
  issues: IssueView[];
  outlet: OutletView | null;
  toReceive: PendingReceiptView[];
  vehicleId: string | null;
  onVehicle: (vehicleId: string) => void;
  onReceive: (orderId: string) => void;
  onOpen: (orderId: string) => void;
  onBack: () => void;
}): React.JSX.Element {
  const { units } = useCounts();
  const t = useT();
  const vehicles = [...new Set(deliveries.map((d) => d.vehicleId))];
  const vehicle = vehicleId && vehicles.includes(vehicleId) ? vehicleId : (vehicles[0] ?? null);
  const stops = deliveries.filter((d) => d.vehicleId === vehicle);
  const pending = new Set(toReceive.map((r) => r.orderId));
  const orderOf = (d: DeliveryRecordView) => orders.find((o) => o.orderId === d.orderId) ?? null;
  const first = stops[0] ? orderOf(stops[0]) : null;
  const arrived = stops.find((d) => d.arrivedAt)?.arrivedAt ?? null;
  const receivable = stops.find((d) => pending.has(d.orderId));
  const [focus, setFocus] = useState<string | null>(null);
  const timelineOf = focus ?? first?.orderId ?? null;
  const history = useResource(timelineOf ? (s) => gateway.history(timelineOf, s) : null, timelineOf ?? "");

  // A chilled order rides only on a refrigerated vehicle (R-PLN-02); nothing else is known of the vehicle.
  const refrigerated = stops.some((d) => orderOf(d)?.temperature === "chilled");

  const shortOf = (orderId: string) => issues.find((i) => i.type === "LOADING_SHORTFALL" && isOpenIssue(i) && i.subjects.some((s) => s.id === orderId));

  return (
    <div className="flex flex-col gap-4">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Track delivery{vehicle ? ` · ${vehicle}` : ""}</h1>
        <Muted>{longDay(depotToday())}</Muted>
      </div>

      {vehicles.length > 1 && (
        <div role="tablist" aria-label={t("Today's deliveries")} className="flex flex-wrap items-center gap-2">
          <span className="text-[13px] text-go-muted">{t("Today’s deliveries")}</span>
          {vehicles.map((v) => (
            <button
              key={v}
              type="button"
              role="tab"
              aria-selected={v === vehicle}
              onClick={() => onVehicle(v)}
              className={cx("min-h-12 rounded-full px-4 text-[14px] font-medium", v === vehicle ? "bg-[#031a0c] text-white" : "bg-white text-black")}
            >
              {v}
            </button>
          ))}
        </div>
      )}

      {!vehicle || stops.length === 0 ? (
        <Card label={t("Nothing on the road")}>
          <p className="text-[15px] text-black">{t("Nothing is on the road for your outlet today.")}</p>
          <Muted>{t("When a vehicle leaves the depot with your order it appears here.")}</Muted>
        </Card>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
          <div className="flex min-w-0 flex-col gap-5">
            <Card label={t("Where it is")} style={{ viewTransitionName: "vt-delivery" }}>
              <div className="flex items-center gap-2">
                <Chip tone={arrived ? "ink" : "mint"}>{t(arrived ? "Arrived" : "On the way")}</Chip>
                {refrigerated && <Chip outline>{t("Refrigerated vehicle")}</Chip>}
              </div>
              {first && <NextStop stop={stops[0] ?? null} order={first} outlet={outlet} />}
              {first && <Progress status={first.status} arrivedAt={stops[0]?.arrivedAt ?? null} />}
            </Card>

            {stops[0] && <LiveMapCard outlet={outlet} stop={stops[0]} />}

            <Card label={t("Timeline")}>
              <h2 className="text-[18px] font-medium text-black">{t("Timeline")}</h2>
              {history.error && <Notice tone="danger" title={t("Could not load the timeline")} onRetry={history.refresh}>{history.error.message}</Notice>}
              <ol className="flex flex-col gap-3">
                {(history.data ?? []).map((h) => (
                  <li key={`${h.to}-${h.at}`} className="flex gap-3">
                    <span className="mt-1 flex size-5 shrink-0 items-center justify-center rounded-full bg-go-success">
                      <Icon name="check-white" />
                    </span>
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="text-[15px] font-medium text-black">{ORDER_STATUS[h.to].label}</span>
                      {h.reason && <span className="text-[13px] text-go-muted">{h.reason}</span>}
                    </span>
                    <span className="text-[13px] text-go-muted">{clock(h.at)}</span>
                  </li>
                ))}
                {arrived && (
                  <li className="flex gap-3">
                    <span className="mt-1 flex size-5 shrink-0 items-center justify-center rounded-full bg-go-success">
                      <Icon name="check-white" />
                    </span>
                    <span className="flex-1 text-[15px] font-medium text-black">{t("Reached your store")}</span>
                    <span className="text-[13px] text-go-muted">{clock(arrived)}</span>
                  </li>
                )}
              </ol>
              {history.loading && !history.data && <SkeletonRows label={t("Loading…")} />}
            </Card>
          </div>

          <div className="flex flex-col gap-5">
            <Card label={t("Need to talk")}>
              <h2 className="text-[18px] font-medium text-black">{t("Need to talk?")}</h2>
              <Pending
                what={t("Calling the dispatcher or the driver")}
                waitingOn={t("Contact numbers are not on record yet. Use Message on the delivery in Deliveries instead.")}
              />
            </Card>

            <Card label={t("Orders on this vehicle")}>
              <h2 className="text-[18px] font-medium text-black">{t("Orders on this vehicle")}</h2>
              <ul className="flex flex-col gap-2">
                {stops.map((d) => {
                  const o = orderOf(d);
                  const short = shortOf(d.orderId);
                  return (
                    <li key={d.deliveryId}>
                      <button
                        type="button"
                        onClick={() => {
                          setFocus(d.orderId);
                          if (o) onOpen(o.orderId);
                        }}
                        className={cx("flex w-full flex-col gap-0.5 rounded-[16px] px-3.5 py-3 text-left", short ? "bg-go-warning-tint" : "bg-go-canvas")}
                      >
                        <span className="text-[15px] font-medium text-black">
                          {o?.orderRef ?? t("Order")} · {o ? t(temperatureLabel(o.temperature)) : ""}
                        </span>
                        {/* The order's own volume, which the warehouse returned: never summed from lines. */}
                        <span className="text-[13px] text-go-muted">{o ? `${units(o.itemCount)} · ${o.volumeM3} m³` : `${d.lines.length} lines`}</span>
                        <span className={cx("text-[13px] font-medium", short ? "text-go-warning-text" : "text-go-success")}>
                          {short ? t(ISSUE_TYPE[short.type]) : o && onTheWay(o.status) ? t("Loaded in full") : o ? t(ORDER_STATUS[o.status].label) : ""}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </Card>

            <Button large disabled={!receivable} onClick={() => receivable && onReceive(receivable.orderId)}>
              {t("Receive delivery")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
