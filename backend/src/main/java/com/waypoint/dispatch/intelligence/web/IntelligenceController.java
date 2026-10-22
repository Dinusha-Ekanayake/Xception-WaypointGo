package com.waypoint.dispatch.intelligence.web;

import com.waypoint.dispatch.intelligence.application.AttentionQuery;
import com.waypoint.dispatch.intelligence.application.CapacityDecisionQuery;
import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityDecisionView;
import com.waypoint.dispatch.intelligence.application.DateOutlookQuery;
import com.waypoint.dispatch.intelligence.application.IntelligenceDataQuery;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionBoardView;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelVersionView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.DateOutlookView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.ForecastOverviewView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanPredictionsView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.SupplyProbabilityView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.TrainingDeliveryView;
import com.waypoint.dispatch.intelligence.contract.TravelAndServiceEstimator.DemandForecast;
import com.waypoint.dispatch.intelligence.application.KpiQuery;
import com.waypoint.dispatch.intelligence.contract.KpiViews;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading what Intelligence predicted. Registering, activating and retiring a
 * model are commands through {@code POST /api/commands}, never endpoints here.
 *
 * <p>Policy decides {@code ml:Read}; row-level security decides which depots
 * (effective access is policy AND scope). No read here calls a model.
 */
@RestController
@RequestMapping("/api/ml")
public class IntelligenceController {
  private final IntelligenceDataQuery intelligence;
  private final DateOutlookQuery outlook;
  private final AttentionQuery attention;
  private final CapacityDecisionQuery capacityDecisions;
  private final com.waypoint.dispatch.intelligence.application.ReplyRankingQuery replyRanking;
  private final com.waypoint.dispatch.intelligence.application.RiskReviewQuery riskReviews;
  private final KpiQuery kpis;
  private final RequestAuthorizer authorizer;
  private final com.waypoint.dispatch.intelligence.application.AiExplainer explainer;
  private final com.waypoint.dispatch.intelligence.application.PlaybookQuery playbooks;
  private final com.waypoint.dispatch.intelligence.application.TripOutlookQuery tripOutlook;
  private final com.waypoint.dispatch.intelligence.application.AiHealthQuery aiHealth;

  public IntelligenceController(
      IntelligenceDataQuery intelligence,
      DateOutlookQuery outlook,
      AttentionQuery attention,
      CapacityDecisionQuery capacityDecisions,
      KpiQuery kpis,
      com.waypoint.dispatch.intelligence.application.ReplyRankingQuery replyRanking,
      com.waypoint.dispatch.intelligence.application.RiskReviewQuery riskReviews,
      RequestAuthorizer authorizer,
      com.waypoint.dispatch.intelligence.application.AiExplainer explainer,
      com.waypoint.dispatch.intelligence.application.PlaybookQuery playbooks,
      com.waypoint.dispatch.intelligence.application.TripOutlookQuery tripOutlook,
      com.waypoint.dispatch.intelligence.application.AiHealthQuery aiHealth) {
    this.aiHealth = aiHealth;
    this.explainer = explainer;
    this.playbooks = playbooks;
    this.tripOutlook = tripOutlook;
    this.intelligence = intelligence;
    this.outlook = outlook;
    this.attention = attention;
    this.capacityDecisions = capacityDecisions;
    this.kpis = kpis;
    this.replyRanking = replyRanking;
    this.riskReviews = riskReviews;
    this.authorizer = authorizer;
  }

  /** What a screen asks the gateway to reword: its key and the explanation the rules already wrote. */
  public record ExplainRequest(String key, java.util.Map<String, Object> facts) {}

  /** {@code state} off, worded or rules_only; {@code text} the model's words when worded. */
  public record ExplainAnswer(String state, String text) {}

