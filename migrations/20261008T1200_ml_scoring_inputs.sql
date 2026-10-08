-- Roadmap MF-05: what the model was given for each route when a plan was
-- scored, as the model server reads it, so a model is retrained and replayed on
-- the facts known at publication, never on ones learned afterwards (R-ML-18).
-- Written for every scored plan, by a model or by the built-in estimate. Holds
-- outlet, vehicle and order figures only: no names, no personal data.
CREATE TABLE IF NOT EXISTS ml.scoring_inputs (
    plan_id              uuid         NOT NULL REFERENCES ml.plan_scorings (plan_id),
    route_id             text         NOT NULL,
    depot_code           text         NOT NULL,
    service_date         date         NOT NULL,
    reference_version_id uuid,
    route                jsonb        NOT NULL,
    recorded_at          timestamptz  NOT NULL,
    PRIMARY KEY (plan_id, route_id)
);

COMMENT ON TABLE ml.scoring_inputs IS
  'MF-05: each scored route as sent to the model server, the training and replay input as at publication.';

CREATE INDEX IF NOT EXISTS ix_scoring_inputs_day ON ml.scoring_inputs (depot_code, service_date, plan_id, route_id);

GRANT SELECT, INSERT, UPDATE ON ml.scoring_inputs TO waypoint_ml;

ALTER TABLE ml.scoring_inputs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.scoring_inputs FORCE ROW LEVEL SECURITY;
-- Read by those who hold the depot; written by the process.
CREATE POLICY scoring_inputs_read ON ml.scoring_inputs
    FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY scoring_inputs_write ON ml.scoring_inputs
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
