package com.waypoint.dispatch.identity.contract;

import java.util.List;
import java.util.UUID;

/** Safe connection context. It contains no credential, name, contact or PIN material. */
public record McpContextView(UUID userId, List<String> roles, List<String> scope, List<String> readActions) {
  public McpContextView {
    roles = List.copyOf(roles);
    scope = List.copyOf(scope);
    readActions = List.copyOf(readActions);
  }
}
