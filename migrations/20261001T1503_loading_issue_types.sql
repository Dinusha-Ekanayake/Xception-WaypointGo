-- Loading issues exposed to operators are limited to Damaged, Doesn't fit,
-- and Missing. Keep existing short rows readable, but reject new writes using
-- the removed legacy issue type.

ALTER TABLE loading.item_checks DROP CONSTRAINT ck_item_checks_status;
ALTER TABLE loading.item_checks ADD CONSTRAINT ck_item_checks_status
    CHECK (status IN ('pending','loaded','missing','damaged','does_not_fit')) NOT VALID;

ALTER TABLE loading.shortfalls DROP CONSTRAINT ck_shortfalls_kind;
ALTER TABLE loading.shortfalls ADD CONSTRAINT ck_shortfalls_kind
    CHECK (kind IN ('missing','damaged','does_not_fit')) NOT VALID;
