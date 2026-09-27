package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures onto RFC 9457 application/problem+json. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<Map<String, Object>> onDomain(DomainException e, HttpServletRequest req) {
    int status = statusFor(e.code());
    ProblemDetails problem =
        new ProblemDetails(
            "https://waypoint.example/problems/" + e.code().name().toLowerCase(),
            e.code().name(),
            status,
            e.getMessage(),
            req.getRequestURI(),
            e.violations());
    return ResponseEntity.status(status)
        .contentType(MediaType.valueOf("application/problem+json"))
        .body(problem.toBody());
  }

  private static int statusFor(ErrorCode code) {
    return switch (code) {
      case VALIDATION_FAILED -> 422;
      case NOT_FOUND -> 404;
      case CONFLICT, VERSION_CONFLICT, CONSTRAINT_VIOLATED -> 409;
      case FORBIDDEN -> 403;
      case UNAUTHENTICATED -> 401;
      case DEPENDENCY_UNAVAILABLE -> 503;
    };
  }

  /**
   * Spring's own failures already carry the right status, for example a 404 for an
   * unknown path. Preserve it rather than flattening everything to 500.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest req) {
    int status = 500;
    String title = "INTERNAL_ERROR";
    String detail = "Unexpected server error";
    if (e instanceof ErrorResponse spring) {
      status = spring.getStatusCode().value();
      title = status == 404 ? "NOT_FOUND" : "REQUEST_REJECTED";
      detail = e.getMessage();
    }
    ProblemDetails problem =
        new ProblemDetails(
            "https://waypoint.example/problems/" + title.toLowerCase(),
            title,
            status,
            detail,
            req.getRequestURI(),
            List.of());
    return ResponseEntity.status(status)
        .contentType(MediaType.valueOf("application/problem+json"))
        .body(problem.toBody());
  }
}
