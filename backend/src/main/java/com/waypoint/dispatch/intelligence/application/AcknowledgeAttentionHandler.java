package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.AttentionViews;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionKind;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository.ItemState;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The dispatcher saying they have seen something the watch raised (issue
 * #268). It is a decision, so it carries who, when and why (rule 8), and it
 * changes an existing row, so it takes its {@code expectedVersion} (rule 6).
 * An acknowledged item leaves the list and is no longer reminded of; it is
 * never deleted.
 *
 * <p>Payload: {@code depotCode}, {@code deliveryId}, {@code kind}, {@code reason}.
 * A depot outside the actor's scope is refused before anything is read.
 */
@Component
class AcknowledgeAttentionHandler implements CommandHandler {
  static final int MIN_REASON = 3;

  private final Database database;
  private final JdbcAttentionRepository repository;
  private final Metrics metrics;
  private final Clock clock;

  AcknowledgeAttentionHandler(Database database, JdbcAttentionRepository repository, Metrics metrics, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return AttentionViews.ACKNOWLEDGE;
  }

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public String resource(Command command) {
    return "wpt:ml:attention:" + CommandPayload.of(command).text("depotCode");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    String depot = p.requiredText("depotCode");
    UUID deliveryId = p.uuid("deliveryId");
    AttentionKind kind = kind(p.requiredText("kind"));
    String reason = p.requiredText("reason").trim();
    if (reason.length() < MIN_REASON) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "reason needs at least " + MIN_REASON + " characters");
    }
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
    }
    boolean inScope = Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok"));
    if (!inScope) {
      throw new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:attention:" + depot + " is outside the actor's scope");
    }
    ItemState item = repository.state(depot, deliveryId, kind).orElseThrow(
        () -> new DomainException(ErrorCode.NOT_FOUND, "Nothing of that kind is raised on that stop"));
    if (item.acknowledged()) {
      throw new DomainException(ErrorCode.CONFLICT, "It was already acknowledged");
    }
    int changed = repository.acknowledge(deliveryId, kind, actor.userId(), reason, clock.now(), command.expectedVersion());
    if (changed == 0) {
      throw new DomainException(ErrorCode.VERSION_CONFLICT,
          "It changed since it was read (version " + item.rowVersion() + ")");
    }
    metrics.increment("waypoint.ml.attention_acknowledged", "kind", kind.name().toLowerCase(java.util.Locale.ROOT));
    return Map.of(
        "deliveryId", deliveryId.toString(), "kind", kind.name(), "status", "ACKNOWLEDGED",
        "rowVersion", command.expectedVersion() + 1);
  }

  private static AttentionKind kind(String text) {
    return Arrays.stream(AttentionKind.values()).filter(k -> k.name().equals(text)).findFirst().orElseThrow(
        () -> new DomainException(ErrorCode.VALIDATION_FAILED, "kind is one of " + Arrays.toString(AttentionKind.values())));
  }
}
