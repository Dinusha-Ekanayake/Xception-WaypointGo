-- 003 Rule and policy versioning

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- required to mix = with && in a GiST exclusion
--    Predicates are code; thresholds and priority orders are data.
--    Nothing is mutated: a change supersedes with a new effective range.
--    effective_from = when the rule governs. created_at = when it was written.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS ops.policy_versions (
    policy_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_kind text NOT NULL,
    definition jsonb NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid REFERENCES iam.users(user_id),
    note text,
    CONSTRAINT ck_policy_kind CHECK (policy_kind IN (
        'deferral_priority','authorization','notification_routing','loading_sequence'
    )),
    CONSTRAINT ck_policy_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_policy_no_overlap EXCLUDE USING gist (
        policy_kind WITH =,
        tstzrange(effective_from, effective_to) WITH &&
    )
);

CREATE TABLE IF NOT EXISTS ops.rule_parameters (
    rule_parameter_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parameter_key text NOT NULL,
    parameter_value numeric NOT NULL,
    unit text NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid REFERENCES iam.users(user_id),
    CONSTRAINT ck_rule_parameter_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_rule_parameter_no_overlap EXCLUDE USING gist (
        parameter_key WITH =,
        tstzrange(effective_from, effective_to) WITH &&
    )
);

-- Thresholds the constraint registry reads. Predicates stay in code.
INSERT INTO ops.rule_parameters (parameter_key, parameter_value, unit, effective_from) VALUES
    ('fresh_time_budget_min',        270, 'minutes',   '2024-01-01T00:00:00+05:30'),
    ('style_tech_time_budget_min',   480, 'minutes',   '2024-01-01T00:00:00+05:30'),
    ('order_cutoff_local_time',     1600, 'hhmm',      '2024-01-01T00:00:00+05:30'),
    ('fresh_window_open_local_time', 330, 'hhmm',      '2024-01-01T00:00:00+05:30'),
    ('max_trips_per_vehicle_day',      2, 'count',     '2024-01-01T00:00:00+05:30'),
    ('depot_turnaround_min',          20, 'minutes',   '2024-01-01T00:00:00+05:30')
ON CONFLICT DO NOTHING;
