-- Rule set authoring uses the same effective-dated values as allocation.
ALTER TABLE planning.rule_sets ADD COLUMN row_version bigint NOT NULL DEFAULT 1;

CREATE OR REPLACE FUNCTION planning.deny_version_edit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'rule_parameters' THEN
        RAISE EXCEPTION 'rule parameters are immutable; create a new rule set'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_TABLE_NAME = 'rule_sets' THEN
        IF (to_jsonb(NEW) - 'effective_to' - 'row_version') IS DISTINCT FROM
           (to_jsonb(OLD) - 'effective_to' - 'row_version')
           OR OLD.effective_to IS NOT NULL
           OR NEW.effective_to IS NULL
           OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'rule set is immutable; only its range may be closed with a version increment'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF (to_jsonb(NEW) - 'effective_to') IS DISTINCT FROM
          (to_jsonb(OLD) - 'effective_to') OR OLD.effective_to IS NOT NULL THEN
        RAISE EXCEPTION '% is immutable; only an open effective_to may be closed', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;

-- These are global configuration rows. The command bus checks planning:CreateRuleSet
-- before adopting waypoint_planning; no depot scope exists for a global rule set.
DROP POLICY rule_sets_write ON planning.rule_sets;
CREATE POLICY rule_sets_write ON planning.rule_sets FOR INSERT TO waypoint_planning
    WITH CHECK (app.current_actor() IS NOT NULL);
DROP POLICY rule_sets_close ON planning.rule_sets;
CREATE POLICY rule_sets_close ON planning.rule_sets FOR UPDATE TO waypoint_planning
    USING (app.current_actor() IS NOT NULL) WITH CHECK (app.current_actor() IS NOT NULL);
DROP POLICY rule_parameters_write ON planning.rule_parameters;
CREATE POLICY rule_parameters_write ON planning.rule_parameters FOR INSERT TO waypoint_planning
    WITH CHECK (app.current_actor() IS NOT NULL);

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('planning:ReadRules', 'planning', 'Read the effective and scheduled planning rule sets', true),
    ('planning:CreateRuleSet', 'planning', 'Schedule a new planning rule set', true)
ON CONFLICT (action) DO NOTHING;

CREATE TEMP TABLE planning_rule_policy ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(pv.document, '{Statement}',
           (pv.document->'Statement') || jsonb_build_array(jsonb_build_object(
               'Sid', 'ManagePlanningRules', 'Effect', 'Allow',
               'Action', jsonb_build_array('planning:ReadRules', 'planning:CreateRuleSet'),
               'Resource', jsonb_build_array('*')))) AS document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAdministrator';

UPDATE iam.policy_versions pv SET is_default = false
FROM planning_rule_policy d WHERE pv.policy_id = d.policy_id AND pv.is_default;
INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions x
                     WHERE x.policy_id = d.policy_id), d.document, true
FROM planning_rule_policy d;
