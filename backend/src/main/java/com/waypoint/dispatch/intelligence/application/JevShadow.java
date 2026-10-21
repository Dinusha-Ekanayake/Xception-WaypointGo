package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.domain.IssueTriage;
import com.waypoint.dispatch.intelligence.domain.ReportTriage;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcJevShadow;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcJevShadow.Pending;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised;
import com.waypoint.dispatch.issues.contract.IssueQuery;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Jev in shadow (roadmap AI-J1, R-ML-11). Nothing anyone sees changes: each new
 * issue a person raised in their own words is queued, a job asks Jev which kind
 * of problem the words describe with no transaction open, and the guess is kept
 * beside the issue's real type. The agreement rate is what the gate reads before
 * Jev may advise anyone. Off unless {@code JEV_ENABLED=true}.
 */
final class JevShadow {
  /** R-ML-27: the instruction and the options in plain words are the prompt; a change to either is a new version. */
  static final String PROMPT_VERSION =
      com.waypoint.dispatch.intelligence.domain.PromptFacts.version(
          IssueTriage.INSTRUCTIONS + "\n" + new java.util.TreeMap<>(IssueTriage.OPTIONS));
  /** R-ML-33: the prompt about a trip-chat report, versioned the same way. */
  static final String REPORT_PROMPT_VERSION =
      com.waypoint.dispatch.intelligence.domain.PromptFacts.version(
          ReportTriage.INSTRUCTIONS + "\n" + new java.util.TreeMap<>(ReportTriage.OPTIONS));

  private JevShadow() {}

  /** Queues a person's issue with words of its own; a redelivered event queues nothing new. */
  @Component
  static class OnIssueRaised implements EventSubscriber<IssueRaised> {
    private final AiProperties properties;
    private final IssueQuery issues;
    private final JdbcJevShadow shadow;
    private final Clock clock;
    private final KnownNames knownNames;

    OnIssueRaised(AiProperties properties, IssueQuery issues, JdbcJevShadow shadow, Clock clock, KnownNames knownNames) {
      this.knownNames = knownNames;
      this.properties = properties;
      this.issues = issues;
      this.shadow = shadow;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "ml.on-issue-raised-jev";
    }

    @Override
    public Class<IssueRaised> eventType() {
      return IssueRaised.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ML;
    }

    @Override
    public void on(EventEnvelope<IssueRaised> envelope) {
      if (!properties.jevEnabled()) {
        return;
      }
      IssueRaised e = envelope.payload();
      // R-ML-31: names are masked before the text is even stored for Jev. Without the
      // list the event is retried later rather than kept unmasked.
      com.waypoint.dispatch.intelligence.domain.PromptNames names = knownNames.current()
          .orElseThrow(() -> new IllegalStateException("account names not read yet; retried"));
      issues.issue(e.issueId())
          .filter(issue -> !Actor.SYSTEM_ID.equals(issue.raisedBy()))
          .flatMap(issue -> IssueTriage.state(issue.description()))
          .map(text -> names.session().mask(text))
          .ifPresent(text -> shadow.queue(
              e.issueId(), "issue", e.depotCode(), text, e.issueType().name().toLowerCase(java.util.Locale.ROOT),
              List.copyOf(IssueTriage.shuffled(e.issueId().getMostSignificantBits()).keySet()), clock.now()));
    }
  }

  /**
   * Decision suggestions PR 5 (R-ML-33): a trip-chat report whose writer picked
   * its type is queued with that type's situation as the label, so Jev is scored
   * on reports before it is ever asked about one sent as "other". A report from
   * the dispatcher, sent as "other", or spoken (its words are a transcript, not the
   * excerpt) is not queued. Only the event's excerpt is read: the thread is
   * Messaging's, and a report that needs more than 120 characters is rare.
   */
  @Component
  static class OnReportPosted implements EventSubscriber<MessagePosted> {
    private final AiProperties properties;
    private final JdbcJevShadow shadow;
    private final Clock clock;
    private final KnownNames knownNames;

