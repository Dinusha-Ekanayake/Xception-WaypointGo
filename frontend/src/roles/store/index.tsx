"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useOnline, useResource } from "@shared/api/useResource";
import type { OrderView } from "@shared/domain/types";
import { Notice } from "@shared/ui";
import TopBar from "./TopBar.tsx";
import { depotToday } from "./data/format.ts";
import { createGateway } from "./data/gateway.ts";
import { isOpenIssue, issuesForOrders, recentOrderIds } from "./data/issues.ts";
import { useCommands } from "./data/useCommands.ts";
import Deliveries from "./screens/Deliveries.tsx";
import Home from "./screens/Home.tsx";
import Issues from "./screens/Issues.tsx";
import OrderSheet from "./screens/OrderSheet.tsx";
import Orders from "./screens/Orders.tsx";
import PlaceOrder from "./screens/PlaceOrder.tsx";
import Receive from "./screens/receive/Receive.tsx";
import Track from "./screens/Track.tsx";
import { SideNav, TabBar, type Tab } from "./ui.tsx";

// The store manager workspace from Figma "15 Store Manager · Mobile" and
// "14 Store Manager · Desktop": a tab bar on phones, a sidebar from lg. Resilient offline tier
// (src/shared/offline/tiers.ts): orders and receipts are kept on the device
// while offline and sent when the connection returns.

type View = { kind: "tabs" } | { kind: "place"; amend: OrderView | null } | { kind: "receive"; orderId: string } | { kind: "track" };