  /**
   * A plan's or an order's explanation in friendlier words, through the language
   * model gateway (roadmap AI-0, R-ML-10). Needs {@code plan:Read} on that plan;
   * off unless {@code AI_ENABLED=true}, and the screen keeps the rules' text.
   */
  @org.springframework.web.bind.annotation.PostMapping("/explain")
  public ExplainAnswer explain(
      @org.springframework.web.bind.annotation.RequestBody ExplainRequest body, HttpServletRequest request) {
    java.util.UUID planId = com.waypoint.dispatch.intelligence.application.AiExplainer.planOf(body.key());
    Actor actor = authorizer.require(request, "plan:Read", "wpt:plan:plan:" + planId);
    var worded = explainer.explain(actor, body.key(), body.facts());
    return new ExplainAnswer(worded.state(), worded.text().orElse(null));
  }

  /**
   * What on the depot's live trips needs the dispatcher, most urgent first, and
   * when the watch last looked (issue #268). Another depot is 403.
   */
  @GetMapping("/attention")
  public AttentionBoardView attention(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      HttpServletRequest request) {
    return attention.board(read(request, "wpt:ml:attention:" + depot), depot, date);
  }

  /** What the dispatcher decided about short weeks from this week on (roadmap T2-01), under the forecast's read. */
  /** Roadmap B-05: the depot's pilot KPIs, recorded and counted, newest week first. */
  @GetMapping("/kpis")
  public KpiViews.KpiReportView kpis(
      @RequestParam String depot, @RequestParam(defaultValue = "8") int weeks, HttpServletRequest request) {
    return kpis.weeks(read(request, "wpt:ml:kpi:" + depot), depot, weeks);
  }

  @GetMapping("/capacity/decisions")
  public List<CapacityDecisionView> capacityDecisions(@RequestParam String depot, HttpServletRequest request) {
    return capacityDecisions.fromThisWeek(read(request, "wpt:ml:forecast:" + depot), depot);
  }

  /**
   * R-ML-27: where the language models and Jev fail, for the administrator. Its
   * own action: it shows every depot's masked inputs, which ml:Read does not reach.
   */
  @GetMapping("/ai-health")
  public com.waypoint.dispatch.intelligence.contract.AiHealthViews.AiHealthView aiHealth(
      @RequestParam(defaultValue = "14") int days, HttpServletRequest request) {
    authorizer.require(request, com.waypoint.dispatch.intelligence.contract.AiHealthViews.READ, "wpt:ml:ai:*");
    return aiHealth.over(days);
  }

  @GetMapping("/models")
  public List<ModelVersionView> models(HttpServletRequest request) {
    return intelligence.models(read(request, "wpt:ml:model:*"));
  }

  /**
   * Roadmap MF-04: each model's predictions over the last finished weeks beside
   * what happened, and whether it beat the built-in estimate. Shown when an
   * administrator chooses a model.
   */
  @GetMapping("/models/backtest")
  public com.waypoint.dispatch.intelligence.contract.PredictionViews.BacktestView backtest(
      @RequestParam(required = false, defaultValue = "8") int weeks, HttpServletRequest request) {
    return intelligence.backtest(read(request, "wpt:ml:model:*"), weeks);
  }

  /**
   * Issue #12, R-ML-40: each night's comparison of the shadow with the model
   * that answered, newest first. Read before promoting a shadow; a rollback the
   * system made is on its review.
   */
  @GetMapping("/models/shadow-reviews")
  public List<com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowReviewView> shadowReviews(
      @RequestParam(required = false, defaultValue = "30") int limit, HttpServletRequest request) {
    return intelligence.shadowReviews(read(request, "wpt:ml:model:*"), limit);
  }

  /** Every stop's expected service minutes and P(late), and whether a model or the fallback answered. */
  @GetMapping("/plans/{planId}/predictions")
  public PlanPredictionsView predictions(@PathVariable UUID planId, HttpServletRequest request) {
    return intelligence.predictionsFor(read(request, "wpt:ml:plan:" + planId), planId);
  }

