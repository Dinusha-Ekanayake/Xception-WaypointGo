package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure onto RFC 9457 {@code application/problem+json}.
 *
 * <p>Three rules. A client's mistake is a 4xx naming what was wrong, never a 500.
 * A detail never echoes framework or driver text, which can carry SQL, class names
 * or the offending value. And an unexpected failure is logged exactly once, here,
 * with the correlation id the body also carries, so a 500 always leaves a trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final MediaType PROBLEM = MediaType.valueOf("application/problem+json");
  private static final String INTERNAL = "INTERNAL_ERROR";

  private final String typeBase;
  private final Metrics metrics;

  public ApiExceptionHandler(AppProperties properties, Metrics metrics) {
    this.typeBase = properties.problemTypeBase();
    this.metrics = metrics;
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<Map<String, Object>> onDomain(DomainException e, HttpServletRequest req) {
    if (e.code() == ErrorCode.VERSION_CONFLICT) {
      // ORD-05, PLN-06, EXE-14: one place sees every stale write, whichever module threw it.
      metrics.increment("waypoint.version.conflict", "path", templateOf(req));
    }
    HttpHeaders headers = new HttpHeaders();
    e.retryAfterSeconds().ifPresent(seconds -> headers.set(HttpHeaders.RETRY_AFTER, seconds.toString()));
    return respond(
        statusFor(e.code()), e.code().name(), e.getMessage(), req, e.violations(), headers, e.extensions());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> onUnreadable(
      HttpMessageNotReadableException e, HttpServletRequest req) {
    // A streamed body cut off by RequestSizeFilter surfaces here, wrapped by the
    // JSON reader. It is too large, not malformed.
    for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
      if (t instanceof DomainException tooLarge) {
        return onDomain(tooLarge, req);
      }
    }
    return problem(ErrorCode.BAD_REQUEST, "The request body is not valid JSON for this endpoint", req);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> onTypeMismatch(
      MethodArgumentTypeMismatchException e, HttpServletRequest req) {
    return respond(
        400,
        ErrorCode.BAD_REQUEST.name(),
        "Parameter " + e.getName() + " has the wrong type",
        req,
        List.of(Violation.onField("request:type", e.getName(), "wrong type")),
        new HttpHeaders());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> onMissingParameter(
      MissingServletRequestParameterException e, HttpServletRequest req) {
    return respond(
        400,
        ErrorCode.BAD_REQUEST.name(),
        "Parameter " + e.getParameterName() + " is required",
        req,
        List.of(Violation.onField("request:required", e.getParameterName(), "required")),
        new HttpHeaders());
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<Map<String, Object>> onNoHandler(Exception e, HttpServletRequest req) {
    return problem(ErrorCode.NOT_FOUND, "No such endpoint", req);
  }

  @ExceptionHandler(AsyncRequestTimeoutException.class)
  public ResponseEntity<Map<String, Object>> onTimeout(
      AsyncRequestTimeoutException e, HttpServletRequest req) {
    return problem(ErrorCode.REQUEST_TIMEOUT, "The request took too long and was abandoned", req);
  }

  /**
   * A constraint the database enforced. A unique or exclusion violation here is a
   * race the application-level check lost (SEC-03, POL-09): someone else got there
   * first. Anything else is a broken reference or check, which the caller sent.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> onIntegrity(
      DataIntegrityViolationException e, HttpServletRequest req) {
    String state = sqlState(e);
    if ("23505".equals(state) || "23P01".equals(state)) {
      metrics.increment("waypoint.race.lost", "path", templateOf(req));
      return problem(
          ErrorCode.CONFLICT, "Another change to the same record was made first; reload and retry", req);
    }
    return respond(
        422,
        ErrorCode.CONSTRAINT_VIOLATED.name(),
        "The change breaks a constraint on stored data",
        req,
        List.of(),
        new HttpHeaders());
  }

  /**
   * Anything else. Spring's own failures already carry the right status, for
   * example 405 or 415, so keep it, with a generic detail rather than Spring's
   * message. A genuine 500 is logged once, with its stack.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest req) {
    if (e instanceof ErrorResponse spring) {
      int status = spring.getStatusCode().value();
      String detail =
          e instanceof HttpRequestMethodNotSupportedException
              ? "Method not allowed on this endpoint"
              : e instanceof HttpMediaTypeNotSupportedException
                  ? "Unsupported content type"
                  : reasonPhrase(status);
      return respond(status, codeFor(status).name(), detail, req, List.of(), new HttpHeaders());
    }
    log.error(
        "Unhandled {} on {} {}", e.getClass().getSimpleName(), req.getMethod(), templateOf(req), e);
    metrics.increment("waypoint.http.unhandled", "exception", e.getClass().getSimpleName());
    return respond(500, INTERNAL, "Unexpected server error", req, List.of(), new HttpHeaders());
  }

  private ResponseEntity<Map<String, Object>> problem(
      ErrorCode code, String detail, HttpServletRequest req) {
    return respond(statusFor(code), code.name(), detail, req, List.of(), new HttpHeaders());
  }

  private ResponseEntity<Map<String, Object>> respond(
      int status,
      String code,
      String detail,
      HttpServletRequest req,
      List<Violation> violations,
      HttpHeaders headers) {
    return respond(status, code, detail, req, violations, headers, Map.of());
  }

  private ResponseEntity<Map<String, Object>> respond(
      int status,
      String code,
      String detail,
      HttpServletRequest req,
      List<Violation> violations,
      HttpHeaders headers,
      Map<String, Object> extensions) {
    metrics.increment("waypoint.problem", "code", code);
    ProblemDetails problem =
        new ProblemDetails(
            typeBase + code.toLowerCase(Locale.ROOT).replace('_', '-'),
            titleFor(code),
            status,
            detail,
            req.getRequestURI(),
            code,
            MDC.get(CorrelationIdFilter.MDC_KEY),
            violations,
            extensions);
    return ResponseEntity.status(status).headers(headers).contentType(PROBLEM).body(problem.toBody());
  }

  static int statusFor(ErrorCode code) {
    return switch (code) {
      case BAD_REQUEST -> 400;
      case VALIDATION_FAILED -> 422;
      case NOT_FOUND -> 404;
      case CONFLICT, VERSION_CONFLICT, CONSTRAINT_VIOLATED -> 409;
      case FORBIDDEN -> 403;
      case UNAUTHENTICATED -> 401;
      case PAYLOAD_TOO_LARGE -> 413;
      case REQUEST_TIMEOUT -> 408;
      case RATE_LIMITED -> 429;
      case DEPENDENCY_UNAVAILABLE -> 503;
    };
  }

  /** For people. Clients branch on {@code code}, so these may be reworded freely. */
  static String titleFor(String code) {
    return switch (code) {
      case "BAD_REQUEST" -> "Malformed request";
      case "VALIDATION_FAILED" -> "Validation failed";
      case "NOT_FOUND" -> "Not found";
      case "CONFLICT" -> "Conflict";
      case "VERSION_CONFLICT" -> "Version conflict";
      case "FORBIDDEN" -> "Forbidden";
      case "UNAUTHENTICATED" -> "Not signed in";
      case "CONSTRAINT_VIOLATED" -> "Constraint violated";
      case "PAYLOAD_TOO_LARGE" -> "Request too large";
      case "REQUEST_TIMEOUT" -> "Request timed out";
      case "RATE_LIMITED" -> "Too many requests";
      case "DEPENDENCY_UNAVAILABLE" -> "Service unavailable";
      default -> "Internal error";
    };
  }

  private static ErrorCode codeFor(int status) {
    return switch (status) {
      case 401 -> ErrorCode.UNAUTHENTICATED;
      case 403 -> ErrorCode.FORBIDDEN;
      case 404 -> ErrorCode.NOT_FOUND;
      case 408 -> ErrorCode.REQUEST_TIMEOUT;
      case 409 -> ErrorCode.CONFLICT;
      case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
      case 429 -> ErrorCode.RATE_LIMITED;
      default -> status >= 500 ? ErrorCode.DEPENDENCY_UNAVAILABLE : ErrorCode.BAD_REQUEST;
    };
  }

  private static String reasonPhrase(int status) {
    HttpStatus known = HttpStatus.resolve(status);
    return known == null ? "Request rejected" : known.getReasonPhrase();
  }

  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  /**
   * The matched route pattern rather than the raw path, so a metric tag or a log
   * line never carries an id from the URL.
   */
  private static String templateOf(HttpServletRequest req) {
    Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? "unmatched" : pattern.toString();
  }
}
