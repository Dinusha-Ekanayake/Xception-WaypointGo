-- R-ML-14: what the fallback learned from Waypoint's own outcomes, refitted
-- nightly. Append-only: each fit is a row, the newest is used, and an empty
-- table means the documented rule (A-37) unchanged.
CREATE TABLE IF NOT EXISTS ml.fallback_tuning (
    tuning_id       uuid PRIMARY KEY,
    fitted_at       timestamptz   NOT NULL,
    window_from     date          NOT NULL,
    window_to       date          NOT NULL,
    labels          integer       NOT NULL CHECK (labels >= 0),
    intercept       numeric(10,6) NOT NULL,
    slope           numeric(10,6) NOT NULL CHECK (slope > 0),
    outlet_offsets  jsonb         NOT NULL DEFAULT '{}'::jsonb
);

COMMENT ON TABLE ml.fallback_tuning IS
  'R-ML-14: the fallback late probability''s calibration and per-outlet offsets, fitted on its own labelled outcomes.';

CREATE INDEX IF NOT EXISTS ix_fallback_tuning_fitted ON ml.fallback_tuning (fitted_at DESC);

GRANT SELECT, INSERT ON ml.fallback_tuning TO waypoint_ml;

ALTER TABLE ml.fallback_tuning ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.fallback_tuning FORCE ROW LEVEL SECURITY;
-- Not per depot: one fit over every depot, read by any ML reader.
CREATE POLICY fallback_tuning_read ON ml.fallback_tuning FOR SELECT TO waypoint_ml USING (true);
CREATE POLICY fallback_tuning_write ON ml.fallback_tuning
    FOR INSERT TO waypoint_ml WITH CHECK (app.actor_is_system());
