package com.waypoint.dispatch.messaging.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Intelligence may ask Messaging about one trip's reports, for the trip's
 * decision outlook (decision suggestions part 2): the reports not yet resolved,
 * with the trip's depot, vehicle and day. Read as the asking person, so
 * row-level security keeps it to the threads they can read; empty when they
 * cannot read this trip's thread. No words of a report cross: only its type,
 * who wrote it and the stop it names.
 */
public interface TripReportQuery {

  Optional<TripReports> openReports(String tripId);

  record TripReports(String depotCode, Optional<String> vehicleId, Optional<LocalDate> serviceDate, List<OpenReport> reports) {
    public TripReports {
      reports = List.copyOf(reports);
    }
  }

  /** @param aboutOutletId the stop the report names; for a store's own report, its store */
  record OpenReport(UUID messageId, Instant at, String reportType, String authorRole, Optional<String> aboutOutletId) {}
}
