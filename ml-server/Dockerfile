# Waypoint model service. Python 3.12 is where the final Datathon models reproduce their submissions.
FROM python:3.12-slim

# LightGBM needs the OpenMP runtime.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv
COPY requirements.txt .
RUN pip install --disable-pip-version-check -r requirements.txt

COPY manifest.json model-globals.txt ./
COPY app ./app
COPY datathon ./datathon
# The planning-time features: one definition for retraining and serving (MF-05).
COPY training ./training
COPY models ./models
# Every deploy builds this image, so a model file naming a class or function outside
# model-globals.txt fails the build before anything loads it, and the running
# service stays as it was. Read statically: nothing in the file is called.
RUN python -m app.pickle_scan models

RUN useradd --uid 10002 --no-create-home ml && chown -R ml /srv
USER ml

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"

# One worker: inference is CPU-bound and the models are loaded once per process.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
