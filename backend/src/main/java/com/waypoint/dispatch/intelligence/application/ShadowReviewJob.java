package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.ModelViews;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.intelligence.contract.PredictionViews;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy;
import com.waypoint.dispatch.intelligence.domain.ShadowReview;
import com.waypoint.dispatch.intelligence.domain.ShadowReview.Result;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Each night, the late-risk shadow against the model that answered (issue #12,
 * R-ML-40), and the one move the system makes on its own (R-ML-42).
 *
 * <p>The review reads the stops of the last {@link ShadowReview#WINDOW_DAYS}
 * service days, from the day the model became shadow, that have a label, a
 * shadow prediction and a prediction by the active model (or the deterministic
 * one when none is active). It runs after the outcomes and the day's metrics.
 *
 * <p>When the shadow is the model a promotion replaced, the promotion was within
 * {@link ShadowReview#ROLLBACK_WINDOW}, and the shadow did better on both
 * scores, the system puts it back: it becomes active and the newer model returns
 * to registered, in this job's transaction, version-guarded, with a model event
 * and an audit row naming the review. A candidate is never activated here; that
 * is an administrator's decision (R-ML-41).
 */
@Component
public class ShadowReviewJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(ShadowReviewJob.class);

  private final Database database;
  private final JdbcIntelligenceRepository repository;
  private final AuditLog audit;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();
  /** The last verdict, as a gauge: 1 better, 0 not enough or mixed, -1 worse, NaN with no shadow. */
  private final AtomicReference<Double> verdict = new AtomicReference<>(Double.NaN);

  ShadowReviewJob(Database database, JdbcIntelligenceRepository repository, AuditLog audit, Metrics metrics) {
    this.database = database;
    this.repository = repository;
    this.audit = audit;
    this.metrics = metrics;
    metrics.gauge("waypoint.ml.shadow_verdict", verdict::get, "kind", ModelViews.DELIVERY_RISK);
  }

  @Override
  public String name() {
    return "intelligence.shadow-review";
  }

  @Override
  public String cron() {
    return "0 10 4 * * *";
  }

  @Override
  public boolean onBusinessClock() {
    return true;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** What one night decided, for tests and the job's log. */
  public record Outcome(String shadowLabel, String incumbentLabel, Result result, boolean rolledBack) {}

  /** @return the review written, or empty with no shadow or no day to read yet */
  public Optional<Outcome> runAt(Instant now) {
    return database.asSystem(ModuleRole.ML, () -> review(now));
  }

  private Optional<Outcome> review(Instant now) {
    String kind = ModelViews.DELIVERY_RISK;
    Optional<ModelRow> found = repository.shadowModel(kind);
    if (found.isEmpty()) {
      verdict.set(Double.NaN);
      return Optional.empty();
    }
    ModelRow shadow = found.get();
    Optional<ModelRow> active = repository.activeModel(kind);
    String label = shadow.view().label();
    String incumbent = active.map(m -> m.view().label()).orElse(PredictionViews.DETERMINISTIC);
    ShadowRole role = shadow.view().shadowRole().orElseThrow();
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate shadowedOn = shadow.view().shadowedAt().orElse(now).atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate from = ShadowReview.windowFrom(today, shadowedOn);
    LocalDate to = today.minusDays(1);
    if (from.isAfter(to)) {
      return Optional.empty();
    }

    Result result = ShadowReview.judge(repository.shadowPairs(label, incumbent, from, to));
    java.util.UUID reviewId = repository.saveReview(UuidV7.generate(now, random), kind, shadow.id(), label, role,
        incumbent, today, from, to, result, now);
    verdict.set(switch (result.verdict()) {
      case BETTER -> 1.0;
      case WORSE -> -1.0;
      default -> 0.0;
    });
    metrics.increment("waypoint.ml.shadow_review", "verdict", result.verdict().name().toLowerCase(Locale.ROOT),
        "role", role.name().toLowerCase(Locale.ROOT));

    boolean rollBack = active.isPresent()
        && ShadowReview.rollBack(role, result.verdict(), active.get().view().activatedAt(), now);
    if (rollBack) {
      rollBack(kind, active.get(), shadow, reviewId, result, now);
    }
    return Optional.of(new Outcome(label, incumbent, result, rollBack));
  }

  private void rollBack(String kind, ModelRow active, ModelRow shadow, java.util.UUID reviewId, Result r, Instant now) {
    String reason = String.format(Locale.ROOT,
        "rolled back: on %d labelled stops %s scored Brier %.4f and log loss %.4f against %.4f and %.4f for %s",
        r.stops(), shadow.view().label(), r.shadowBrier().orElseThrow(), r.shadowLogLoss().orElseThrow(),
        r.incumbentBrier().orElseThrow(), r.incumbentLogLoss().orElseThrow(), active.view().label());
    List<ModelRow> standing = repository.standingOf(kind);
    var moves = ModelRegistryPolicy.rollBack(RegistryMoves.standing(active), RegistryMoves.standing(shadow));
    RegistryMoves.apply(repository, standing, moves, shadow.id(), shadow.view().rowVersion(), Actor.SYSTEM_ID, now,
        "rolled_back", Optional.of(reason), Optional.of(reviewId));
    repository.markRolledBack(reviewId);
    audit.record(AuditEntry.allowed(Actor.SYSTEM_ID, null, ModelViews.ACTIVATE,
        "wpt:ml:model:" + shadow.view().label(), reason).withTargetFromResource());
    metrics.increment("waypoint.ml.model_rolled_back", "kind", kind);
    log.warn("Model {} rolled back to {}: {}", active.view().label(), shadow.view().label(), reason);
  }
}
