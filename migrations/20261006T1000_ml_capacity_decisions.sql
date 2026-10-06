-- Roadmap T2-01: what the dispatcher decided about a week the capacity plan
-- shows short of vehicles or reefers (hire, move workshop days, advise stores,
-- accept the shortfall), with who, when and why (decisions are recorded).
--
-- Append only: a decision is never edited or deleted. A change of mind is a
-- new decision for the same week, so the trail shows both.

CREATE TABLE ml.capacity_decisions (
    decision_id  uuid        PRIMARY KEY,
    depot_code   text        NOT NULL,
    iso_year     integer     NOT NULL CHECK (iso_year BETWEEN 2000 AND 2100),
    iso_week     integer     NOT NULL CHECK (iso_week BETWEEN 1 AND 53),
    action       text        NOT NULL CHECK (action IN ('HIRE_VEHICLES', 'MOVE_WORKSHOP', 'ADVISE_STORES', 'ACCEPT_SHORTFALL', 'OTHER')),
    vehicles     integer     CHECK (vehicles IS NULL OR vehicles BETWEEN 1 AND 60),
    refrigerated boolean     NOT NULL DEFAULT false,
    reason       text        NOT NULL CHECK (length(btrim(reason)) >= 3 AND length(reason) <= 500),
    decided_by   uuid        NOT NULL,
    decided_at   timestamptz NOT NULL
);

CREATE INDEX capacity_decisions_week ON ml.capacity_decisions (depot_code, iso_year, iso_week, decided_at);

GRANT SELECT, INSERT ON ml.capacity_decisions TO waypoint_ml;

ALTER TABLE ml.capacity_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.capacity_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY capacity_decisions_scope ON ml.capacity_decisions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_has_depot(depot_code));
