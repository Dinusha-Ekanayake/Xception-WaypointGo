-- Short is offered again, as Figma "03 Report an issue" shows it ("Fewer
-- packages than picked"): one item with some of its units missing. The units
-- that arrived stay loaded. Decision 2026-10-01, reversing 20261001T1503.

ALTER TABLE loading.item_checks DROP CONSTRAINT ck_item_checks_status;
ALTER TABLE loading.item_checks ADD CONSTRAINT ck_item_checks_status
    CHECK (status IN ('pending','loaded','short','missing','damaged','does_not_fit'));

ALTER TABLE loading.shortfalls DROP CONSTRAINT ck_shortfalls_kind;
ALTER TABLE loading.shortfalls ADD CONSTRAINT ck_shortfalls_kind
    CHECK (kind IN ('short','missing','damaged','does_not_fit'));
