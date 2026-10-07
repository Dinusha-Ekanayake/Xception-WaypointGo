// Reading a typed message aloud on the phone, in the message's own language
// (roadmap: listen to every message). The language comes from the script: a
// Sinhala letter means Sinhala, a Tamil letter Tamil, else English. A phone
// with no voice for that language says so; a Sinhala message is never read with
// an English voice. Server-made audio for every language comes later; this is
// the phone's own voice, labelled as such.

export type SpeechLang = "si" | "ta" | "en";

const SINHALA = /[඀-෿]/;
const TAMIL = /[஀-௿]/;

export function languageOf(text: string): SpeechLang {
  if (SINHALA.test(text)) return "si";
  if (TAMIL.test(text)) return "ta";
  return "en";
}

export type VoiceLike = { lang: string; name: string; localService?: boolean };

/** The phone's voice for the language, preferring one on the device (works with no signal); null when it has none. */
export function pickVoice<V extends VoiceLike>(voices: V[], lang: SpeechLang): V | null {
  const fits = voices.filter((v) => v.lang.toLowerCase().replace("_", "-").startsWith(lang));
  if (fits.length === 0) return null;
  const local = fits.filter((v) => v.localService);
  const pool = local.length > 0 ? local : fits;
  if (lang === "en") return pool.find((v) => /en-(GB|IN|LK)/i.test(v.lang)) ?? pool[0]!;
  return pool[0]!;
}

export type Spoken = "done" | "stopped" | "no-voice" | "unsupported";

/** The voices, which some browsers only list after a moment. */
export function voices(): Promise<SpeechSynthesisVoice[]> {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return Promise.resolve([]);
  const now = window.speechSynthesis.getVoices();
  if (now.length > 0) return Promise.resolve(now);
  return new Promise((resolve) => {
    const done = () => resolve(window.speechSynthesis.getVoices());
    window.speechSynthesis.addEventListener("voiceschanged", done, { once: true });
    setTimeout(done, 1_500);
  });
}

/** Reads the text aloud in its language; resolves when it ends, or with why it could not. */
export async function speak(text: string): Promise<Spoken> {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return "unsupported";
  const lang = languageOf(text);
  const voice = pickVoice(await voices(), lang);
  if (!voice) return "no-voice";
  window.speechSynthesis.cancel();
  return new Promise((resolve) => {
    const u = new SpeechSynthesisUtterance(text);
    u.voice = voice;
    u.lang = voice.lang;
    u.rate = 0.95;
    u.onend = () => resolve("done");
    u.onerror = () => resolve("stopped");
    window.speechSynthesis.speak(u);
  });
}

export function stopSpeaking(): void {
  if (typeof window !== "undefined" && "speechSynthesis" in window) window.speechSynthesis.cancel();
}

/** What the screen says when the phone cannot read a message aloud. */
export function cannotSpeak(text: string, why: Exclude<Spoken, "done" | "stopped">): string {
  if (why === "unsupported") return "This browser cannot read messages aloud.";
  const lang = languageOf(text);
  return lang === "si" ? "This phone has no Sinhala voice to read it." : lang === "ta" ? "This phone has no Tamil voice to read it." : "This phone has no voice to read it.";
}

/**
 * A typed message spoken on our own server (R-MSG-10): played when it is ready,
 * else "missing" so the caller can use the phone's voice instead. Resolves when
 * the audio ends.
 */
export function playServerSpeech(threadId: string, messageId: string, onAudio?: (a: HTMLAudioElement) => void): Promise<"done" | "missing"> {
  if (typeof window === "undefined" || typeof Audio === "undefined") return Promise.resolve("missing");
  return new Promise((resolve) => {
    const a = new Audio(`/api/threads/${encodeURIComponent(threadId)}/messages/${encodeURIComponent(messageId)}/speech`);
    onAudio?.(a);
    a.onended = () => resolve("done");
    a.onerror = () => resolve("missing");
    a.play().catch(() => resolve("missing"));
  });
}

