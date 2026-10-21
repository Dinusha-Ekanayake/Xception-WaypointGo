-- Decision suggestions (docs/issues/decision-suggestions/PLAN.md): a decision
-- taken without a chip is logged too. When the chips beside a report are on
-- screen the chat records what was shown (an impression); when the stop is then
-- skipped or the trip ended by any route, Intelligence writes the decision row
-- from that impression, with the action taken and whether it came from a chip.
-- Append only, scoped by depot like the decisions.

CREATE TABLE IF NOT EXISTS ml.suggestion_impressions (
    impression_id      uuid        PRIMARY KEY,
    delivery_id        uuid        NOT NULL,
    depot_code         text        NOT NULL,
    situation          text        NOT NULL CHECK (situation ~ '^[A-Z_]+$' AND length(situation) <= 32),
    catalogue_version  integer     NOT NULL CHECK (catalogue_version BETWEEN 1 AND 1000),
    shown              text[]      NOT NULL CHECK (cardinality(shown) BETWEEN 1 AND 3),
    shown_by           uuid        NOT NULL,
    shown_at           timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_suggestion_impressions_delivery ON ml.suggestion_impressions (delivery_id, shown_at);

GRANT SELECT, INSERT ON ml.suggestion_impressions TO waypoint_ml;

ALTER TABLE ml.suggestion_impressions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.suggestion_impressions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS suggestion_impressions_scope ON ml.suggestion_impressions;
CREATE POLICY suggestion_impressions_scope ON ml.suggestion_impressions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_has_depot(depot_code));

-- The decision row says what was done, chip or not, and whether a chip did it.
ALTER TABLE ml.decision_suggestions ADD COLUMN IF NOT EXISTS taken text
    CHECK (taken IS NULL OR (taken ~ '^[A-Z_]+$' AND length(taken) <= 32));
ALTER TABLE ml.decision_suggestions ADD COLUMN IF NOT EXISTS via_chip boolean NOT NULL DEFAULT true;

-- Intelligence writes the row for a decision taken elsewhere, as the system.
DROP POLICY IF EXISTS decision_suggestions_scope ON ml.decision_suggestions;
CREATE POLICY decision_suggestions_scope ON ml.decision_suggestions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));
