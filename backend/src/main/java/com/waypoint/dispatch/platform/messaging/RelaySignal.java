package com.waypoint.dispatch.platform.messaging;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Tells the relay that this process has just committed an event.
 *
 * <p>An optimisation only. The relay polls regardless, so a signal that is lost,
 * or an event written by another replica, costs one poll interval and nothing else.
 */
@Component
public class RelaySignal {
  private final Semaphore pending = new Semaphore(0);

  public void signal() {
    // One permit is enough: the relay drains the outbox, not a count of signals.
    if (pending.availablePermits() == 0) {
      pending.release();
    }
  }

  /** Waits for a signal or the timeout, whichever is first. */
  public void await(Duration timeout) throws InterruptedException {
    if (pending.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      pending.drainPermits();
    }
  }
}
