package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptAutoClosed;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Silence becomes a record: a receipt the store has not answered by its
 * deadline is closed as {@code AUTO_CLOSED} by the system actor and announced
 * as {@code receipt.auto_closed}, so Ordering marks the order UNCONFIRMED and
 * never silently received (R-RCP-05, RCP-02).
 *
 * <p>Each receipt is its own transaction, so one failure does not hold back the
 * rest; a failure is counted and retried on the next run. Running twice changes
 * nothing, because a closed receipt is no longer due. A store may still report a
 * shortage afterwards (RCP-08).
 */
@Component
public class ReceiptAutoCloseJob implements ScheduledJob {
  private final Database database;
  private final JdbcReceiptRepository receipts;
  private final EventPublisher events;
  private final Metrics metrics;
  /** Held here so the gauges read live values, not boxed numbers that go NaN after GC. */
  private final AtomicInteger closedLastRun = new AtomicInteger();
  private final AtomicLong awaitingAnswer = new AtomicLong();

  public ReceiptAutoCloseJob(
      Database database, JdbcReceiptRepository receipts, EventPublisher events, Metrics metrics) {
    this.database = database;
    this.receipts = receipts;
    this.events = events;
    this.metrics = metrics;
    metrics.gauge("waypoint.receipt.auto_closed_last_run", closedLastRun::get);
    metrics.gauge("waypoint.receipt.unconfirmed", awaitingAnswer::get);
  }

  @Override
  public String name() {
    return "receipt.auto-close";
  }

  /** Every fifteen minutes: a receipt closes within a quarter hour of its deadline. */
  @Override
  public String cron() {
    return "0 */15 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.RECEIPT;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many receipts were closed */
  int runAt(Instant now) {
    List<UUID> due = database.asSystem(ModuleRole.RECEIPT, () -> receipts.dueBy(now));
    int closed = 0;
    for (UUID receiptId : due) {
      try {
        if (database.asSystem(ModuleRole.RECEIPT, () -> close(receiptId, now))) {
          closed++;
        }
      } catch (DomainException | DataAccessException e) {
        metrics.increment("waypoint.receipt.auto_close_failed");
      }
    }
    closedLastRun.set(closed);
    awaitingAnswer.set(database.asSystem(ModuleRole.RECEIPT, receipts::pendingCount));
    return closed;
  }

  private boolean close(UUID receiptId, Instant now) {
    Optional<Receipt> found = receipts.find(receiptId).map(JdbcReceiptRepository.Stored::receipt);
    if (found.isEmpty() || !found.get().isDue(now)) {
      return false;
    }
    Receipt current = found.get();
    Receipt closed = current.autoClose(now);
    receipts.update(closed, current.rowVersion(), now);
    receipts.recordStatus(
        receiptId, Optional.of(ReceiptStatus.PENDING), ReceiptStatus.AUTO_CLOSED,
        "no answer from the store within the window", Actor.SYSTEM_ID, Optional.empty(), now);
    events.publish(Actor.SYSTEM, new ReceiptAutoClosed(receiptId, current.orderId(), current.outletId(), now));
    metrics.increment("waypoint.receipt.auto_closed");
    return true;
  }
}
