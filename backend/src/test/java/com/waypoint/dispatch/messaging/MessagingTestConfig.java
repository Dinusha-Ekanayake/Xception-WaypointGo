package com.waypoint.dispatch.messaging;

import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock the test moves, so the thread's posting window (R-MSG-04) can be exercised on purpose. */
@TestConfiguration
class MessagingTestConfig {

  @Bean
  @Primary
  MovableClock messagingClock() {
    return new MovableClock();
  }

  /** A stand-in speech service: on, and counts what it was asked to speak. */
  static final java.util.concurrent.atomic.AtomicInteger SPOKEN = new java.util.concurrent.atomic.AtomicInteger();

  @Bean
  @Primary
  com.waypoint.dispatch.messaging.application.SpeechSynthesis stubSpeech() {
    return new com.waypoint.dispatch.messaging.application.SpeechSynthesis() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public java.util.Optional<byte[]> speak(String text, String lang) {
        SPOKEN.incrementAndGet();
        return java.util.Optional.of(("AUDIO:" + lang + ":" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    };
  }

  /** A stand-in transcription engine (FF-05): on, and fails while FAIL_TRANSCRIBE is set. */
  static final java.util.concurrent.atomic.AtomicBoolean FAIL_TRANSCRIBE = new java.util.concurrent.atomic.AtomicBoolean();

  @Bean
  @Primary
  com.waypoint.dispatch.messaging.application.Transcription stubTranscription() {
    return new com.waypoint.dispatch.messaging.application.Transcription() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String engine() {
        return "stub";
      }

      @Override
      public java.util.Optional<com.waypoint.dispatch.messaging.domain.TranscriptText.Heard> transcribe(
          byte[] audio, String contentType) {
        if (FAIL_TRANSCRIBE.get()) return java.util.Optional.empty();
        return com.waypoint.dispatch.messaging.domain.TranscriptText.heard("ටයරය පැලුණා, මහනුවර පාරේ", "si");
      }
    };
  }

  static final class MovableClock implements Clock {
    private volatile Instant now;

    @Override
    public Instant now() {
      return now == null ? Instant.now() : now;
    }

    void set(Instant instant) {
      now = instant;
    }

    void reset() {
      now = null;
    }
  }
}