  /** The newest forecast per ISO week, {@code from} and {@code to} as {@code 2026-W41}. */
  @GetMapping("/forecast")
  public List<DemandForecast> forecast(
      @RequestParam String depot,
      @RequestParam String brand,
      @RequestParam String from,
      @RequestParam String to,
      HttpServletRequest request) {
    Actor actor = read(request, "wpt:ml:forecast:" + depot);
    return intelligence.forecast(actor, depot, brand, week(from, "from"), week(to, "to"));
  }

  /** Roadmap T2-03: how the last finished weeks' forecasts did against what was ordered. */
  @GetMapping("/forecast/accuracy")
  public com.waypoint.dispatch.intelligence.contract.PredictionViews.ForecastAccuracyView forecastAccuracy(
      @RequestParam String depot,
      @RequestParam(required = false, defaultValue = "4") int weeks,
      HttpServletRequest request) {
    return intelligence.forecastAccuracy(read(request, "wpt:ml:forecast:" + depot), depot, weeks);
  }

  /**
   * The Forecast screen: the next {@code weeks} weeks for a depot, all brands,
   * with the calendar and fleet capacity alongside. A depot outside scope is 403.
   */
  @GetMapping("/forecast/overview")
  public ForecastOverviewView forecastOverview(
      @RequestParam String depot,
      @RequestParam(required = false, defaultValue = "10") int weeks,
      HttpServletRequest request) {
    return intelligence.forecastOverview(read(request, "wpt:ml:forecast:" + depot), depot, weeks);
  }

  /**
   * How likely each day is to be kept, for a store choosing one ahead (issue #224,
   * R-ML-07). Advice from the depot's totals; another outlet is 403.
   */
  @GetMapping("/outlook")
  public DateOutlookView outlook(
      @RequestParam String outlet,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      HttpServletRequest request) {
    Actor actor = authorizer.require(request, DateOutlookQuery.READ, "wpt:ml:outlet:" + outlet);
    return outlook.outlook(actor, outlet, from, to);
  }

  /** R-RCP-06. */
  @GetMapping("/orders/{orderId}/supply-probability")
  public SupplyProbabilityView supplyProbability(@PathVariable UUID orderId, HttpServletRequest request) {
    return intelligence.supplyProbability(read(request, "wpt:ml:order:" + orderId), orderId);
  }

  /** Delivery actuals for retraining, wait kept apart from service (EXE-18). */
  @GetMapping("/training/deliveries")
  public Page<TrainingDeliveryView> trainingDeliveries(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    Actor actor = authorizer.require(request, IntelligenceDataQuery.EXPORT, "wpt:ml:training:" + depot);
    return intelligence.trainingDeliveries(actor, depot, from, to, Optional.ofNullable(after), limit);
  }

  /** Issue #7: every order of a depot as demand, for retraining the demand forecast. */
  @GetMapping("/training/orders")
  public Page<com.waypoint.dispatch.intelligence.contract.PredictionViews.TrainingOrderView> trainingOrders(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    Actor actor = authorizer.require(request, IntelligenceDataQuery.EXPORT, "wpt:ml:training:" + depot);
    return intelligence.trainingOrders(actor, depot, from, to, Optional.ofNullable(after), limit);
  }

  /** Roadmap MF-05: scored routes as the model read them, with their outcomes, for retraining. */
  @GetMapping("/training/routes")
  public Page<com.waypoint.dispatch.intelligence.contract.PredictionViews.TrainingRouteView> trainingRoutes(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    Actor actor = authorizer.require(request, IntelligenceDataQuery.EXPORT, "wpt:ml:training:" + depot);
    return intelligence.trainingRoutes(actor, depot, from, to, Optional.ofNullable(after), limit);
  }

