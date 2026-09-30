"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useOnline, useResource } from "@shared/api/useResource";
import type { OrderView } from "@shared/domain/types";
import { Notice } from "@shared/ui";
import TopBar from "./TopBar.tsx";
import { createGateway } from "./data/gateway.ts";
import { useCommands } from "./data/useCommands.ts";
import Deliveries from "./screens/Deliveries.tsx";
import Home from "./screens/Home.tsx";
import OrderSheet from "./screens/OrderSheet.tsx";
import Orders from "./screens/Orders.tsx";
import PlaceOrder from "./screens/PlaceOrder.tsx";
import Receive from "./screens/Receive.tsx";
import { TabBar, type Tab } from "./ui.tsx";

// The store manager workspace from Figma "15 Store Manager · Mobile", phone
// first and usable at a counter desktop. Resilient offline tier
// (src/shared/offline/tiers.ts): orders and receipts are kept on the device
// while offline and sent when the connection returns.

type View = { kind: "tabs" } | { kind: "place"; amend: OrderView | null } | { kind: "receive"; orderId: string };

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

  const forOutlet = <T,>(fn: (id: string, s: AbortSignal) => Promise<T>) => (outletId ? (s: AbortSignal) => fn(outletId, s) : null);
  const outlet = useResource(forOutlet(gateway.outlet), outletId);
  const orders = useResource(forOutlet(gateway.orders), outletId, 20_000);
  const pending = useResource(forOutlet(gateway.pendingReceipts), outletId, 20_000);
  const warehouse = useResource((s) => gateway.catalogueStatus(s), "catalogue", 60_000);

  const { refresh: refreshOrders } = orders;
  const { refresh: refreshPending } = pending;
  const refresh = useCallback(() => {
    refreshOrders();
    refreshPending();
  }, [refreshOrders, refreshPending]);
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
        setWaiting(report.remaining);
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
        onBack={backToTabs}
      />
    );
  } else if (view.kind === "receive") {
    body = <Receive gateway={gateway} orderId={view.orderId} order={all.find((o) => o.orderId === view.orderId) ?? null} commands={commands} onBack={backToTabs} />;
  } else if (tab === "home") {
    body = (
      <Home
        orders={all}
        loading={orders.loading}
        error={orders.error}
        displayName={displayName}
        outlet={outlet.data}
        toReceive={toReceive}
        onOpen={setOpenOrder}
        onPlace={place}
        onReceive={receive}
        onTrack={() => setTab("deliveries")}
      />
    );
  } else if (tab === "orders") {
    body = <Orders orders={all} loading={orders.loading} error={orders.error} onOpen={setOpenOrder} onPlace={place} />;
  } else {
    body = <Deliveries orders={all} outlet={outlet.data} toReceive={toReceive} onOpen={setOpenOrder} onReceive={receive} />;
  }

  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-[720px] flex-col gap-5 bg-go-canvas px-4 pt-5 pb-36 font-go text-go-ink sm:px-[25px]">
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
      {view.kind === "tabs" && (
        <TabBar
          tab={tab}
          onTab={setTab}
          badges={{ deliveries: toReceive.length, orders: all.filter((o) => o.status === "DEFERRED" || o.status === "STOCK_UNKNOWN").length }}
        />
      )}
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
