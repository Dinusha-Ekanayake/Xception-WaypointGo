package com.waypoint.dispatch.identity.contract;

import java.util.List;
import java.util.UUID;

/**
 * Safe connection context. It contains no credential, name, contact or PIN material.
 *
 * @param scope the depots and outlets the person may see (row scope)
 * @param readActions the reads both the person's policy and the connection's scopes allow
 * @param grantedScopes what this connection was granted (R-IAM-34)
 * @param writeTools the confirmed write tools this connection may use now (R-IAM-35)
 * @param personalFields whether personal fields may be returned (R-IAM-36)
 */
public record McpContextView(
    UUID userId,
    List<String> roles,
    List<String> scope,
    List<String> readActions,
    List<String> grantedScopes,
    List<String> writeTools,
    boolean personalFields) {
  public McpContextView {
    roles = List.copyOf(roles);
    scope = List.copyOf(scope);
    readActions = List.copyOf(readActions);
    grantedScopes = List.copyOf(grantedScopes);
    writeTools = List.copyOf(writeTools);
  }
}
