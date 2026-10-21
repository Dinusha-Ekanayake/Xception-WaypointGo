-- The warehouse API now names each product (docs/issues/product-names/PLAN.md).
-- Kept beside the cached row for display only: every row is still a
-- reconstruction (verified_real_sku = false), shown as inferred, and capacity
-- never reads it. Nullable: a product the warehouse has not named keeps today's label.
ALTER TABLE warehouse.products ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE warehouse.products ADD COLUMN IF NOT EXISTS category text;
COMMENT ON COLUMN warehouse.products.name IS 'The warehouse''s product name; display only, labelled inferred';

-- ---- names for the loader and the driver ------------------------------------
-- Their screens list products, so they read names through warehouse:ReadCatalogue
-- (already in the catalogue), on the catalogue only. A new default version each.
CREATE OR REPLACE FUNCTION pg_temp.names_statement(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'ReadProductNames')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'ReadProductNames',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('warehouse:ReadCatalogue'),
                           'Resource', jsonb_build_array('wpt:warehouse:catalogue:*'))))
    END
$$;

CREATE TEMP TABLE names_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, pg_temp.names_statement(pv.document) AS document, pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointLoader', 'WaypointDriver');

DELETE FROM names_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM names_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM names_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM names_policy_docs);
