-- R-PLN-47: a second trip leaves only after the drive back from the first
-- trip's district and a reload at the depot. A rule set is never edited: the
-- open one closes the day before its successor starts (the only change
-- allowed, with its version incremented, as authoring does) and its values are
-- copied into the successor with the two new ones: the drive back counted, and
-- 20 minutes reloading, a placeholder until the depot gives its own figure.
-- The time budgets keep the booklet's formula; only the clock that delivery
-- windows and arrival times read changes. Rule sets made before this read as
-- the booklet's model (no return, no reload).

ALTER TABLE planning.rule_sets NO FORCE ROW LEVEL SECURITY;
ALTER TABLE planning.rule_parameters NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    next_id      uuid := '01923c00-0000-7000-8000-000000000004';
    open_id      uuid;
    open_from    date;
    open_version bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM planning.rule_sets WHERE rule_set_id = next_id) THEN
        RETURN;
    END IF;
    SELECT rule_set_id, effective_from, row_version INTO open_id, open_from, open_version
      FROM planning.rule_sets WHERE effective_to IS NULL ORDER BY effective_from DESC LIMIT 1;
    IF open_id IS NULL OR open_from >= DATE '2026-10-09' THEN
        -- No rule set, or one already scheduled from that day: an administrator sets the two values.
        RETURN;
    END IF;
    UPDATE planning.rule_sets SET effective_to = DATE '2026-10-09', row_version = row_version + 1
     WHERE rule_set_id = open_id;
    INSERT INTO planning.rule_sets (rule_set_id, effective_from, note, created_at, row_version)
    VALUES (next_id, DATE '2026-10-09',
            'R-PLN-47: a second trip waits for the drive back and a 20 minute reload (placeholder)', now(),
            open_version + 1);
    INSERT INTO planning.rule_parameters (rule_set_id, parameter_key, parameter_value, unit)
    SELECT next_id, parameter_key, parameter_value, unit FROM planning.rule_parameters
     WHERE rule_set_id = open_id AND parameter_key NOT IN ('trip.return.counted', 'trip.reload.min');
    INSERT INTO planning.rule_parameters (rule_set_id, parameter_key, parameter_value, unit) VALUES
        (next_id, 'trip.return.counted', 1, 'on/off'),
        (next_id, 'trip.reload.min', 20, 'min');
END $$;

ALTER TABLE planning.rule_sets FORCE ROW LEVEL SECURITY;
ALTER TABLE planning.rule_parameters FORCE ROW LEVEL SECURITY;
