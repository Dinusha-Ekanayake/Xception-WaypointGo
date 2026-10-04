package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.DemoDayQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DemoDayDataQuery implements DemoDayQuery {
  private final Database db;
  public DemoDayDataQuery(Database db) { this.db = db; }
  @Override public Optional<Instant> latestClose() {
    return db.readAs(ModuleRole.ORDERING, db.ambientActor().orElse(null), () -> {
      var r = db.queryOne("SELECT max(closed_at) AS at FROM ordering.day_closures");
      return Optional.ofNullable((Timestamp) r.get("at")).map(Timestamp::toInstant);
    });
  }
  @Override public boolean empty(String depot, LocalDate day) {
    return db.readAs(ModuleRole.ORDERING, db.ambientActor().orElse(null), () -> db.queryOne(
        "SELECT 1 FROM ordering.orders WHERE depot_code=? AND delivery_date=? LIMIT 1", depot, Date.valueOf(day)) == null
        && db.queryOne("SELECT 1 FROM ordering.day_closures WHERE depot_code=? AND service_date=?", depot, Date.valueOf(day)) == null);
  }
}
