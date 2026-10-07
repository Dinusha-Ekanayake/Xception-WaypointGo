package com.waypoint.dispatch.intelligence.application;

import java.util.Optional;

/**
 * A language model behind the gateway (roadmap AI-0): the one port every use of
 * one goes through. It answers text or nothing; it never decides, fills a slot,
 * or sends anything on its own.
 */
public interface LanguageModel {
  /** The provider and pinned model, for the record of the call. */
  String provider();

  String model();

  /** One answer to a fixed instruction and the masked facts; empty on any failure. */
  Optional<String> complete(String instruction, String facts);
}
