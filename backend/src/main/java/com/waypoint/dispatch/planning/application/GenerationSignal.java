package com.waypoint.dispatch.planning.application;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Wakes the generation worker when a job is queued in this process, so a
 * Generate starts at once instead of at the next poll. Jobs queued by another
 * replica are found by the poll.
 */
@Component
public class GenerationSignal {
  private final Semaphore permits = new Semaphore(0);

  public void wake() {
    if (permits.availablePermits() == 0) {
      permits.release();
    }
  }

  /** Waits for a wake or the timeout, whichever comes first. */
  boolean await(long millis) throws InterruptedException {
    return permits.tryAcquire(millis, TimeUnit.MILLISECONDS);
  }
}
