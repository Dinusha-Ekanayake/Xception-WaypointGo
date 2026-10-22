package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The language model gateway (roadmap AI-0). Off unless {@code AI_ENABLED=true}:
 * nothing leaves the process until someone turns it on, and the screen then
 * says "rules only" (rule 9). Only the gateway holds a key; the browser never does.
 *
 * @param enabled the switch, and the kill switch
 * @param provider groq or openrouter, both OpenAI-compatible chat completions
 * @param groqKey {@code GROQ_API_KEY}
 * @param openrouterKey {@code OPENROUTER_API_KEY}; give that key its own credit limit in OpenRouter
 * @param model the pinned model; blank takes the provider's default below
 * @param dailyCalls the most calls a day, across everyone: the spend cap in calls
 * @param timeout one call
 * @param jevEnabled Jev in shadow (roadmap AI-J1, R-ML-33): it guesses each new issue's type, and
 *     each typed trip-chat report's situation, from its text and the guess is recorded beside the real type; nobody sees it. Needs OPENROUTER_API_KEY
 * @param jevModel pinned, never the latest alias: thresholds hold only for the version they were tuned on
 * @param jevLive Jev live on the trip's chat (decision suggestions part 2, R-ML-35): its class of a driver's or
 *     store's own words feeds the trip's Next step card. Off by default; production keeps it off until
 *     @jv-ransika agrees to the data processing. Needs {@code jevEnabled}
 * @param jevLiveMinConfidence below this, a class Jev gave a message adds nothing to the card (P-39)
 */
@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("groq") String provider,
    @DefaultValue("") String groqKey,
    @DefaultValue("") String openrouterKey,
    @DefaultValue("") String model,
    @DefaultValue("300") @Min(0) @Max(100_000) int dailyCalls,
    @DefaultValue("12s") @NotNull Duration timeout,
    @DefaultValue("false") boolean jevEnabled,
    @DefaultValue("typesafe/jev-1.13") String jevModel,
    /** The watcher's AI layer, in shadow (docs/issues/watch-review/PLAN.md); off by default. */
    @DefaultValue("false") boolean watchReview,
    @DefaultValue("false") boolean jevLive,
    @DefaultValue("0.5") @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0")
        double jevLiveMinConfidence) {

  public static final String GROQ = "groq";
  public static final String OPENROUTER = "openrouter";

  public String baseUrl() {
    return OPENROUTER.equals(provider) ? "https://openrouter.ai/api/v1" : "https://api.groq.com/openai/v1";
  }

  public String key() {
    return OPENROUTER.equals(provider) ? openrouterKey : groqKey;
  }

  public String pinnedModel() {
    if (model != null && !model.isBlank()) return model;
    return OPENROUTER.equals(provider) ? "google/gemini-2.5-flash-lite" : "qwen/qwen3.8-27b";
  }

  @AssertTrue(message = "app.ai.provider must be groq or openrouter")
  public boolean isProviderKnown() {
    return GROQ.equals(provider) || OPENROUTER.equals(provider);
  }

  @AssertTrue(message = "app.ai.jev-enabled needs OPENROUTER_API_KEY, and a pinned Jev model, never ~jev-latest")
  public boolean isJevUsable() {
    return !jevEnabled || (openrouterKey != null && !openrouterKey.isBlank() && jevModel != null && !jevModel.contains("latest"));
  }

  @AssertTrue(message = "app.ai.jev-live needs app.ai.jev-enabled: Jev reads nothing live that it is not also measured on")
  public boolean isJevLiveMeasured() {
    return !jevLive || jevEnabled;
  }

  @AssertTrue(message = "app.ai.enabled needs the provider's key (GROQ_API_KEY or OPENROUTER_API_KEY)")
  public boolean isKeyPresentWhenEnabled() {
    return !enabled || (key() != null && !key().isBlank());
  }
}
