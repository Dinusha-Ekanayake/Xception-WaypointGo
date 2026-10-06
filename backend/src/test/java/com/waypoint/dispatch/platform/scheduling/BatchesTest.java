package com.waypoint.dispatch.platform.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** F-12: a retention run goes on until a batch comes back short, up to its ceiling. */
class BatchesTest {

  @Test
  void aBacklogIsClearedInOneRunAndTheLastShortBatchEndsIt() {
    AtomicInteger left = new AtomicInteger(12_345);
    Batches.Outcome outcome = Batches.untilDone(5_000, 200, () -> {
      int take = Math.min(5_000, left.get());
      left.addAndGet(-take);
      return take;
    });
    assertEquals(12_345, outcome.total());
    assertFalse(outcome.backlog());
    assertEquals(0, left.get());
  }

  @Test
  void aRunStopsAtItsCeilingAndSaysWorkIsLeft() {
    AtomicInteger calls = new AtomicInteger();
    Batches.Outcome outcome = Batches.untilDone(10, 3, () -> {
      calls.incrementAndGet();
      return 10;
    });
    assertEquals(3, calls.get());
    assertEquals(30, outcome.total());
    assertTrue(outcome.backlog());
  }

  @Test
  void nothingToDoIsOneEmptyBatch() {
    Batches.Outcome outcome = Batches.untilDone(10, 3, () -> 0);
    assertEquals(0, outcome.total());
    assertFalse(outcome.backlog());
  }
}
