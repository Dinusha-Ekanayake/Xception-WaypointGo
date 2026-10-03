-- R-REF-02: additive location data. Historical snapshots remain unchanged.
ALTER TABLE ref.depots
  ADD COLUMN latitude numeric(9,6),
  ADD COLUMN longitude numeric(9,6),
  ADD COLUMN location_precision text,
  ADD CONSTRAINT ck_depot_lat CHECK (latitude BETWEEN -90 AND 90),
  ADD CONSTRAINT ck_depot_lon CHECK (longitude BETWEEN -180 AND 180),
  ADD CONSTRAINT ck_depot_precision CHECK (location_precision IN ('exact', 'approximate')),
  ADD CONSTRAINT ck_depot_geo_complete CHECK (
    (latitude IS NULL AND longitude IS NULL AND location_precision IS NULL) OR
    (latitude IS NOT NULL AND longitude IS NOT NULL AND location_precision IS NOT NULL));

ALTER TABLE ref.districts
  ADD COLUMN centroid_latitude numeric(9,6),
  ADD COLUMN centroid_longitude numeric(9,6),
  ADD CONSTRAINT ck_district_lat CHECK (centroid_latitude BETWEEN -90 AND 90),
  ADD CONSTRAINT ck_district_lon CHECK (centroid_longitude BETWEEN -180 AND 180),
  ADD CONSTRAINT ck_district_geo_complete CHECK ((centroid_latitude IS NULL) = (centroid_longitude IS NULL));

ALTER TABLE ref.outlets
  ADD COLUMN location_precision text,
  ADD CONSTRAINT ck_outlet_precision CHECK (location_precision IN ('exact', 'district')),
  ADD CONSTRAINT ck_outlet_geo_precision CHECK (
    location_precision IS NULL OR (latitude IS NOT NULL AND longitude IS NOT NULL));
