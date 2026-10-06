-- Roadmap FF-03: the outcome of an inquiry into an issue and, when goods are
-- written off, its value and approval (P-DG-1). Values are entered by hand: the
-- catalogue has no prices. A write-off is recorded once and then approved or
-- rejected once, with an actor, a reason and a time; never deleted.
CREATE TABLE issues.write_offs (
    write_off_id   uuid          PRIMARY KEY,
    issue_id       uuid          NOT NULL REFERENCES issues.issues (issue_id),
    depot_code     text          NOT NULL,
    outcome        text          NOT NULL,
    value_lkr      numeric(12,2) NOT NULL,
    tech_line      boolean       NOT NULL,
    attachment_ids uuid[]        NOT NULL DEFAULT '{}',
    recorded_by    uuid          NOT NULL,
    recorded_at    timestamptz   NOT NULL,
    command_id     uuid          NOT NULL,
    status         text          NOT NULL DEFAULT 'recorded',
    decided_by     uuid,
    decided_at     timestamptz,
    decision_note  text,
    row_version    bigint        NOT NULL DEFAULT 1,
    CONSTRAINT ck_write_offs_value CHECK (value_lkr >= 0),
    CONSTRAINT ck_write_offs_status CHECK (status IN ('recorded', 'approved', 'rejected')),
    CONSTRAINT ck_write_offs_decided CHECK (
        (status = 'recorded') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_at IS NULL) = (decision_note IS NULL))
);

COMMENT ON TABLE issues.write_offs IS
  'A write-off recorded by an inquiry (FF-03) and its approval under P-DG-1. Recorded once, decided once, never deleted.';

CREATE INDEX ix_write_offs_issue ON issues.write_offs (issue_id);
CREATE INDEX ix_write_offs_open ON issues.write_offs (depot_code) WHERE status = 'recorded';

GRANT SELECT, INSERT, UPDATE ON issues.write_offs TO waypoint_issues;

ALTER TABLE issues.write_offs ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.write_offs FORCE ROW LEVEL SECURITY;
CREATE POLICY write_offs_scope ON issues.write_offs
    FOR ALL TO waypoint_issues
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

-- P-DG-1: a depot operations manager approves up to this value; above it, and
-- any Tech line, the Head of Distribution. Finance confirms it before the pilot.
-- The table forces row-level security and has no insert policy, so the force is
-- lifted around the insert (AGENTS.md, Data and Migration Rules).
ALTER TABLE issues.parameters NO FORCE ROW LEVEL SECURITY;
INSERT INTO issues.parameters (parameter_key, parameter_value, effective_from, note) VALUES
    ('write_off.depot_limit_lkr', '25000', DATE '2026-01-01', 'P-DG-1, to be confirmed by Finance')
ON CONFLICT DO NOTHING;
ALTER TABLE issues.parameters FORCE ROW LEVEL SECURITY;
