package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.IssueTriage;
import com.waypoint.dispatch.intelligence.domain.MessageTriage;
import com.waypoint.dispatch.intelligence.domain.PromptNames;
import com.waypoint.dispatch.intelligence.domain.ReportTriage;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcJevShadow;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations.Pending;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessageTranscribed;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Jev live on a trip's chat (decision suggestions part 2, R-ML-35). A driver's
 * or a store's own words, typed, spoken (once written down) or a report sent as
 * "other", are queued masked, and a job asks Jev every few seconds, with no
 * transaction open, which situation they report. A class at or above the live
 * confidence becomes a signal on the trip's Next step card ({@link TripOutlookQuery}).
 * Off unless {@code JEV_LIVE=true} with {@code JEV_ENABLED=true}; production
 * stays off until @jv-ransika agrees to the data processing.
 */
final class MessageSituations {
  /** R-ML-27: the instruction and the options are the prompt; a change to either is a new version. */
  static final String PROMPT_VERSION =
      com.waypoint.dispatch.intelligence.domain.PromptFacts.version(
          MessageTriage.INSTRUCTIONS + "\n" + new java.util.TreeMap<>(MessageTriage.OPTIONS));

  /**
   * Who is read: the people on the road and at the store, and the dispatcher too, whose question steers
   * the store's replies (R-ML-43). Only drivers' and stores' words can open a problem on the room.
   */
  static final Set<String> READ = Set.of("driver", "store_manager", "dispatcher");

  private MessageSituations() {}

  /** What is worth asking about a post: its own words, unless a report already names its situation. */
  static boolean worthReading(String authorRole, String kind, Optional<String> reportType) {
    if (!READ.contains(authorRole)) return false;
    if ("dispatcher".equals(authorRole)) return "message".equals(kind);
    return "message".equals(kind) || ("report".equals(kind) && "OTHER".equals(reportType.map(ReportTriage::label).orElse("OTHER")));
  }

  static void queue(JdbcMessageSituations store, KnownNames knownNames, UUID messageId, UUID threadId, String tripId,
      String depot, String authorRole, Optional<String> outletId, String source, String excerpt, Instant at, boolean unclear,
      List<String> reached) {
    Optional<String> words = IssueTriage.state(excerpt);
    if (words.isEmpty()) return;
    PromptNames names = knownNames.current()
        .orElseThrow(() -> new IllegalStateException("account names not read yet; retried"));
    store.queue(messageId, threadId, tripId, depot, authorRole, outletId, source, names.session().mask(words.get()),
        List.copyOf(MessageTriage.shuffled(messageId.getMostSignificantBits()).keySet()), at, unclear, reached);
  }

  /** A typed post; a voice note waits for its words ({@link OnTranscribed}). */
  @Component
  static class OnPosted implements EventSubscriber<MessagePosted> {
    private final AiProperties properties;
    private final JdbcMessageSituations store;
    private final KnownNames knownNames;
    private final Clock clock;

    OnPosted(AiProperties properties, JdbcMessageSituations store, KnownNames knownNames, Clock clock) {
      this.properties = properties;
      this.store = store;
      this.knownNames = knownNames;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "ml.on-message-posted-situation";
    }

    @Override
    public Class<MessagePosted> eventType() {
      return MessagePosted.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<MessagePosted> envelope) {
      MessagePosted e = envelope.payload();
      if (!properties.jevLive() || !"trip".equals(e.subjectType()) || e.voiceNoteId().isPresent()
          || !worthReading(e.authorRole(), e.kind(), e.reportType())) {
        return;
      }
      queue(store, knownNames, e.messageId(), e.threadId(), e.subjectId(), e.depotCode(), e.authorRole(), e.aboutOutletId(),
          "report".equals(e.kind()) ? "report" : "message", e.excerpt(), clock.now(), false, e.outletIds());
    }
  }

  /** A voice note written down: read like a typed post. */
  @Component
  static class OnTranscribed implements EventSubscriber<MessageTranscribed> {
    private final AiProperties properties;
    private final JdbcMessageSituations store;
    private final KnownNames knownNames;
    private final Clock clock;

    OnTranscribed(AiProperties properties, JdbcMessageSituations store, KnownNames knownNames, Clock clock) {
      this.properties = properties;
      this.store = store;
      this.knownNames = knownNames;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "ml.on-message-transcribed-situation";
    }

