package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.observability.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** F-07b: committed events become topic signals for the depots that watch them. */
class ChangeSignalsTest {
  private final ChangeSignals signals = new ChangeSignals(new Metrics(new SimpleMeterRegistry()));

  @AfterEach
  void stop() {
    signals.destroy();
  }

  @Test
  void anEventTypeIsTopicByItsFirstWord() {
    assertEquals("plan", ChangeSignals.topicOf("plan.published"));
    assertEquals("order", ChangeSignals.topicOf("orders.closed"));
    assertEquals("loading", ChangeSignals.topicOf("loading.shortfall"));
    assertEquals("trip", ChangeSignals.topicOf("trip.released"));
  }

  @Test
  void aWatcherHearsItsDepotsAndChangesThatNameNone() {
    assertTrue(ChangeSignals.hears(Set.of("KDY"), "KDY"));
    assertFalse(ChangeSignals.hears(Set.of("KDY"), "PLG"));
    assertTrue(ChangeSignals.hears(Set.of("KDY"), null));
  }

  @Test
  void changesTogetherAreSentOnceToTheDepotsThatWatchThem() {
    List<String> kandy = new ArrayList<>();
    List<String> other = new ArrayList<>();
    signals.listen(Set.of("KDY"), listener(kandy));
    signals.listen(Set.of("PLG"), listener(other));

    signals.committed("plan.published", "KDY");
    signals.committed("plan.revised", "KDY");
    signals.committed("issue.raised", "KDY");
    signals.flushNow();

    assertEquals(List.of("issue@KDY", "plan@KDY"), kandy.stream().sorted().toList(), "two plan events, one signal");
    assertTrue(other.isEmpty(), "another depot hears nothing of Kandy");
  }

  @Test
  void aStoreHearsTheTopicOfAChangeThatNamesItsOutletAndNothingElse() {
    List<String> store = new ArrayList<>();
    List<String> otherStore = new ArrayList<>();
    List<String> dispatcher = new ArrayList<>();
    signals.listen(Set.of(), Set.of("OUT0101"), listener(store));
    signals.listen(Set.of(), Set.of("OUT0202"), listener(otherStore));
    signals.listen(Set.of("KDY"), listener(dispatcher));

    signals.committed("delivery.arrived", "KDY", Set.of("OUT0101"));
    signals.committed("delivery.arrived", "KDY", Set.of("OUT0303"));
    signals.committed("plan.revised", "KDY");
    signals.committed("trip.released", null, Set.of("OUT0101", "OUT0303"));
    signals.flushNow();

    assertEquals(List.of("delivery@null", "trip@null"), store.stream().sorted().toList(), "the topic, never the depot");
    assertTrue(otherStore.isEmpty(), "a store hears nothing of another's outlets or of its depot: " + otherStore);
    assertEquals(
        List.of("delivery@KDY", "plan@KDY", "trip@null"),
        dispatcher.stream().sorted().toList(),
        "two arrivals at two outlets, one signal; a change naming no depot still reaches the depot");
  }

  @Test
  void aListenerThatFailsIsDropped() {
    signals.listen(Set.of("KDY"), new ChangeSignals.Listener() {
      @Override
      public void changed(String topic, String depot) throws Exception {
        throw new java.io.IOException("connection closed");
      }

      @Override
      public void heartbeat() {}
    });
    signals.committed("plan.published", "KDY");
    signals.flushNow();
    assertEquals(0, signals.watching());
  }

  private static ChangeSignals.Listener listener(List<String> heard) {
    return new ChangeSignals.Listener() {
      @Override
      public void changed(String topic, String depot) {
        heard.add(topic + "@" + depot);
      }

      @Override
      public void heartbeat() {}
    };
  }
}
