package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.TripReportQuery;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link TripReportQuery}, read as the asking person in its own transaction. */
@Component
public class TripReportReads implements TripReportQuery {
  private final Database database;
  private final JdbcThreadRepository threads;

  public TripReportReads(Database database, JdbcThreadRepository threads) {
    this.database = database;
    this.threads = threads;
  }

  @Override
  public Optional<TripReports> openReports(String tripId) {
    // A separate read as the same person: the caller is in its own module's transaction.
    return database.readAs(
        ModuleRole.MESSAGING,
        database.ambientActor().orElse(null),
        () -> threads.bySubject("trip", tripId).map(t -> {
          List<OpenReport> reports = threads.openReports(t.threadId()).stream().map(TripReportReads::report).toList();
          return new TripReports(t.depotCode(), t.vehicleId(), t.serviceDate(), reports);
        }));
  }

  private static OpenReport report(Map<String, Object> r) {
    return new OpenReport(
        (UUID) r.get("message_id"),
        ((Timestamp) r.get("created_at")).toInstant(),
        (String) r.get("report_type"),
        (String) r.get("author_role"),
        Optional.ofNullable((String) r.get("about_outlet")),
        Optional.ofNullable((UUID) r.get("source_issue_id")));
  }
}
