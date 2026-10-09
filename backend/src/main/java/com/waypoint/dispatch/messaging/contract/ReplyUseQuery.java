package com.waypoint.dispatch.messaging.contract;

import java.time.Instant;
import java.util.List;

/**
 * What Intelligence may ask Messaging about the ready replies (roadmap AI-J5):
 * each post that came from one, with the replies that were on screen beside it.
 * Read as the asking person, so row-level security keeps it to the threads they
 * can read. Posts from before the screen sent the shown list are left out.
 */
public interface ReplyUseQuery {

  List<ReplyChoice> choices(Instant from, Instant to);

  /**
   * @param shown the replies on screen, in order; the picked one is at {@code rank}
   * @param explored the second and third were swapped on purpose
   */
  record ReplyChoice(Instant usedAt, String templateId, int rank, boolean edited, List<String> shown, boolean explored) {
    public ReplyChoice {
      shown = List.copyOf(shown);
    }
  }
}