    OnReportPosted(AiProperties properties, JdbcJevShadow shadow, Clock clock, KnownNames knownNames) {
      this.properties = properties;
      this.shadow = shadow;
      this.clock = clock;
      this.knownNames = knownNames;
    }

    @Override
    public String consumerName() {
      return "ml.on-report-posted-jev";
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
      if (!properties.jevEnabled() || !"report".equals(e.kind()) || "dispatcher".equals(e.authorRole())
          || e.voiceNoteId().isPresent()) {
        return;
      }
      String label = e.reportType().map(ReportTriage::label).orElse("OTHER");
      if ("OTHER".equals(label)) {
        return;
      }
      Optional<String> words = IssueTriage.state(e.excerpt());
      if (words.isEmpty()) {
        return;
      }
      com.waypoint.dispatch.intelligence.domain.PromptNames names = knownNames.current()
          .orElseThrow(() -> new IllegalStateException("account names not read yet; retried"));
      shadow.queue(e.messageId(), "report", e.depotCode(), names.session().mask(words.get()), label,
          List.copyOf(ReportTriage.shuffled(e.messageId().getMostSignificantBits()).keySet()), clock.now());
    }
  }

  /** Every minute: the queued issues and reports, asked one by one outside any transaction, within the day's cap. */
  @Component
  static class Job implements ScheduledJob {
    static final int BATCH = 20;
    static final int ATTEMPTS = 3;

    private final AiProperties properties;
    private final DecisionPort jev;
    private final JdbcJevShadow shadow;
    private final Database database;
    private final ObjectMapper json;
    private final Metrics metrics;
    private final Clock clock;

    Job(AiProperties properties, DecisionPort jev, JdbcJevShadow shadow, Database database, ObjectMapper json,
        Metrics metrics, Clock clock) {
      this.properties = properties;
      this.jev = jev;
      this.shadow = shadow;
      this.database = database;
      this.json = json;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String name() {
      return "ml.jev-shadow";
    }

    @Override
    public String cron() {
      return "30 * * * * *";
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
      if (!properties.jevEnabled()) {
        return 0;
      }
      Instant dayStart = now.atZone(Clock.OPERATING_ZONE).toLocalDate().atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      long left = properties.dailyCalls() - database.asSystem(ModuleRole.ML, () -> shadow.answeredSince(dayStart));
      if (left <= 0) {
        metrics.increment("waypoint.ai.capped", "port", "jev");
        return 0;
      }
      List<Pending> queued = database.asSystem(ModuleRole.ML, () -> shadow.pending((int) Math.min(BATCH, left)));
      int answered = 0;
      for (Pending p : queued) {
        boolean report = "report".equals(p.source());
        Map<String, String> all = report ? ReportTriage.OPTIONS : IssueTriage.OPTIONS;
        Map<String, String> options = new LinkedHashMap<>();
        p.optionOrder().forEach(k -> options.put(k, all.get(k)));
        // No transaction is open here: a slow provider never holds a connection.
        Optional<DecisionPort.Choice> choice = jev.choose(
            p.maskedText(), report ? ReportTriage.INSTRUCTIONS : IssueTriage.INSTRUCTIONS, options);
        String version = report ? REPORT_PROMPT_VERSION : PROMPT_VERSION;
        if (choice.isPresent()) {
          DecisionPort.Choice c = choice.get();
          boolean agrees = c.option().equals(p.actualType());
          String probabilities;
          try {
            probabilities = json.writeValueAsString(c.probabilities());
          } catch (Exception e) {
            probabilities = "{}";
          }
          String probs = probabilities;
          database.asSystem(ModuleRole.ML, () -> shadow.answered(
              p.issueId(), c.option(), c.confidence(), probs, c.model(), agrees, clock.now(), version));
          metrics.increment("waypoint.ai.jev_shadow", "agrees", String.valueOf(agrees), "source", p.source());
          answered++;
        } else {
          database.asSystem(ModuleRole.ML, () -> shadow.missed(p.issueId(), p.attempts() + 1 >= ATTEMPTS));
        }
      }
      return answered;
    }
  }
}
