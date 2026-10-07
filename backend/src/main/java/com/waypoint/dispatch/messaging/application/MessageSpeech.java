package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.messaging.domain.SpeechText;
import com.waypoint.dispatch.messaging.infrastructure.JdbcSpeech;
import com.waypoint.dispatch.messaging.infrastructure.JdbcSpeech.Pending;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every typed message spoken on our own server (R-MSG-10). A posted message is
 * queued; a job asks the speech service with no transaction open, keeps the
 * audio once per text and language, and marks every message waiting for it
 * ready. The phone plays it, and keeps it for when there is no signal. With the
 * service off nothing is queued, and the phone's own voice is used, saying so.
 */
public final class MessageSpeech {
  private MessageSpeech() {}

  /** Queues a typed message; a voice note is already audio. Applying the event twice queues nothing new. */
  @Component
  static class OnMessagePosted implements EventSubscriber<MessagePosted> {
    private final SpeechSynthesis speech;
    private final JdbcThreadRepository threads;
    private final JdbcSpeech spoken;
    private final Clock clock;

    OnMessagePosted(SpeechSynthesis speech, JdbcThreadRepository threads, JdbcSpeech spoken, Clock clock) {
      this.speech = speech;
      this.threads = threads;
      this.spoken = spoken;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "messaging.on-message-posted-speech";
    }

    @Override
    public Class<MessagePosted> eventType() {
      return MessagePosted.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void on(EventEnvelope<MessagePosted> envelope) {
      MessagePosted e = envelope.payload();
      if (!speech.enabled() || e.voiceNoteId().isPresent()) {
        return;
      }
      threads.message(e.messageId())
          .flatMap(m -> SpeechText.spoken(m.body()))
          .ifPresent(text -> spoken.queue(e.messageId(), SpeechText.key(text), SpeechText.languageOf(text), clock.now()));
    }
  }

  /** Every 20 seconds: the waiting messages, spoken once per text and language. */
  @Component
  public static class Job implements ScheduledJob {
    static final int BATCH = 20;
    static final int ATTEMPTS = 3;

    private final SpeechSynthesis speech;
    private final JdbcSpeech spoken;
    private final JdbcThreadRepository threads;
    private final Database database;
    private final Metrics metrics;
    private final Clock clock;

    Job(SpeechSynthesis speech, JdbcSpeech spoken, JdbcThreadRepository threads, Database database, Metrics metrics,
        Clock clock) {
      this.speech = speech;
      this.spoken = spoken;
      this.threads = threads;
      this.database = database;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String name() {
      return "messaging.speech";
    }

    @Override
    public String cron() {
      return "*/20 * * * * *";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public void run(Instant now) {
      runAt(now);
    }

    /** @return how many texts were spoken */
    public int runAt(Instant now) {
      if (!speech.enabled()) {
        return 0;
      }
      List<Pending> queued = database.asSystem(ModuleRole.MESSAGING, () -> spoken.pending(BATCH));
      Set<String> done = new HashSet<>();
      int made = 0;
      for (Pending p : queued) {
        String key = p.textSha256() + "|" + p.lang();
        if (done.contains(key)) continue;
        if (database.asSystem(ModuleRole.MESSAGING, () -> spoken.rendered(p.textSha256(), p.lang()))) {
          database.asSystem(ModuleRole.MESSAGING, () -> spoken.ready(p.textSha256(), p.lang(), clock.now()));
          done.add(key);
          continue;
        }
        Optional<String> text = database.asSystem(ModuleRole.MESSAGING,
            () -> threads.message(p.messageId()).flatMap(m -> SpeechText.spoken(m.body())));
        // No transaction is open here: a slow speech service never holds a connection.
        Optional<byte[]> audio = text.flatMap(t -> speech.speak(t, p.lang()));
        if (audio.isPresent()) {
          byte[] bytes = audio.get();
          database.asSystem(ModuleRole.MESSAGING, () -> {
            spoken.storeRender(p.textSha256(), p.lang(), bytes, "audio/mp4", clock.now());
            spoken.ready(p.textSha256(), p.lang(), clock.now());
          });
          done.add(key);
          made++;
          metrics.increment("waypoint.speech.made", "lang", p.lang());
        } else {
          database.asSystem(ModuleRole.MESSAGING, () -> spoken.missed(p.messageId(), p.attempts() + 1 >= ATTEMPTS));
          metrics.increment("waypoint.speech.missed", "lang", p.lang());
        }
      }
      return made;
    }
  }

  /** The audio of a message the reader may read; 404 while it is not ready, so the phone uses its own voice. */
  @Component
  public static class Reader {
    private final JdbcSpeech spoken;
    private final Database database;

    Reader(JdbcSpeech spoken, Database database) {
      this.spoken = spoken;
      this.database = database;
    }

    public VoiceNotes.Audio audio(Actor actor, UUID threadId, UUID messageId) {
      Map<String, Object> row = database.readAs(ModuleRole.MESSAGING, actor.userId(), () -> spoken.audio(threadId, messageId))
          .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "no spoken audio for this message yet",
              List.of("R-MSG-10")));
      return new VoiceNotes.Audio((String) row.get("content_type"), (byte[]) row.get("audio"));
    }
  }
}
