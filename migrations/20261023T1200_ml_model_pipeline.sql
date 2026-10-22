-- Issue #12 (docs/issues/012-mlops-pipeline/PLAN.md): the models as a pipeline.
--
--     A model may be the shadow of its kind: it scores every published plan
--     beside the active model and never answers (R-ML-38). At most one shadow per
--     kind. Its role says how it got there: a candidate retraining produced
--     (ml:ShadowModel), or the model a promotion replaced, kept as the
--     challenger so the system can put it back (R-ML-40).
--
--     Shadow predictions live apart from ml.delivery_predictions, which every
--     screen, outcome and metric reads: a shadow must change none of them.
--     Reviews and model events are records (rule 8) and are never deleted.

-- ---- the registry -------------------------------------------------------------------

ALTER TABLE ml.model_versions DROP CONSTRAINT IF EXISTS ck_model_status;
ALTER TABLE ml.model_versions ADD CONSTRAINT ck_model_status
    CHECK (status IN ('registered', 'active', 'shadow', 'retired'));

ALTER TABLE ml.model_versions ADD COLUMN IF NOT EXISTS shadow_role text;
ALTER TABLE ml.model_versions ADD COLUMN IF NOT EXISTS shadowed_by uuid;
ALTER TABLE ml.model_versions ADD COLUMN IF NOT EXISTS shadowed_at timestamptz;

ALTER TABLE ml.model_versions DROP CONSTRAINT IF EXISTS ck_model_shadow;
ALTER TABLE ml.model_versions ADD CONSTRAINT ck_model_shadow CHECK (
    (status = 'shadow') = (shadow_role IS NOT NULL)
    AND (shadow_role IS NULL
         OR (shadow_role IN ('candidate', 'replaced') AND shadowed_by IS NOT NULL AND shadowed_at IS NOT NULL)));

CREATE UNIQUE INDEX IF NOT EXISTS ux_model_versions_shadow ON ml.model_versions (kind) WHERE status = 'shadow';

COMMENT ON COLUMN ml.model_versions.shadow_role IS
  'candidate: retraining produced it; replaced: a promotion replaced it and it stays the challenger (issue #12).';

-- ---- what the shadow predicted ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ml.shadow_predictions (
    shadow_prediction_id uuid          PRIMARY KEY,
    plan_id              uuid          NOT NULL REFERENCES ml.plan_scorings (plan_id),
    order_id             uuid          NOT NULL,
    depot_code           text          NOT NULL,
    service_date         date          NOT NULL,
    model_version_id     uuid          NOT NULL REFERENCES ml.model_versions (model_version_id),
    model_label          text          NOT NULL,
    service_min          numeric(8,2)  NOT NULL,
    late_prob            numeric(6,4)  NOT NULL,
    generated_at         timestamptz   NOT NULL,
    CONSTRAINT uq_shadow_predictions UNIQUE (plan_id, order_id, model_label),
    CONSTRAINT ck_shadow_prediction_values CHECK (service_min >= 0 AND late_prob BETWEEN 0 AND 1)
);

CREATE INDEX IF NOT EXISTS ix_shadow_predictions_model ON ml.shadow_predictions (model_version_id, service_date);
CREATE INDEX IF NOT EXISTS ix_shadow_predictions_order ON ml.shadow_predictions (order_id);

COMMENT ON TABLE ml.shadow_predictions IS
  'The shadow model''s answer for each stop of a published plan (R-ML-38). Read by the shadow review only, never by a screen.';

