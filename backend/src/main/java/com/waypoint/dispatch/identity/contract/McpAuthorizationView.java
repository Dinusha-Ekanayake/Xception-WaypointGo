package com.waypoint.dispatch.identity.contract;

/**
 * What the sign-in page shows before a password is typed: which client asked,
 * and the host the person will be sent back to. Both come from the client's own
 * registration, so the page presents them as claims, not as verified facts.
 */
public record McpAuthorizationView(String clientName, String redirectHost) {}
