package com.waypoint.dispatch.intelligence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ML-51: Jev's answer counts only when it is one of the options it was offered. */
class JevDecisionsTest {
  private HttpServer server;

  private JevDecisions answering(String choice) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] body = ("{\"model\":\"typesafe/jev-1.13\",\"answers\":{\"pick\":{\"choice\":\"" + choice
          + "\",\"confidence\":0.9,\"probabilities\":{}}}}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    AiProperties properties = new AiProperties(false, "groq", "", "key", "", 300, Duration.ofSeconds(5), true,
        "typesafe/jev-1.13", false);
    return new JevDecisions(properties, new ObjectMapper(), new Metrics(new SimpleMeterRegistry()), Clock.system(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void anOfferedOptionIsTheAnswer() throws Exception {
    assertEquals("BREAKDOWN", answering("BREAKDOWN").choose("Flat tyre", "Which?",
        Map.of("BREAKDOWN", "vehicle", "OTHER", "none")).orElseThrow().option());
  }

  @Test
  void anAnswerOutsideTheOptionsIsNoAnswer() throws Exception {
    assertTrue(answering("FIRE").choose("Flat tyre", "Which?", Map.of("BREAKDOWN", "vehicle", "OTHER", "none")).isEmpty());
  }
}
