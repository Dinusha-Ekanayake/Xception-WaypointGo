package com.waypoint.dispatch.identity.application;

/**
 * A refusal in the shape OAuth clients read (RFC 6749 section 5.2, RFC 7591
 * section 3.2.2): a fixed {@code error} word and a description.
 *
 * <p>Only the endpoints third-party OAuth libraries call throw this. Everything
 * our own screens call stays a {@code DomainException} and problem+json
 * (R-IAM-31).
 */
public class OAuthProtocolException extends RuntimeException {
  private final String error;
  private final int status;

  public OAuthProtocolException(int status, String error, String description) {
    super(description);
    this.status = status;
    this.error = error;
  }

  /** One description for every cause: which check failed is a hint. */
  public static OAuthProtocolException invalidGrant() {
    return new OAuthProtocolException(400, "invalid_grant", "The authorization code is not valid");
  }

  public String error() {
    return error;
  }

  public int status() {
    return status;
  }
}
