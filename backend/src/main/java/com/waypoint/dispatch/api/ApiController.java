package com.waypoint.dispatch.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.service.DispatchService;
import com.waypoint.dispatch.service.DomainException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface. Mirrors app/api/[...path]/route.ts exactly: same paths, same
 * auth cookie, same origin / content-type / size guards, same error shapes.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
  private static final Logger log = LoggerFactory.getLogger(ApiController.class);
  private static final long MAX_BODY = 3_500_000L;

  private final DispatchService service;
  private final ObjectMapper mapper;
  private final boolean cookieSecure;

  public ApiController(
      DispatchService service,
      ObjectMapper mapper,
      @Value("${app.cookie-secure:0}") String cookieSecure) {
    this.service = service;
    this.mapper = mapper;
    this.cookieSecure = "1".equals(cookieSecure);
  }

  private static ResponseEntity<Map<String, Object>> json(Object data, int status) {
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body((Map<String, Object>) toMap(data));
  }

  @SuppressWarnings("unchecked")
  private static Object toMap(Object data) {
    return data;
  }

  private static String sessionCookie(HttpServletRequest request) {
    if (request.getCookies() == null) return "";
    for (Cookie c : request.getCookies()) {
      if ("session".equals(c.getName())) return c.getValue();
    }
    return "";
  }

  // ---------- GET ----------

  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    boolean ok = service.get("SELECT 1 AS ok") != null;
    return json(Map.of("ok", ok), 200);
  }

  @GetMapping("/proof")
  public ResponseEntity<Map<String, Object>> proof(
      HttpServletRequest request,
      @RequestParam(value = "order_id", defaultValue = "") String orderId,
      @RequestParam(value = "image_id", defaultValue = "") String imageId) {
    try {
      DispatchService.User user = service.session(sessionCookie(request));
      return json(service.proofImage(user, orderId, imageId), 200);
    } catch (DomainException e) {
      return json(Map.of("error", e.getMessage()), e.getStatus());
    } catch (Exception e) {
      log.error("proof failed", e);
      return json(Map.of("error", "The action could not be saved. Retry with the same command."), 500);
    }
  }

  @GetMapping("/state")
  public ResponseEntity<Map<String, Object>> state(HttpServletRequest request) {
    try {
      return json(service.state(service.session(sessionCookie(request))), 200);
    } catch (DomainException e) {
      return json(Map.of("error", e.getMessage()), e.getStatus());
    } catch (Exception e) {
      log.error("state failed", e);
      return json(Map.of("error", "The action could not be saved. Retry with the same command."), 500);
    }
  }

  @GetMapping("/assignments")
  public ResponseEntity<Object> assignments(
      HttpServletRequest request,
      @RequestParam(value = "day", defaultValue = "") String day,
      @RequestParam(value = "order_id", defaultValue = "") String orderId) {
    try {
      DispatchService.User user = service.session(sessionCookie(request));
      List<Map<String, Object>> rows = service.previewAssignments(user, day, orderId);
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body((Object) rows);
    } catch (DomainException e) {
      return ResponseEntity.status(e.getStatus())
          .header("Cache-Control", "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body((Object) Map.of("error", e.getMessage()));
    } catch (Exception e) {
      log.error("assignments failed", e);
      return ResponseEntity.status(500)
          .header("Cache-Control", "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body((Object) Map.of("error",
              "The action could not be saved. Retry with the same command."));
    }
  }

  // ---------- POST ----------

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(
      HttpServletRequest request, HttpServletResponse response) {
    try {
      Map<String, Object> body = readJsonObject(request);
      Object email = body.get("email");
      Object password = body.get("password");
      if (!(email instanceof String s) || s.isEmpty() || s.length() > 200
          || !(password instanceof String p) || p.isEmpty() || p.length() > 200) {
        // Preserve the envelope error shape (first-issue message).
        String message = !(email instanceof String e2) || e2.isEmpty()
            ? "email: Invalid input."
            : "password: Invalid input.";
        return json(Map.of("error", message), 400);
      }
      service.checkLoginRate((String) email);
      DispatchService.LoginResult result = service.login(email, password);
      ResponseCookie cookie = ResponseCookie.from("session", result.token())
          .httpOnly(true)
          .sameSite("Strict")
          .secure(cookieSecure)
          .path("/")
          .maxAge(86_400)
          .build();
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .header("Set-Cookie", cookie.toString())
          .contentType(MediaType.APPLICATION_JSON)
          .body(new java.util.LinkedHashMap<String, Object>(result.user()));
    } catch (DomainException e) {
      return json(Map.of("error", e.getMessage()), e.getStatus());
    } catch (Exception e) {
      log.error("login failed", e);
      return json(Map.of("error", "The action could not be saved. Retry with the same command."), 500);
    }
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
    try {
      readJsonObject(request);
      service.session(sessionCookie(request));
      service.logout(sessionCookie(request));
      ResponseCookie cookie = ResponseCookie.from("session", "")
          .httpOnly(true)
          .sameSite("Strict")
          .path("/")
          .maxAge(0)
          .build();
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .header("Set-Cookie", cookie.toString())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ok", true));
    } catch (DomainException e) {
      return json(Map.of("error", e.getMessage()), e.getStatus());
    } catch (Exception e) {
      log.error("logout failed", e);
      return json(Map.of("error", "The action could not be saved. Retry with the same command."), 500);
    }
  }

  @PostMapping("/command")
  public ResponseEntity<Map<String, Object>> command(HttpServletRequest request) {
    try {
      Map<String, Object> body = readJsonObject(request);
      DispatchService.User user = service.session(sessionCookie(request));
      String envelopeError = envelopeError(body);
      if (envelopeError != null) return json(Map.of("error", envelopeError), 400);
      return json(service.command(user, body), 200);
    } catch (DomainException e) {
      return json(Map.of("error", e.getMessage()), e.getStatus());
    } catch (Exception e) {
      log.error("command failed", e);
      return json(Map.of("error", "The action could not be saved. Retry with the same command."), 500);
    }
  }

  // ---------- request guards (mirror route.ts) ----------

  private void checkOrigin(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null || origin.isEmpty()) return;
    try {
      String originHost = new java.net.URI(origin).getHost();
      // The Next.js proxy cannot override Host on its server-side fetch
      // (undici forbids it), so it forwards the browser host here.
      String host = request.getHeader("X-Forwarded-Host");
      if (host == null || host.isEmpty()) host = request.getHeader("Host");
      host = stripPort(host);
      if (originHost != null && !originHost.equalsIgnoreCase(host)) {
        throw new DomainException("Cross-origin request rejected.", 403);
      }
    } catch (DomainException e) {
      throw e;
    } catch (Exception e) {
      throw new DomainException("Cross-origin request rejected.", 403);
    }
  }

  private static String stripPort(String host) {
    if (host == null) return null;
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      return end == -1 ? host : host.substring(0, end + 1);
    }
    int last = host.lastIndexOf(':');
    if (last != -1 && host.indexOf(':') == last) host = host.substring(0, last);
    return host;
  }

  private Map<String, Object> readJsonObject(HttpServletRequest request) {
    checkOrigin(request);
    String contentType = request.getContentType();
    String base = contentType == null ? "" : contentType.split(";")[0].trim();
    if (!"application/json".equalsIgnoreCase(base)) {
      throw new DomainException("JSON required.", 415);
    }
    try (InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      long size = 0;
      int n;
      while ((n = in.read(buf)) != -1) {
        size += n;
        if (size > MAX_BODY) throw new DomainException("Request exceeds 3.5 MB.", 413);
        out.write(buf, 0, n);
      }
      Object parsed = mapper.readValue(out.toByteArray(), Object.class);
      if (!(parsed instanceof Map)) throw new DomainException("JSON object required.");
      @SuppressWarnings("unchecked")
      Map<String, Object> obj = (Map<String, Object>) parsed;
      // Re-parse into insertion-ordered map so idempotency fingerprints are stable.
      LinkedHashMap<String, Object> ordered = mapper.convertValue(
          parsed, new TypeReference<LinkedHashMap<String, Object>>() {});
      return ordered;
    } catch (DomainException e) {
      throw e;
    } catch (Exception e) {
      throw new DomainException("Invalid JSON.");
    }
  }

  /** Lightweight envelope check mirroring CommandSchema (service re-checks semantics). */
  private static String envelopeError(Map<String, Object> body) {
    Object kind = body.get("kind");
    List<String> kinds = List.of("plan", "publish", "move", "defer_note", "order", "load",
        "shortfall", "resolve", "resolve_exception", "depart", "arrive", "deliver", "receive", "dispute");
    if (!(kind instanceof String) || !kinds.contains(kind)) {
      return "kind: Invalid option.";
    }
    Object id = body.get("id");
    if (!(id instanceof String s) || s.isEmpty() || s.length() > 100) {
      return "id: Invalid input.";
    }
    Object day = body.get("day");
    if (day != null
        && (!(day instanceof String ds) || !ds.matches("^\\d{4}-\\d{2}-\\d{2}$"))) {
      return "day: Expected YYYY-MM-DD.";
    }
    return null;
  }

  @RequestMapping("/**")
  public ResponseEntity<Map<String, Object>> notFound() {
    return json(Map.of("error", "Not found"), 404);
  }
}
