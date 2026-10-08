package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.infrastructure.JdbcExecutionReads;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery.PhotoView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The photo of the driver bringing a delivery (FF-07), so the store knows who is
 * at its door. Only on the day of the delivery and only to someone who can read
 * the delivery: row-level security decides that, as for the delivery itself.
 */
@Component
public class DriverPhotos {
  private final Database database;
  private final JdbcExecutionReads reads;
  private final IdentityQuery identity;
  private final ProfilePhotoQuery photos;
  private final Metrics metrics;
  private final Clock clock;

  public DriverPhotos(
      Database database,
      JdbcExecutionReads reads,
      IdentityQuery identity,
      ProfilePhotoQuery photos,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.reads = reads;
    this.identity = identity;
    this.photos = photos;
    this.metrics = metrics;
    this.clock = clock;
  }

  public PhotoView forDelivery(Actor actor, UUID deliveryId) {
    Map<String, Object> record =
        database.readAs(ModuleRole.EXECUTION, actor.userId(), () -> reads.record(deliveryId));
    if (record == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No delivery " + deliveryId + " within your scope");
    }
    LocalDate serviceDate = ((Date) record.get("service_date")).toLocalDate();
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    if (!serviceDate.equals(today)) {
      metrics.increment("waypoint.execution.driver_photo", "outcome", "not_today");
      throw new DomainException(ErrorCode.NOT_FOUND, "The driver's photo is shown on the day of the delivery only");
    }
    UUID driver =
        identity.driverOn((String) record.get("vehicle_id"), serviceDate)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No driver is assigned to this delivery"));
    PhotoView photo =
        photos.photoOf(driver).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "The driver has no photo"));
    metrics.increment("waypoint.execution.driver_photo", "outcome", "read");
    return photo;
  }
}
