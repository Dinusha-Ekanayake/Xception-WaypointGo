package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.McpContextView;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Conservative tool discovery; every concrete resource is authorized again by its read API. */
@Component
public class McpContextQuery {
  private static final Map<String, String> READS = Map.of(
      "order:Read", "wpt:order:outlet:*",
      "plan:Read", "wpt:plan:depot:*",
      "loading:Read", "wpt:loading:trip:*",
      "delivery:Read", "wpt:execution:delivery:*",
      "receipt:Read", "wpt:receipt:order:*",
      "issue:Read", "wpt:issue:issue:*",
      "audit:Read", "wpt:platform:audit:*",
      "iam:ReadPolicy", "wpt:iam:policy:*");
  private final McpAccessHandler access;
  private final SessionRegistry sessions;
  private final PolicyDecisionPoint policies;

  public McpContextQuery(McpAccessHandler access, SessionRegistry sessions, PolicyDecisionPoint policies) {
    this.access = access;
    this.sessions = sessions;
    this.policies = policies;
  }

  public McpContextView context(String token) {
    var session = access.require(token, "GET", "/api/mcp/context");
    var actor = sessions.actorOf(session);
    var actions = READS.entrySet().stream()
        .filter(entry -> policies.decide(actor, entry.getKey(), entry.getValue(), Map.of()).allowed())
        .map(Map.Entry::getKey).sorted().toList();
    access.recordRead(actor, McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE);
    return new McpContextView(session.userId(), session.roles(), session.scope(), actions);
  }
}