    @Override
    public Class<MessageTranscribed> eventType() {
      return MessageTranscribed.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<MessageTranscribed> envelope) {
      MessageTranscribed e = envelope.payload();
      if (!properties.jevLive() || !"trip".equals(e.subjectType()) || !worthReading(e.authorRole(), e.kind(), e.reportType())) {
        return;
      }
      queue(store, knownNames, e.messageId(), e.threadId(), e.subjectId(), e.depotCode(), e.authorRole(), e.aboutOutletId(),
          "voice", e.excerpt(), clock.now(), e.unclear(), List.of());
    }
  }

  /** Every five seconds: the queued posts, asked one by one outside any transaction, within the day's cap. */
  @Component
  static class Job implements ScheduledJob {
    static final int BATCH = 10;
    static final int ATTEMPTS = 3;

    private final AiProperties properties;
    private final DecisionPort jev;
    private final JdbcMessageSituations store;
    private final JdbcJevShadow shadow;
    private final Database database;
    private final Metrics metrics;
    private final Clock clock;
    private final com.waypoint.dispatch.platform.messaging.EventPublisher events;

    Job(AiProperties properties, DecisionPort jev, JdbcMessageSituations store, JdbcJevShadow shadow, Database database,
        Metrics metrics, Clock clock, com.waypoint.dispatch.platform.messaging.EventPublisher events) {
      this.events = events;
      this.properties = properties;
      this.jev = jev;
      this.store = store;
      this.shadow = shadow;
      this.database = database;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String name() {
      return "ml.message-situations";
    }

    @Override
    public String cron() {
      return "*/5 * * * * *";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void run(Instant now) {
      runAt(now);
    }

    /** @return how many were answered */
    int runAt(Instant now) {
      if (!properties.jevLive()) {
        return 0;
      }
      Instant dayStart = now.atZone(Clock.OPERATING_ZONE).toLocalDate().atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      // One cap for every call Jev answers, shadow and live alike.
      long used = database.asSystem(ModuleRole.ML, () -> shadow.answeredSince(dayStart) + store.answeredSince(dayStart));
      long left = properties.dailyCalls() - used;
      if (left <= 0) {
        metrics.increment("waypoint.ai.capped", "port", "jev-live");
        return 0;
      }
      List<Pending> queued = database.asSystem(ModuleRole.ML, () -> store.pending((int) Math.min(BATCH, left)));
      int answered = 0;
      for (Pending p : queued) {
        Map<String, String> options = new LinkedHashMap<>();
        // An order queued before the acts existed names only problems; every key is still an option.
        p.optionOrder().stream().filter(MessageTriage.OPTIONS::containsKey).forEach(k -> options.put(k, MessageTriage.OPTIONS.get(k)));
        // No transaction is open here: a slow provider never holds a connection.
        Optional<DecisionPort.Choice> choice = jev.choose(p.maskedText(), MessageTriage.INSTRUCTIONS, options);
        if (choice.isPresent()) {
          DecisionPort.Choice c = choice.get();
          database.asSystem(ModuleRole.ML, () -> {
            store.answered(p.messageId(), c.option(), c.confidence(), c.model(), PROMPT_VERSION, clock.now());
            // R-ML-43: Messaging keeps what the message does, so its readers' suggested replies follow it.
            store.forEvent(p.messageId()).ifPresent(f -> events.publish(com.waypoint.dispatch.shared.domain.Actor.SYSTEM,
                new com.waypoint.dispatch.intelligence.contract.ChatEvents.MessageUnderstood(
                    f.threadId(), p.messageId(), f.depotCode(), f.reached(), c.option(),
                    MessageTriage.steering(c.option(), c.confidence(), properties.jevLiveMinConfidence(), f.unclear()).isPresent(),
                    clock.now())));
            return null;
          });
          boolean counts = MessageTriage.signal(c.option(), c.confidence(), properties.jevLiveMinConfidence()).isPresent();
          metrics.increment("waypoint.ai.jev_live", "counts", String.valueOf(counts));
          answered++;
        } else {
          database.asSystem(ModuleRole.ML, () -> store.missed(p.messageId(), p.attempts() + 1 >= ATTEMPTS));
          metrics.increment("waypoint.ai.jev_live", "counts", "missed");
        }
      }
      return answered;
    }
  }
}
