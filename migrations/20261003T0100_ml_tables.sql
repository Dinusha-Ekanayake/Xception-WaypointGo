-- Intelligence's tables (issue #16). Schema and role came with 20260930T1200.
--
--     Predictions support planning; they are never part of it. Nothing here is
--     written inside an operational transaction, every stored prediction names
--     the model that produced it (or "deterministic"), so a result can be
--     reproduced and a bad model traced, and nothing is ever deleted: a model is
--     retired, a scoring is superseded by a newer generation.
--
--     Plans, orders, trips and outlets are referenced by id with no foreign key
--     (D-B, DATA-MODEL-REVIEW finding 19). Foreign keys point only into iam and
--     within this schema.

-- ---- model registry -----------------------------------------------------------

CREATE TABLE ml.model_versions (
    model_version_id uuid PRIMARY KEY,
    model_name       text        NOT NULL,
    model_version    text        NOT NULL,
    kind             text        NOT NULL,
    status           text        NOT NULL,
    metrics          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    trained_from     date,
    trained_to       date,
    registered_by    uuid        NOT NULL,
    registered_at    timestamptz NOT NULL,
    activated_by     uuid,
    activated_at     timestamptz,
    retired_by       uuid,
    retired_at       timestamptz,
    retired_reason   text,
    row_version      bigint      NOT NULL DEFAULT 1,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT uq_model_versions UNIQUE (model_name, model_version),
    CONSTRAINT ck_model_kind CHECK (kind IN ('delivery_risk','demand_forecast')),
    CONSTRAINT ck_model_status CHECK (status IN ('registered','active','retired')),
    CONSTRAINT ck_model_trained CHECK (trained_from IS NULL OR trained_to IS NULL OR trained_from <= trained_to),
    -- Rule 8: a retirement carries an actor, a reason and a time.
    CONSTRAINT ck_model_retired CHECK (
        (status = 'retired') = (retired_at IS NOT NULL)
        AND (retired_at IS NULL OR (retired_by IS NOT NULL AND retired_reason IS NOT NULL)))
);

-- One active model per kind: activating another returns the first to registered.
CREATE UNIQUE INDEX ux_model_versions_active ON ml.model_versions (kind) WHERE status = 'active';

COMMENT ON TABLE ml.model_versions IS
  'Model registry. A model is used only while active and only when the serving process reports the same name and version.';

-- ---- plan scoring --------------------------------------------------------------

