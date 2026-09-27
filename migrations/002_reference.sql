-- 002 Reference data.
--
--     Decision D1: structural master data is versioned as immutable snapshots.
--       An import publishes a whole new version; the current version is a pointer.
--       Date-keyed series (calendar_days, road_conditions) and daily operational
--       state (vehicle_day_status) are excluded: correcting one day must not mint
--       a new version of the world.
--     Decision D2: depots and districts are identified by natural codes, never by
--       a generated number that could renumber on re-import.
--     Decision D3: lookup tables carry no value CHECK. Adding a brand is an insert.
--     Decision D8: depot is a function of district, and district_travel is keyed by
--       district alone. Verified: all 120 outlets sit in their district's depot.
--     Decision D9: traffic_speed and road_conditions are reference data.
-- ---------------------------------------------------------------------

-- ---- version registry -------------------------------------------------

CREATE TABLE ref.reference_versions (
    reference_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_label         text        NOT NULL,
    content_hash         text        NOT NULL UNIQUE,
    imported_at          timestamptz NOT NULL DEFAULT now(),
    imported_by          uuid,
    is_current           boolean     NOT NULL DEFAULT false
);

-- At most one current version, enforced rather than assumed.
CREATE UNIQUE INDEX uq_reference_version_current
    ON ref.reference_versions (is_current) WHERE is_current;

-- ---- stable identities ------------------------------------------------
-- Operational tables reference these, never a versioned row, so a plan keeps
-- pointing at OUT001 while its attributes may differ between versions.

CREATE TABLE ref.outlet_registry  (outlet_id  text PRIMARY KEY);
CREATE TABLE ref.vehicle_registry (vehicle_id text PRIMARY KEY);

-- ---- versioned structural data ----------------------------------------

CREATE TABLE ref.brands (
    reference_version_id uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    brand_code           text NOT NULL,
    display_name         text NOT NULL,
    PRIMARY KEY (reference_version_id, brand_code)
);

CREATE TABLE ref.depots (
    reference_version_id uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    depot_code           text NOT NULL,
    display_name         text NOT NULL,
    timezone_name        text NOT NULL DEFAULT 'Asia/Colombo',
    PRIMARY KEY (reference_version_id, depot_code)
);

CREATE TABLE ref.districts (
    reference_version_id uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    district_name        text NOT NULL,
    depot_code           text NOT NULL,
    PRIMARY KEY (reference_version_id, district_name),
    FOREIGN KEY (reference_version_id, depot_code)
        REFERENCES ref.depots (reference_version_id, depot_code)
);

