package com.waypoint.dispatch.service;

/** HTTP-aware domain failure. Carries the status the API must respond with. */
public class DomainException extends RuntimeException {
  private final int status;

  public DomainException(String message, int status) {
    super(message);
    this.status = status;
  }

  public DomainException(String message) {
    this(message, 400);
  }

  public int getStatus() {
    return status;
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw new DomainException(message, 400);
  }

  public static void require(boolean ok, String message, int status) {
    if (!ok) throw new DomainException(message, status);
  }
}
