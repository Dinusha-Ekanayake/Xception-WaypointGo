package com.waypoint.dispatch.platform.scheduling;

import java.util.function.IntSupplier;

/**
 * Runs a bounded batch again until a batch comes back short (roadmap F-12).
 *
 * <p>A retention job used to remove one batch a night, so a table that gained
 * more than that in a day never caught up. Each batch is its own transaction,
 * so no lock is held long; the run stops at {@code maxBatches} so one night's
 * work is bounded too, and the next run carries on.
 */
public final class Batches {
  private Batches() {}

  /** What one run did: rows changed, and whether it stopped at the ceiling with work left. */
  public record Outcome(int total, boolean backlog) {}

  public static Outcome untilDone(int batchSize, int maxBatches, IntSupplier oneBatch) {
    int total = 0;
    for (int i = 0; i < maxBatches; i++) {
      int done = oneBatch.getAsInt();
      total += done;
      if (done < batchSize) {
        return new Outcome(total, false);
      }
    }
    return new Outcome(total, true);
  }
}
