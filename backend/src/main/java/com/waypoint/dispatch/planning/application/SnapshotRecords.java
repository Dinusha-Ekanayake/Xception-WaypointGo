package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotKind;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotView;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.SnapshotRow;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Saved plans. A draft is written down as the dispatcher sees it and never
 * touched again, so looking back at an earlier plan, returning to it and
 * comparing two are all reads of something that cannot have changed.
 */
@Component
class SnapshotRecords {
  private final JdbcPlanRepository plans;
  private final PlanDataQuery view;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  SnapshotRecords(JdbcPlanRepository plans, PlanDataQuery view, ObjectMapper json) {
    this.plans = plans;
    this.view = view;
    this.json = json;
  }

  /** Stores {@code draft} as it stands. The label defaults to its kind and number. */
  SnapshotRow save(RunRow draft, SnapshotKind kind, Optional<String> label, UUID actor, Instant now) {
    int number = plans.nextSnapshotNumber(draft.depotCode(), draft.serviceDate());
    String text = label.map(String::trim).filter(l -> !l.isEmpty()).orElse(defaultLabel(kind, number));
    SnapshotRow row =
        new SnapshotRow(
            UuidV7.generate(now, random),
            draft.depotCode(),
            draft.serviceDate(),
            number,
            text.length() > 120 ? text.substring(0, 120) : text,
            kind.name().toLowerCase(Locale.ROOT),
            draft.planId(),
            draft.planVersion(),
            write(draft),
            actor,
            now);
    plans.insertSnapshot(row);
    return row;
  }

  /**
   * Stores a plan that was never a draft of its own, such as the rules plan the
   * cost stage replaced. It is kept under the draft it was made beside, so it is
   * compared and restored like any other saved plan.
   */
  SnapshotRow saveView(PlanView plan, RunRow beside, SnapshotKind kind, UUID actor, Instant now) {
    int number = plans.nextSnapshotNumber(beside.depotCode(), beside.serviceDate());
    String payload;
    try {
      payload = json.writeValueAsString(plan);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("plan beside " + beside.planId() + " cannot be saved", e);
    }
    SnapshotRow row =
        new SnapshotRow(
            UuidV7.generate(now, random),
            beside.depotCode(),
            beside.serviceDate(),
            number,
            defaultLabel(kind, number),
            kind.name().toLowerCase(Locale.ROOT),
            beside.planId(),
            beside.planVersion(),
            payload,
            actor,
            now);
    plans.insertSnapshot(row);
    return row;
  }

  static SnapshotView view(SnapshotRow row) {
    return new SnapshotView(
        row.snapshotId(),
        row.depotCode(),
        row.serviceDate(),
        row.number(),
        row.label(),
        SnapshotKind.valueOf(row.kind().toUpperCase(Locale.ROOT)),
        row.sourcePlanId(),
        row.planVersion(),
        row.createdBy(),
        row.createdAt());
  }

  private static String defaultLabel(SnapshotKind kind, int number) {
    return switch (kind) {
      case AUTO -> "Auto plan";
      case MANUAL -> "Snapshot " + number;
      case REGENERATED -> "Before regenerate (snapshot " + number + ")";
      case RULES -> "Rules plan";
    };
  }

  private String write(RunRow draft) {
    try {
      return json.writeValueAsString(view.draftView(draft));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("plan " + draft.planId() + " cannot be saved", e);
    }
  }
}
