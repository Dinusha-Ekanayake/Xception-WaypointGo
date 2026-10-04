-- Issues' tables (issue #13). Schema and role came with 20260930T1200.
--
--     One lifecycle for every operational problem, wherever it is raised. Every
--     resolution carries an action, a reason, a person and a time (rule 8), and
--     an issue is never deleted: it is resolved, closed or cancelled.
--
--     An issue is about one or more subjects (order, trip, delivery, receipt,
--     shortfall, vehicle), referenced by id with no foreign key (D-B). A vehicle
--     alone is a valid subject: fault and disruption events carry no trip.
--
--     An issue raised from another module's event records the event's key in
--     source_key, so a redelivered event raises nothing new.

CREATE TABLE issues.issues (
    issue_id                uuid PRIMARY KEY,
    issue_type              text        NOT NULL,
    severity                text        NOT NULL,
    -- Most severe first in every inbox, without a CASE in each query.
    severity_rank           smallint    GENERATED ALWAYS AS (
                                CASE severity WHEN 'critical' THEN 4 WHEN 'high' THEN 3
                                              WHEN 'medium' THEN 2 ELSE 1 END) STORED,
    status                  text        NOT NULL,
    depot_code              text        NOT NULL,
    outlet_id               text        REFERENCES ref.outlet_registry (outlet_id),
    description             text        NOT NULL,
    investigation           boolean     NOT NULL DEFAULT false,
    source_key              text        UNIQUE,
    assignee_user_id        uuid        REFERENCES iam.users (user_id),
    resolution_action       text,
    resolution_note         text,
    resolved_by             uuid,
    resolved_at             timestamptz,
    redelivery_requested_at timestamptz,
    escalated_at            timestamptz,
    raised_by               uuid        NOT NULL,
    raised_at               timestamptz NOT NULL,
    row_version             bigint      NOT NULL DEFAULT 1,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT ck_issues_type CHECK (issue_type IN (
        'loading_shortfall','damaged_goods','failed_delivery','late_delivery','vehicle_fault',
        'road_disruption','receipt_dispute','stock_discrepancy','other')),
    CONSTRAINT ck_issues_severity CHECK (severity IN ('low','medium','high','critical')),
    CONSTRAINT ck_issues_status CHECK (status IN ('open','assigned','resolved','closed','cancelled')),
    -- A resolution exists exactly when the issue was resolved, with all four parts (rule 8).
    CONSTRAINT ck_issues_resolution CHECK (
        (status IN ('resolved','closed') AND resolution_action IS NOT NULL AND resolution_note IS NOT NULL
             AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
        OR (status IN ('open','assigned','cancelled') AND resolution_action IS NULL)),
    CONSTRAINT ck_issues_action CHECK (resolution_action IS NULL OR resolution_action IN (
        'replacement','redelivery','write_off','no_fault_found','other')),
    CONSTRAINT ck_issues_assigned CHECK (status <> 'assigned' OR assignee_user_id IS NOT NULL),
    -- R-RCP-07: a shortage investigation is never closed by the system actor.
    CONSTRAINT ck_issues_investigation_by_person CHECK (
        NOT investigation OR resolved_by IS NULL
        OR resolved_by <> '00000000-0000-0000-0000-000000000001'::uuid),
    CONSTRAINT ck_issues_redelivery CHECK (redelivery_requested_at IS NULL OR resolution_action = 'redelivery')
);

COMMENT ON TABLE issues.issues IS
  'One operational problem: owner, lifecycle, recorded resolution. Never deleted.';
COMMENT ON COLUMN issues.issues.investigation IS
  'A loaded-but-not-received investigation (R-RCP-07): resolved only by a person, never by the system.';
COMMENT ON COLUMN issues.issues.source_key IS
  'The event that raised it, for example loading.shortfall:<id>, so a redelivered event raises nothing new.';

CREATE INDEX ix_issues_open ON issues.issues (depot_code, severity_rank DESC, raised_at, issue_id)
    WHERE status IN ('open','assigned');
CREATE INDEX ix_issues_overdue ON issues.issues (raised_at) WHERE status = 'open' AND escalated_at IS NULL;
CREATE INDEX ix_issues_outlet ON issues.issues (outlet_id) WHERE outlet_id IS NOT NULL;
CREATE INDEX ix_issues_assignee ON issues.issues (assignee_user_id) WHERE assignee_user_id IS NOT NULL;
CREATE INDEX ix_issues_raised_by ON issues.issues (raised_by);

CREATE TABLE issues.issue_subjects (
    issue_id     uuid NOT NULL REFERENCES issues.issues (issue_id),
    subject_type text NOT NULL,
    subject_id   text NOT NULL,
    PRIMARY KEY (issue_id, subject_type, subject_id),
    CONSTRAINT ck_issue_subjects_type CHECK (subject_type IN (
        'order','trip','delivery','receipt','shortfall','vehicle'))
);

CREATE INDEX ix_issue_subjects_subject ON issues.issue_subjects (subject_type, subject_id);

CREATE TABLE issues.issue_history (
    history_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id    uuid        NOT NULL REFERENCES issues.issues (issue_id),
    from_status text,
    to_status   text        NOT NULL,
    action      text        NOT NULL,
    reason      text        NOT NULL,
    actor_id    uuid,
    event_id    uuid,
    occurred_at timestamptz NOT NULL
);

COMMENT ON TABLE issues.issue_history IS
  'Every change with its actor, reason and time (rule 8), escalations included.';

CREATE INDEX ix_issue_history_issue ON issues.issue_history (issue_id, history_id);

-- ---- parameters -------------------------------------------------------------
-- Default severity for issues the system raises, and how long an unassigned
-- issue may wait before it escalates (P-20 to P-23). Effective-dated; a change
-- is a new row from a migration.

CREATE TABLE issues.parameters (
    parameter_key   text NOT NULL,
    parameter_value text NOT NULL,
    effective_from  date NOT NULL,
    effective_to    date,
    note            text NOT NULL,
    PRIMARY KEY (parameter_key, effective_from),
    CONSTRAINT ck_issue_parameters_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_issue_parameters_overlap EXCLUDE USING gist (
        parameter_key WITH =, daterange(effective_from, effective_to, '[)') WITH &&)
);

INSERT INTO issues.parameters (parameter_key, parameter_value, effective_from, note) VALUES
    ('default.severity.LOADING_SHORTFALL', 'HIGH',     DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.DAMAGED_GOODS',     'HIGH',     DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.FAILED_DELIVERY',   'HIGH',     DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.LATE_DELIVERY',     'LOW',      DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.VEHICLE_FAULT',     'CRITICAL', DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.ROAD_DISRUPTION',   'MEDIUM',   DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.RECEIPT_DISPUTE',   'HIGH',     DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.STOCK_DISCREPANCY', 'MEDIUM',   DATE '2026-01-01', 'issue #13 decision 8'),
    ('default.severity.OTHER',             'MEDIUM',   DATE '2026-01-01', 'issue #13 decision 8'),
    ('escalation.minutes.CRITICAL',        '15',       DATE '2026-01-01', 'P-20'),
    ('escalation.minutes.HIGH',            '60',       DATE '2026-01-01', 'P-21'),
    ('escalation.minutes.MEDIUM',          '240',      DATE '2026-01-01', 'P-22'),
    ('escalation.minutes.LOW',             '1440',     DATE '2026-01-01', 'P-23');

-- ---- privileges -------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON issues.issues, issues.issue_subjects, issues.issue_history TO waypoint_issues;
GRANT SELECT ON issues.parameters TO waypoint_issues;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA issues TO waypoint_issues;
REVOKE INSERT, UPDATE ON issues.parameters FROM waypoint_issues;
-- Subjects are fixed when an issue is raised; a different subject is a different issue.
REVOKE UPDATE ON issues.issue_subjects FROM waypoint_issues;

-- ---- row-level security -----------------------------------------------------
-- A dispatcher or loader sees their depots, a store manager their outlet's
-- issues, anyone what they raised themselves (a driver reporting from the road),
-- and the process everything in this schema.

ALTER TABLE issues.issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.issues FORCE ROW LEVEL SECURITY;
CREATE POLICY issues_scope ON issues.issues
    FOR ALL TO waypoint_issues
    USING (app.actor_is_system()
           OR app.actor_has_depot(depot_code)
           OR (outlet_id IS NOT NULL AND app.actor_has_outlet(outlet_id))
           OR raised_by = app.current_actor())
    WITH CHECK (app.actor_is_system()
                OR app.actor_has_depot(depot_code)
                OR (outlet_id IS NOT NULL AND app.actor_has_outlet(outlet_id))
                OR raised_by = app.current_actor());

ALTER TABLE issues.issue_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.issue_subjects FORCE ROW LEVEL SECURITY;
CREATE POLICY issue_subjects_scope ON issues.issue_subjects
    FOR ALL TO waypoint_issues
    USING (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_subjects.issue_id))
    WITH CHECK (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_subjects.issue_id));

ALTER TABLE issues.issue_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.issue_history FORCE ROW LEVEL SECURITY;
CREATE POLICY issue_history_scope ON issues.issue_history
    FOR ALL TO waypoint_issues
    USING (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_history.issue_id))
    WITH CHECK (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_history.issue_id));

ALTER TABLE issues.parameters ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.parameters FORCE ROW LEVEL SECURITY;
CREATE POLICY parameters_read ON issues.parameters FOR SELECT TO waypoint_issues USING (true);
