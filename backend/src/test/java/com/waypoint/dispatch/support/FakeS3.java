package com.waypoint.dispatch.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An S3-compatible store in the test process (M-01): PUT, GET and DELETE of one
 * object by path, nothing more. It checks a request carries a SigV4
 * Authorization header and keeps what it was sent, so a test can see that
 * only ciphertext reached it.
 */
public final class FakeS3 implements AutoCloseable {
  public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final HttpServer server;

  public FakeS3() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      byte[] body = exchange.getRequestBody().readAllBytes();
      int status;
      byte[] out = new byte[0];
      if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 Credential=")) {
        status = 403;
      } else {
        switch (exchange.getRequestMethod()) {
          case "PUT" -> { objects.put(path, body); status = 200; }
          case "GET" -> { out = objects.get(path); status = out == null ? 404 : 200; if (out == null) out = new byte[0]; }
          case "DELETE" -> { objects.remove(path); status = 204; }
          default -> status = 405;
        }
      }
      exchange.sendResponseHeaders(status, status == 204 || out.length == 0 ? -1 : out.length);
      if (out.length > 0) exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
  }

  public String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
