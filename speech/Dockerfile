# Waypoint's speech service: Piper text to speech for English, Sinhala and Tamil,
# with ffmpeg for the AAC audio every phone plays. Voices are downloaded at build
# time and checked against their SHA-256, so a changed file fails the build.
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir piper-tts==1.8.0

WORKDIR /voices
COPY voices.sha256 /voices/voices.sha256
RUN curl -fsSL -o en_GB-cori-medium.onnx https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/cori/medium/en_GB-cori-medium.onnx \
 && curl -fsSL -o en_GB-cori-medium.onnx.json https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/cori/medium/en_GB-cori-medium.onnx.json \
 && curl -fsSL -o si_LK-dilu-medium.onnx https://huggingface.co/chaturadilan/dilu-tts/resolve/main/onnx/si_LK-dilu-medium.onnx \
 && curl -fsSL -o si_LK-dilu-medium.onnx.json https://huggingface.co/chaturadilan/dilu-tts/resolve/main/onnx/si_LK-dilu-medium.onnx.json \
 && curl -fsSL -o ta_IN-rasa_male-medium.onnx https://huggingface.co/tinisoft/piper-ta_IN-rasa_male-medium/resolve/main/ta_IN-rasa_male-medium.onnx \
 && curl -fsSL -o ta_IN-rasa_male-medium.onnx.json https://huggingface.co/tinisoft/piper-ta_IN-rasa_male-medium/resolve/main/ta_IN-rasa_male-medium.onnx.json \
 && sha256sum -c voices.sha256

WORKDIR /app
COPY server.py /app/server.py
RUN useradd --system --no-create-home speech
USER speech
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s CMD curl -fsS http://127.0.0.1:8090/health || exit 1
CMD ["python", "/app/server.py"]
