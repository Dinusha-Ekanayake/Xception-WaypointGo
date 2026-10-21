package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.PositionSignals;
import com.waypoint.dispatch.execution.application.PositionsQuery;
import com.waypoint.dispatch.platform.web.LiveStreamPart;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Vehicle positions as a live part: {@code positions} with the latest fixes of a
 * depot's vehicles, or of the vehicle coming to an outlet, on a day. Exactly one
 * of {@code depot} or {@code outlet}, and {@code date}; checked by policy and
 * scope, and read once, before anything is sent.
 */
@Component
public class PositionsStreamPart implements LiveStreamPart {
  private final PositionsQuery positions;
  private final PositionSignals signals;
  private final RequestAuthorizer authorizer;

  public PositionsStreamPart(PositionsQuery positions, PositionSignals signals, RequestAuthorizer authorizer) {
    this.positions = positions;
    this.signals = signals;
    this.authorizer = authorizer;
  }

  @Override
  public String name() {
    return "positions";
  }

  @Override
  public Runnable open(HttpServletRequest request, String prefix, Sink sink) {
    String depot = request.getParameter(prefix + "depot");
    String outlet = request.getParameter(prefix + "outlet");
    if ((depot == null) == (outlet == null)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give exactly one of depot or outlet");
    }
    LocalDate date;
    try {
      date = LocalDate.parse(String.valueOf(request.getParameter(prefix + "date")));
    } catch (DateTimeParseException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "date is a day, such as 2026-10-21");
    }
    PositionSignals.Watch watch;
    Actor actor;
    if (depot != null) {
      actor = authorizer.require(request, PositionsController.READ, "wpt:execution:depot:" + depot);
      positions.ofDepot(actor, depot, date);
      watch = PositionSignals.Watch.depot(depot, date);
    } else {
      actor = authorizer.require(request, PositionsController.READ, "wpt:execution:outlet:" + outlet);
      positions.forOutlet(actor, outlet, date);
      watch = PositionSignals.Watch.outlet(outlet, date);
    }
    return signals.listen(actor, watch, list -> sink.send("positions", list));
  }
}
