package com.waypoint.dispatch.platform.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Local access can be enabled independently; remote access needs a canonical resource URL. */
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String publicUrl,
    /** P-30: requests a minute per MCP credential; one tool call is about two requests. */
    @DefaultValue("120") int ratePerCredentialPerMinute,
    /** P-30: requests a minute per OAuth client, across every person using it. */
    @DefaultValue("1200") int ratePerClientPerMinute) {
  public McpProperties {
    if (ratePerCredentialPerMinute < 1 || ratePerClientPerMinute < 1) {
      throw new IllegalArgumentException("MCP_RATE_PER_CREDENTIAL and MCP_RATE_PER_CLIENT must be at least 1");
    }
    publicUrl = publicUrl == null ? "" : publicUrl.trim();
    if (!publicUrl.isEmpty()) {
      URI uri = URI.create(publicUrl);
      boolean local = uri.getHost() != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
      if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
          || !"/mcp".equals(uri.getRawPath()) || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
        throw new IllegalArgumentException("MCP_PUBLIC_URL must be an HTTPS URL ending in /mcp, or loopback HTTP for development");
      }
    }
  }
  public boolean remoteEnabled() { return enabled && !publicUrl.isEmpty(); }
  public String publicOrigin() { return publicUrl.substring(0, publicUrl.length() - "/mcp".length()); }
}
