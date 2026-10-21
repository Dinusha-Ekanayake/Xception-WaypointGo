package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.messaging.ChangeSignals;
import com.waypoint.dispatch.platform.messaging.ChangeWatchers;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The change stream (roadmap F-07b) as a part: {@code change} with a topic and a
 * depot whenever something committed there, {@code ping} every 25 s. A store
 * names outlets instead of depots. Every depot or outlet is checked by policy
 * and scope before anything is sent: one outside is {@code 403} and an audit row.
 */
@Component
public class ChangesStreamPart implements LiveStreamPart {
  static final int MAX_PLACES = 20;

  private final ChangeWatchers watchers;
  private final RequestAuthorizer authorizer;

  public ChangesStreamPart(ChangeWatchers watchers, RequestAuthorizer authorizer) {
    this.watchers = watchers;
    this.authorizer = authorizer;
  }

  @Override
  public String name() {
    return "changes";
  }

  @Override
  public Runnable open(HttpServletRequest request, String prefix, Sink sink) {
    Set<String> depots = LiveStreamPart.values(request, prefix + "depot");
    Set<String> outlets = LiveStreamPart.values(request, prefix + "outlet");
    if (depots.isEmpty() == outlets.isEmpty() || depots.size() + outlets.size() > MAX_PLACES) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Name between 1 and " + MAX_PLACES + " depots, or outlets, to watch");
    }
    Actor actor = null;
    for (String depot : depots) {
      actor = authorizer.require(request, "plan:Read", "wpt:plan:depot:" + depot);
    }
    for (String outlet : outlets) {
      actor = authorizer.require(request, "receipt:Read", "wpt:receipt:outlet:" + outlet);
    }
    ChangeSignals.Listener listener =
        new ChangeSignals.Listener() {
          @Override
          public void changed(String topic, String depot) throws Exception {
            sink.send("change", depot == null ? Map.of("topic", topic) : Map.of("topic", topic, "depot", depot));
          }

          @Override
          public void heartbeat() throws Exception {
            sink.send("ping", "");
          }
        };
    return depots.isEmpty()
        ? watchers.openOutlets(actor, "receipt:Read", outlets, listener)
        : watchers.open(actor, "plan:Read", depots, listener);
  }
}
