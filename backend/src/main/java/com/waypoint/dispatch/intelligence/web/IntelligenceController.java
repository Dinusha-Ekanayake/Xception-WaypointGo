package com.waypoint.dispatch.intelligence.web;

import com.waypoint.dispatch.intelligence.application.AttentionQuery;
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
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
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
  private final RequestAuthorizer authorizer;

  public IntelligenceController(
      IntelligenceDataQuery intelligence,
      DateOutlookQuery outlook,
      AttentionQuery attention,
      RequestAuthorizer authorizer) {
    this.intelligence = intelligence;
    this.outlook = outlook;
    this.attention = attention;
    this.authorizer = authorizer;
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

  @GetMapping("/models")
  public List<ModelVersionView> models(HttpServletRequest request) {
    return intelligence.models(read(request, "wpt:ml:model:*"));
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
