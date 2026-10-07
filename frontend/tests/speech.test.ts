import assert from "node:assert/strict";
import test from "node:test";
import { cannotSpeak, languageOf, pickVoice } from "../src/shared/messaging/speech.ts";

// Every typed message can be listened to, in its own language, or the screen says why not.

test("the language comes from the script", () => {
  assert.equal(languageOf("Running 20 min late"), "en");
  assert.equal(languageOf("විනාඩි 20 ක් ප්‍රමාදයි"), "si");
  assert.equal(languageOf("20 நிமிடம் தாமதம்"), "ta");
  assert.equal(languageOf("OUT063 ලඟට ආවා"), "si", "mixed with a store code is still Sinhala");
});

test("a Sinhala message is never read with an English voice", () => {
  const voices = [
    { lang: "en-US", name: "Samantha", localService: true },
    { lang: "en-GB", name: "Daniel", localService: true },
    { lang: "ta-IN", name: "Tamil", localService: false },
  ];
  assert.equal(pickVoice(voices, "si"), null);
  assert.equal(pickVoice(voices, "ta")?.name, "Tamil");
  assert.equal(pickVoice(voices, "en")?.name, "Daniel", "British or South Asian English before American");
  assert.equal(pickVoice([{ lang: "si_LK", name: "Sinhala" }], "si")?.name, "Sinhala");
  assert.equal(cannotSpeak("විනාඩි 20", "no-voice"), "This phone has no Sinhala voice to read it.");
});
