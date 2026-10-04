package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The audit log's partitions (issue #6, PLT-09, PLT-10).
 *
 * @param partitionsAhead whole future months created ahead of the current one. A
 *     missing partition fails every command, so this is the safety margin between
 *     the scheduler failing and the system stopping
 * @param retentionMonths months of audit kept attached to the log; older monthly
 *     partitions are detached, not dropped. Zero keeps everything (P-14 is open)
 */
@Validated
@ConfigurationProperties(prefix = "app.audit")
public record AuditProperties(
    @DefaultValue("3") @Min(2) @Max(24) int partitionsAhead,
    @DefaultValue("24") @Min(0) @Max(600) int retentionMonths) {}
