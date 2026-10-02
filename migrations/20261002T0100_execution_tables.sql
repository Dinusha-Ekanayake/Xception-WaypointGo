-- Execution's tables (issue #12). Schema and role came with 20260930T1200.
--
--     Execution keeps its own copy of each released trip, built from
--     trip.released, so it never reads Loading's or Planning's tables (D-B).
--     The unit of work is the delivery record: one order at one stop.
--
--     Every record carries the outlet's window as it stood at release, so a
--     later reference change never rewrites what the driver was held to, and
--     the depot, vehicle, date and outlet it is scoped by, so row-level
--     security decides without a join.
--
--     Server time decides (R-EXE-10). Each step keeps the device's clock
--     beside the server's; the device's is never read for a decision.
--
--     Nothing is deleted; the role has no DELETE. Proofs are append only.

CREATE TABLE execution.trips (
    trip_id                 uuid        PRIMARY KEY,
    plan_id                 uuid        NOT NULL,
    plan_version            integer     NOT NULL,
    depot_code              text        NOT NULL,
    vehicle_id              text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    service_date            date        NOT NULL,
    released_at             timestamptz NOT NULL,
    announced_delay_minutes integer     NOT NULL DEFAULT 0,
    CONSTRAINT ck_trips_delay CHECK (announced_delay_minutes >= 0)
);

COMMENT ON TABLE execution.trips IS
  'A trip as it left the dock (trip.released). announced_delay_minutes is the delay last told to the stops ahead.';

CREATE INDEX ix_trips_vehicle_day ON execution.trips (vehicle_id, service_date);
CREATE INDEX ix_trips_depot_day   ON execution.trips (depot_code, service_date);

CREATE TABLE execution.delivery_records (
    delivery_id        uuid        PRIMARY KEY,
    trip_id            uuid        NOT NULL REFERENCES execution.trips (trip_id),
    order_id           uuid        NOT NULL,
    outlet_id          text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    depot_code         text        NOT NULL,
    vehicle_id         text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    service_date       date        NOT NULL,
    stop_sequence      integer     NOT NULL,
    item_count         integer     NOT NULL,
    planned_arrival    time        NOT NULL,
    window_open        time        NOT NULL,
    window_close       time        NOT NULL,
    mall_outlet        boolean     NOT NULL DEFAULT false,
    released_at        timestamptz NOT NULL,
    outcome            text        NOT NULL DEFAULT 'pending',
    expected_arrival   timestamptz,
    started_at         timestamptz,
    arrived_at         timestamptz,
    service_started_at timestamptz,
    completed_at       timestamptz,
    wait_minutes       integer,
    late_minutes       integer,
    late_reason        text,
    timing_uncertain   boolean     NOT NULL DEFAULT false,
    delivered_units    integer,
    failure_reason     text,
    disposition_note   text,
    low_evidence       boolean     NOT NULL DEFAULT false,
    proof_id           uuid,
    client_arrived_at  timestamptz,
    client_recorded_at timestamptz,
    server_recorded_at timestamptz NOT NULL,
    recorded_by        uuid,
    device_id          uuid,
    row_version        bigint      NOT NULL DEFAULT 1,
    CONSTRAINT uq_delivery_trip_order UNIQUE (trip_id, order_id),
    CONSTRAINT ck_delivery_outcome CHECK (outcome IN (
        'pending','arrived','delivered','partial','failed','skipped')),
    CONSTRAINT ck_delivery_sequence CHECK (stop_sequence > 0),
    CONSTRAINT ck_delivery_items CHECK (item_count > 0),
    CONSTRAINT ck_delivery_window CHECK (window_open < window_close),
    CONSTRAINT ck_delivery_minutes CHECK (
        (wait_minutes IS NULL OR wait_minutes >= 0) AND (late_minutes IS NULL OR late_minutes >= 0)),
    -- A stop the vehicle reached has its timing; one it has not, does not.
    CONSTRAINT ck_delivery_arrival CHECK (
        (arrived_at IS NULL) = (service_started_at IS NULL)
        AND (arrived_at IS NULL) = (wait_minutes IS NULL)
        AND (arrived_at IS NULL) = (late_minutes IS NULL)
        AND (outcome NOT IN ('arrived','delivered','partial') OR arrived_at IS NOT NULL)
        AND (service_started_at IS NULL OR service_started_at >= arrived_at)),
    CONSTRAINT ck_delivery_completion CHECK (
        (outcome IN ('pending','arrived')) = (completed_at IS NULL)),
    CONSTRAINT ck_delivery_units CHECK (
        (outcome <> 'delivered' OR delivered_units = item_count)
        AND (outcome <> 'partial' OR (delivered_units >= 1 AND delivered_units < item_count))
        AND (outcome <> 'failed' OR delivered_units = 0)),
    -- R-EXE-12: goods that were not delivered are accounted for.
    CONSTRAINT ck_delivery_disposition CHECK (
        outcome NOT IN ('partial','failed') OR (failure_reason IS NOT NULL AND disposition_note IS NOT NULL)),
    -- R-EXE-05: a late delivery carries a reason.
    CONSTRAINT ck_delivery_late_reason CHECK (
        outcome NOT IN ('delivered','partial') OR coalesce(late_minutes, 0) = 0 OR late_reason IS NOT NULL),
    -- EXE-20: nothing is unloaded at a mall after its window.
    CONSTRAINT ck_delivery_mall CHECK (
        NOT (mall_outlet AND outcome IN ('delivered','partial') AND coalesce(late_minutes, 0) > 0))
);

