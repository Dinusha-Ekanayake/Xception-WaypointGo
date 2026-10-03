package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.intelligence.contract.ModelViews;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The model registry, written by the administrator (decision 10: commands, no
 * screen yet). Policy decides who; the auditor is denied all three by its
 * policy (20260930T1201).
 *
 * <p>Registering only records a model. Activating makes it the one of its kind
 * that answers, returning the previous one to registered, so there is never
 * more than one (R-ML-03). Retiring is final and needs a reason (rule 8).
 * Activate and Retire change an existing row, so they take its
 * {@code expectedVersion} (rule 6).
 */
final class ModelHandlers {
  private ModelHandlers() {}

  private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");

  abstract static class ModelHandler implements CommandHandler {
    protected final JdbcIntelligenceRepository repository;
    protected final Metrics metrics;
    protected final Clock clock;

    ModelHandler(JdbcIntelligenceRepository repository, Metrics metrics, Clock clock) {
      this.repository = repository;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public final String action() {
      return kind();
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public String resource(Command command) {
      CommandPayload p = CommandPayload.of(command);
      return "wpt:ml:model:" + p.text("name") + "@" + p.text("version");
    }

    protected ModelRow existing(Command command) {
      CommandPayload p = CommandPayload.of(command);
      String name = p.requiredText("name");
      String version = p.requiredText("version");
      return repository.model(name, version).orElseThrow(
          () -> new DomainException(ErrorCode.NOT_FOUND, "No model " + name + "@" + version));
    }

    protected static long expected(Command command, ModelRow row) {
      if (command.expectedVersion() == null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
      }
      if (row.view().rowVersion() != command.expectedVersion()) {
        throw new DomainException(ErrorCode.VERSION_CONFLICT,
            "The model changed since it was read (version " + row.view().rowVersion() + ")");
      }
      return command.expectedVersion();
    }

    protected static Map<String, Object> result(ModelRow row, String status, long version) {
      return Map.of("name", row.view().name(), "version", row.view().version(), "kind", row.view().kind(),
          "status", status, "rowVersion", version);
    }
  }

  @Component
  static class Register extends ModelHandler {
    private final SecureRandom random = new SecureRandom();

    Register(JdbcIntelligenceRepository repository, Metrics metrics, Clock clock) {
      super(repository, metrics, clock);
    }

    @Override
    public String kind() {
      return ModelViews.REGISTER;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload p = CommandPayload.of(command);
      String name = p.requiredText("name");
      String version = p.requiredText("version");
      String kind = p.requiredText("kind");
      if (!NAME.matcher(name).matches() || !NAME.matcher(version).matches()) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED,
            "name and version are lower-case letters, digits, '.', '_' or '-', up to 80 characters");
      }
      if (!ModelViews.KINDS.contains(kind)) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "kind is one of " + ModelViews.KINDS);
      }
      LocalDate from = p.optionalDate("trainedFrom");
      LocalDate to = p.optionalDate("trainedTo");
      if (from != null && to != null && from.isAfter(to)) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "trainedFrom is after trainedTo");
      }
      if (repository.model(name, version).isPresent()) {
        throw new DomainException(ErrorCode.CONFLICT, "Model " + name + "@" + version + " is already registered");
      }
      Instant now = clock.now();
      repository.insertModel(UuidV7.generate(now, random), name, version, kind, metrics(command), from, to,
          actor.userId(), now);
      metrics.increment("waypoint.ml.model_registered", "kind", kind);
      return Map.of("name", name, "version", version, "kind", kind, "status", "REGISTERED", "rowVersion", 1L);
    }

    private static Map<String, BigDecimal> metrics(Command command) {
      JsonNode node = command.payload() == null ? null : command.payload().get("metrics");
      Map<String, BigDecimal> out = new LinkedHashMap<>();
      if (node == null || node.isNull()) {
        return out;
      }
      if (!node.isObject()) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "metrics is an object of numbers");
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!e.getValue().isNumber()) {
          throw new DomainException(ErrorCode.VALIDATION_FAILED, "metrics." + e.getKey() + " is not a number");
        }
        out.put(e.getKey(), e.getValue().decimalValue());
      }
      return out;
    }
  }

  @Component
  static class Activate extends ModelHandler {
    Activate(JdbcIntelligenceRepository repository, Metrics metrics, Clock clock) {
      super(repository, metrics, clock);
    }

    @Override
    public String kind() {
      return ModelViews.ACTIVATE;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      ModelRow row = existing(command);
      long expected = expected(command, row);
      if (row.view().status() == ModelStatus.RETIRED) {
        throw new DomainException(ErrorCode.CONFLICT, "A retired model cannot be activated",
            List.of("R-ML-03"));
      }
      if (row.view().status() == ModelStatus.ACTIVE) {
        return result(row, "ACTIVE", expected);
      }
      Instant now = clock.now();
      repository.demoteActive(row.view().kind(), now);
      repository.activate(row.id(), expected, actor.userId(), now);
      metrics.increment("waypoint.ml.model_activated", "kind", row.view().kind());
      return result(row, "ACTIVE", expected + 1);
    }
  }

  @Component
  static class Retire extends ModelHandler {
    Retire(JdbcIntelligenceRepository repository, Metrics metrics, Clock clock) {
      super(repository, metrics, clock);
    }

    @Override
    public String kind() {
      return ModelViews.RETIRE;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      ModelRow row = existing(command);
      long expected = expected(command, row);
      String reason = CommandPayload.of(command).requiredText("reason");
      if (row.view().status() == ModelStatus.RETIRED) {
        throw new DomainException(ErrorCode.CONFLICT, "The model is already retired");
      }
      repository.retire(row.id(), expected, actor.userId(), reason, clock.now());
      metrics.increment("waypoint.ml.model_retired", "kind", row.view().kind());
      return result(row, "RETIRED", expected + 1);
    }
  }
}
