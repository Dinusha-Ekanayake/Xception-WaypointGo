package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.ReplyRanker;
import com.waypoint.dispatch.messaging.contract.ReplyUseQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The ready replies' order learned from the last four weeks (AI-J5), for the
 * dispatcher's composer. Read as the dispatcher, so it learns only from the
 * threads they can read. When Messaging cannot answer, the ranking is untrained
 * and the screen says "rules only" (rule 9).
 */
@Component
public class ReplyRankingQuery {
  static final Duration WINDOW = Duration.ofDays(28);

  private final Database database;
  private final ReplyUseQuery replies;
  private final Metrics metrics;
  private final Clock clock;

  public ReplyRankingQuery(Database database, ReplyUseQuery replies, Metrics metrics, Clock clock) {
    this.database = database;
    this.replies = replies;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * @param minPosts the posts needed before the order is learned
   * @param unavailable Messaging did not answer, so nothing was learned
   */
  public record ReplyRankingView(
      boolean trained,
      boolean unavailable,
      int posts,
      int minPosts,
      double topAcceptance,
      Map<String, Double> scores,
      List<Double> placeEffect,
      Instant from,
      Instant to) {}

  public ReplyRankingView ranking(Actor actor) {
    Instant to = clock.now();
    Instant from = to.minus(WINDOW);
    try {
      // Inside a read as the dispatcher, so the contract reads as the same person.
      List<ReplyRanker.Choice> choices =
          database.readAs(
              ModuleRole.ML,
              actor.userId(),
              () -> replies.choices(from, to).stream()
                  .map(c -> new ReplyRanker.Choice(c.shown(), c.rank(), c.explored()))
                  .toList());
      ReplyRanker.Ranking r = ReplyRanker.fit(choices);
      metrics.increment("waypoint.ml.reply_ranking", "outcome", r.trained() ? "trained" : "rules_only");
      return new ReplyRankingView(
          r.trained(), false, r.posts(), ReplyRanker.MIN_POSTS, r.topAcceptance(), r.scores(), r.placeEffect(), from, to);
    } catch (RuntimeException e) {
      metrics.increment("waypoint.ml.reply_ranking", "outcome", "unavailable");
      return new ReplyRankingView(false, true, 0, ReplyRanker.MIN_POSTS, 0, Map.of(), List.of(), from, to);
    }
  }
}
