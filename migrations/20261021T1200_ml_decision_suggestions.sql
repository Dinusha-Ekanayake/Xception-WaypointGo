-- Issue #269 and docs/issues/decision-suggestions/PLAN.md: the decisions
-- suggested beside a stop and the one the dispatcher took, or none. The log the
-- learned chances will count, and the measure of whether suggestions help.
-- Action keys and ids only, no text. Append only; scoped by depot like the
-- risk reviews.

CREATE TABLE IF NOT EXISTS ml.decision_suggestions (
    suggestion_id      uuid        PRIMARY KEY,
    delivery_id        uuid        NOT NULL,
    depot_code         text        NOT NULL,
    situation          text        NOT NULL CHECK (situation ~ '^[A-Z_]+$' AND length(situation) <= 32),
    catalogue_version  integer     NOT NULL CHECK (catalogue_version BETWEEN 1 AND 1000),
    shown              text[]      NOT NULL CHECK (cardinality(shown) BETWEEN 1 AND 3),
    chosen             text        CHECK (chosen IS NULL OR chosen = ANY (shown)),
    decided_by         uuid        NOT NULL,
    decided_at         timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_decision_suggestions_situation ON ml.decision_suggestions (situation, decided_at);
CREATE INDEX IF NOT EXISTS ix_decision_suggestions_delivery ON ml.decision_suggestions (delivery_id);

GRANT SELECT, INSERT ON ml.decision_suggestions TO waypoint_ml;

ALTER TABLE ml.decision_suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.decision_suggestions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS decision_suggestions_scope ON ml.decision_suggestions;
CREATE POLICY decision_suggestions_scope ON ml.decision_suggestions
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_has_depot(depot_code));

-- A new action needs a catalogue row and a handler (R-IAM). Only the dispatcher
-- policy gets it; the reads are ml:Read, which the dispatcher already holds.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:RecordSuggestion', 'ml', 'Record the decisions suggested beside a stop and the one taken', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.grant_suggestions(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'DecisionSuggestions')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'DecisionSuggestions',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('ml:RecordSuggestion'),
                           'Resource', jsonb_build_array('wpt:ml:delivery:*'))))
    END
$$;

CREATE TEMP TABLE suggestion_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_suggestions(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM suggestion_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM suggestion_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM suggestion_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM suggestion_policy_docs);