COMMENT ON TABLE execution.delivery_records IS
  'One order at one stop of a released trip, and what happened there. row_version moves on by one per accepted command.';
COMMENT ON COLUMN execution.delivery_records.released_at IS
  'When the trip left the dock, copied from the trip so a store manager, who sees stops and not trips, can read them in order.';
COMMENT ON COLUMN execution.delivery_records.late_minutes IS
  'Minutes after window_close at arrival, by the server clock. Never measured against planned_arrival (R-EXE-14).';
COMMENT ON COLUMN execution.delivery_records.wait_minutes IS
  'Minutes waited for the window to open. Kept apart from service time (R-EXE-04).';
COMMENT ON COLUMN execution.delivery_records.timing_uncertain IS
  'Device and server clocks differed by more than five minutes at arrival; usually recorded offline and replayed.';
COMMENT ON COLUMN execution.delivery_records.client_recorded_at IS
  'The device clock of the latest accepted command. Forensics only (R-EXE-10).';

CREATE INDEX ix_delivery_trip        ON execution.delivery_records (trip_id, stop_sequence);
CREATE INDEX ix_delivery_vehicle_day ON execution.delivery_records (vehicle_id, service_date, stop_sequence);
CREATE INDEX ix_delivery_depot_day   ON execution.delivery_records (depot_code, service_date);
CREATE INDEX ix_delivery_outlet_day  ON execution.delivery_records (outlet_id, service_date);
CREATE INDEX ix_delivery_order       ON execution.delivery_records (order_id);

CREATE TABLE execution.attachments (
    attachment_id uuid        PRIMARY KEY,
    delivery_id   uuid        NOT NULL REFERENCES execution.delivery_records (delivery_id),
    kind          text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    integer     NOT NULL,
    sha256        text        NOT NULL,
    storage_key   text        NOT NULL,
    scan_status   text        NOT NULL DEFAULT 'not_scanned',
    retain_until  date        NOT NULL,
    uploaded_by   uuid        NOT NULL,
    uploaded_at   timestamptz NOT NULL,
    CONSTRAINT ck_attachments_kind CHECK (kind IN ('photo','signature')),
    CONSTRAINT ck_attachments_type CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT ck_attachments_size CHECK (size_bytes > 0),
    CONSTRAINT ck_attachments_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attachments_scan CHECK (scan_status IN ('not_scanned','clean','rejected'))
);

COMMENT ON TABLE execution.attachments IS
  'A proof artifact held in the proof store. The id is minted on the device, so a repeated upload is a no-op.';

