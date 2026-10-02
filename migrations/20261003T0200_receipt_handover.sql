-- The handover PIN (Figma store manager "06b Enter PIN on driver's phone").
--
--     When the store answers a receipt it is given a one-time PIN. The driver
--     types it on their own phone, which shows that the person who handed the
--     goods over stood at the store when it was entered. The PIN is evidence,
--     never a gate: a driver who cannot get it, or a phone with no signal, does
--     not hold up the trip or the receipt. The receipt then reads "handover not
--     confirmed" and nothing else changes (R-RCP-09).
--
--     Only a salted hash is kept. The PIN itself is returned once, in the answer
--     to the command that issued it. It is four digits, so it is protected by
--     what surrounds it and not by the hash: five wrong entries lock it, it
--     expires, and a new one can be issued by the store, which resets the count.
--
--     The vehicle and service date are copied from the delivery record at issue,
--     so the driver's row-level scope (the vehicle on that date) can be decided
--     here without reading Execution's tables (rule 4).

CREATE TABLE receipt.handovers (
    receipt_id    uuid        PRIMARY KEY REFERENCES receipt.confirmations (receipt_id),
    order_id      uuid        NOT NULL UNIQUE,
    outlet_id     text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    depot_code    text        NOT NULL,
    vehicle_id    text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    service_date  date        NOT NULL,
    pin_salt      text        NOT NULL,
    pin_hash      text        NOT NULL,
    state         text        NOT NULL,
    attempts      integer     NOT NULL DEFAULT 0,
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    confirmed_at  timestamptz,
    confirmed_by  uuid,
    row_version   bigint      NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT ck_handovers_state CHECK (state IN ('awaiting','confirmed','locked')),
    CONSTRAINT ck_handovers_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_handovers_expiry CHECK (expires_at > issued_at),
    -- Confirmed by a person, or not confirmed at all.
    CONSTRAINT ck_handovers_confirmed CHECK (
        (state = 'confirmed') = (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL))
);

COMMENT ON TABLE receipt.handovers IS
  'The one-time PIN the store shows and the driver enters. Evidence of presence, never a gate (R-RCP-09).';
COMMENT ON COLUMN receipt.handovers.pin_hash IS
  'SHA-256 of the salt and the PIN. The PIN is returned once, by the command that issued it, and never stored.';

CREATE INDEX ix_handovers_outlet ON receipt.handovers (outlet_id);
CREATE INDEX ix_handovers_vehicle_day ON receipt.handovers (vehicle_id, service_date);
CREATE INDEX ix_handovers_depot ON receipt.handovers (depot_code);

-- Every issue, reissue and entry with who made it (rule 8), so a run of wrong
-- entries is on record and not only counted.
CREATE TABLE receipt.handover_history (
    history_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id  uuid        NOT NULL REFERENCES receipt.handovers (receipt_id),
    action      text        NOT NULL,
    actor_id    uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_handover_history_action CHECK (action IN (
        'issued','reissued','verified','wrong_pin','locked','expired_entry'))
);

CREATE INDEX ix_handover_history_receipt ON receipt.handover_history (receipt_id, history_id);

GRANT SELECT, INSERT, UPDATE ON receipt.handovers, receipt.handover_history TO waypoint_receipt;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA receipt TO waypoint_receipt;

-- ---- row-level security -----------------------------------------------------
-- The outlet's manager, the depot's dispatcher, the driver of the vehicle on its
-- service date, and the process. The driver's reach is the vehicle on the day,
-- as everywhere else (R-IAM-13); the handlers decide who may do what within it.

ALTER TABLE receipt.handovers ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.handovers FORCE ROW LEVEL SECURITY;
CREATE POLICY handovers_scope ON receipt.handovers
    FOR ALL TO waypoint_receipt
    USING (app.actor_is_system() OR app.actor_has_outlet(outlet_id) OR app.actor_has_depot(depot_code)
           OR app.actor_drives(vehicle_id, service_date))
    WITH CHECK (app.actor_is_system() OR app.actor_has_outlet(outlet_id) OR app.actor_has_depot(depot_code)
                OR app.actor_drives(vehicle_id, service_date));

ALTER TABLE receipt.handover_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.handover_history FORCE ROW LEVEL SECURITY;
CREATE POLICY handover_history_scope ON receipt.handover_history
    FOR ALL TO waypoint_receipt
    USING (EXISTS (SELECT 1 FROM receipt.handovers h WHERE h.receipt_id = handover_history.receipt_id))
    WITH CHECK (EXISTS (SELECT 1 FROM receipt.handovers h WHERE h.receipt_id = handover_history.receipt_id));

-- ---- the actions ------------------------------------------------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('receipt:VerifyHandover', 'receipt', 'Enter the store''s one-time PIN on the driver''s phone', true),
    ('receipt:ReissueHandoverPin', 'receipt', 'Issue a new handover PIN for a delivery', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable, so each grant is a new version, built from the
-- current default rather than restated so a grant another branch added survives.
-- The store manager already holds receipt:* and so reissues; the driver gains
-- the entry; the auditor's deny list grows with both.
CREATE FUNCTION pg_temp.add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
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

CREATE TEMP TABLE policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.add_actions(pv.document, g.sid, g.actions) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
JOIN (VALUES
        ('WaypointDriver',  'RoadWork',   ARRAY['receipt:VerifyHandover']),
        ('WaypointAuditor', 'NeverWrite', ARRAY['receipt:VerifyHandover', 'receipt:ReissueHandoverPin'])
     ) AS g(name, sid, actions) ON g.name = p.name;

DELETE FROM policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM policy_docs d;
