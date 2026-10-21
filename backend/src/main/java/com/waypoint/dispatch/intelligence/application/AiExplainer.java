package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.domain.PromptFacts;
import com.waypoint.dispatch.intelligence.domain.PromptFacts.Kind;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAiCalls;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A plan's or an order's explanation in friendlier words (roadmap AI-0, AI-1,
 * R-ML-10). The rules already wrote the explanation; a language model only
 * rewords it. Through the gateway only: off unless switched on; the reader must
 * be able to read the plan; only facts on the allow-list go, masked; a fixed
 * instruction, so this is no open chat; at most the daily cap of calls; the
 * call is made with no transaction open; every call is recorded; and an answer
 * with a figure the facts do not hold is thrown away. Anything else leaves the
 * screen with the rules' text and says "rules only" (rule 9).
 */
@Component
public class AiExplainer {
  static final String INSTRUCTION = String.join(" ",
      "You explain a delivery plan to a dispatcher in plain, friendly English.",
      "Use only the facts given. Never add a number, a name, a vehicle, a store, a time or a reason that is not in them.",
      "What the facts mean: carries is what the plan delivers. leftOff lists orders that are NOT in this plan:",
      "they wait for a later delivery day, each because putting it on a trip would have broken the rule its label names",
      "(a missed window, a time budget, too many trips, no refrigerated vehicle, no room); so nothing in the plan breaks",
      "these rules. Say they were left off and why, never that a delivery will be late or a trip is over a limit.",
      "cannotBeServed lists orders no vehicle can carry at all. Each item is its own fact: never join two items into one.",
      "No rule codes, no technical or programming words, no maths. Short sentences.",
      "Format as Markdown: one short opening paragraph, then at most five bullet points, using **bold** for what matters and *italic* for the planner's own reasons.",
      "Never use an em dash or an en dash. Under 130 words.");

  /** The long dashes a model likes to write, built from their codes so this file holds none. */
  private static final String DASHES = " *[" + (char) 0x2013 + (char) 0x2014 + "] *";

  /** off: the gateway is switched off; worded: the model's words; rules_only: the rules' text stands. */
  public record Worded(String state, Optional<String> text) {
    static Worded off() {
      return new Worded("off", Optional.empty());
    }

    static Worded rulesOnly() {
      return new Worded("rules_only", Optional.empty());
    }
  }

  private final AiProperties properties;
  private final LanguageModel model;
  private final JdbcAiCalls calls;
  private final PlanQuery plans;
  private final Database database;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  private final KnownNames knownNames;

  public AiExplainer(
      AiProperties properties, LanguageModel model, JdbcAiCalls calls, PlanQuery plans, Database database,
      ObjectMapper json, Metrics metrics, Clock clock, KnownNames knownNames) {
    this.knownNames = knownNames;
    this.properties = properties;
    this.model = model;
    this.calls = calls;
    this.plans = plans;
    this.database = database;
    this.json = json;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** The plan a key is about: {@code plan:<planId>:...} or {@code order:<planId>:...}. */
  public static UUID planOf(String key) {
    String[] parts = key == null ? new String[0] : key.split(":");
    if (parts.length < 2 || PromptFacts.Kind.ofKey(key).isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "key must be plan:<planId>:... or order:<planId>:...",
          List.of("R-ML-10"));
    }
    try {
      return UUID.fromString(parts[1]);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "key names no plan", List.of("R-ML-10"));
    }
  }

  public Worded explain(Actor actor, String key, Map<?, ?> facts) {
    if (!properties.enabled()) {
      return Worded.off();
    }
    Kind kind = Kind.ofKey(key).orElseThrow();
    UUID planId = planOf(key);
    // The reader must be able to read the plan the facts are about (rule 7).
    if (database.readAs(ModuleRole.ML, actor.userId(), () -> plans.plan(planId)).isEmpty()) {
      throw new DomainException(ErrorCode.FORBIDDEN, "plan " + planId + " is outside the actor's scope", List.of("R-ML-10"));
    }
    Map<String, Object> allowed = PromptFacts.allowed(kind, facts);
    if (allowed.isEmpty()) {
      return Worded.rulesOnly();
    }
    // R-ML-31: our people's names become [person-N] here and come back only on our side.
    Optional<com.waypoint.dispatch.intelligence.domain.PromptNames> names = knownNames.current();
    if (names.isEmpty()) {
      return Worded.rulesOnly();
    }
    com.waypoint.dispatch.intelligence.domain.PromptNames.Session people = names.get().session();
    String sent;
    try {
      sent = json.writeValueAsString(people.maskValues(allowed));
    } catch (JsonProcessingException e) {
      return Worded.rulesOnly();
    }
    java.util.function.UnaryOperator<String> back =
        text -> com.waypoint.dispatch.intelligence.domain.PromptNames.restore(text, people.tokens());
    String cacheKey = key.length() > 200 ? key.substring(0, 200) : key;
    Optional<String> had = database.asSystem(ModuleRole.ML, () -> calls.usedAnswer(cacheKey, PromptFacts.version(INSTRUCTION)));
    if (had.isPresent()) {
      return new Worded("worded", had.map(back));
    }
    Instant now = clock.now();
    Instant dayStart = now.atZone(Clock.OPERATING_ZONE).toLocalDate().atStartOfDay(Clock.OPERATING_ZONE).toInstant();
    if (database.asSystem(ModuleRole.ML, () -> calls.callsSince(dayStart)) >= properties.dailyCalls()) {
      record(cacheKey, kind, sent, Optional.empty(), "capped", actor, now, 0);
      metrics.increment("waypoint.ai.capped");
      return Worded.rulesOnly();
    }
    // No transaction is open here: a slow provider never holds a connection.
    long started = System.nanoTime();
    Optional<String> raw = model.complete(INSTRUCTION, sent);
    long millis = (System.nanoTime() - started) / 1_000_000;
    Optional<String> used = raw.map(a -> a.replaceAll(DASHES, ", ")).flatMap(a -> PromptFacts.faithful(a, sent));
    String outcome = raw.isEmpty() ? "failed" : used.isEmpty() ? "unfaithful" : "used";
    record(cacheKey, kind, sent, used, outcome, actor, now, millis);
    metrics.increment("waypoint.ai.explain", "outcome", outcome);
    return used.map(text -> new Worded("worded", Optional.of(back.apply(text)))).orElseGet(Worded::rulesOnly);
  }

  private void record(
      String cacheKey, Kind kind, String sent, Optional<String> answer, String outcome, Actor actor, Instant at, long millis) {
    database.asSystem(ModuleRole.ML, () -> calls.record(
        UuidV7.generate(at, random), "explain_" + kind.name().toLowerCase(java.util.Locale.ROOT), cacheKey,
        model.provider(), model.model(), sent, answer, outcome, actor.userId(), at, millis,
        PromptFacts.version(INSTRUCTION)));
  }
}
