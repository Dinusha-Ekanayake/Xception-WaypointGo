package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessageTranscribed;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.domain.TranscriptText.Heard;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository.VoiceNote;
import com.waypoint.dispatch.messaging.infrastructure.JdbcTranscripts;
import com.waypoint.dispatch.messaging.infrastructure.JdbcTranscripts.Pending;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Voice notes written down by a transcription engine (roadmap FF-05). A posted
 * voice note is queued; a job sends the audio with no transaction open, on its
 * own thread so a slow engine holds nothing else up (F-09a), and keeps the words
 * with the engine's name. With no engine configured nothing is queued, and the
 * screen says there is no transcript and to play the audio (rule 9).
 */
public final class VoiceTranscripts {
  private VoiceTranscripts() {}

  /** Queues a voice note once. A typed message needs no transcript. */
  @Component
  static class OnMessagePosted implements EventSubscriber<MessagePosted> {
    private final Transcription engine;
    private final JdbcTranscripts transcripts;
    private final Clock clock;

    OnMessagePosted(Transcription engine, JdbcTranscripts transcripts, Clock clock) {
      this.engine = engine;
      this.transcripts = transcripts;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "messaging.on-message-posted-transcript";
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
      if (!engine.enabled()) return;
      envelope.payload().voiceNoteId().ifPresent(id -> transcripts.queue(id, engine.engine(), clock.now()));
    }
  }

  /** Every 30 seconds: the waiting notes, a few at a time. */
  @Component
  public static class Job implements ScheduledJob {
    static final int BATCH = 5;
    static final int ATTEMPTS = 3;

    private final Transcription engine;
    private final JdbcTranscripts transcripts;
    private final JdbcThreadRepository threads;
    private final Database database;
    private final Metrics metrics;
    private final Clock clock;

    private final boolean keepAfterAudio;
    private final com.waypoint.dispatch.platform.messaging.EventPublisher events;

    Job(Transcription engine, JdbcTranscripts transcripts, JdbcThreadRepository threads, Database database,
        Metrics metrics, Clock clock, com.waypoint.dispatch.platform.config.TranscribeProperties properties,
        com.waypoint.dispatch.platform.messaging.EventPublisher events) {
      this.events = events;
      this.keepAfterAudio = properties.keepAfterAudio();
      this.engine = engine;
      this.transcripts = transcripts;
      this.threads = threads;
      this.database = database;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String name() {
      return "messaging.transcribe";
    }

    @Override
    public String cron() {
      return "*/30 * * * * *";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.MESSAGING;
    }

    @Override
    public String bulkhead() {
      return "transcribe";
    }

    @Override
    public void run(Instant now) {
      runAt(now);
    }

    /** @return how many notes were written down */
    public int runAt(Instant now) {
      // Words whose recording is gone are kept and marked (R-MSG-15), or go too when
      // TRANSCRIPT_KEEP_AFTER_AUDIO=false; engine or not.
      int purged = database.asSystem(ModuleRole.MESSAGING, () -> keepAfterAudio
          ? transcripts.markAudioGone(clock.now())
          : transcripts.purgeWithAudio(clock.now()));
      if (purged > 0) metrics.count("waypoint.transcribe.purged", purged);
      if (!engine.enabled()) return 0;
      List<Pending> queued = database.asSystem(ModuleRole.MESSAGING, () -> transcripts.pending(BATCH));
      int made = 0;
      for (Pending p : queued) {
        Optional<VoiceNote> note = database.asSystem(ModuleRole.MESSAGING, () -> threads.voice(p.voiceNoteId()));
        Optional<byte[]> audio = database.asSystem(ModuleRole.MESSAGING, () -> threads.voiceContent(p.voiceNoteId()));
        // No transaction is open here: a slow engine never holds a connection.
        Optional<Heard> heard =
            note.isPresent() && audio.isPresent()
                ? engine.transcribe(audio.get(), note.get().contentType())
                : Optional.empty();
        if (heard.isPresent()) {
          Heard h = heard.get();
          database.asSystem(ModuleRole.MESSAGING, () -> {
            transcripts.done(p.voiceNoteId(), h.text(), h.language(), h.unclear(), engine.engine(), clock.now());
            // What was said can now be read like a typed post (R-ML-35): said in the same transaction.
            threads.postOfVoice(p.voiceNoteId()).ifPresent(m -> events.publish(Actor.SYSTEM, new MessageTranscribed(
                (UUID) m.get("thread_id"), (UUID) m.get("message_id"), (String) m.get("subject_type"),
                (String) m.get("subject_id"), (String) m.get("depot_code"), (String) m.get("author_role"),
                (String) m.get("kind"), Optional.ofNullable((String) m.get("report_type")),
                Optional.ofNullable((String) m.get("about_outlet")), MessagePolicy.excerpt(h.text()), h.language(),
                clock.now(), h.unclear())));
            return null;
          });
          metrics.increment("waypoint.transcribe.made", "lang", h.language().orElse("unknown"));
          made++;
        } else {
          boolean last = audio.isEmpty() || p.attempts() + 1 >= ATTEMPTS;
          database.asSystem(ModuleRole.MESSAGING, () -> transcripts.missed(p.voiceNoteId(), last, clock.now()));
          metrics.increment("waypoint.transcribe.missed", "final", String.valueOf(last));
        }
      }
      return made;
    }
  }
}
