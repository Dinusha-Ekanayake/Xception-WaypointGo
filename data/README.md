# Reference data

Copied from the supplied synthetic Tech-Triathlon 2026 records and tracked here so a fresh clone and the Docker image need no separate download.

- `General Data/`: all 120 outlets, all 60 vehicles, the operating calendar, district travel profiles and service allowances, unchanged. Also the traffic speed index (district, hour, monsoon) and the road conditions (district, date, 2024-01-01 to 2026-06-28), which the predictor reads (issue #16).
- `Training Data/` and `Test Data/`: supplied order and availability records, kept for reference and fixtures.
- `provenance.json`: original locations, transformations and SHA-256 hashes.

`java -jar backend.jar import-reference` stages these CSVs, validates them and publishes them as one reference version in `ref`, or changes nothing if the content hash matches the current version. Past the end of the supplied calendar, days come from the extension policy (R-CAL-03), and single days are overridden with the `calendar:Override` command, never by editing these files. `waypoint_reference_calendar_days_remaining` warns before the supplied calendar runs out.

The original `Tech-Triathlon 2026/` directory remains untouched and ignored. These are synthetic records, not validated production data, and contain no personal information.
