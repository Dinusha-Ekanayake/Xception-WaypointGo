package com.waypoint.dispatch.intelligence.application;

import java.util.Map;
import java.util.Optional;

/**
 * A typed decision model behind the gateway (roadmap AI-0, Jev): it picks one of
 * the options code allows and says how sure it is; it writes no text. Its answer
 * is advice that code consumes, and in shadow it changes nothing at all.
 */
public interface DecisionPort {
  /** One choice, with its confidence and every option's probability. */
  record Choice(String option, double confidence, Map<String, Double> probabilities, String model) {}

  /**
   * @param state the masked text to decide about
   * @param instructions the question, fixed by code
   * @param options each option's key and what it means, in the order they are offered
   */
  Optional<Choice> choose(String state, String instructions, Map<String, String> options);
}
