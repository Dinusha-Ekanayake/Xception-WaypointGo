package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Who to call about an outlet's order (R-IAM-41): its active store managers,
 * each with the name and phone they keep on their own profile (R-IAM-32). Only
 * for an outlet in a depot the caller holds; any other outlet, or one that does
 * not exist, is the same 404, so neither is disclosed. Never logged.
 */
@Component
public class StoreContactQuery {
  /** @param phone empty when the manager has not added one */
  public record StoreContact(String name, Optional<String> phone) {}

  private final Database database;
  private final ReferenceQuery reference;

  public StoreContactQuery(Database database, ReferenceQuery reference) {
    this.database = database;
    this.reference = reference;
  }

  public List<StoreContact> of(Actor actor, String outletId) {
    var outlet = reference.outlet(outletId, null)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such outlet"));
    return database.readAs(ModuleRole.IAM, actor.userId(), () -> {
      if (!Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", outlet.depotCode()).get("ok"))) {
        throw new DomainException(ErrorCode.NOT_FOUND, "No such outlet");
      }
      return database.query(
              "SELECT u.display_name, u.phone FROM iam.users u"
                  + " JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = 'store_manager'"
                  + " JOIN iam.user_outlet_access a ON a.user_id = u.user_id AND a.outlet_id = ?"
                  + " WHERE u.is_active ORDER BY u.display_name",
              outletId)
          .stream()
          .map(r -> new StoreContact((String) r.get("display_name"), Optional.ofNullable((String) r.get("phone"))))
          .toList();
    });
  }
}
