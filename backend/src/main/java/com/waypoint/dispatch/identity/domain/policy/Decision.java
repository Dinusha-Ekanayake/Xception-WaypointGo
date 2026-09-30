package com.waypoint.dispatch.identity.domain.policy;

import java.util.Optional;

/**
 * The answer, and why.
 *
 * <p>The reason exists because a denial with no explanation is the failure this
 * architecture keeps warning about: it looks exactly like missing data. Both the
 * audit row and the message shown to the user come from here.
 */
public record Decision(boolean allowed, String reason, String matchedStatementSid) {

  public static Decision allow(String sid) {
    return new Decision(true, "allowed by statement " + sid, sid);
  }

  public static Decision explicitDeny(String sid) {
    return new Decision(false, "denied by statement " + sid, sid);
  }

  public static Decision defaultDeny(String action) {
    return new Decision(false, "no policy allows " + action, null);
  }

  public boolean denied() {
    return !allowed;
  }

  public Optional<String> sid() {
    return Optional.ofNullable(matchedStatementSid);
  }
}
