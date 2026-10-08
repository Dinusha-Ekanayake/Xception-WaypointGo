-- R-PLN-21: deferral priority version 2. A version is never edited; the first
-- closes the day before its successor starts (the only change allowed), and
-- every plan already made keeps the version it was stamped with.
-- Version 2 moves "longest since last served" from the last tie-break to just
-- after Fresh and chilled: among Fresh chilled orders the store that has
-- waited longer is served first. Replayed over 80 stressed days at both depots
-- it differs on 3 (2 better, 1 worse), and on the Task 2B peak day it serves
-- 74 orders and 118.8 m3 chilled instead of 73 and 109.2 (docs/datathon).

ALTER TABLE planning.policy_versions NO FORCE ROW LEVEL SECURITY;

UPDATE planning.policy_versions
   SET effective_to = DATE '2026-10-09'
 WHERE policy_version_id = '01923c00-0000-7000-8000-000000000002' AND effective_to IS NULL;

INSERT INTO planning.policy_versions (policy_version_id, kind, keys, effective_from, note, created_at)
SELECT '01923c00-0000-7000-8000-000000000003', 'deferral_priority',
       '["PRIOR_SKIP","FRESH","CHILLED","LONGEST_UNSERVED","STRICT_WINDOW","CADENCE","EARLIEST_CLOSE","LONGEST_DISTANCE","LARGEST_VOLUME"]',
       DATE '2026-10-09', 'Version 2: longest since last served after Fresh and chilled (R-PLN-21, replay of 80 days)', now()
WHERE NOT EXISTS (SELECT 1 FROM planning.policy_versions WHERE policy_version_id = '01923c00-0000-7000-8000-000000000003');

ALTER TABLE planning.policy_versions FORCE ROW LEVEL SECURITY;
