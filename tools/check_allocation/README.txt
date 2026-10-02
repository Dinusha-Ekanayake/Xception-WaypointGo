check_allocation.py is the official Task 2B feasibility checker from the
competition dataset bundle, vendored unmodified (sha256 a9e02c46...dc0bc).
Do not edit it: CI must judge our output exactly as the organisers will.

It looks for its CSVs under data/ beside itself, so data/ here is a symlink
to the repository's data/ folder.

Run after the backend tests have written the engine's peak-day allocation:

  python tools/check_allocation/check_allocation.py backend/target/task2b/submission_task2b.csv
