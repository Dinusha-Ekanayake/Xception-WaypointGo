package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.domain.TranscriptText.Heard;
import java.util.Optional;

/** Voice note to text (roadmap FF-05): what was heard, or nothing when the engine is off or fails. */
public interface Transcription {
  boolean enabled();

  /** The engine's name, kept with each transcript. */
  String engine();

  Optional<Heard> transcribe(byte[] audio, String contentType);
}