-- ---- the nightly review -------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ml.shadow_reviews (
    review_id           uuid          PRIMARY KEY,
    kind                text          NOT NULL,
    model_version_id    uuid          NOT NULL REFERENCES ml.model_versions (model_version_id),
    model_label         text          NOT NULL,
    shadow_role         text          NOT NULL,
    incumbent_label     text          NOT NULL,
    reviewed_on         date          NOT NULL,
    window_from         date          NOT NULL,
    window_to           date          NOT NULL,
    stops               integer       NOT NULL,
    shadow_brier        numeric(10,6),
    shadow_log_loss     numeric(10,6),
    incumbent_brier     numeric(10,6),
    incumbent_log_loss  numeric(10,6),
    verdict             text          NOT NULL,
    action              text,
    reviewed_at         timestamptz   NOT NULL,
    CONSTRAINT uq_shadow_reviews UNIQUE (model_version_id, reviewed_on),
    CONSTRAINT ck_shadow_review_role CHECK (shadow_role IN ('candidate', 'replaced')),
    CONSTRAINT ck_shadow_review_verdict CHECK (verdict IN ('BETTER', 'WORSE', 'MIXED', 'NOT_ENOUGH')),
    CONSTRAINT ck_shadow_review_action CHECK (action IS NULL OR action = 'rolled_back'),
    CONSTRAINT ck_shadow_review_window CHECK (window_from <= window_to AND stops >= 0)
);

CREATE INDEX IF NOT EXISTS ix_shadow_reviews_day ON ml.shadow_reviews (reviewed_on DESC);

COMMENT ON TABLE ml.shadow_reviews IS
  'Each night, the shadow against the active model on the same labelled stops (R-ML-38). A rerun the same day replaces its figures.';

-- ---- every change of a model's standing ---------------------------------------------------

CREATE TABLE IF NOT EXISTS ml.model_events (
    event_id          uuid         PRIMARY KEY,
    model_version_id  uuid         NOT NULL REFERENCES ml.model_versions (model_version_id),
    action            text         NOT NULL,
    actor_id          uuid         NOT NULL,
    reason            text,
    review_id         uuid         REFERENCES ml.shadow_reviews (review_id),
    occurred_at       timestamptz  NOT NULL,
    CONSTRAINT ck_model_event_action
        CHECK (action IN ('shadowed', 'activated', 'demoted', 'rolled_back')),
    -- Rule 8: the system's one decision carries its reason and the review behind it.
    CONSTRAINT ck_model_event_rollback CHECK (action <> 'rolled_back' OR (reason IS NOT NULL AND review_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_model_events_model ON ml.model_events (model_version_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_model_events_review ON ml.model_events (review_id);

COMMENT ON TABLE ml.model_events IS
  'Append only: a model made shadow, activated, demoted or rolled back, by whom, why and when (issue #12, rule 8).';

-- ---- privileges and row-level security ---------------------------------------------------

GRANT SELECT, INSERT ON ml.shadow_predictions, ml.model_events TO waypoint_ml;
GRANT SELECT, INSERT, UPDATE ON ml.shadow_reviews TO waypoint_ml;

ALTER TABLE ml.shadow_predictions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.shadow_predictions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shadow_predictions_scope ON ml.shadow_predictions;
CREATE POLICY shadow_predictions_scope ON ml.shadow_predictions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());

-- A review is a model's figures over every depot, like the registry and its backtest: read by
-- anyone whose policy grants ml:Read, written by the process.
ALTER TABLE ml.shadow_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.shadow_reviews FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shadow_reviews_read ON ml.shadow_reviews;
CREATE POLICY shadow_reviews_read ON ml.shadow_reviews FOR SELECT TO waypoint_ml USING (true);
DROP POLICY IF EXISTS shadow_reviews_write ON ml.shadow_reviews;
CREATE POLICY shadow_reviews_write ON ml.shadow_reviews
    FOR INSERT TO waypoint_ml WITH CHECK (app.actor_is_system());
DROP POLICY IF EXISTS shadow_reviews_update ON ml.shadow_reviews;
CREATE POLICY shadow_reviews_update ON ml.shadow_reviews
    FOR UPDATE TO waypoint_ml USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

-- Written by the registry's commands (the administrator) and by the rollback (the process), as the registry is.
ALTER TABLE ml.model_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.model_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS model_events_read ON ml.model_events;
CREATE POLICY model_events_read ON ml.model_events FOR SELECT TO waypoint_ml USING (true);
DROP POLICY IF EXISTS model_events_write ON ml.model_events;
CREATE POLICY model_events_write ON ml.model_events FOR INSERT TO waypoint_ml WITH CHECK (true);
