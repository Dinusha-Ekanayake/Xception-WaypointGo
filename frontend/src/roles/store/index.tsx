"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useOnline, useResource } from "@shared/api/useResource";
import type { OrderView } from "@shared/domain/types";
import { Notice, useScrollMemory } from "@shared/ui";
import TopBar from "./TopBar.tsx";
import { depotToday } from "./data/format.ts";
import { createGateway } from "./data/gateway.ts";
import { isOpenIssue, issuesForOrders, recentOrderIds } from "./data/issues.ts";
import { useCommands } from "./data/useCommands.ts";
import { deferralRead, markDeferralRead } from "./data/deferral.ts";
import DeferredOrder from "./screens/DeferredOrder.tsx";
import Deliveries from "./screens/deliveries/Deliveries.tsx";
import Home from "./screens/Home.tsx";
import Issues from "./screens/issues/Issues.tsx";
import OrderSheet from "./screens/OrderSheet.tsx";
import Orders from "./screens/Orders.tsx";
import PlaceOrder from "./screens/order/PlaceOrder.tsx";
import Receive from "./screens/receive/Receive.tsx";
import Track from "./screens/Track.tsx";
import ProfileDialog from "./screens/account/ProfileDialog.tsx";
import StoreDetailsDialog from "./screens/account/StoreDetailsDialog.tsx";
import { SideNav, TabBar, Toast, type Tab } from "./ui.tsx";
import { NotificationsCard, NotificationsDrawer } from "./screens/Notifications.tsx";
import TripMessages, { type OpenThread } from "./screens/TripMessages.tsx";
import { useInbox } from "@shared/notifications/useInbox";
import type { NotificationView } from "@shared/domain/types";

// The store manager workspace from Figma "15 Store Manager · Mobile" and
// "14 Store Manager · Desktop": a tab bar on phones, a sidebar from lg. Resilient offline tier
// (src/shared/offline/tiers.ts): orders and receipts are kept on the device
// while offline and sent when the connection returns.

