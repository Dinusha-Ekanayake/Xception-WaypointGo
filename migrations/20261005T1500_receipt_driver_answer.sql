-- The store's answer, read on the driver's phone (issue #21, store-led handover).
--
-- The driver hands over, the store checks the load and answers, and the driver
-- sees that answer and accepts it with the store's PIN (R-RCP-09). The driver
-- reads it through the handover, which carries the vehicle and the service date,
-- so only the driver of that vehicle on that day sees it, and never the depot's
-- other receipts (rule 7). The answer only exists once the store has given it:
-- before that there is no handover, and the read is a 404.
--
-- Read only: the new policies are FOR SELECT, so a driver can never write a
-- receipt or its lines through them; the store's own policies are unchanged.

CREATE POLICY confirmations_driver_answer ON receipt.confirmations
    FOR SELECT TO waypoint_receipt
    USING (EXISTS (
        SELECT 1 FROM receipt.handovers h
        WHERE h.receipt_id = confirmations.receipt_id
          AND app.actor_drives(h.vehicle_id, h.service_date)));

CREATE POLICY confirmation_lines_driver_answer ON receipt.confirmation_lines
    FOR SELECT TO waypoint_receipt
    USING (EXISTS (
        SELECT 1 FROM receipt.handovers h
        WHERE h.receipt_id = confirmation_lines.receipt_id
          AND app.actor_drives(h.vehicle_id, h.service_date)));

-- ---- the action ---------------------------------------------------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('receipt:ReadAnswer', 'receipt', 'Read the store''s answer to a delivery on the driver''s phone', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable, so the grant is a new version built from the
-- current default. Every pending migration runs in one transaction, so the
-- temporary names here are this file's own.
CREATE OR REPLACE FUNCTION pg_temp.answer_add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
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

CREATE TEMP TABLE answer_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.answer_add_actions(pv.document, g.sid, g.actions) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
JOIN (VALUES
        ('WaypointDriver', 'RoadWork', ARRAY['receipt:ReadAnswer'])
     ) AS g(name, sid, actions) ON g.name = p.name;

DELETE FROM answer_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM answer_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM answer_policy_docs d;
