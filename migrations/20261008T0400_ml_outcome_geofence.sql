-- R-ML-12: a stop with no trustworthy tap may be labelled from the vehicle's GPS
-- at an outlet with an exact location. The label's source says so.
ALTER TABLE ml.delivery_outcomes DROP CONSTRAINT IF EXISTS delivery_outcomes_label_source_check;
ALTER TABLE ml.delivery_outcomes ADD CONSTRAINT delivery_outcomes_label_source_check
    CHECK (label_source IN ('device', 'geofence', 'none'));