type View =
  | { kind: "tabs" }
  | { kind: "place"; amend: OrderView | null }
  | { kind: "receive"; orderId: string }
  | { kind: "track" }
  | { kind: "deferred"; orderId: string };

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
  // A tab comes back where it was scrolled; a form or detail opens at the top (UX polish 2).
  useScrollMemory(view.kind === "tabs" ? `store:${tab}` : null);
  const [openOrder, setOpenOrder] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(0);
  const [flushError, setFlushError] = useState<string | null>(null);
  const [vehicle, setVehicle] = useState<string | null>(null);
  // The account menu's two editors, and the name as just saved (the session's is read at sign-in).
  const [editing, setEditing] = useState<"profile" | "store" | null>(null);
  const [savedName, setSavedName] = useState<string | null>(null);
  const [note, setNote] = useState<{ title: string; detail?: string } | null>(null);
  // Notifications for this store (issue #118); none in the sample.
  const inbox = useInbox(userId, !gateway.sample);
  const [inboxOpen, setInboxOpen] = useState(false);
  const [thread, setThread] = useState<OpenThread | null>(null);
  const [syncing, setSyncing] = useState(false);
  useEffect(() => {
    if (!note) return;
    const timer = setTimeout(() => setNote(null), 4000);
    return () => clearTimeout(timer);
  }, [note]);
  const name = savedName ?? displayName;

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

  // "Synced 02:23 AM" syncs now: send what waits, then read everything again.
  const syncNow = () => {
    refresh();
    if (!online || waiting === 0) return;
    setSyncing(true);
    gateway
      .flush()
      .then((report) => {
        setWaiting(report.remaining - report.heldForReview);
        refresh();
      })
      .catch(() => undefined)
      .finally(() => setSyncing(false));
  };
  // A notification opens what it is about: the order, the deliveries or the issues.
  const openSubject = (n: NotificationView) => {
    if (n.subjectType === "thread" && n.subjectId) {
      setInboxOpen(false);
      setThread({ threadId: n.subjectId });
      return;
    }
    setView({ kind: "tabs" });
    if (n.subjectType === "order" && n.subjectId) {
      setTab("orders");
      setOpenOrder(n.subjectId);
    } else if (n.subjectType === "issue") {
      setTab("issues");
    } else if (n.subjectType === "delivery" || n.subjectType === "trip" || n.subjectType === "receipt") {
      setTab("deliveries");
    }
  };

  const all = orders.data ?? [];
  const toReceive = pending.data ?? [];
  const warehouseDown = warehouse.data?.circuitState === "open" || warehouse.data?.stale === true;
  const backToTabs = () => setView({ kind: "tabs" });
  const allIssues = issues.data ?? [];
  const stops = deliveries.data ?? [];
  const badges = {
    deliveries: toReceive.length,
    orders: all.filter((o) => (o.status === "DEFERRED" && !deferralRead(outletId, o)) || o.status === "STOCK_UNKNOWN").length,
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
  } else if (view.kind === "deferred" && all.some((o) => o.orderId === view.orderId)) {
    const order = all.find((o) => o.orderId === view.orderId)!;
    body = (
      <DeferredOrder
        gateway={gateway}
        order={order}
        outlet={outlet.data}
        onGotIt={() => {
          markDeferralRead(outletId, order);
          backToTabs();
        }}
        onBack={backToTabs}
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
        displayName={name}
        outlet={outlet.data}
        toReceive={toReceive}
        deliveries={stops}
        issues={allIssues}
        onOpen={setOpenOrder}
        onPlace={place}
        onReceive={receive}
        onTrack={() => setView({ kind: "track" })}
        notifications={<NotificationsCard inbox={inbox} onSubject={openSubject} onAll={() => setInboxOpen(true)} />}
      />
    );
  } else if (tab === "orders") {
    body = <Orders orders={all} loading={orders.loading} error={orders.error} onOpen={setOpenOrder} onPlace={place} />;
  } else if (tab === "issues") {
    body = (
      <Issues
        gateway={gateway}
        issues={allIssues}
        orders={all}
        outlet={outlet.data}
        loading={issues.loading}
        error={issues.error}
        commands={commands}
        onOpenOrder={setOpenOrder}
        onSent={refresh}
      />
    );
  } else {
    body = (
      <Deliveries
        gateway={gateway}
        orders={all}
        deliveries={stops}
        issues={allIssues}
        outlet={outlet.data}
        toReceive={toReceive}
        onOpen={setOpenOrder}
        onOrders={() => setTab("orders")}
        onReceive={receive}
        onTrack={(vehicleId) => {
          setVehicle(vehicleId);
          setView({ kind: "track" });
        }}
        onMessage={(tripId, vehicleId) => setThread({ tripId, vehicleId })}
      />
    );
  }

  return (
    <div className="relative mx-auto flex min-h-dvh w-full max-w-[720px] flex-col gap-5 bg-go-canvas px-4 pt-5 pb-36 font-go text-go-ink sm:px-[25px] lg:max-w-none lg:pt-8 lg:pr-10 lg:pb-10 lg:pl-[300px]">
      <TopBar
        online={online}
        syncedAt={orders.loadedAt}
        waiting={waiting}
        sample={gateway.sample}
        displayName={name}
        outlet={outlet.data}
        onEditProfile={() => setEditing("profile")}
        onEditStore={() => setEditing("store")}
        onSync={syncNow}
        syncing={syncing || orders.loading}
        unread={inbox.unread}
        onNotifications={gateway.sample ? undefined : () => setInboxOpen(true)}
      />
      {thread && <TripMessages accountId={userId} open={thread} online={online} onQueued={queued} onClose={() => setThread(null)} />}
      {inboxOpen && <NotificationsDrawer inbox={inbox} onSubject={openSubject} onClose={() => setInboxOpen(false)} />}
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
        tab={view.kind === "place" || view.kind === "deferred" ? "orders" : view.kind === "receive" || view.kind === "track" ? "deliveries" : tab}
        onTab={(t) => {
          setView({ kind: "tabs" });
          setTab(t);
        }}
        badges={badges}
        outlet={outlet.data}
        displayName={name}
        onEditProfile={() => setEditing("profile")}
        onEditStore={() => setEditing("store")}
      />
      {editing === "profile" && (
        <ProfileDialog
          gateway={gateway}
          commands={commands}
          onSaved={(saved, queued) => {
            setEditing(null);
            setSavedName(saved);
            setNote(queued ? { title: "Profile saved on this phone", detail: "It is sent when the connection returns" } : { title: "Profile saved" });
          }}
          onClose={() => setEditing(null)}
        />
      )}
      {editing === "store" && outlet.data && (
        <StoreDetailsDialog
          gateway={gateway}
          outlet={outlet.data}
          commands={commands}
          onSaved={(queued) => {
            setEditing(null);
            outlet.refresh();
            setNote(
              queued
                ? { title: "Store details saved on this phone", detail: "They are sent when the connection returns" }
                : { title: "Store details saved", detail: "A new window or dock is used from the next plan" },
            );
          }}
          onClose={() => setEditing(null)}
        />
      )}
      <Toast note={note} />
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
          onDeferred={() => {
            setOpenOrder(null);
            setView({ kind: "deferred", orderId: open.orderId });
          }}
          onClose={() => setOpenOrder(null)}
        />
      )}
    </div>
  );
}