CREATE TABLE ml.plan_scorings (
    plan_id          uuid PRIMARY KEY,
    depot_code       text        NOT NULL,
    service_date     date        NOT NULL,
    plan_version     integer     NOT NULL,
    status           text        NOT NULL,
    model_version_id uuid        REFERENCES ml.model_versions (model_version_id),
    model_label      text,
    road_conditions  text,
    reason           text,
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    requested_at     timestamptz NOT NULL,
    scored_at        timestamptz,
    row_version      bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_scoring_status CHECK (status IN ('pending','scored','degraded')),
    CONSTRAINT ck_scoring_road CHECK (road_conditions IS NULL OR road_conditions IN ('used','fallback')),
    -- A model scoring names its model; a degraded one says why.
    CONSTRAINT ck_scoring_scored CHECK (status <> 'scored' OR (model_version_id IS NOT NULL AND scored_at IS NOT NULL)),
    CONSTRAINT ck_scoring_degraded CHECK (status <> 'degraded' OR (reason IS NOT NULL AND scored_at IS NOT NULL)),
    CONSTRAINT ck_scoring_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_plan_scorings_due ON ml.plan_scorings (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX ix_plan_scorings_depot ON ml.plan_scorings (depot_code, service_date);

COMMENT ON TABLE ml.plan_scorings IS
  'One per published plan: whether its stops were scored by a model or by the deterministic estimator, and why.';

CREATE TABLE ml.delivery_predictions (
    prediction_id    uuid PRIMARY KEY,
    plan_id          uuid          NOT NULL REFERENCES ml.plan_scorings (plan_id),
    order_id         uuid          NOT NULL,
    trip_id          uuid          NOT NULL,
    stop_sequence    integer       NOT NULL,
    outlet_id        text          NOT NULL,
    depot_code       text          NOT NULL,
    model_version_id uuid          REFERENCES ml.model_versions (model_version_id),
    model_label      text          NOT NULL,
    service_min      numeric(8,2)  NOT NULL,
    late_prob        numeric(6,4)  NOT NULL,
    degraded         boolean       NOT NULL,
    generated_at     timestamptz   NOT NULL,
    -- One generation per model label: a degraded scoring later rescored by the
    -- model keeps both, and reads prefer the model's.
    CONSTRAINT uq_delivery_predictions UNIQUE (plan_id, order_id, model_label),
    CONSTRAINT ck_prediction_values CHECK (service_min >= 0 AND late_prob BETWEEN 0 AND 1),
    CONSTRAINT ck_prediction_model CHECK (degraded OR model_version_id IS NOT NULL)
);

CREATE INDEX ix_delivery_predictions_plan ON ml.delivery_predictions (plan_id, degraded);
CREATE INDEX ix_delivery_predictions_order ON ml.delivery_predictions (order_id, generated_at DESC);

-- ---- demand forecasts -------------------------------------------------------------

CREATE TABLE ml.demand_forecasts (
    forecast_id      uuid PRIMARY KEY,
    run_id           uuid          NOT NULL,
    depot_code       text          NOT NULL,
    brand_code       text          NOT NULL,
    iso_year         integer       NOT NULL,
    iso_week         smallint      NOT NULL,
    total_m3         numeric(14,4) NOT NULL,
    chilled_m3       numeric(14,4) NOT NULL,
    model_version_id uuid          REFERENCES ml.model_versions (model_version_id),
    model_label      text          NOT NULL,
    degraded         boolean       NOT NULL,
    generated_at     timestamptz   NOT NULL,
    CONSTRAINT uq_demand_forecasts UNIQUE (run_id, depot_code, brand_code, iso_year, iso_week),
    CONSTRAINT ck_forecast_week CHECK (iso_week BETWEEN 1 AND 53),
    CONSTRAINT ck_forecast_values CHECK (total_m3 >= 0 AND chilled_m3 >= 0 AND chilled_m3 <= total_m3),
    CONSTRAINT ck_forecast_model CHECK (degraded OR model_version_id IS NOT NULL)
);

CREATE INDEX ix_demand_forecasts_period
    ON ml.demand_forecasts (depot_code, brand_code, iso_year, iso_week, generated_at DESC);

COMMENT ON TABLE ml.demand_forecasts IS
  'Weekly depot x brand forecasts. Every run is kept; a read takes the newest per week.';

-- ---- privileges ----------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE
    ON ml.model_versions, ml.plan_scorings, ml.delivery_predictions, ml.demand_forecasts
    TO waypoint_ml;
-- A prediction or forecast, once written, is a record of what was predicted.
REVOKE UPDATE ON ml.delivery_predictions, ml.demand_forecasts FROM waypoint_ml;

-- ---- row-level security ---------------------------------------------------------------
-- The registry is readable by anyone whose policy grants ml:Read. Predictions,
-- scorings and forecasts belong to a depot, and a reader sees only their depots;
-- the process sees everything in this schema.

ALTER TABLE ml.model_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.model_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY model_versions_read ON ml.model_versions FOR SELECT TO waypoint_ml USING (true);
CREATE POLICY model_versions_write ON ml.model_versions FOR INSERT TO waypoint_ml WITH CHECK (true);
CREATE POLICY model_versions_update ON ml.model_versions FOR UPDATE TO waypoint_ml USING (true) WITH CHECK (true);

ALTER TABLE ml.plan_scorings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.plan_scorings FORCE ROW LEVEL SECURITY;
CREATE POLICY plan_scorings_scope ON ml.plan_scorings
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());

ALTER TABLE ml.delivery_predictions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.delivery_predictions FORCE ROW LEVEL SECURITY;
CREATE POLICY delivery_predictions_scope ON ml.delivery_predictions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());

ALTER TABLE ml.demand_forecasts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.demand_forecasts FORCE ROW LEVEL SECURITY;
CREATE POLICY demand_forecasts_scope ON ml.demand_forecasts
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());
