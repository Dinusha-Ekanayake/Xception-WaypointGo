package com.waypoint.dispatch.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Roadmap T1-01: the late-risk review before Publish, for the dispatcher. On by
 * default and labelled an estimate; {@code PUBLISH_RISK_REVIEW=false} hides it
 * without a deployment of code.
 */
@ConfigurationProperties(prefix = "app.ml.risk-review")
public record RiskReviewProperties(@DefaultValue("true") boolean enabled) {}
