package com.waypoint.dispatch.notification.web;

import com.waypoint.dispatch.notification.application.InboxSignals;
import com.waypoint.dispatch.notification.contract.NotificationViews.UnreadCountView;
import com.waypoint.dispatch.platform.web.LiveStreamPart;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** The reader's own unread count as a live part: {@code unread} whenever it changes. No parameters. */
@Component
public class UnreadStreamPart implements LiveStreamPart {
  private final InboxSignals signals;
  private final RequestAuthorizer authorizer;

  public UnreadStreamPart(InboxSignals signals, RequestAuthorizer authorizer) {
    this.signals = signals;
    this.authorizer = authorizer;
  }

  @Override
  public String name() {
    return "unread";
  }

  @Override
  public Runnable open(HttpServletRequest request, String prefix, Sink sink) {
    Actor actor = authorizer.require(request, NotificationController.READ, "wpt:notification:inbox:self");
    return signals.listen(actor.userId(), count -> sink.send("unread", new UnreadCountView(count)));
  }
}
