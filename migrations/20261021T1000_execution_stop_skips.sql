-- R-EXE-31: the dispatcher skips a store or ends a trip on the road
-- (delivery:SkipStop, delivery:EndTrip; docs/issues/trip-skip/PLAN.md).
--
-- The stop becomes 'skipped' (already allowed by ck_delivery_outcome, reached
-- or not); the decision, what happens to the order and why, is kept beside it
-- once, append only, like a driver's store-answer waiver (rule 8).

CREATE TABLE execution.stop_skips (
    delivery_id  uuid        PRIMARY KEY REFERENCES execution.delivery_records (delivery_id),
    then_action  text        NOT NULL,
    reason       text        NOT NULL,
    whole_trip   boolean     NOT NULL,
    decided_by   uuid        NOT NULL,
    command_id   uuid        NOT NULL,
    decided_at   timestamptz NOT NULL,
    CONSTRAINT ck_stop_skips_then CHECK (then_action IN ('next_day','cancel')),
    CONSTRAINT ck_stop_skips_reason CHECK (char_length(reason) BETWEEN 3 AND 500),
    -- End trip sends every stop of the trip to the next day.
    CONSTRAINT ck_stop_skips_trip CHECK (NOT whole_trip OR then_action = 'next_day')
);

-- One End trip skips several stops under one command id, so the id is indexed, not unique.
CREATE INDEX ix_stop_skips_command ON execution.stop_skips (command_id);

COMMENT ON TABLE execution.stop_skips IS
  'A stop the dispatcher took off a trip on the road: next day or cancel, why, who and when (R-EXE-31, rule 8).';

GRANT SELECT, INSERT ON execution.stop_skips TO waypoint_execution;

-- Visible where the stop is visible; written only by a dispatcher of the stop's depot.
ALTER TABLE execution.stop_skips ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.stop_skips FORCE ROW LEVEL SECURITY;
CREATE POLICY stop_skips_read ON execution.stop_skips
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = stop_skips.delivery_id));
CREATE POLICY stop_skips_insert ON execution.stop_skips
    FOR INSERT TO waypoint_execution
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = stop_skips.delivery_id
                          AND app.actor_oversees_depot(d.depot_code)));

-- Until now only the system and the vehicle's driver could write a stop. A
-- dispatcher of the depot may now write one thing: the stop skipped. Any other
-- outcome written by a dispatcher fails the check, whatever the policy says.
CREATE POLICY delivery_dispatcher_skip ON execution.delivery_records
    FOR UPDATE TO waypoint_execution
    USING (app.actor_oversees_depot(depot_code))
    WITH CHECK (app.actor_oversees_depot(depot_code) AND outcome = 'skipped');

-- ---- the actions ----------------------------------------------------------------
-- A new action needs a catalogue row and a handler (R-IAM). The dispatcher is
-- allowed both; the driver, whose policy allows delivery:*, is denied both
-- explicitly (a matching Deny wins); the auditor's NeverWrite already denies
-- delivery:*. The administrator holds every action.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('delivery:SkipStop', 'execution', 'Take one stop off a trip on the road: next day or cancel, with a reason', true),
    ('delivery:EndTrip', 'execution', 'End a trip on the road: every stop not yet done goes to the next day', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.skip_statement(doc jsonb, sid text, effect text, resources jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = sid)
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', sid,
                           'Effect', effect,
                           'Action', jsonb_build_array('delivery:SkipStop', 'delivery:EndTrip'),
                           'Resource', resources)))
    END
$$;

CREATE TEMP TABLE skip_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       CASE p.name
           WHEN 'WaypointDispatcher' THEN pg_temp.skip_statement(pv.document, 'SkipStopsOnTheRoad', 'Allow',
               jsonb_build_array('wpt:execution:delivery:*', 'wpt:execution:trip:*'))
           ELSE pg_temp.skip_statement(pv.document, 'NotTheDispatchersSkips', 'Deny', jsonb_build_array('*'))
       END AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointDispatcher', 'WaypointDriver');

DELETE FROM skip_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM skip_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM skip_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM skip_policy_docs);
