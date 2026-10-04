package com.waypoint.dispatch.identity.contract;

/**
 * What the sign-in page shows before a password is typed: which client asked,
 * and the host the person will be sent back to. Both come from the client's own
 * registration, so the page presents them as claims, not as verified facts.
 * {@code scopes} is what approving grants (R-IAM-34), so the person sees it first.
 */
public record McpAuthorizationView(String clientName, String redirectHost, java.util.List<String> scopes) {
  public McpAuthorizationView {
    scopes = java.util.List.copyOf(scopes);
  }
}
