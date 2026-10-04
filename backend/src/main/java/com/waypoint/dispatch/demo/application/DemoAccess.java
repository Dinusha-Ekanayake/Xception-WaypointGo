package com.waypoint.dispatch.demo.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class DemoAccess {
  private final IdentityQuery identity;
  public DemoAccess(IdentityQuery identity) { this.identity = identity; }
  public void admin(Actor actor) {
    if (actor.isSystem() || !identity.scopeOf(actor.userId()).roles().contains("admin"))
      throw new DomainException(ErrorCode.FORBIDDEN, "Demo controls require an administrator");
  }
}
