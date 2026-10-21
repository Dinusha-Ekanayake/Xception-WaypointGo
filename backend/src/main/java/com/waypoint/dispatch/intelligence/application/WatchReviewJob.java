package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionItemView;
import com.waypoint.dispatch.intelligence.domain.PromptFacts;
import com.waypoint.dispatch.intelligence.domain.WatchReview;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAiCalls;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The watcher's AI layer, in shadow (docs/issues/watch-review/PLAN.md). Every 5
 * minutes, for each depot with two or more open attention items today, a
 * language model orders what the rules raised and says why in one line. Nothing
 * is shown to anyone: each call is recorded in ml.ai_calls (purpose
 * {@code watch_review}) for the AI health screen, and a metric counts how often
 * the model's first item is the rules' first. Off unless AI and the review are
 * both on; within the day's cap; no transaction open during the call.
 */
@Component
public class WatchReviewJob implements ScheduledJob {
  static final String PURPOSE = "watch_review";
  static final int AT_LEAST = 2;

  private final AiProperties properties;
  private final LanguageModel model;
  private final JdbcAttentionRepository attention;
  private final JdbcAiCalls calls;
  private final Database database;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();

  public WatchReviewJob(
      AiProperties properties, LanguageModel model, JdbcAttentionRepository attention, JdbcAiCalls calls,
      Database database, ObjectMapper json, Metrics metrics) {
    this.properties = properties;
    this.model = model;
    this.attention = attention;
    this.calls = calls;
    this.database = database;
    this.json = json;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "intelligence.watch-review";
  }

  @Override
  public String cron() {
    return "0 */5 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now, now.atZone(Clock.OPERATING_ZONE).toLocalDate());
  }

  /** @return how many depots were reviewed, whatever the outcome */
  public int runAt(Instant now, LocalDate day) {
    if (!properties.enabled() || !properties.watchReview()) return 0;
    List<String> depots = database.asSystem(ModuleRole.ML, () -> attention.depotsWithOpen(day, AT_LEAST));
    int reviewed = 0;
    for (String depot : depots) {
      review(depot, day, now);
      reviewed++;
    }
    return reviewed;
  }

  private void review(String depot, LocalDate day, Instant now) {
    List<AttentionItemView> open = database.asSystem(ModuleRole.ML, () -> attention.open(depot, day));
    List<WatchReview.Item> rulesOrder = new ArrayList<>();
    for (AttentionItemView it : open) {
      rulesOrder.add(new WatchReview.Item(null, it.kind().name(), it.severity().name(), it.minutesLeft().orElse(null),
          it.outletId(), it.vehicleId()));
    }
    List<WatchReview.Item> sent = WatchReview.items(rulesOrder);
    String facts;
    try {
      facts = json.writeValueAsString(WatchReview.facts(sent));
    } catch (JsonProcessingException e) {
      return;
    }
    // Codes only, but masked like every call: a badge or a phone in an outlet code never leaves.
    facts = PromptFacts.mask(facts.length() > 4000 ? facts.substring(0, 4000) : facts);
    String key = "watch:" + depot + ":" + now.getEpochSecond() / 300;
    Instant dayStart = day.atStartOfDay(Clock.OPERATING_ZONE).toInstant();
    String sentFacts = facts;
    if (database.asSystem(ModuleRole.ML, () -> calls.callsSince(dayStart)) >= properties.dailyCalls()) {
      record(key, sentFacts, Optional.empty(), "capped", now, 0);
      metrics.increment("waypoint.ai.capped");
      return;
    }
    long started = System.nanoTime();
    Optional<String> raw = model.complete(WatchReview.INSTRUCTION, facts);
    long millis = (System.nanoTime() - started) / 1_000_000;
    Optional<WatchReview.Verdict> verdict = raw.flatMap(answer -> read(sent, answer, sentFacts));
    String outcome = raw.isEmpty() ? "failed" : verdict.isEmpty() ? "unfaithful" : "used";
    record(key, sentFacts, verdict.isPresent() ? raw : Optional.empty(), outcome, now, millis);
    metrics.increment("waypoint.ai.watch_review", "outcome", outcome);
    verdict.ifPresent(v -> metrics.increment("waypoint.ai.watch_review_first", "agrees", String.valueOf(v.agreesFirst())));
  }

  /** The answer's order, if it orders exactly what was sent and its line holds no figure the facts do not. */
  private Optional<WatchReview.Verdict> read(List<WatchReview.Item> sent, String answer, String facts) {
    try {
      String body = answer.strip();
      int from = body.indexOf('{');
      int to = body.lastIndexOf('}');
      if (from < 0 || to <= from) return Optional.empty();
      JsonNode root = json.readTree(body.substring(from, to + 1));
      List<String> order = new ArrayList<>();
      root.path("order").forEach(n -> order.add(n.asText()));
      String why = root.path("why").asText("");
      // A reference is a label, not a figure: it is in the facts.
      if (!why.isBlank() && PromptFacts.faithful(why, facts).isEmpty()) return Optional.empty();
      return WatchReview.judge(sent, order);
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  private void record(String key, String sent, Optional<String> answer, String outcome, Instant at, long millis) {
    database.asSystem(ModuleRole.ML, () -> calls.record(
        UuidV7.generate(at, random), PURPOSE, key, model.provider(), model.model(), sent, answer, outcome,
        Actor.SYSTEM_ID, at, millis, PromptFacts.version(WatchReview.INSTRUCTION)));
  }
}
