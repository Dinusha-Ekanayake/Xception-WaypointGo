-- R-EXE-32: a driver's "Store closed" and "Cannot unload here" reach the trip's
-- thread as reports of their own, so the dispatcher's suggested decisions follow
-- the situation (R-ML-26). The check is replaced without a long lock: added NOT
-- VALID, then validated, which reads the table but blocks no writes.
ALTER TABLE messaging.messages DROP CONSTRAINT IF EXISTS ck_messages_report;
ALTER TABLE messaging.messages ADD CONSTRAINT ck_messages_report CHECK (
    (kind = 'report') = (report_type IS NOT NULL)
    AND (report_type IS NULL OR report_type IN (
        'loading_shortfall','vehicle_fault','road_disruption','failed_delivery','damaged_goods',
        'receipt_dispute','stock_discrepancy','late','other','store_closed','cannot_unload'))
    AND (kind = 'message' OR audience = 'dispatch')) NOT VALID;
ALTER TABLE messaging.messages VALIDATE CONSTRAINT ck_messages_report;