CREATE INDEX ix_attachments_delivery ON execution.attachments (delivery_id);
CREATE INDEX ix_attachments_retain   ON execution.attachments (retain_until);

CREATE TABLE execution.proofs (
    proof_id                uuid        PRIMARY KEY,
    delivery_id             uuid        NOT NULL REFERENCES execution.delivery_records (delivery_id),
    attempt                 integer     NOT NULL,
    photo_attachment_id     uuid,
    signature_attachment_id uuid,
    recipient_name          text,
    fallback_reason         text,
    low_evidence            boolean     NOT NULL,
    captured_by             uuid        NOT NULL,
    device_id               uuid,
    command_id              uuid        NOT NULL,
    captured_at             timestamptz NOT NULL,
    client_recorded_at      timestamptz,
    CONSTRAINT uq_proofs_attempt UNIQUE (delivery_id, attempt),
    CONSTRAINT ck_proofs_attempt CHECK (attempt > 0),
    -- R-EXE-11: with neither photo nor signature there is a reason, and the proof says so.
    CONSTRAINT ck_proofs_evidence CHECK (
        low_evidence = (photo_attachment_id IS NULL AND signature_attachment_id IS NULL)
        AND (NOT low_evidence OR fallback_reason IS NOT NULL))
);

COMMENT ON TABLE execution.proofs IS
  'Append only. The latest attempt is the delivery''s proof; earlier ones stay as evidence. recipient_name is personal data.';
COMMENT ON COLUMN execution.proofs.photo_attachment_id IS
  'Minted on the device. No foreign key: the proof may be recorded before the upload reaches the server (EXE-10), and is shown as pending until it does.';

CREATE INDEX ix_proofs_photo     ON execution.proofs (photo_attachment_id) WHERE photo_attachment_id IS NOT NULL;
CREATE INDEX ix_proofs_signature ON execution.proofs (signature_attachment_id) WHERE signature_attachment_id IS NOT NULL;

ALTER TABLE execution.delivery_records
    ADD CONSTRAINT fk_delivery_proof FOREIGN KEY (proof_id) REFERENCES execution.proofs (proof_id);
CREATE INDEX ix_delivery_proof ON execution.delivery_records (proof_id) WHERE proof_id IS NOT NULL;

CREATE TABLE execution.vehicle_reports (
    report_id          uuid        PRIMARY KEY,
    vehicle_id         text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    depot_code         text        NOT NULL,
    service_date       date        NOT NULL,
    kind               text        NOT NULL,
    status             text,
    note               text,
    delivery_id        uuid        REFERENCES execution.delivery_records (delivery_id),
    reported_by        uuid        NOT NULL,
    device_id          uuid,
    command_id         uuid        NOT NULL,
    reported_at        timestamptz NOT NULL,
    client_recorded_at timestamptz,
    CONSTRAINT ck_vehicle_reports_kind CHECK (kind IN ('status','fault')),
    CONSTRAINT ck_vehicle_reports_status CHECK (
        (kind = 'status') = (status IS NOT NULL)
        AND (status IS NULL OR status IN ('available','on_trip','at_workshop','fault'))),
    CONSTRAINT ck_vehicle_reports_fault CHECK (kind <> 'fault' OR note IS NOT NULL)
);

COMMENT ON TABLE execution.vehicle_reports IS
  'What a driver reported about the vehicle (R-EXE-06). A report changes nothing in reference data; the dispatcher applies vehicle:SetDayStatus.';

CREATE INDEX ix_vehicle_reports_vehicle  ON execution.vehicle_reports (vehicle_id, service_date);
CREATE INDEX ix_vehicle_reports_depot    ON execution.vehicle_reports (depot_code, service_date);
CREATE INDEX ix_vehicle_reports_delivery ON execution.vehicle_reports (delivery_id) WHERE delivery_id IS NOT NULL;

