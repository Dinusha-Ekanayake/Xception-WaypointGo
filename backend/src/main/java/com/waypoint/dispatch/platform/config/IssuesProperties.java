package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Issues: photos of a delivery problem (Figma store manager "06d", "08b3").
 *
 * @param maxAttachmentBytes the largest photo accepted; the store's screen shrinks a photo below it
 * @param attachmentRetention how long a photo's bytes are kept (P-14, as proof of delivery)
 */
@Validated
@ConfigurationProperties(prefix = "app.issues")
public record IssuesProperties(
    @DefaultValue("3145728") @Min(1024) @Max(3_500_000) int maxAttachmentBytes,
    @DefaultValue("400d") @NotNull Duration attachmentRetention) {

  @AssertTrue(message = "app.issues: attachment retention must be positive")
  public boolean isCoherent() {
    return attachmentRetention != null && !attachmentRetention.isNegative() && !attachmentRetention.isZero();
  }
}
