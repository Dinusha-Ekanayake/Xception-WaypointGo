-- Decision suggestions part 2 (R-ML-35, docs/issues/decision-suggestions/PLAN.md):
-- what Jev read from a driver's or a store's own words on a trip's chat, typed,
-- spoken or a report sent as "other". Its class at or above the live confidence
-- is a signal on the trip's Next step card until the dispatcher dismisses it or
-- what it is about is decided. The masked first line is kept for the question
-- and its retries; never the whole message. Scoped by depot; never deleted.

CREATE TABLE IF NOT EXISTS ml.message_situations (
    message_id      uuid          PRIMARY KEY,
    trip_id         text          NOT NULL,
    depot_code      text          NOT NULL,
    author_role     text          NOT NULL CHECK (author_role IN ('driver', 'store_manager', 'loader')),
    outlet_id       text,
    source          text          NOT NULL CHECK (source IN ('message', 'voice', 'report')),
    masked_text     text          NOT NULL CHECK (length(masked_text) <= 400),
    option_order    text[]        NOT NULL,
    status          text          NOT NULL DEFAULT 'pending',
    attempts        integer       NOT NULL DEFAULT 0,
    situation       text          CHECK (situation IS NULL OR (situation ~ '^[A-Z_]+$' AND length(situation) <= 32)),
    confidence      numeric(5,4)  CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    model           text,
    prompt_version  text,
    created_at      timestamptz   NOT NULL,
    answered_at     timestamptz,
    dismissed_at    timestamptz,
    dismissed_by    uuid,
    dismiss_reason  text          CHECK (dismiss_reason IS NULL OR length(dismiss_reason) BETWEEN 1 AND 300),
    CONSTRAINT ck_message_situations_status CHECK (status IN ('pending', 'answered', 'failed')),
    CONSTRAINT ck_message_situations_answer CHECK ((status = 'answered') = (situation IS NOT NULL)),
    -- Rule 8: a dismissal carries who, why and when.
    CONSTRAINT ck_message_situations_dismissal CHECK (
        (dismissed_at IS NULL) = (dismissed_by IS NULL) AND (dismissed_at IS NULL) = (dismiss_reason IS NULL))
);

COMMENT ON TABLE ml.message_situations IS
  'R-ML-35: Jev''s class of a driver''s or store''s own words on a trip''s chat, for the trip''s Next step card.';

CREATE INDEX IF NOT EXISTS ix_message_situations_trip ON ml.message_situations (trip_id) WHERE status = 'answered';
CREATE INDEX IF NOT EXISTS ix_message_situations_pending ON ml.message_situations (created_at) WHERE status = 'pending';

GRANT SELECT, INSERT, UPDATE ON ml.message_situations TO waypoint_ml;

ALTER TABLE ml.message_situations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.message_situations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS message_situations_scope ON ml.message_situations;
CREATE POLICY message_situations_scope ON ml.message_situations
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

-- A new action needs a catalogue row and a handler (R-IAM). Only the dispatcher
-- policy gets it.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:DismissSignal', 'ml', 'Take a message read automatically off the trip''s Next step card, with a reason', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.grant_dismissal(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'DismissSignals')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'DismissSignals',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('ml:DismissSignal'),
                           'Resource', jsonb_build_array('wpt:ml:trip:*'))))
    END
$$;

CREATE TEMP TABLE dismissal_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_dismissal(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM dismissal_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM dismissal_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM dismissal_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM dismissal_policy_docs);
