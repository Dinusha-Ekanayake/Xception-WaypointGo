package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What a store has said about itself (R-REF-01), as it said it.
 *
 * <p>A null window or dock means the published value stands: the outlet read
 * already shows the window and dock in force. {@code rowVersion} is 0 until the
 * store first saves, and is what a change names as its {@code expectedVersion}.
 */
@Component
public class OutletDetailsQuery {
  private final Database database;

  public OutletDetailsQuery(Database database) {
    this.database = database;
  }

  public record OutletDetailsView(
      String outletId,
      LocalTime windowOpen,
      LocalTime windowClose,
      String dockType,
      String contactName,
      String contactPhone,
      String receivingNotes,
      long rowVersion,
      Instant updatedAt) {}

  public OutletDetailsView of(String outletId) {
    Map<String, Object> r =
        database.asModule(
            ModuleRole.REF,
            null,
            () ->
                database.queryOne(
                    """
                    SELECT outlet_id, window_open, window_close, dock_type, contact_name, contact_phone,
                           receiving_notes, row_version, updated_at
                    FROM ref.outlet_details WHERE outlet_id = ?
                    """,
                    outletId));
    if (r == null) {
      return new OutletDetailsView(outletId, null, null, null, null, null, null, 0, null);
    }
    return new OutletDetailsView(
        outletId,
        time(r.get("window_open")),
        time(r.get("window_close")),
        (String) r.get("dock_type"),
        (String) r.get("contact_name"),
        (String) r.get("contact_phone"),
        (String) r.get("receiving_notes"),
        ((Number) r.get("row_version")).longValue(),
        instant(r.get("updated_at")));
  }

  private static LocalTime time(Object value) {
    return value == null ? null : ((java.sql.Time) value).toLocalTime();
  }

  private static Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    return null;
  }
}
