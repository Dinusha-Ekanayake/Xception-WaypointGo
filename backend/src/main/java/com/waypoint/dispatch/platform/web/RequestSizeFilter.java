package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request body larger than {@code app.http.max-body-bytes} (SEC-08).
 *
 * <p>Enforced here and not only at nginx, because the backend is also reached
 * through the Next proxy and directly in development, and a limit that exists in
 * one deployment path is not a limit. A declared {@code Content-Length} over the
 * limit is refused before anything is read; a body without one is counted as it
 * streams and cut off at the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RequestSizeFilter extends OncePerRequestFilter {
  private final long maxBytes;
  private final String typeBase;

  public RequestSizeFilter(AppProperties properties) {
    this.maxBytes = properties.http().maxBodyBytes();
    this.typeBase = properties.problemTypeBase();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long declared = request.getContentLengthLong();
    if (declared > maxBytes) {
      reject(request, response);
      return;
    }
    chain.doFilter(declared >= 0 ? request : new Bounded(request, maxBytes), response);
  }

  /**
   * Written here rather than by the exception handler, because this runs before
   * Spring MVC and the handler never sees it. Same shape as every other problem.
   */
  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    String body =
        """
        {"type":"%spayload-too-large","title":"Request too large","status":413,\
        "detail":"The request body exceeds %d bytes","instance":"%s","code":"PAYLOAD_TOO_LARGE",\
        "correlationId":"%s","violations":[]}"""
            .formatted(
                typeBase,
                maxBytes,
                jsonEscape(request.getRequestURI()),
                correlationId == null ? "" : correlationId);
    response.setStatus(413);
    response.setContentType("application/problem+json");
    response.setHeader("Connection", "close");
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String jsonEscape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** A body with no declared length, counted as it is read. */
  private static final class Bounded extends HttpServletRequestWrapper {
    private final long maxBytes;

    Bounded(HttpServletRequest request, long maxBytes) {
      super(request);
      this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream in = super.getInputStream();
      return new ServletInputStream() {
        private long read;

        @Override
        public int read() throws IOException {
          int b = in.read();
          if (b >= 0) {
            count(1);
          }
          return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          int n = in.read(buffer, offset, length);
          if (n > 0) {
            count(n);
          }
          return n;
        }

        private void count(int n) {
          read += n;
          if (read > maxBytes) {
            throw new DomainException(
                ErrorCode.PAYLOAD_TOO_LARGE, "The request body exceeds " + maxBytes + " bytes");
          }
        }

        @Override
        public boolean isFinished() {
          return in.isFinished();
        }

        @Override
        public boolean isReady() {
          return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          in.setReadListener(listener);
        }
      };
    }
  }
}
