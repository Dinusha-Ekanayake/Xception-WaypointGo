-- 003 Identity and access.
--
--     Decision D3: roles are a lookup table with no value CHECK, so adding a role
--       is an insert rather than a migration.
--     Decision D4: auditor exists. A read-only investigative role is the safe way
--       to grant access during a dispute.
--     Decision D5: sessions and login attempts are stored server-side. Revocation
--       must be immediate, which a self-contained token cannot do, and throttling
--       must be shared across replicas or a second replica is a bypass.
-- ---------------------------------------------------------------------

CREATE TABLE iam.roles (
    role_code   text PRIMARY KEY,
    description text NOT NULL
);

INSERT INTO iam.roles (role_code, description) VALUES
    ('dispatcher',    'Plans and publishes daily allocation; resolves exceptions'),
    ('loader',        'Loads to the planned stop sequence and flags shortfalls'),
    ('driver',        'Executes stops and captures proof of delivery'),
    ('store_manager', 'Places orders and confirms receipt for an outlet'),
    ('auditor',       'Read-only across the operation, for investigating a dispute'),
    ('admin',         'Accounts, roles, scopes, reference import, calendar override');

-- user_id is immutable and is the only foreign key target. Email is a mutable
-- attribute: people change names and domains, and a key must not.
CREATE TABLE iam.users (
    user_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text NOT NULL UNIQUE,
    display_name  text NOT NULL,
    password_hash text NOT NULL,
    is_active     boolean NOT NULL DEFAULT true,
    row_version   bigint  NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE iam.user_roles (
    user_id   uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    role_code text NOT NULL REFERENCES iam.roles(role_code),
    PRIMARY KEY (user_id, role_code)
);

-- Scope is attribute-based: a role grants the verb, these grant the rows.
CREATE TABLE iam.user_depot_access (
    user_id    uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    depot_code text NOT NULL,
    PRIMARY KEY (user_id, depot_code)
);

CREATE TABLE iam.user_outlet_access (
    user_id   uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    outlet_id text NOT NULL REFERENCES ref.outlet_registry(outlet_id),
    PRIMARY KEY (user_id, outlet_id)
);

-- Driver scope is a vehicle on a date, not a vehicle forever: yesterday's driver
-- cannot post today's delivery. The exclusion constraint makes two drivers on one
-- vehicle impossible at the database, not merely unlikely in the application.
CREATE TABLE iam.vehicle_driver_assignments (
    assignment_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id     text NOT NULL REFERENCES ref.vehicle_registry(vehicle_id),
    driver_user_id uuid NOT NULL REFERENCES iam.users(user_id),
    validity       daterange NOT NULL,
    assigned_by    uuid REFERENCES iam.users(user_id),
    assigned_at    timestamptz NOT NULL DEFAULT now(),
    EXCLUDE USING gist (vehicle_id WITH =, validity WITH &&)
);

CREATE INDEX ix_driver_assignment_driver ON iam.vehicle_driver_assignments (driver_user_id);

-- A shared dock tablet is one device used by many people. Audit has to tell them
-- apart, so device identity is separate from user identity.
CREATE TABLE iam.devices (
    device_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_label  text NOT NULL,
    device_kind   text NOT NULL,
    depot_code    text,
    registered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz,
    is_active     boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_device_kind
        CHECK (device_kind IN ('shared_tablet','personal_phone','desktop','terminal'))
);

CREATE TABLE iam.sessions (
    session_token text PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES iam.users(user_id) ON DELETE CASCADE,
    device_id     uuid REFERENCES iam.devices(device_id),
    issued_at     timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    absolute_expiry timestamptz NOT NULL,
    idle_expiry     timestamptz NOT NULL
);

CREATE INDEX ix_sessions_user   ON iam.sessions (user_id);
CREATE INDEX ix_sessions_expiry ON iam.sessions (absolute_expiry);

-- Shared across replicas, or a second replica is a bypass.
CREATE TABLE iam.login_attempts (
    email        text NOT NULL,
    attempted_at timestamptz NOT NULL DEFAULT now(),
    succeeded    boolean NOT NULL,
    source_ip    inet
);

CREATE INDEX ix_login_attempts_email_time ON iam.login_attempts (email, attempted_at DESC);
