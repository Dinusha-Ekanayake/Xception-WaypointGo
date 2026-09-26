# Reproducible competition data

This is the application seed, copied from the supplied synthetic Tech-Triathlon 2026 records. It is included in the repository and Docker image so judges do not need the original download folder.

- General Data: all 120 outlets, all 60 vehicles, full operating calendar, district travel and service allowances, unchanged.
- Training Data/deliveries_train.csv: only February 9, 12, 13 and 14, 2026. All source columns and order identifiers are preserved. This is representative operational seed data, not model training.
- Test Data: supplied S1 peak-day orders and vehicle availability, unchanged. The app assigns this scenario February 16, 2026 for demonstration. These are not Datathon outputs.
- `provenance.json`: original locations, transformations and SHA-256 hashes.

The active Spring seed and scenario logic lives in `backend/src/main/java/com/waypoint/dispatch/service/DispatchService.java`; `frontend/lib/scenarios.ts` is the legacy Node equivalent used by regression tests. February 9 has simulated delivery outcomes, February 17 has simulated fuel pressure, and February 18 has isolated constraint boundary fixtures. No production transactions or personal information are included.

The original `Tech-Triathlon 2026/` directory remains untouched and ignored. The app's runtime source is this tracked `data/` directory, loaded by Spring's `ReferenceLoader`. Production mode extends the calendar in memory and can apply `CALENDAR_FILE` overrides without changing these source CSVs. This does not turn the synthetic outlet, fleet or travel records into validated production data.