  /**
   * Issue #12, R-ML-38: the outlets, service allowances, traffic speed and
   * calendar a retraining run reads, as a scoring request builds them.
   */
  @GetMapping("/training/reference")
  public Map<String, Object> trainingReference(
      @RequestParam List<String> depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      HttpServletRequest request) {
    Actor actor = null;
    for (String d : depot) {
      actor = authorizer.require(request, IntelligenceDataQuery.EXPORT, "wpt:ml:training:" + d);
    }
    if (actor == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "name at least one depot");
    }
    return intelligence.trainingReference(actor, depot, from, to);
  }

  /** T1-01: a draft's stops likely to be late, each with the dispatcher's latest keep or swap. */
  @GetMapping("/plans/{planId}/risk-review")
  public com.waypoint.dispatch.intelligence.contract.RiskReviewViews.RiskReviewReport riskReview(
      @PathVariable UUID planId, HttpServletRequest request) {
    Actor actor = authorizer.require(request, "plan:Read", "wpt:plan:plan:" + planId);
    return riskReviews.report(actor, planId);
  }

  /** AI-J5: the ready replies' learned order for the composer, or "rules only" while there is too little to learn from. */
  @GetMapping("/reply-ranking")
  public com.waypoint.dispatch.intelligence.application.ReplyRankingQuery.ReplyRankingView replyRanking(
      HttpServletRequest request) {
    return replyRanking.ranking(read(request, "wpt:ml:replies:*"));
  }

  /** Issue #269: every situation's steps and ready message, for Live's "Needs you" and the trip's chat. */
  @GetMapping("/playbooks")
  public java.util.List<com.waypoint.dispatch.intelligence.contract.PlaybookViews.PlaybookView> playbooks(
      HttpServletRequest request) {
    read(request, "wpt:ml:playbooks:*");
    return playbooks.catalogue();
  }

  /**
   * The decisions to suggest for one stop in a situation (a situation's name or
   * a report type), checked against the rules, most likely first. Advice only.
   */
  @GetMapping("/decision-suggestions")
  public com.waypoint.dispatch.intelligence.contract.PlaybookViews.SuggestionsView decisionSuggestions(
      @org.springframework.web.bind.annotation.RequestParam java.util.UUID deliveryId,
      @org.springframework.web.bind.annotation.RequestParam String situation,
      HttpServletRequest request) {
    return playbooks.forStop(read(request, "wpt:ml:delivery:" + deliveryId), deliveryId, situation);
  }

  /**
   * One trip's decision outlook (R-ML-34): the chance of each decision about the
   * trip and of each outcome for every stop still waiting, from its open reports.
   * Advice only.
   */
  @GetMapping("/trip-outlook")
  public com.waypoint.dispatch.intelligence.contract.PlaybookViews.TripOutlookView tripOutlook(
      @org.springframework.web.bind.annotation.RequestParam String tripId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Integer extraMinutes,
      HttpServletRequest request) {
    if (extraMinutes != null && (extraMinutes < 0 || extraMinutes > 240)) {
      throw new com.waypoint.dispatch.shared.error.DomainException(
          com.waypoint.dispatch.shared.error.ErrorCode.VALIDATION_FAILED, "extraMinutes is 0 to 240");
    }
    return tripOutlook.forTrip(read(request, "wpt:ml:trip:" + tripId), tripId, java.util.Optional.ofNullable(extraMinutes));
  }

  private Actor read(HttpServletRequest request, String resource) {
    return authorizer.require(request, IntelligenceDataQuery.READ, resource);
  }

  /** {@code 2026-W41} as {@code 202641}, the key the forecast table sorts on. */
  static int week(String text, String field) {
    var m = java.util.regex.Pattern.compile("(\\d{4})-W(\\d{1,2})").matcher(text == null ? "" : text.trim());
    if (!m.matches() || Integer.parseInt(m.group(2)) < 1 || Integer.parseInt(m.group(2)) > 53) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be an ISO week such as 2026-W41");
    }
    return Integer.parseInt(m.group(1)) * 100 + Integer.parseInt(m.group(2));
  }
}