export default function Store({
  userId,
  displayName,
  scope,
}: {
  userId: string;
  displayName: string;
  /** Outlet ids from the session. The server enforces them. */
  scope: string[];
}): React.JSX.Element {
  const gateway = useMemo(() => createGateway(userId), [userId]);
  const online = useOnline();
  const outletId = scope[0] ?? (gateway.sample ? "OUT085" : "");
  const [tab, setTab] = useState<Tab>("home");
  const [view, setView] = useState<View>({ kind: "tabs" });
  const [openOrder, setOpenOrder] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(0);
  const [flushError, setFlushError] = useState<string | null>(null);
  const [vehicle, setVehicle] = useState<string | null>(null);

  const forOutlet = <T,>(fn: (id: string, s: AbortSignal) => Promise<T>) => (outletId ? (s: AbortSignal) => fn(outletId, s) : null);
  const outlet = useResource(forOutlet(gateway.outlet), outletId);
  const orders = useResource(forOutlet(gateway.orders), outletId, 20_000);
  const pending = useResource(forOutlet(gateway.pendingReceipts), outletId, 20_000);
  const warehouse = useResource((s) => gateway.catalogueStatus(s), "catalogue", 60_000);
  const today = depotToday();
  const deliveries = useResource(outletId ? (s) => gateway.deliveries(outletId, today, s) : null, `${outletId}|${today}`, 20_000);
  const orderIds = recentOrderIds(orders.data ?? [], today);
  const issues = useResource(orderIds.length ? (s) => issuesForOrders(gateway, orderIds, s) : null, orderIds.join(","), 60_000);

  const { refresh: refreshOrders } = orders;
  const { refresh: refreshPending } = pending;
  const { refresh: refreshDeliveries } = deliveries;
  const { refresh: refreshIssues } = issues;
  const refresh = useCallback(() => {
    refreshOrders();
    refreshPending();
    refreshDeliveries();
    refreshIssues();
  }, [refreshOrders, refreshPending, refreshDeliveries, refreshIssues]);
  const queued = useCallback(() => setWaiting((n) => n + 1), []);
  const commands = useCommands(gateway, online, queued, refresh);

  // Send kept writes as soon as the connection returns, whichever screen is open.
  useEffect(() => {
    if (!online || waiting === 0) return;
    let cancelled = false;
    gateway
      .flush()
      .then((report) => {
        if (cancelled) return;
        if (report.heldForReview > 0) {
          setFlushError(`${report.heldForReview} saved ${report.heldForReview === 1 ? "change was" : "changes were"} refused when sent. Check your orders.`);
        }
        setWaiting(report.remaining - report.heldForReview);
        refresh();
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [online, waiting, gateway, refresh]);

  const all = orders.data ?? [];
  const toReceive = pending.data ?? [];
  const warehouseDown = warehouse.data?.circuitState === "open" || warehouse.data?.stale === true;
  const backToTabs = () => setView({ kind: "tabs" });
  const allIssues = issues.data ?? [];
  const stops = deliveries.data ?? [];
  const badges = {
    deliveries: toReceive.length,
    orders: all.filter((o) => o.status === "DEFERRED" || o.status === "STOCK_UNKNOWN").length,
    issues: allIssues.filter(isOpenIssue).length,
  };
  const open = all.find((o) => o.orderId === openOrder) ?? null;
  const receive = (orderId: string) => setView({ kind: "receive", orderId });
  const place = () => setView({ kind: "place", amend: null });

  let body: React.JSX.Element;
  if (!outletId) {
    body = <p className="text-[15px] text-go-muted">Your account has no outlet in scope. Ask an administrator to grant one.</p>;
  } else if (view.kind === "place") {
    body = (
      <PlaceOrder
        gateway={gateway}
        outlet={outlet.data}
        orders={all}
        amend={view.amend}
        warehouseDown={warehouseDown}
        commands={commands}
        onDone={() => {
          backToTabs();
          setTab("orders");
        }}
        onEdit={(orderId) => {
          backToTabs();
          setTab("orders");
          setOpenOrder(orderId);
        }}
        onBack={backToTabs}
      />
    );
  } else if (view.kind === "receive") {
    // This vehicle's orders still waiting to be counted, so the store can move between them (06-4).
    const stop = stops.find((d) => d.orderId === view.orderId);
    const waiting = new Set(toReceive.map((p) => p.orderId));
    const siblings = stop
      ? all.filter((o) => o.orderId === view.orderId || (waiting.has(o.orderId) && stops.some((d) => d.orderId === o.orderId && d.vehicleId === stop.vehicleId)))
      : all.filter((o) => o.orderId === view.orderId);
    body = (
      <Receive
        key={view.orderId}
        gateway={gateway}
        orderId={view.orderId}
        order={all.find((o) => o.orderId === view.orderId) ?? null}
        delivery={stop ?? null}
        outlet={outlet.data}
        siblings={siblings}
        onSwitch={receive}
        commands={commands}
        onBack={() => {
          backToTabs();
          setTab("deliveries");
        }}
        onViewIssues={() => {
          refresh();
          backToTabs();
          setTab("issues");
        }}
      />
    );
  } else if (view.kind === "track") {
    body = (
      <Track
        gateway={gateway}
        deliveries={stops}
        orders={all}
        issues={allIssues}
        outlet={outlet.data}
        toReceive={toReceive}
        vehicleId={vehicle}
        onVehicle={setVehicle}
        onReceive={receive}
        onOpen={setOpenOrder}
        onBack={backToTabs}
      />
    );
  } else if (tab === "home") {
    body = (
      <Home
        orders={all}
        loading={orders.loading}
        error={orders.error}
        displayName={displayName}
        outlet={outlet.data}
        toReceive={toReceive}
        deliveries={stops}
        issues={allIssues}
        onOpen={setOpenOrder}
        onPlace={place}
        onReceive={receive}
        onTrack={() => setView({ kind: "track" })}
      />
    );
  } else if (tab === "orders") {
    body = <Orders orders={all} loading={orders.loading} error={orders.error} onOpen={setOpenOrder} onPlace={place} />;
  } else if (tab === "issues") {
    body = <Issues issues={allIssues} orders={all} outlet={outlet.data} loading={issues.loading} error={issues.error} focus={null} onOpenOrder={setOpenOrder} />;
  } else {
    body = <Deliveries orders={all} outlet={outlet.data} toReceive={toReceive} onOpen={setOpenOrder} onReceive={receive} onTrack={() => setView({ kind: "track" })} />;
  }

  return (
    <div className="relative mx-auto flex min-h-dvh w-full max-w-[720px] flex-col gap-5 bg-go-canvas px-4 pt-5 pb-36 font-go text-go-ink sm:px-[25px] lg:max-w-none lg:pt-8 lg:pr-10 lg:pb-10 lg:pl-[300px]">
      <TopBar online={online} syncedAt={orders.loadedAt} waiting={waiting} sample={gateway.sample} />
      {warehouseDown && (
        <Notice tone="warning" live title="The warehouse is not answering">
          You can still place orders. They are kept as &ldquo;stock not checked&rdquo; and confirmed once the warehouse is back, never before.
        </Notice>
      )}
      {flushError && (
        <Notice
          tone="danger"
          live
          title={flushError}
          action={
            <button type="button" onClick={() => setFlushError(null)} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              Dismiss
            </button>
          }
        />
      )}
      {body}
      {gateway.sample && view.kind === "tabs" && (
        <div className="flex flex-wrap gap-x-4 text-[13px] text-go-warning-text">
          <button
            type="button"
            className="min-h-12 underline"
            onClick={() => {
              gateway.setWarehouseDown!(!warehouseDown);
              warehouse.refresh();
            }}
          >
            Sample data: {warehouseDown ? "bring the warehouse back" : "take the warehouse down"}
          </button>
          {all.some((o) => o.status === "IN_TRANSIT") && (
            <button
              type="button"
              className="min-h-12 underline"
              onClick={() => {
                gateway.deliver!(all.find((o) => o.status === "IN_TRANSIT")!.orderId);
                refresh();
              }}
            >
              Sample data: the truck arrives
            </button>
          )}
        </div>
      )}
      {view.kind === "tabs" && <TabBar tab={tab} onTab={setTab} badges={badges} />}
      <SideNav
        tab={view.kind === "place" ? "orders" : view.kind === "receive" || view.kind === "track" ? "deliveries" : tab}
        onTab={(t) => {
          setView({ kind: "tabs" });
          setTab(t);
        }}
        badges={badges}
        outlet={outlet.data}
        displayName={displayName}
      />
      {open && (
        <OrderSheet
          gateway={gateway}
          order={open}
          commands={commands}
          onAmend={() => {
            setOpenOrder(null);
            setView({ kind: "place", amend: open });
          }}
          onReceive={() => {
            setOpenOrder(null);
            receive(open.orderId);
          }}
          onClose={() => setOpenOrder(null)}
        />
      )}
    </div>
  );
}
