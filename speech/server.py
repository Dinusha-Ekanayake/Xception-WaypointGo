"""Waypoint's speech service: text to speech with Piper, on our own server.

POST /speak {"text": "...", "lang": "en" | "si" | "ta"} answers AAC audio in an
MP4 container (audio/mp4), mono, 48 kbps: the one format every phone plays,
iPhones included. GET /health answers the voices loaded. Nothing leaves this
container, and no text is logged: a message can hold personal data.
"""

import io
import json
import os
import subprocess
import tempfile
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from piper import PiperVoice

VOICES = {
    "en": "en_GB-cori-medium",
    "si": "si_LK-dilu-medium",
    "ta": "ta_IN-rasa_male-medium",
}
VOICE_DIR = os.environ.get("VOICE_DIR", "/voices")
MAX_TEXT = 600
LOADED = {lang: PiperVoice.load(os.path.join(VOICE_DIR, name + ".onnx")) for lang, name in VOICES.items()}
# One synthesis at a time per voice: Piper's model is not shared across threads safely.
LOCKS = {lang: threading.Lock() for lang in VOICES}


def speak(text, lang):
    voice = LOADED[lang]
    with LOCKS[lang]:
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as w:
            voice.synthesize_wav(text, w)
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "in.wav")
        dst = os.path.join(tmp, "out.m4a")
        with open(src, "wb") as f:
            f.write(buffer.getvalue())
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-c:a", "aac", "-b:a", "48k",
             "-movflags", "+faststart", dst],
            check=True, timeout=30)
        with open(dst, "rb") as f:
            return f.read()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # never log a request: the text may be personal
        pass

    def reply(self, status, body, kind="application/json"):
        self.send_response(status)
        self.send_header("content-type", kind)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, json.dumps({"voices": VOICES}).encode())
        return self.reply(404, b'{"error":"not found"}')

    def do_POST(self):
        if self.path != "/speak":
            return self.reply(404, b'{"error":"not found"}')
        try:
            length = int(self.headers.get("content-length", "0"))
            if length <= 0 or length > 8_192:
                return self.reply(400, b'{"error":"body is missing or too long"}')
            body = json.loads(self.rfile.read(length))
            text = str(body.get("text", "")).strip()
            lang = str(body.get("lang", ""))
            if not text or len(text) > MAX_TEXT or lang not in LOADED:
                return self.reply(400, b'{"error":"text 1 to 600 characters and lang en, si or ta"}')
            return self.reply(200, speak(text, lang), "audio/mp4")
        except (ValueError, KeyError):
            return self.reply(400, b'{"error":"bad request"}')
        except Exception:
            return self.reply(500, b'{"error":"could not speak"}')


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8090"))), Handler).serve_forever()
