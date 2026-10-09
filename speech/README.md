# Speech service

Text to speech for Waypoint's messages on our own server (roadmap: every message
can be heard; R-MSG-10). Piper with three voices; nothing leaves the container.

| Language | Voice | Licence |
| --- | --- | --- |
| English | `en_GB-cori-medium` (rhasspy/piper-voices) | Dataset public domain (LibriVox) |
| Sinhala | `si_LK-dilu-medium` (chaturadilan/dilu-tts) | MIT |
| Tamil | `ta_IN-rasa_male-medium` (tinisoft), Indian Tamil | CC BY 4.0: credit "ta_IN-rasa_male-medium by tinisoft" |

Piper itself (`piper-tts` 1.8, OHF-Voice/piper1-gpl) is GPL-3.0. It runs as its
own process behind HTTP, so Waypoint's code does not link it.

- `POST /speak` with `{"text": "...", "lang": "en" | "si" | "ta"}` answers `audio/mp4` (AAC, mono, 48 kbps).
- `GET /health` lists the voices.

The backend calls it from a job, never while a request waits, and only when
`SPEECH_URL` is set. Run it with `docker compose --profile speech up -d speech`.
