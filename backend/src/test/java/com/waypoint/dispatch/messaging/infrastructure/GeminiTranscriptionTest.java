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
  void theEngineSaysWhetherItHeardClearlyAndAnAnswerThatDoesNotSayIsTakenAsUnclear() throws Exception {
    // R-ML-37: a word in doubt is reviewed, never trusted by default.
    String clear = """
        {"candidates":[{"content":{"parts":[{"text":"{\\"language\\":\\"en\\",\\"text\\":\\"Tyre burst\\",\\"clear\\":true}"}]}}]}
        """;
    String noisy = clear.replace("true", "false");
    String silent = """
        {"candidates":[{"content":{"parts":[{"text":"{\\"language\\":\\"en\\",\\"text\\":\\"Tyre burst\\"}"}]}}]}
        """;
    assertTrue(!GeminiTranscription.parse(json, clear).orElseThrow().unclear());
    assertTrue(GeminiTranscription.parse(json, noisy).orElseThrow().unclear());
    assertTrue(GeminiTranscription.parse(json, silent).orElseThrow().unclear());
  }

  @Test
  void noCandidateOrNoSpeechIsNothing() throws Exception {
    assertTrue(GeminiTranscription.parse(json, "{\"candidates\":[]}").isEmpty());
    assertTrue(GeminiTranscription.parse(json,
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"language\\\":\\\"en\\\",\\\"text\\\":\\\"\\\"}\"}]}}]}").isEmpty());
    assertEquals("audio/webm", GeminiTranscription.mime("audio/webm;codecs=opus"));
  }
}
