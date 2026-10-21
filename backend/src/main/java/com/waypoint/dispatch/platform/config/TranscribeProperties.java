package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Voice note transcription (roadmap FF-05). Blank is off: notes still play and
 * the screen says there is no transcript (rule 9). {@code gemini} sends the
 * audio to Google's Gemini API, which takes Sinhala and Tamil; the recording
 * leaves the country, so production turns it on only once the data processing
 * agreement is signed (policy P-PD-1).
 *
 * @param engine blank or {@code gemini}
 * @param geminiKey the Gemini API key; required with {@code gemini}, never logged
 * @param model the Gemini model, pinned. The default heard place names right in
 *     trials where the lite models did not ("Kandy" as "candy"); it takes about a
 *     minute a note, which a background job can afford
 * @param thinking the model's thinking level, blank for a model that has none
 * @param timeout one note
 * @param keepAfterAudio R-MSG-15: when a note's audio passes its retention, keep the
 *     words, marked "audio no longer kept"; false clears them with the audio. Legal
 *     to confirm (P-33)
 */
@Validated
@ConfigurationProperties(prefix = "app.transcribe")
public record TranscribeProperties(
    @DefaultValue("") String engine,
    @DefaultValue("") String geminiKey,
    @DefaultValue("gemini-3.8-flash") @NotNull String model,
    @DefaultValue("low") @NotNull String thinking,
    @DefaultValue("120s") @NotNull Duration timeout,
    @DefaultValue("true") boolean keepAfterAudio) {

  public boolean enabled() {
    return engine != null && !engine.isBlank();
  }

  @AssertTrue(message = "app.transcribe.engine must be blank or gemini")
  public boolean isEngineKnown() {
    return !enabled() || "gemini".equals(engine.strip());
  }

  @AssertTrue(message = "app.transcribe.engine=gemini needs GEMINI_API_KEY")
  public boolean isKeyPresent() {
    return !enabled() || (geminiKey != null && !geminiKey.isBlank());
  }

  @Override
  public String toString() {
    return "TranscribeProperties[engine=" + engine + ", model=" + model + ", thinking=" + thinking + ", timeout=" + timeout + ", keepAfterAudio=" + keepAfterAudio + "]";
  }
}
