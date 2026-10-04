import { isOutage } from "@shared/offline";
import type { Sender } from "@shared/messaging/useThread";
import type { NotificationView } from "@shared/domain/types";
import type { DriverGateway } from "./gateway.ts";

// The driver's messages on the trip's thread (issue #136). A typed message or
// report is a write like any other on this phone: sent now when there is
// signal, else kept and sent when it returns, under the same command and
// message id so a resend is one message (R-MSG-02, MSG-06). A refusal from the
// server is said, never queued.

export function messageSender(gateway: DriverGateway, online: boolean, onQueued: () => void): Sender {
  const keep: Sender = async (command) => {
    const saved = await gateway.queue(command);
    if (!saved.durable) throw new Error(`Not saved on this phone: ${saved.reason ?? "storage unavailable"}`);
    onQueued();
    return { queued: true };
  };
  return async (command) => {
    if (!online) return keep(command);
    try {
      await gateway.send(command);
      return { queued: false };
    } catch (failure) {
      if (!isOutage(failure)) throw failure;
      return keep(command);
    }
  };
}

/** Unread notifications of messages for this driver: the count on the Messages button. */
export function unreadMessages(items: NotificationView[]): NotificationView[] {
  return items.filter((n) => n.eventType === "message.posted" && n.readAt === null);
}
