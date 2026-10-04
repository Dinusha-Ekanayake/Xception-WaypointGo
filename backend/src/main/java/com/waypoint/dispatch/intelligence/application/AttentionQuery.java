package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionBoardView;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionItemView;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What needs the dispatcher on a depot's live trips, most urgent first, and
 * when the watch last looked (issue #268). A depot outside the reader's scope
 * is refused and audited, never answered with an empty list.
 */
@Component
public class AttentionQuery {
  /** The watch runs every minute; three missed runs means the list may be out of date. */
  static final Duration STALE_AFTER = Duration.ofMinutes(3);

  private final Database database;
  private final JdbcAttentionRepository repository;
  private final AuditLog audit;
  private final Clock clock;

  public AttentionQuery(Database database, JdbcAttentionRepository repository, AuditLog audit, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.audit = audit;
    this.clock = clock;
  }

  public AttentionBoardView board(Actor actor, String depotCode, LocalDate serviceDate) {
    boolean inScope = database.readAs(ModuleRole.ML, actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok")));
    if (!inScope) {
      // Audited outside the read: a read-only transaction cannot write the entry.
      String resource = "wpt:ml:attention:" + depotCode;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), IntelligenceDataQuery.READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
    return database.readAs(ModuleRole.ML, actor.userId(), () -> {
      List<AttentionItemView> items = repository.open(depotCode, serviceDate);
      Optional<Instant> checkedAt = repository.checkedAt(depotCode);
      return new AttentionBoardView(depotCode, serviceDate, items, checkedAt, stale(checkedAt, clock.now()));
    });
  }

  /** True when the watch has never looked, or not for longer than it should. */
  static boolean stale(Optional<Instant> checkedAt, Instant now) {
    return checkedAt.map(at -> Duration.between(at, now).compareTo(STALE_AFTER) > 0).orElse(true);
  }
}
