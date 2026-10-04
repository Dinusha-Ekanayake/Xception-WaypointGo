package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.db.Database;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Records that a consumer has applied an event, in the consumer's own transaction.
 *
 * <p>The relay calls {@link #claim} before {@link EventSubscriber#on}. A false
 * answer means this consumer already applied this event, and the delivery is a
 * no-op. Because the claim commits or rolls back with the consumer's change, a
 * consumer that fails half way is redelivered rather than marked done.
 */
@Component
public class ConsumerInbox {
  private final Database database;

  public ConsumerInbox(Database database) {
    this.database = database;
  }

  /** @return true the first time, false for every redelivery */
  public boolean claim(String consumer, UUID eventId) {
    return database.update(
            "INSERT INTO integration.consumed_events (consumer, event_id) VALUES (?, ?)"
                + " ON CONFLICT DO NOTHING",
            consumer,
            eventId)
        == 1;
  }
}
