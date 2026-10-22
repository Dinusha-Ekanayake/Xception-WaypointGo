package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.ReplyDraftView;
import com.waypoint.dispatch.intelligence.domain.AiCacheKey;
import com.waypoint.dispatch.intelligence.domain.MessageTriage;
import com.waypoint.dispatch.intelligence.domain.PromptFacts;
import com.waypoint.dispatch.intelligence.domain.ReplyDraftPolicy;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAiCache;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAiCalls;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations;
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
 * A reply drafted by the language model for a message no ready reply fits
 * (R-ML-46). Only what Jev already read is used, masked as it was queued, with
 * the earlier lines of the same chat; the reader must hold the message's depot.
 * One call per message and prompt, within the day's cap, recorded with what was
 * sent and what came back; a draft that fails {@link ReplyDraftPolicy#accept}
 * is never shown. The same words to the same depot are drafted once (R-ML-47).
 * Off unless both the gateway and Jev live are on.
 */
@Component
public class ReplyDraftQuery {
  static final String PROMPT_VERSION = PromptFacts.version(ReplyDraftPolicy.INSTRUCTION);

  private final AiProperties properties;
  private final LanguageModel model;
  private final JdbcAiCalls calls;
  private final JdbcMessageSituations read;
  private final JdbcAiCache cache;
  private final Database database;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ReplyDraftQuery(AiProperties properties, LanguageModel model, JdbcAiCalls calls, JdbcMessageSituations read,
      JdbcAiCache cache, Database database, ObjectMapper json, Metrics metrics, Clock clock) {
    this.cache = cache;
    this.properties = properties;
    this.model = model;
    this.calls = calls;
    this.read = read;
    this.database = database;
    this.json = json;
    this.metrics = metrics;
    this.clock = clock;
  }

  public ReplyDraftView draft(Actor actor, UUID messageId) {
    if (!properties.enabled() || !properties.jevLive()) {
      return ReplyDraftView.off();
    }
    // Read as the reader: a message outside their depots is not there (rule 7).
    Optional<JdbcMessageSituations.AsRead> message = database.readAs(ModuleRole.ML, actor.userId(), () -> read.asRead(messageId));
    if (message.isEmpty()) {
      // Read but outside the reader's depots is refused, never answered as nothing (deny by default).
      if (database.asSystem(ModuleRole.ML, () -> read.asRead(messageId)).isPresent()) {
        throw new DomainException(ErrorCode.FORBIDDEN, "message " + messageId + " is outside the actor's scope", List.of("R-ML-46"));
      }
      return ReplyDraftView.none();
    }
    JdbcMessageSituations.AsRead m = message.get();
    if (!ReplyDraftPolicy.worthDrafting(m.authorRole(), m.situation(), m.confidence(), properties.jevLiveMinConfidence(),
        m.unclear(), m.maskedText())) {
      return ReplyDraftView.none();
    }
    String cacheKey = "reply:" + messageId;
    Optional<String> had = database.asSystem(ModuleRole.ML, () -> calls.usedAnswer(cacheKey, PROMPT_VERSION));
    if (had.isPresent()) {
      return ReplyDraftView.drafted(had.get());
    }
    // The earlier lines, already masked, as Jev read them; off-topic ones left out.
    List<JdbcMessageSituations.Earlier> rows = database.asSystem(ModuleRole.ML,
        () -> read.earlier(messageId, MessageTriage.CONTEXT_WINDOW, 2 * MessageTriage.CONTEXT_TURNS));
    List<MessageTriage.Turn> earlier = rows.stream().map(e -> new MessageTriage.Turn(e.authorRole(), e.maskedText(), e.readAs())).toList();
    List<JdbcMessageSituations.Earlier> kept = rows.stream()
        .filter(e -> !e.readAs().map(MessageTriage.OFF_TOPIC::equals).orElse(false)).toList();
    kept = kept.subList(Math.max(0, kept.size() - MessageTriage.CONTEXT_TURNS), kept.size());
    // R-ML-47: the same words to the same depot, answering a line read the same way, are drafted once.
    String shared = AiCacheKey.draft(PROMPT_VERSION, model.model(), m.depotCode(), m.authorRole(), m.maskedText(),
        kept.isEmpty() ? List.of() : List.of(kept.get(kept.size() - 1).readAs().orElse("?")));
    Optional<ReplyDraftView> served = database.asSystem(ModuleRole.ML, () -> cache.hit(shared, clock.now())).flatMap(this::viewOf);
    metrics.increment("waypoint.ai.cache", "purpose", "draft", "result", served.isPresent() ? "hit" : "miss");
    if (served.isPresent()) {
      return served.get();
    }
    if (database.asSystem(ModuleRole.ML, () -> calls.asked(cacheKey, PROMPT_VERSION))) {
      return ReplyDraftView.none();
    }
    String sent = MessageTriage.inContext(earlier, m.authorRole(), m.maskedText());
    String sentFacts;
    try {
      sentFacts = json.writeValueAsString(Map.of("lines", sent));
    } catch (JsonProcessingException e) {
      return ReplyDraftView.none();
    }
    Instant now = clock.now();
    Instant dayStart = now.atZone(Clock.OPERATING_ZONE).toLocalDate().atStartOfDay(Clock.OPERATING_ZONE).toInstant();
    if (database.asSystem(ModuleRole.ML, () -> calls.callsSince(dayStart)) >= properties.dailyCalls()) {
      record(cacheKey, sentFacts, Optional.empty(), "capped", actor, now, 0);
      metrics.increment("waypoint.ai.capped", "port", "reply-draft");
      return ReplyDraftView.none();
    }
    // No transaction is open here: a slow provider never holds a connection.
    long started = System.nanoTime();
    Optional<String> raw = model.completeShort(ReplyDraftPolicy.INSTRUCTION, sent);
    long millis = (System.nanoTime() - started) / 1_000_000;
    Optional<String> used = raw.flatMap(r -> ReplyDraftPolicy.accept(r, sent));
    String outcome = raw.isEmpty() ? "failed" : used.isEmpty() ? "unfaithful" : "used";
    record(cacheKey, sentFacts, used, outcome, actor, now, millis);
    metrics.increment("waypoint.ai.reply_draft", "outcome", outcome);
    // A failed call is not kept: the provider being down says nothing about the words.
    if (raw.isPresent()) {
      database.asSystem(ModuleRole.ML, () -> {
        cache.keep(shared, "draft", PROMPT_VERSION, model.model(), m.depotCode(), draftJson(used), now,
            used.isPresent() ? AiCacheKey.DRAFT : AiCacheKey.NO_DRAFT);
        return null;
      });
    }
    return used.map(ReplyDraftView::drafted).orElseGet(ReplyDraftView::none);
  }

  private Optional<ReplyDraftView> viewOf(String answer) {
    try {
      var n = json.readTree(answer);
      return Optional.of(n.path("draft").isTextual() ? ReplyDraftView.drafted(n.path("draft").asText()) : ReplyDraftView.none());
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  private String draftJson(Optional<String> draft) {
    try {
      return json.writeValueAsString(java.util.Collections.singletonMap("draft", draft.orElse(null)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void record(String cacheKey, String sentFacts, Optional<String> answer, String outcome, Actor actor, Instant at,
      long millis) {
    database.asSystem(ModuleRole.ML, () -> {
      calls.record(UuidV7.generate(at, random), "reply_draft", cacheKey, model.provider(), model.model(), sentFacts, answer,
          outcome, actor.userId(), at, millis, PROMPT_VERSION);
      return null;
    });
  }
}
