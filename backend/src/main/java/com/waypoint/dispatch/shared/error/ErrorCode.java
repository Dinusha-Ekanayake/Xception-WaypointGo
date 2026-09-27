package com.waypoint.dispatch.shared.error;

/** Stable machine-readable codes. These appear in API responses, so they are part of the contract. */
public enum ErrorCode {
  VALIDATION_FAILED,
  NOT_FOUND,
  CONFLICT,
  VERSION_CONFLICT,
  FORBIDDEN,
  UNAUTHENTICATED,
  CONSTRAINT_VIOLATED,
  DEPENDENCY_UNAVAILABLE
}
