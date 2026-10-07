package com.waypoint.dispatch.messaging.application;

import java.util.Optional;

/** Text to speech on our own server (R-MSG-10): audio, or nothing when it is off or fails. */
public interface SpeechSynthesis {
  boolean enabled();

  /** AAC audio in an MP4 container for the text in the language (en, si, ta); empty on any failure. */
  Optional<byte[]> speak(String text, String lang);
}
