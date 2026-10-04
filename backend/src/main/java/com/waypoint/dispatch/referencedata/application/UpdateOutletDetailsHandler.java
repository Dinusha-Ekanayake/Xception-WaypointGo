package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.audit.AuditContext;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.domain.DeliveryWindow;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.OutletDetails;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A store manager changing what their store says about itself (R-REF-01): its
 * delivery window, its dock type and its contacts.
 *
 * <p>The payload is the details as they should be, so a field left blank goes back
 * to the published value or clears the contact. The change is checked against the
 * outlet as the published version has it, written to {@code ref.outlet_details},
 * and the snapshot republished so the next plan, the run sheet and the loading
 * manifest all read it. A published plan is immutable and does not change.
 *
 * <p>Scope is checked through Identity's contract before anything is written:
 * the reference role cannot read the scope tables (D-B, R-IAM-28). The audit row
 * names what changed but carries no contact, which may be a person's number.
 */
@Component
public class UpdateOutletDetailsHandler implements CommandHandler {
  static final String ACTION = "reference:UpdateOutletDetails";

  private final Database database;
  private final ReferenceQuery reference;
  private final ReferenceVersionReader reader;
  private final ReferenceCache cache;
  private final ReferenceScope scope;
  private final AuditLog audit;
  private final Metrics metrics;

  public UpdateOutletDetailsHandler(
      Database database,
      ReferenceQuery reference,
      ReferenceVersionReader reader,
      ReferenceCache cache,
      ReferenceScope scope,
      AuditLog audit,
      Metrics metrics) {
    this.database = database;
    this.reference = reference;
    this.reader = reader;
    this.cache = cache;
    this.scope = scope;
    this.audit = audit;
    this.metrics = metrics;
  }

  @Override
  public String kind() {
    return ACTION;
  }

  @Override
  public String action() {
    return ACTION;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.REF;
  }

  @Override
  public String resource(Command command) {
    String outletId = CommandPayload.of(command).text("outletId");
    return outletId == null ? null : "wpt:ref:outlet:" + outletId;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String outletId = payload.requiredText("outletId");
    String resource = "wpt:ref:outlet:" + outletId;
    Outlet published =
        reader
            .publishedOutlet(outletId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId));
    String depotCode = reference.outlet(outletId, null).map(o -> o.depotCode()).orElse(null);
    scope.requireOutletChange(actor, ACTION, resource, outletId, depotCode);

    OutletDetails next =
        OutletDetails.of(
            published,
            time(payload, "windowOpen"),
            time(payload, "windowClose"),
            payload.text("dockType"),
            payload.text("contactName"),
            payload.text("contactPhone"),
            payload.text("receivingNotes"));

    Map<String, Object> current =
        database.queryOne(
            "SELECT window_open, window_close, dock_type, row_version FROM ref.outlet_details WHERE outlet_id = ?",
            outletId);
    Long expected = command.expectedVersion();
    long version;
    if (current == null) {
      // No details yet: the first save names version 0, or none at all.
      if (expected != null && expected != 0) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT, "Outlet " + outletId + " has no details yet, not version " + expected);
      }
      database.update(
          """
          INSERT INTO ref.outlet_details
              (outlet_id, window_open, window_close, dock_type, contact_name, contact_phone,
               receiving_notes, row_version, updated_by)
          VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
          """,
          outletId,
          next.window().map(w -> java.sql.Time.valueOf(w.open())).orElse(null),
          next.window().map(w -> java.sql.Time.valueOf(w.close())).orElse(null),
          next.dockType().map(d -> d.code()).orElse(null),
          next.contactName(),
          next.contactPhone(),
          next.receivingNotes(),
          actor.userId());
      version = 1;
    } else {
      long actual = ((Number) current.get("row_version")).longValue();
      if (expected == null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required to change outlet " + outletId);
      }
      if (actual != expected) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT, "Outlet " + outletId + " details are at version " + actual + ", not " + expected);
      }
      database.updateExpectingOneRow(
          """
          UPDATE ref.outlet_details
             SET window_open = ?, window_close = ?, dock_type = ?, contact_name = ?, contact_phone = ?,
                 receiving_notes = ?, row_version = row_version + 1, updated_by = ?, updated_at = now()
           WHERE outlet_id = ? AND row_version = ?
          """,
          next.window().map(w -> java.sql.Time.valueOf(w.open())).orElse(null),
          next.window().map(w -> java.sql.Time.valueOf(w.close())).orElse(null),
          next.dockType().map(d -> d.code()).orElse(null),
          next.contactName(),
          next.contactPhone(),
          next.receivingNotes(),
          actor.userId(),
          outletId,
          actual);
      version = actual + 1;
    }

    // The before state for the audit trail: what planning read, never the contacts.
    Outlet before = current == null ? published : beforeOf(published, current);
    Map<String, Object> was = new LinkedHashMap<>();
    was.put("window", before.window().open() + "-" + before.window().close());
    was.put("dockType", before.dockType().code());
    AuditContext.before(was);

    // Reference is read from memory, so the snapshot is rebuilt for the change to
    // be seen at all, inside this transaction as the calendar override does.
    cache.loadedVersionId().flatMap(reader::load).ifPresent(cache::publish);

    Outlet after = next.applyTo(published);
    boolean windowChanged = !after.window().equals(before.window());
    boolean dockChanged = after.dockType() != before.dockType();
    audit.record(
        AuditEntry.allowed(
            actor.userId(),
            actor.deviceId(),
            ACTION,
            resource,
            "window " + after.window().open() + "-" + after.window().close() + ", dock " + after.dockType().code()
                + "; contacts reviewed"));
    metrics.increment(
        "waypoint.reference.outlet_details_changed",
        "window",
        String.valueOf(windowChanged),
        "dock",
        String.valueOf(dockChanged));

    return Map.of("outletId", outletId, "rowVersion", version);
  }

  private static Outlet beforeOf(Outlet published, Map<String, Object> row) {
    DeliveryWindow window =
        row.get("window_open") == null
            ? published.window()
            : new DeliveryWindow(
                ((java.sql.Time) row.get("window_open")).toLocalTime(),
                ((java.sql.Time) row.get("window_close")).toLocalTime());
    return new Outlet(
        published.id(),
        published.brandCode(),
        published.districtName(),
        row.get("dock_type") == null
            ? published.dockType()
            : com.waypoint.dispatch.referencedata.domain.DockType.parse((String) row.get("dock_type")),
        published.parkingConstraint(),
        window,
        published.mallWindow());
  }

  private static LocalTime time(CommandPayload payload, String field) {
    String value = payload.text(field);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be a time as hh:mm, not " + value, List.of(OutletDetails.RULE));
    }
  }
}
