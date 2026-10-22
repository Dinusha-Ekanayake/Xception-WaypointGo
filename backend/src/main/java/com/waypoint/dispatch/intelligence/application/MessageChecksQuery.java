package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.PlaybookViews.MessageCheckView;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.ReviewView;
import com.waypoint.dispatch.intelligence.domain.MessageTriage;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R-ML-37: the depot's messages waiting for a dispatcher to check, that day, for
 * the watcher's "Needs you": the same readings the trip's chat puts as "Check
 * this", so a message in doubt is not missed when its chat is closed. Nothing
 * while Jev is off. A depot outside the reader's scope is refused.
 */
@Component
public class MessageChecksQuery {
  private final JdbcMessageSituations read;
  private final AiProperties ai;
  private final Database database;

  public MessageChecksQuery(JdbcMessageSituations read, AiProperties ai, Database database) {
    this.read = read;
    this.ai = ai;
    this.database = database;
  }

  public List<MessageCheckView> forDepot(Actor actor, String depotCode, LocalDate day) {
    return database.readAs(ModuleRole.ML, actor.userId(), () -> {
      boolean inScope = Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok"));
      if (!inScope) {
        throw new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:depot:" + depotCode + " is outside the actor's scope");
      }
      if (!ai.jevLive()) {
        return List.<MessageCheckView>of();
      }
      Instant from = day.atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      Instant to = day.plusDays(1).atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      return read.unconfirmed(depotCode, from, to).stream()
          .filter(r -> !"dispatcher".equals(r.get("author_role")))
          .filter(r -> MessageTriage.reading((String) r.get("situation"), ((Number) r.get("confidence")).doubleValue(),
              ai.jevLiveMinConfidence(), Boolean.TRUE.equals(r.get("unclear")), Optional.empty()) == MessageTriage.Reading.REVIEW)
          .map(r -> new MessageCheckView((String) r.get("trip_id"), new ReviewView(
              ((UUID) r.get("message_id")).toString(),
              MessageTriage.signal((String) r.get("situation"), 1.0, 0.0).map(Situation::name),
              Boolean.TRUE.equals(r.get("unclear")), (String) r.get("source"), (String) r.get("author_role"),
              Optional.ofNullable((String) r.get("outlet_id")))))
          .toList();
    });
  }
}
