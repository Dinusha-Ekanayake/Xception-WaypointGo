package com.waypoint.dispatch.intelligence.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What Intelligence tells the rest of the system about a trip's chat (R-ML-43). */
public final class ChatEvents {
  private ChatEvents() {}

  /**
   * Jev read a chat message: what it does, and whether the reading is sure enough
   * to steer the suggested replies. Messaging keeps it on the message, so only
   * those who may read the message read it. Carries no words.
   *
   * @param act a problem (BREAKDOWN, STORE_CLOSED...), an act (ASKS_CAN_WAIT, SAYS_NO...), OFF_TOPIC or OTHER
   * @param outletIds the stores the message reached, so their open chats hear it at once
   */
  public record MessageUnderstood(
      UUID threadId, UUID messageId, String depotCode, List<String> outletIds, String act, boolean sure, Instant at)
      implements DomainEvent {
    public static final String TYPE = "message.understood";

    public MessageUnderstood {
      outletIds = List.copyOf(outletIds);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "thread";
    }

    @Override
    public String aggregateId() {
      return threadId.toString();
    }
  }
}