CREATE TABLE execution.road_reports (
    report_id          uuid        PRIMARY KEY,
    vehicle_id         text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    depot_code         text        NOT NULL,
    service_date       date        NOT NULL,
    district_name      text,
    description        text        NOT NULL,
    delivery_id        uuid        REFERENCES execution.delivery_records (delivery_id),
    reported_by        uuid        NOT NULL,
    device_id          uuid,
    command_id         uuid        NOT NULL,
    reported_at        timestamptz NOT NULL,
    client_recorded_at timestamptz
);

COMMENT ON TABLE execution.road_reports IS
  'A road fault or delay a driver reported (R-EXE-07).';

CREATE INDEX ix_road_reports_vehicle  ON execution.road_reports (vehicle_id, service_date);
CREATE INDEX ix_road_reports_depot    ON execution.road_reports (depot_code, service_date);
CREATE INDEX ix_road_reports_delivery ON execution.road_reports (delivery_id) WHERE delivery_id IS NOT NULL;

-- ---- privileges ---------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON execution.trips, execution.delivery_records, execution.attachments
    TO waypoint_execution;
-- Append only: evidence and reports are never rewritten.
GRANT SELECT, INSERT ON execution.proofs, execution.vehicle_reports, execution.road_reports
    TO waypoint_execution;

-- ---- row-level security -------------------------------------------------
-- Effective access is policy AND scope. Policy grants delivery:* to drivers and
-- delivery:Read to dispatchers and store managers; these policies decide rows.
--
-- Reading: the driver assigned to the vehicle on its service date, a dispatcher
-- of the depot, a store manager of the outlet, and the process itself.
-- Writing: the driver and the process only. A dispatcher who somehow reached a
-- write would be refused here even if policy had allowed it.

ALTER TABLE execution.trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.trips FORCE ROW LEVEL SECURITY;
CREATE POLICY trips_read ON execution.trips
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_has_depot(depot_code));
CREATE POLICY trips_insert ON execution.trips
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system());
CREATE POLICY trips_update ON execution.trips
    FOR UPDATE TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date))
    WITH CHECK (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date));

ALTER TABLE execution.delivery_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.delivery_records FORCE ROW LEVEL SECURITY;
CREATE POLICY delivery_read ON execution.delivery_records
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_has_depot(depot_code) OR app.actor_has_outlet(outlet_id));
CREATE POLICY delivery_insert ON execution.delivery_records
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system());
CREATE POLICY delivery_update ON execution.delivery_records
    FOR UPDATE TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date))
    WITH CHECK (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date));

-- Children follow their delivery record: visible where it is visible, written
-- by whoever may write it.

ALTER TABLE execution.attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY attachments_read ON execution.attachments
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = attachments.delivery_id));
CREATE POLICY attachments_insert ON execution.attachments
    FOR INSERT TO waypoint_execution
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = attachments.delivery_id
                          AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))));
CREATE POLICY attachments_update ON execution.attachments
    FOR UPDATE TO waypoint_execution
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE execution.proofs ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.proofs FORCE ROW LEVEL SECURITY;
CREATE POLICY proofs_read ON execution.proofs
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = proofs.delivery_id));
CREATE POLICY proofs_insert ON execution.proofs
    FOR INSERT TO waypoint_execution
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = proofs.delivery_id
                          AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))));

ALTER TABLE execution.vehicle_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.vehicle_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY vehicle_reports_read ON execution.vehicle_reports
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_has_depot(depot_code));
CREATE POLICY vehicle_reports_insert ON execution.vehicle_reports
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date));

ALTER TABLE execution.road_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.road_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY road_reports_read ON execution.road_reports
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_has_depot(depot_code));
CREATE POLICY road_reports_insert ON execution.road_reports
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date));

-- ---- the catalogue catches up -------------------------------------------
-- implemented means "a handler or an endpoint enforces this action" (008), and
-- the command bus refuses a kind whose action is not marked.

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('delivery:Start', 'delivery:RecordArrival', 'delivery:Record', 'delivery:CaptureProof',
                  'delivery:ReportVehicleStatus', 'delivery:ReportFault', 'delivery:Read');
