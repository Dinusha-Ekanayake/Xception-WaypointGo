-- Moving on without the store's answer (issue #21, store-led handover).
--
-- After handing over, the driver waits for the store to check the load. The wait
-- is never a gate (R-RCP-09): the driver may move on at any time, but says why,
-- so the decision carries an actor, a reason and a time (rule 8) and the
-- dispatcher sees it on the run sheet. One decision per stop; append only.

CREATE TABLE execution.store_answer_waivers (
    delivery_id        uuid        PRIMARY KEY REFERENCES execution.delivery_records (delivery_id),
    reason             text        NOT NULL,
    decided_by         uuid        NOT NULL,
    device_id          uuid,
    command_id         uuid        NOT NULL UNIQUE,
    decided_at         timestamptz NOT NULL,
    client_recorded_at timestamptz,
    CONSTRAINT ck_store_answer_waivers_reason CHECK (reason IN ('store_absent','no_signal','disagree'))
);

COMMENT ON TABLE execution.store_answer_waivers IS
  'The driver moved on before the store answered, and why. Never a gate (R-RCP-09); a decision on record (rule 8).';

GRANT SELECT, INSERT ON execution.store_answer_waivers TO waypoint_execution;

-- Visible where the stop is visible; written only by the driver of the stop's vehicle that day.
ALTER TABLE execution.store_answer_waivers ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.store_answer_waivers FORCE ROW LEVEL SECURITY;
CREATE POLICY store_answer_waivers_read ON execution.store_answer_waivers
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = store_answer_waivers.delivery_id));
CREATE POLICY store_answer_waivers_insert ON execution.store_answer_waivers
    FOR INSERT TO waypoint_execution
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = store_answer_waivers.delivery_id
                          AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))));

-- ---- the action -----------------------------------------------------------------
-- Drivers hold delivery:*, so the grant is already theirs; the auditor's explicit
-- deny names it like every other delivery write.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('delivery:LeaveWithoutStoreAnswer', 'execution', 'Move on from a stop before the store answered, with the reason', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.waiver_add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_set(
               doc, '{Statement}',
               (SELECT jsonb_agg(
                           CASE WHEN s ->> 'Sid' = target_sid
                                THEN jsonb_set(
                                         s, '{Action}',
                                         (s -> 'Action')
                                         || (SELECT coalesce(jsonb_agg(w), '[]'::jsonb)
                                               FROM unnest(wanted) AS w
                                              WHERE NOT jsonb_exists(s -> 'Action', w)))
                                ELSE s END
                           ORDER BY ord)
                  FROM jsonb_array_elements(doc -> 'Statement') WITH ORDINALITY AS t(s, ord)))
$$;

CREATE TEMP TABLE waiver_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.waiver_add_actions(pv.document, 'NeverWrite', ARRAY['delivery:LeaveWithoutStoreAnswer']) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAuditor';

DELETE FROM waiver_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM waiver_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM waiver_policy_docs d;
