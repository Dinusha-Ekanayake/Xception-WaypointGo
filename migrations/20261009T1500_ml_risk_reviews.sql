-- Roadmap T1-01: before Publish, the dispatcher keeps or swaps each stop likely
-- to arrive after its window, with a reason (decisions are recorded). Advice
-- only: the plan is changed, if at all, by the dispatcher's own plan commands.
--
-- Append only: a change of mind is a new row for the same stop, so the trail
-- shows both. Scoped by depot like every plan read.

CREATE TABLE IF NOT EXISTS ml.risk_reviews (
    review_id   uuid        PRIMARY KEY,
    plan_id     uuid        NOT NULL,
    depot_code  text        NOT NULL,
    order_id    uuid        NOT NULL,
    percent     integer     NOT NULL CHECK (percent BETWEEN 0 AND 100),
    choice      text        NOT NULL CHECK (choice IN ('KEEP', 'SWAP')),
    reason      text        NOT NULL CHECK (length(btrim(reason)) >= 3 AND length(reason) <= 500),
    decided_by  uuid        NOT NULL,
    decided_at  timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_risk_reviews_plan ON ml.risk_reviews (plan_id, order_id, decided_at);

GRANT SELECT, INSERT ON ml.risk_reviews TO waypoint_ml;

ALTER TABLE ml.risk_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.risk_reviews FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS risk_reviews_scope ON ml.risk_reviews;
CREATE POLICY risk_reviews_scope ON ml.risk_reviews
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_has_depot(depot_code));
