package com.waypoint.dispatch.platform.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import com.waypoint.dispatch.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RFC 9457 body is part of the API contract: clients branch on {@code code}
 * and read {@code violations}. This pins its shape, and pins that a client's
 * mistake is a 4xx rather than a 500, without a database.
 */
class ProblemContractTest {
  private static final String CORRELATION = "0b6e4c1a-5f2d-4a8e-9c3b-7d1e2f3a4b5c";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger handlerLog = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
  private MockMvc mvc;

  @RestController
  static class Probe {
    record Body(String name) {}

    @GetMapping("/probe/version-conflict")
    void versionConflict() {
      throw DomainException.withViolations(
          ErrorCode.VERSION_CONFLICT,
          "Account is at version 3, not 2",
          List.of(Violation.onField("R-IAM-01", "expectedVersion", "stale")));
    }

    @GetMapping("/probe/rules")
    void rules() {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Window too short", List.of("R-ORD-08"));
    }

    @GetMapping("/probe/rate-limited")
    void rateLimited() {
      throw DomainException.rateLimited("Too many attempts", 900);
    }

    @GetMapping("/probe/extension")
    void extension() {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Insufficient stock", List.of("STK-01"))
          .with("availability", List.of(Map.of("productId", "P-1", "requested", 12, "available", 4)))
          .with("code", "NOT_A_CODE");
    }

    @GetMapping("/probe/uuid/{id}")
    void uuid(@PathVariable String id) {
      RequestValues.uuid("id", id);
    }

    @GetMapping("/probe/typed")
    void typed(@RequestParam int limit) {}

    @PostMapping("/probe/json")
    void json(@RequestBody Body body) {}

    @GetMapping("/probe/duplicate")
    void duplicate() {
      throw new DataIntegrityViolationException(
          "duplicate key value violates unique constraint \"users_email_key\" (email)=(a@b.c)",
          new SQLException("duplicate", "23505"));
    }

    @GetMapping("/probe/boom")
    void boom() {
      throw new IllegalStateException("secret internals for someone@example.com");
    }
  }

  @BeforeEach
  void setUp() {
    logs.start();
    handlerLog.addAppender(logs);
    mvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ApiExceptionHandler(TestProperties.app(), new Metrics(registry)))
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @AfterEach
  void tearDown() {
    handlerLog.detachAppender(logs);
  }

  @Test
  void aDomainFailureCarriesCodeCorrelationAndStructuredViolations() throws Exception {
    mvc.perform(get("/probe/version-conflict").header(CorrelationIdFilter.HEADER, CORRELATION))
        .andExpect(status().isConflict())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:waypoint:problem:version-conflict"))
        .andExpect(jsonPath("$.title").value("Version conflict"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.detail").value("Account is at version 3, not 2"))
        .andExpect(jsonPath("$.instance").value("/probe/version-conflict"))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION))
        .andExpect(jsonPath("$.violations", hasSize(1)))
        .andExpect(jsonPath("$.violations[0].rule").value("R-IAM-01"))
        .andExpect(jsonPath("$.violations[0].field").value("expectedVersion"))
        .andExpect(jsonPath("$.violations[0].message").value("stale"));
    assertEquals(
        1.0,
        registry.get("waypoint.version.conflict").tag("path", "/probe/version-conflict").counter().count());
  }

  @Test
  void ruleIdsBecomeViolationsWithoutAField() throws Exception {
    mvc.perform(get("/probe/rules"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.violations[0].rule").value("R-ORD-08"))
        .andExpect(jsonPath("$.violations[0].field").doesNotExist())
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void anExtensionIsAddedBesideTheStandardMembersAndNeverReplacesOne() throws Exception {
    mvc.perform(get("/probe/extension"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.violations[0].rule").value("STK-01"))
        .andExpect(jsonPath("$.availability[0].productId").value("P-1"))
        .andExpect(jsonPath("$.availability[0].requested").value(12))
        .andExpect(jsonPath("$.availability[0].available").value(4));
  }

  @Test
  void rateLimitingIs429WithRetryAfter() throws Exception {
    mvc.perform(get("/probe/rate-limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "900"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void aBadUuidIs400NamingTheFieldWithoutEchoingTheValue() throws Exception {
    mvc.perform(get("/probe/uuid/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.violations[0].field").value("id"))
        .andExpect(jsonPath("$.detail").value("id must be a UUID"))
        .andExpect(jsonPath("$.violations[0].message", not(containsString("not-a-uuid"))));
  }

  @Test
  void aWronglyTypedParameterIs400() throws Exception {
    mvc.perform(get("/probe/typed").param("limit", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.violations[0].field").value("limit"));
  }

  @Test
  void malformedJsonIs400() throws Exception {
    mvc.perform(post("/probe/json").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void aLostUniqueRaceIs409AndCountedWithoutLeakingTheRow() throws Exception {
    mvc.perform(get("/probe/duplicate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(content().string(not(containsString("a@b.c"))))
        .andExpect(content().string(not(containsString("users_email_key"))));
    assertEquals(
        1.0, registry.get("waypoint.race.lost").tag("path", "/probe/duplicate").counter().count());
  }

  @Test
  void aWrongMethodKeepsItsStatusWithAGenericDetail() throws Exception {
    mvc.perform(post("/probe/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.detail").value("Method not allowed on this endpoint"));
  }

  @Test
  void anUnexpectedFailureIsOne500LogLineWithTheCorrelationIdAndNoInternals() throws Exception {
    mvc.perform(get("/probe/boom").header(CorrelationIdFilter.HEADER, CORRELATION))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value("Unexpected server error"))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION))
        .andExpect(content().string(not(containsString("someone@example.com"))));

    List<ILoggingEvent> errors =
        logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    assertEquals(1, errors.size(), "exactly one error line per 500");
    ILoggingEvent line = errors.get(0);
    Map<String, String> mdc = line.getMDCPropertyMap();
    assertEquals(CORRELATION, mdc.get(CorrelationIdFilter.MDC_KEY));
    assertTrue(line.getThrowableProxy() != null, "the stack trace is logged");
    assertTrue(
        !line.getFormattedMessage().contains("someone@example.com"),
        "the message line itself carries no exception text");
  }

  @Test
  void aBodyOverTheLimitIs413BeforeItIsRead() throws Exception {
    MockMvc limited =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ApiExceptionHandler(TestProperties.app(), new Metrics(registry)))
            .addFilters(new CorrelationIdFilter(), new RequestSizeFilter(TestProperties.withMaxBody(16)))
            .build();
    limited
        .perform(
            post("/probe/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(64) + "\"}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }
}
