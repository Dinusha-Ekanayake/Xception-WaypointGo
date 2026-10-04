package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.AttentionPolicy;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Finding;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.StopFacts;
import com.waypoint.dispatch.intelligence.domain.AttentionThresholds;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * How the attention watch decides whether a stop needs the dispatcher (issue
 * #268). The built-in scorer is the rules in {@link AttentionPolicy}, which is
 * the one definition and always the fallback.
 *
 * <p>This is the single place another way of ranking would plug in. Anything
 * that does must keep to what the rules allow: a finding of a kind the rules
 * know, explainable from the stop's own facts, and the rules' answer whenever
 * it cannot say.
 */
public interface AttentionScorer {
  Optional<Finding> score(StopFacts stop, AttentionThresholds thresholds, Instant now);

  /** The rules, as written. */
  @Component
  class Rules implements AttentionScorer {
    @Override
    public Optional<Finding> score(StopFacts stop, AttentionThresholds thresholds, Instant now) {
      return AttentionPolicy.assess(stop, thresholds, now);
    }
  }
}
