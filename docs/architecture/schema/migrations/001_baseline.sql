-- 001 baseline: the original industry schema, unchanged.
-- Authored by the team. Do not edit; corrections are new migrations.

-- Waypoint Group / Tech-Triathlon 2026
-- PostgreSQL 16+ reference schema
-- Design goal: normalized OLTP core, immutable business identifiers, auditable planning,
-- offline-safe writes, and ML/output compatibility.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS ref;
CREATE SCHEMA IF NOT EXISTS ops;
CREATE SCHEMA IF NOT EXISTS ml;
CREATE SCHEMA IF NOT EXISTS integration;

-- =========================================================
-- Reference / master data
-- =========================================================

CREATE TABLE ref.brands (
    brand_code text PRIMARY KEY,
    display_name text NOT NULL,
    CONSTRAINT ck_brand_code CHECK (brand_code IN ('Fresh','Style','Tech'))
);

CREATE TABLE ref.depots (
    depot_id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    depot_code text NOT NULL UNIQUE,
    display_name text NOT NULL,
    timezone_name text NOT NULL DEFAULT 'Asia/Colombo',
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE ref.districts (
    district_id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    district_name text NOT NULL UNIQUE
);

CREATE TABLE ref.outlets (
    outlet_id text PRIMARY KEY,                         -- keep OUT001... exactly
    brand_code text NOT NULL REFERENCES ref.brands(brand_code),
    district_id smallint NOT NULL REFERENCES ref.districts(district_id),
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    dock_type text NOT NULL,
    parking_constraint text NOT NULL,
    mall_window_open time,
    mall_window_close time,
    delivery_window_open time NOT NULL,
    delivery_window_close time NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_outlet_dock_type CHECK (dock_type IN ('rear_dock','street','mall_bay')),
    CONSTRAINT ck_outlet_parking CHECK (parking_constraint IN ('normal','van_only','mall_dock')),
    CONSTRAINT ck_outlet_mall_window CHECK (
        parking_constraint <> 'mall_dock'
        OR (mall_window_open IS NOT NULL AND mall_window_close IS NOT NULL)
    )
);

CREATE TABLE ref.vehicles (
    vehicle_id text PRIMARY KEY,                        -- keep VEH001... exactly
    vehicle_type text NOT NULL,
    temperature_capability text NOT NULL,
    weight_cap_kg numeric(12,3) NOT NULL,
    volume_cap_m3 numeric(12,4) NOT NULL,
    fuel_type text NOT NULL,
    km_per_l numeric(10,4) NOT NULL,
    weekly_fuel_quota_l numeric(12,3) NOT NULL,
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_type CHECK (vehicle_type IN ('truck','van')),
    CONSTRAINT ck_vehicle_temp CHECK (temperature_capability IN ('reefer','ambient')),
    CONSTRAINT ck_vehicle_caps CHECK (weight_cap_kg > 0 AND volume_cap_m3 > 0),
    CONSTRAINT ck_vehicle_fuel CHECK (km_per_l > 0 AND weekly_fuel_quota_l >= 0)
);

CREATE TABLE ref.vehicle_day_status (
    vehicle_id text NOT NULL REFERENCES ref.vehicles(vehicle_id),
    service_date date NOT NULL,
    status text NOT NULL,
    reason text,
    PRIMARY KEY (vehicle_id, service_date),
    CONSTRAINT ck_vehicle_day_status CHECK (status IN ('available','in_workshop','unavailable'))
);

CREATE TABLE ref.calendar_days (
    calendar_date date PRIMARY KEY,
    dow smallint NOT NULL,
    dow_name text NOT NULL,
    is_weekend boolean NOT NULL,
    iso_year integer NOT NULL,
    iso_week smallint NOT NULL,
    is_payday boolean NOT NULL,
    festival text,
    festival_ramp numeric(4,3) NOT NULL DEFAULT 0,
    is_holiday boolean NOT NULL,
    monsoon boolean NOT NULL,
    is_operating boolean NOT NULL,
    CONSTRAINT ck_calendar_dow CHECK (dow BETWEEN 0 AND 6),
    CONSTRAINT ck_calendar_week CHECK (iso_week BETWEEN 1 AND 53),
    CONSTRAINT ck_festival_ramp CHECK (festival_ramp BETWEEN 0 AND 1)
);

CREATE TABLE ref.district_travel (
    district_id smallint NOT NULL REFERENCES ref.districts(district_id),
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    road_class text NOT NULL,
    free_flow_kmh numeric(8,3) NOT NULL,
    depot_to_district_km numeric(10,3) NOT NULL,
    depot_to_district_freeflow_min numeric(10,3) NOT NULL,
    inter_stop_km numeric(10,3) NOT NULL,
    inter_stop_freeflow_min numeric(10,3) NOT NULL,
    PRIMARY KEY (district_id, depot_id),
    CONSTRAINT ck_road_class CHECK (road_class IN ('urban','suburban','highway','hill')),
    CONSTRAINT ck_district_travel_nonnegative CHECK (
        free_flow_kmh > 0 AND depot_to_district_km >= 0
        AND depot_to_district_freeflow_min >= 0
        AND inter_stop_km >= 0 AND inter_stop_freeflow_min >= 0
    )
);

CREATE TABLE ref.service_allowances (
    brand_code text NOT NULL REFERENCES ref.brands(brand_code),
    dock_type text NOT NULL,
    service_allowance_min numeric(8,2) NOT NULL,
    PRIMARY KEY (brand_code, dock_type),
    CONSTRAINT ck_service_dock_type CHECK (dock_type IN ('rear_dock','street','mall_bay')),
    CONSTRAINT ck_service_allowance CHECK (service_allowance_min >= 0)
);

-- Product/item detail is an operational extension. The competition files aggregate at order level,
-- so order_items may legitimately be empty for seeded challenge data.
CREATE TABLE ref.products (
    product_id text PRIMARY KEY,
    brand_code text NOT NULL REFERENCES ref.brands(brand_code),
    product_name text NOT NULL,
    temperature_requirement text NOT NULL DEFAULT 'ambient',
    unit_weight_kg numeric(12,4),
    unit_volume_m3 numeric(12,6),
    is_active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_product_temp CHECK (temperature_requirement IN ('ambient','chilled','frozen')),
    CONSTRAINT ck_product_dimensions CHECK (
        (unit_weight_kg IS NULL OR unit_weight_kg >= 0)
        AND (unit_volume_m3 IS NULL OR unit_volume_m3 >= 0)
    )
);

-- =========================================================
-- Identity and access
-- =========================================================

CREATE TABLE iam.users (
    user_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE,
    display_name text NOT NULL,
    password_hash text NOT NULL,                         -- never store plaintext passwords
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE iam.roles (
    role_code text PRIMARY KEY,
    description text NOT NULL,
    CONSTRAINT ck_role_code CHECK (role_code IN ('dispatcher','loader','driver','store_manager','admin'))
);

CREATE TABLE iam.user_roles (
    user_id uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    role_code text NOT NULL REFERENCES iam.roles(role_code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE iam.user_depot_access (
    user_id uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    PRIMARY KEY (user_id, depot_id)
);

CREATE TABLE iam.user_outlet_access (
    user_id uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    outlet_id text NOT NULL REFERENCES ref.outlets(outlet_id),
    PRIMARY KEY (user_id, outlet_id)
);

CREATE TABLE iam.vehicle_driver_assignments (
    assignment_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id text NOT NULL REFERENCES ref.vehicles(vehicle_id),
    driver_user_id uuid NOT NULL REFERENCES iam.users(user_id),
    valid_from date NOT NULL,
    valid_to date,
    CONSTRAINT ck_driver_assignment_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE TABLE iam.devices (
    device_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_label text NOT NULL,
    device_kind text NOT NULL,
    depot_id smallint REFERENCES ref.depots(depot_id),
    registered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    is_active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_device_kind CHECK (device_kind IN ('shared_tablet','personal_phone','desktop','terminal'))
);

-- =========================================================
-- Order capture and lifecycle
-- =========================================================

CREATE TABLE ops.orders (
    order_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id text NOT NULL UNIQUE,                    -- preserves challenge/order business identifier
    outlet_id text NOT NULL REFERENCES ref.outlets(outlet_id),
    requested_delivery_date date NOT NULL,
    eligible_dispatch_date date NOT NULL,
    submitted_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    temperature_requirement text NOT NULL,
    order_units integer NOT NULL,
    order_weight_kg numeric(12,3) NOT NULL,
    order_volume_m3 numeric(12,4) NOT NULL,
    status text NOT NULL DEFAULT 'draft',
    created_by uuid REFERENCES iam.users(user_id),
    row_version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_temp CHECK (temperature_requirement IN ('ambient','chilled','frozen')),
    CONSTRAINT ck_order_size CHECK (order_units >= 0 AND order_weight_kg >= 0 AND order_volume_m3 >= 0),
    CONSTRAINT ck_order_status CHECK (status IN (
        'draft','confirmed','queued','allocated','deferred','loading','dispatched',
        'delivered','partially_delivered','failed','received','cancelled','not_run'
    ))
);

CREATE TABLE ops.order_items (
    order_item_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES ops.orders(order_id) ON DELETE CASCADE,
    product_id text NOT NULL REFERENCES ref.products(product_id),
    quantity integer NOT NULL,
    unit_weight_kg_snapshot numeric(12,4),
    unit_volume_m3_snapshot numeric(12,6),
    UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_item_qty CHECK (quantity > 0)
);

CREATE TABLE ops.order_status_history (
    order_status_event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES ops.orders(order_id) ON DELETE CASCADE,
    from_status text,
    to_status text NOT NULL,
    changed_by uuid REFERENCES iam.users(user_id),
    changed_at timestamptz NOT NULL DEFAULT now(),
    reason text
);

-- =========================================================
-- Planning and allocation
-- =========================================================

CREATE TABLE ops.planning_runs (
    planning_run_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code text NOT NULL UNIQUE,
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    service_date date NOT NULL,
    plan_version integer NOT NULL DEFAULT 1,
    status text NOT NULL DEFAULT 'draft',
    created_by uuid NOT NULL REFERENCES iam.users(user_id),
    published_by uuid REFERENCES iam.users(user_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    notes text,
    CONSTRAINT uq_plan_version UNIQUE (depot_id, service_date, plan_version),
    CONSTRAINT ck_plan_version CHECK (plan_version > 0),
    CONSTRAINT ck_plan_status CHECK (status IN ('draft','published','superseded','cancelled'))
);

-- At most one published source-of-truth plan for a depot/day.
CREATE UNIQUE INDEX uq_one_published_plan_per_day
    ON ops.planning_runs(depot_id, service_date)
    WHERE status = 'published';

CREATE TABLE ops.trips (
    trip_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    planning_run_id uuid NOT NULL REFERENCES ops.planning_runs(planning_run_id) ON DELETE CASCADE,
    vehicle_id text NOT NULL REFERENCES ref.vehicles(vehicle_id),
    trip_number smallint NOT NULL,
    brand_code text NOT NULL REFERENCES ref.brands(brand_code),
    district_id smallint NOT NULL REFERENCES ref.districts(district_id),
    status text NOT NULL DEFAULT 'planned',
    planned_departure_at timestamptz,
    planned_end_at timestamptz,
    planned_distance_km numeric(10,3) NOT NULL DEFAULT 0,
    planned_duration_min numeric(10,2) NOT NULL DEFAULT 0,
    planned_weight_kg numeric(12,3) NOT NULL DEFAULT 0,
    planned_volume_m3 numeric(12,4) NOT NULL DEFAULT 0,
    planned_fuel_l numeric(12,3) NOT NULL DEFAULT 0,
    row_version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vehicle_trip_in_plan UNIQUE (planning_run_id, vehicle_id, trip_number),
    CONSTRAINT ck_trip_number CHECK (trip_number IN (1,2)),
    CONSTRAINT ck_trip_status CHECK (status IN ('planned','loading','ready','dispatched','in_progress','completed','cancelled')),
    CONSTRAINT ck_trip_nonnegative CHECK (
        planned_distance_km >= 0 AND planned_duration_min >= 0
        AND planned_weight_kg >= 0 AND planned_volume_m3 >= 0 AND planned_fuel_l >= 0
    )
);

CREATE TABLE ops.order_allocations (
    allocation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    planning_run_id uuid NOT NULL REFERENCES ops.planning_runs(planning_run_id) ON DELETE CASCADE,
    order_id uuid NOT NULL REFERENCES ops.orders(order_id),
    decision text NOT NULL,
    trip_id uuid REFERENCES ops.trips(trip_id),
    stop_sequence integer,
    planned_arrival_at timestamptz,
    predicted_service_min numeric(10,2),
    predicted_late_probability numeric(7,6),
    reason_code text,
    reason_detail text,
    deferred_to_date date,
    decided_by uuid NOT NULL REFERENCES iam.users(user_id),
    decided_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_once_per_plan UNIQUE (planning_run_id, order_id),
    CONSTRAINT uq_trip_stop_sequence UNIQUE (trip_id, stop_sequence),
    CONSTRAINT ck_allocation_decision CHECK (decision IN ('served','deferred')),
    CONSTRAINT ck_prediction_range CHECK (
        (predicted_service_min IS NULL OR predicted_service_min >= 0)
        AND (predicted_late_probability IS NULL OR predicted_late_probability BETWEEN 0 AND 1)
    ),
    CONSTRAINT ck_served_has_trip CHECK (
        (decision = 'served' AND trip_id IS NOT NULL AND stop_sequence IS NOT NULL AND stop_sequence >= 0)
        OR
        (decision = 'deferred' AND trip_id IS NULL AND stop_sequence IS NULL)
    )
);

CREATE TABLE ops.order_deferrals (
    deferral_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES ops.orders(order_id),
    planning_run_id uuid NOT NULL REFERENCES ops.planning_runs(planning_run_id),
    deferred_from_date date NOT NULL,
    deferred_to_date date,
    reason_code text NOT NULL,
    reason_detail text,
    consequence_note text,
    decided_by uuid NOT NULL REFERENCES iam.users(user_id),
    decided_at timestamptz NOT NULL DEFAULT now()
);

-- Route leg mirrors the challenge's route-leg model and preserves planned vs actual times.
CREATE TABLE ops.route_legs (
    route_leg_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    leg_code text UNIQUE,
    trip_id uuid NOT NULL REFERENCES ops.trips(trip_id) ON DELETE CASCADE,
    allocation_id uuid NOT NULL UNIQUE REFERENCES ops.order_allocations(allocation_id) ON DELETE CASCADE,
    seq integer NOT NULL,
    from_kind text NOT NULL,
    from_outlet_id text REFERENCES ref.outlets(outlet_id),
    to_outlet_id text NOT NULL REFERENCES ref.outlets(outlet_id),
    distance_km numeric(10,3) NOT NULL,
    planned_depart_at timestamptz,
    planned_travel_duration_min numeric(10,2),
    planned_arrival_at timestamptz,
    actual_depart_at timestamptz,
    actual_travel_duration_min numeric(10,2),
    actual_arrival_at timestamptz,
    leave_outlet_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_route_leg_seq UNIQUE (trip_id, seq),
    CONSTRAINT ck_route_seq CHECK (seq >= 0),
    CONSTRAINT ck_from_kind CHECK (from_kind IN ('depot','outlet')),
    CONSTRAINT ck_from_outlet_consistency CHECK (
        (from_kind = 'depot' AND from_outlet_id IS NULL)
        OR (from_kind = 'outlet' AND from_outlet_id IS NOT NULL)
    ),
    CONSTRAINT ck_leg_metrics CHECK (
        distance_km >= 0
        AND (planned_travel_duration_min IS NULL OR planned_travel_duration_min >= 0)
        AND (actual_travel_duration_min IS NULL OR actual_travel_duration_min >= 0)
    )
);

CREATE TABLE ops.vehicle_trip_fuel_usage (
    trip_id uuid PRIMARY KEY REFERENCES ops.trips(trip_id) ON DELETE CASCADE,
    distance_km numeric(10,3) NOT NULL,
    estimated_litres numeric(12,3) NOT NULL,
    actual_litres numeric(12,3),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_trip_fuel_nonnegative CHECK (
        distance_km >= 0 AND estimated_litres >= 0 AND (actual_litres IS NULL OR actual_litres >= 0)
    )
);

-- =========================================================
-- Loading
-- =========================================================

CREATE TABLE ops.loading_sessions (
    loading_session_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id uuid NOT NULL UNIQUE REFERENCES ops.trips(trip_id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'not_started',
    started_by uuid REFERENCES iam.users(user_id),
    started_at timestamptz,
    completed_by uuid REFERENCES iam.users(user_id),
    completed_at timestamptz,
    row_version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_loading_session_status CHECK (status IN ('not_started','in_progress','blocked','completed'))
);

CREATE TABLE ops.loading_checks (
    loading_check_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    loading_session_id uuid NOT NULL REFERENCES ops.loading_sessions(loading_session_id) ON DELETE CASCADE,
    allocation_id uuid NOT NULL UNIQUE REFERENCES ops.order_allocations(allocation_id),
    status text NOT NULL DEFAULT 'pending',
    planned_units integer NOT NULL,
    loaded_units integer NOT NULL DEFAULT 0,
    notes text,
    checked_by uuid REFERENCES iam.users(user_id),
    checked_at timestamptz,
    row_version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_loading_check_status CHECK (status IN ('pending','loaded','short','missing','damaged','blocked')),
    CONSTRAINT ck_loading_units CHECK (planned_units >= 0 AND loaded_units >= 0)
);

-- =========================================================
-- Delivery, POD, receipt, issues
-- =========================================================

CREATE TABLE ops.delivery_records (
    delivery_record_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    allocation_id uuid NOT NULL UNIQUE REFERENCES ops.order_allocations(allocation_id),
    driver_user_id uuid REFERENCES iam.users(user_id),
    outcome text NOT NULL DEFAULT 'pending',
    arrived_at timestamptz,
    service_started_at timestamptz,
    completed_at timestamptz,
    delivered_units integer,
    failure_reason text,
    notes text,
    row_version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_delivery_outcome CHECK (outcome IN ('pending','arrived','delivered','partial','failed','skipped')),
    CONSTRAINT ck_delivered_units CHECK (delivered_units IS NULL OR delivered_units >= 0)
);

CREATE TABLE ops.attachments (
    attachment_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_key text NOT NULL UNIQUE,
    media_type text NOT NULL,
    byte_size bigint NOT NULL,
    sha256_hex text NOT NULL,
    uploaded_by uuid REFERENCES iam.users(user_id),
    captured_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_attachment_size CHECK (byte_size >= 0)
);

CREATE TABLE ops.proof_of_delivery (
    pod_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_record_id uuid NOT NULL UNIQUE REFERENCES ops.delivery_records(delivery_record_id) ON DELETE CASCADE,
    recipient_name text,
    signature_attachment_id uuid REFERENCES ops.attachments(attachment_id),
    photo_attachment_id uuid REFERENCES ops.attachments(attachment_id),
    captured_by uuid REFERENCES iam.users(user_id),
    captured_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ops.receipt_confirmations (
    receipt_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL UNIQUE REFERENCES ops.orders(order_id),
    delivery_record_id uuid NOT NULL UNIQUE REFERENCES ops.delivery_records(delivery_record_id),
    receipt_status text NOT NULL,
    received_units integer,
    issue_notes text,
    confirmed_by uuid NOT NULL REFERENCES iam.users(user_id),
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_receipt_status CHECK (receipt_status IN ('accepted','accepted_with_issue','rejected')),
    CONSTRAINT ck_receipt_units CHECK (received_units IS NULL OR received_units >= 0)
);

CREATE TABLE ops.operational_issues (
    issue_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_type text NOT NULL,
    severity text NOT NULL DEFAULT 'medium',
    status text NOT NULL DEFAULT 'open',
    trip_id uuid REFERENCES ops.trips(trip_id),
    allocation_id uuid REFERENCES ops.order_allocations(allocation_id),
    order_id uuid REFERENCES ops.orders(order_id),
    reported_by uuid REFERENCES iam.users(user_id),
    reported_at timestamptz NOT NULL DEFAULT now(),
    description text NOT NULL,
    resolved_by uuid REFERENCES iam.users(user_id),
    resolved_at timestamptz,
    resolution_note text,
    CONSTRAINT ck_issue_type CHECK (issue_type IN (
        'loading_shortfall','missing_goods','damaged_goods','vehicle_problem','delay',
        'access_problem','connectivity','delivery_dispute','other'
    )),
    CONSTRAINT ck_issue_severity CHECK (severity IN ('low','medium','high','critical')),
    CONSTRAINT ck_issue_status CHECK (status IN ('open','acknowledged','resolved','cancelled')),
    CONSTRAINT ck_issue_subject CHECK (trip_id IS NOT NULL OR allocation_id IS NOT NULL OR order_id IS NOT NULL)
);

CREATE TABLE ops.notifications (
    notification_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id uuid REFERENCES iam.users(user_id),
    outlet_id text REFERENCES ref.outlets(outlet_id),
    notification_type text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    CONSTRAINT ck_notification_recipient CHECK (recipient_user_id IS NOT NULL OR outlet_id IS NOT NULL)
);

-- =========================================================
-- ML / prediction persistence (optional integration with Datathon models)
-- =========================================================

CREATE TABLE ml.model_versions (
    model_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    model_name text NOT NULL,
    model_version text NOT NULL,
    task_name text NOT NULL,
    artifact_uri text,
    metrics jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (model_name, model_version)
);

CREATE TABLE ml.delivery_predictions (
    prediction_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES ops.orders(order_id),
    planning_run_id uuid REFERENCES ops.planning_runs(planning_run_id),
    model_version_id uuid NOT NULL REFERENCES ml.model_versions(model_version_id),
    pred_service_min numeric(10,3) NOT NULL,
    pred_late_prob numeric(7,6) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    feature_snapshot jsonb,
    CONSTRAINT ck_delivery_prediction CHECK (pred_service_min >= 0 AND pred_late_prob BETWEEN 0 AND 1)
);

CREATE TABLE ml.demand_forecasts (
    forecast_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    depot_id smallint NOT NULL REFERENCES ref.depots(depot_id),
    brand_code text NOT NULL REFERENCES ref.brands(brand_code),
    iso_year integer NOT NULL,
    iso_week smallint NOT NULL,
    model_version_id uuid NOT NULL REFERENCES ml.model_versions(model_version_id),
    pred_total_volume_m3 numeric(14,4) NOT NULL,
    pred_chilled_volume_m3 numeric(14,4) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (depot_id, brand_code, iso_year, iso_week, model_version_id),
    CONSTRAINT ck_forecast_week CHECK (iso_week BETWEEN 1 AND 53),
    CONSTRAINT ck_forecast_values CHECK (
        pred_total_volume_m3 >= 0
        AND pred_chilled_volume_m3 >= 0
        AND pred_chilled_volume_m3 <= pred_total_volume_m3
    )
);

-- =========================================================
-- Offline sync, integration, and audit
-- =========================================================

-- Client-generated operation_id makes retries idempotent after connectivity returns.
CREATE TABLE integration.sync_operations (
    operation_id uuid PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES iam.devices(device_id),
    user_id uuid NOT NULL REFERENCES iam.users(user_id),
    entity_type text NOT NULL,
    entity_id uuid,
    operation_type text NOT NULL,
    base_row_version bigint,
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    status text NOT NULL DEFAULT 'received',
    conflict_reason text,
    CONSTRAINT ck_sync_operation_type CHECK (operation_type IN ('create','update','delete','command')),
    CONSTRAINT ck_sync_status CHECK (status IN ('received','applied','conflict','rejected'))
);

-- Transactional outbox: write domain state and event atomically, publish asynchronously.
CREATE TABLE integration.outbox_events (
    event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_publish_attempts CHECK (publish_attempts >= 0)
);

CREATE TABLE integration.audit_log (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid REFERENCES iam.users(user_id),
    device_id uuid REFERENCES iam.devices(device_id),
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    action text NOT NULL,
    before_data jsonb,
    after_data jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    correlation_id uuid
);

-- =========================================================
-- Indexes for the main operational queries
-- =========================================================

CREATE INDEX ix_outlets_depot_brand ON ref.outlets(depot_id, brand_code);
CREATE INDEX ix_outlets_district ON ref.outlets(district_id);
CREATE INDEX ix_vehicles_depot_capability ON ref.vehicles(depot_id, temperature_capability, vehicle_type) WHERE is_active;

CREATE INDEX ix_orders_outlet_date ON ops.orders(outlet_id, requested_delivery_date DESC);
CREATE INDEX ix_orders_queue ON ops.orders(eligible_dispatch_date, status) WHERE status IN ('confirmed','queued','deferred');
CREATE INDEX ix_orders_status_updated ON ops.orders(status, updated_at DESC);

CREATE INDEX ix_planning_runs_day ON ops.planning_runs(service_date, depot_id, status);
CREATE INDEX ix_trips_plan_vehicle ON ops.trips(planning_run_id, vehicle_id);
CREATE INDEX ix_allocations_trip ON ops.order_allocations(trip_id, stop_sequence) WHERE decision = 'served';
CREATE INDEX ix_allocations_deferred ON ops.order_allocations(planning_run_id, decision) WHERE decision = 'deferred';
CREATE INDEX ix_deferrals_order_history ON ops.order_deferrals(order_id, decided_at DESC);
CREATE INDEX ix_route_legs_trip_seq ON ops.route_legs(trip_id, seq);
CREATE INDEX ix_loading_checks_status ON ops.loading_checks(status);
CREATE INDEX ix_delivery_outcome ON ops.delivery_records(outcome, updated_at DESC);
CREATE INDEX ix_operational_issues_open ON ops.operational_issues(status, severity, reported_at DESC) WHERE status IN ('open','acknowledged');
CREATE INDEX ix_notifications_user_unread ON ops.notifications(recipient_user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX ix_delivery_predictions_order ON ml.delivery_predictions(order_id, generated_at DESC);
CREATE INDEX ix_forecasts_period ON ml.demand_forecasts(iso_year, iso_week, depot_id, brand_code);
CREATE INDEX ix_sync_pending ON integration.sync_operations(status, received_at) WHERE status IN ('received','conflict');
CREATE INDEX ix_outbox_unpublished ON integration.outbox_events(occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_audit_entity ON integration.audit_log(entity_type, entity_id, occurred_at DESC);

-- =========================================================
-- Seed role / brand values
-- =========================================================

INSERT INTO ref.brands (brand_code, display_name) VALUES
    ('Fresh','Waypoint Fresh'),
    ('Style','Waypoint Style'),
    ('Tech','Waypoint Tech')
ON CONFLICT DO NOTHING;

INSERT INTO iam.roles (role_code, description) VALUES
    ('dispatcher','Plans and allocates orders, handles deferrals, monitors execution'),
    ('loader','Loads planned trips and records loading exceptions'),
    ('driver','Executes trips and records delivery outcomes / POD'),
    ('store_manager','Places orders, receives ETA/deferral notices, confirms receipt'),
    ('admin','Administrative access')
ON CONFLICT DO NOTHING;

-- =========================================================
-- IMPORTANT DOMAIN VALIDATION AT PLAN PUBLISH TIME
-- =========================================================
-- Enforce these in ONE transaction in a publish-plan service (and optionally mirror them
-- in a stored procedure) before changing planning_runs.status to 'published':
--
-- 1. Every served order belongs to the trip's brand and district.
-- 2. Chilled/frozen -> reefer vehicle.
-- 3. van_only outlet -> van.
-- 4. Outlet depot = plan depot = vehicle depot.
-- 5. Each order is whole and appears once in the published plan.
-- 6. SUM(order_weight_kg) <= vehicle.weight_cap_kg per trip.
-- 7. SUM(order_volume_m3) <= vehicle.volume_cap_m3 per trip.
-- 8. vehicle_day_status must be available.
-- 9. trip_number is only 1 or 2 (schema already enforces this).
-- 10. Fresh total planned_duration_min per vehicle/day <= 270.
-- 11. Style+Tech total planned_duration_min per vehicle/day <= 480.
-- 12. Weekly planned/actual fuel usage <= vehicles.weekly_fuel_quota_l.
-- 13. Planned arrival respects outlet delivery window and mall window where applicable.
--
-- Do not publish a plan if any hard constraint fails. Store deferrals with explicit reason codes.
--
-- NOTE ON traffic_speed.csv / road_conditions.csv:
-- The booklet documents their purpose and only selected key columns, not the complete key schema.
-- Create typed ref tables after inspecting the actual CSV headers; do not invent undocumented keys.
