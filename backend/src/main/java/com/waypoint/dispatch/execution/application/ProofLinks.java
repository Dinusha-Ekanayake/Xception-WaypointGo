package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.domain.ProofLink;
import com.waypoint.dispatch.platform.config.ExecutionProperties;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints and checks the short-lived links proof artifacts are read through.
 *
 * <p>A link is minted only inside a read that row-level security already
 * allowed, so holding one means someone entitled to the delivery asked for it.
 */
@Component
public class ProofLinks {
  private final ProofLink signer;
  private final ExecutionProperties properties;
  private final Clock clock;

  public ProofLinks(ExecutionProperties properties, Clock clock) {
    this.signer = new ProofLink(properties.proofUrlKey());
    this.properties = properties;
    this.clock = clock;
  }

  public Instant expiry() {
    return clock.now().plus(properties.proofUrlTtl());
  }

  public String urlFor(UUID attachmentId, Instant expiresAt) {
    return "/api/execution/attachments/" + attachmentId + "/content?exp=" + expiresAt.getEpochSecond()
        + "&sig=" + signer.sign(attachmentId, expiresAt);
  }

  public boolean opens(UUID attachmentId, long expiresAtEpochSecond, String signature) {
    return signer.verify(attachmentId, expiresAtEpochSecond, signature, clock.now());
  }
}
