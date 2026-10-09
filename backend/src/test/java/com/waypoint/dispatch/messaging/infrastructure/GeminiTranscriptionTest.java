package com.waypoint.dispatch.messaging.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The Gemini answer as the adapter reads it, without calling Google. */
class GeminiTranscriptionTest {
  final ObjectMapper json = new ObjectMapper();

  @Test
  void theFirstCandidatesJsonIsTheTranscript() throws Exception {
    String body = """
        {"candidates":[{"content":{"parts":[{"text":"{\\"language\\":\\"si\\",\\"text\\":\\"ටයරය පැලුණා\\"}"}]}}]}
        """;
    var heard = GeminiTranscription.parse(json, body).orElseThrow();
    assertEquals("ටයරය පැලුණා", heard.text());
    assertEquals("si", heard.language().orElseThrow());
  }

  @Test
  void noCandidateOrNoSpeechIsNothing() throws Exception {
    assertTrue(GeminiTranscription.parse(json, "{\"candidates\":[]}").isEmpty());
    assertTrue(GeminiTranscription.parse(json,
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"language\\\":\\\"en\\\",\\\"text\\\":\\\"\\\"}\"}]}}]}").isEmpty());
    assertEquals("audio/webm", GeminiTranscription.mime("audio/webm;codecs=opus"));
  }
}