CREATE TABLE ref.outlets (
    reference_version_id uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    outlet_id            text NOT NULL REFERENCES ref.outlet_registry(outlet_id),
    brand_code           text NOT NULL,
    district_name        text NOT NULL,
    dock_type            text NOT NULL,
    parking_constraint   text NOT NULL,
    mall_window_open     time,
    mall_window_close    time,
    window_open_time     time NOT NULL,
    window_close_time    time NOT NULL,
    latitude             numeric(9,6),
    longitude            numeric(9,6),
    PRIMARY KEY (reference_version_id, outlet_id),
    FOREIGN KEY (reference_version_id, brand_code)
        REFERENCES ref.brands (reference_version_id, brand_code),
    FOREIGN KEY (reference_version_id, district_name)
        REFERENCES ref.districts (reference_version_id, district_name),
    CONSTRAINT ck_outlet_dock_type CHECK (dock_type IN ('rear_dock','street','mall_bay')),
    CONSTRAINT ck_outlet_parking   CHECK (parking_constraint IN ('normal','van_only','mall_dock')),
    -- R-PLN-29: a mall outlet must carry the mall access window it is bound by.
    CONSTRAINT ck_outlet_mall_window CHECK (
        parking_constraint <> 'mall_dock'
        OR (mall_window_open IS NOT NULL AND mall_window_close IS NOT NULL)),
    CONSTRAINT ck_outlet_window  CHECK (window_open_time < window_close_time),
    CONSTRAINT ck_outlet_lat     CHECK (latitude  IS NULL OR latitude  BETWEEN  -90 AND  90),
    CONSTRAINT ck_outlet_lon     CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE ref.vehicles (
    reference_version_id   uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    vehicle_id             text NOT NULL REFERENCES ref.vehicle_registry(vehicle_id),
    vehicle_type           text NOT NULL,
    temperature_capability text NOT NULL,
    weight_cap_kg          numeric(12,2) NOT NULL,
    volume_cap_m3          numeric(12,3) NOT NULL,
    fuel_type              text NOT NULL,
    km_per_l               numeric(10,3) NOT NULL,
    weekly_fuel_quota_l    numeric(12,3) NOT NULL,
    depot_code             text NOT NULL,
    PRIMARY KEY (reference_version_id, vehicle_id),
    FOREIGN KEY (reference_version_id, depot_code)
        REFERENCES ref.depots (reference_version_id, depot_code),
    CONSTRAINT ck_vehicle_type CHECK (vehicle_type IN ('truck','van')),
    -- A-01: reefers may also carry ambient goods; the capability is a ceiling, not an exclusion.
    CONSTRAINT ck_vehicle_temp CHECK (temperature_capability IN ('reefer','ambient')),
    CONSTRAINT ck_vehicle_caps CHECK (weight_cap_kg > 0 AND volume_cap_m3 > 0),
    CONSTRAINT ck_vehicle_fuel CHECK (km_per_l > 0 AND weekly_fuel_quota_l >= 0)
);

-- D8: one row per district. Depot is carried for readability and checked against districts.
CREATE TABLE ref.district_travel (
    reference_version_id           uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    district_name                  text NOT NULL,
    road_class                     text NOT NULL,
    free_flow_kmh                  numeric(8,3)  NOT NULL,
    depot_to_district_km           numeric(10,3) NOT NULL,
    depot_to_district_freeflow_min numeric(10,3) NOT NULL,
    inter_stop_km                  numeric(10,3) NOT NULL,
    inter_stop_freeflow_min        numeric(10,3) NOT NULL,
    PRIMARY KEY (reference_version_id, district_name),
    FOREIGN KEY (reference_version_id, district_name)
        REFERENCES ref.districts (reference_version_id, district_name),
    CONSTRAINT ck_road_class CHECK (road_class IN ('urban','suburban','highway','hill')),
    CONSTRAINT ck_travel_nonneg CHECK (
        free_flow_kmh > 0 AND depot_to_district_km >= 0
        AND depot_to_district_freeflow_min >= 0
        AND inter_stop_km >= 0 AND inter_stop_freeflow_min >= 0)
);

-- R-PLN-06 and the trip time formula both read this. A-16: it is a planning
-- allowance, not an observed duration.
CREATE TABLE ref.service_allowances (
    reference_version_id  uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    brand_code            text NOT NULL,
    dock_type             text NOT NULL,
    service_allowance_min numeric(8,2) NOT NULL,
    PRIMARY KEY (reference_version_id, brand_code, dock_type),
    FOREIGN KEY (reference_version_id, brand_code)
        REFERENCES ref.brands (reference_version_id, brand_code),
    CONSTRAINT ck_allowance_dock CHECK (dock_type IN ('rear_dock','street','mall_bay')),
    CONSTRAINT ck_allowance_positive CHECK (service_allowance_min > 0)
);

CREATE TABLE ref.traffic_speed (
    reference_version_id uuid NOT NULL REFERENCES ref.reference_versions(reference_version_id) ON DELETE CASCADE,
    district_name        text     NOT NULL,
    hour                 smallint NOT NULL,
    monsoon              boolean  NOT NULL,
    speed_index          numeric(6,2) NOT NULL,
    PRIMARY KEY (reference_version_id, district_name, hour, monsoon),
    FOREIGN KEY (reference_version_id, district_name)
        REFERENCES ref.districts (reference_version_id, district_name),
    CONSTRAINT ck_traffic_hour CHECK (hour BETWEEN 0 AND 23),
    CONSTRAINT ck_traffic_index CHECK (speed_index > 0)
);

-- ---- date-keyed series, deliberately not versioned (D1) ----------------

CREATE TABLE ref.calendar_days (
    calendar_date date PRIMARY KEY,
    dow           smallint NOT NULL,
    dow_name      text     NOT NULL,
    is_weekend    boolean  NOT NULL,
    iso_year      integer  NOT NULL,
    iso_week      smallint NOT NULL,
    is_payday     boolean  NOT NULL,
    festival      text,
    festival_ramp numeric(4,3) NOT NULL DEFAULT 0,
    is_holiday    boolean  NOT NULL,
    monsoon       boolean  NOT NULL,
    is_operating  boolean  NOT NULL,
    -- R-CAL-03: a generated day must be distinguishable from a supplied one, so a
    -- dispatcher can tell a real holiday from an assumed one.
    is_generated  boolean  NOT NULL DEFAULT false,
    CONSTRAINT ck_calendar_dow   CHECK (dow BETWEEN 0 AND 6),
    CONSTRAINT ck_calendar_week  CHECK (iso_week BETWEEN 1 AND 53),
    CONSTRAINT ck_festival_ramp  CHECK (festival_ramp BETWEEN 0 AND 1)
);

CREATE TABLE ref.road_conditions (
    district_name    text NOT NULL,
    condition_date   date NOT NULL,
    disruption_index numeric(6,2) NOT NULL,
    PRIMARY KEY (district_name, condition_date),
    CONSTRAINT ck_disruption CHECK (disruption_index > 0)
);

-- ---- daily operational state, not versioned (D6) -----------------------

CREATE TABLE ref.vehicle_day_status (
    vehicle_id   text NOT NULL REFERENCES ref.vehicle_registry(vehicle_id),
    service_date date NOT NULL,
    status       text NOT NULL,
    reason       text,
    set_by       uuid,
    set_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (vehicle_id, service_date),
    -- R-FLT-03: an in_workshop vehicle cannot be allocated.
    CONSTRAINT ck_vehicle_day_status CHECK (status IN ('available','in_workshop','unavailable'))
);

CREATE INDEX ix_vehicle_day_status_date ON ref.vehicle_day_status (service_date);
CREATE INDEX ix_outlets_version_district ON ref.outlets (reference_version_id, district_name);
CREATE INDEX ix_vehicles_version_depot   ON ref.vehicles (reference_version_id, depot_code);
