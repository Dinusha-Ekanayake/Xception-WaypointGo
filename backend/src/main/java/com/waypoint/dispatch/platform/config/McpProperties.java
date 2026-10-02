package com.waypoint.dispatch.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Optional personal MCP connections; disabled until a deployment enables them. */
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(@DefaultValue("false") boolean enabled) {}
