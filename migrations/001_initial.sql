CREATE TABLE users (
  id text PRIMARY KEY,
  role text NOT NULL CHECK (role IN ('dispatcher','loader','driver','store')),
  scope text NOT NULL,
  salt text NOT NULL,
  hash text NOT NULL
);
CREATE TABLE sessions (
  token text PRIMARY KEY,
  user_id text NOT NULL REFERENCES users(id),
  expires bigint NOT NULL
);
CREATE INDEX sessions_expiry_idx ON sessions(expires);
CREATE TABLE orders (
  id text PRIMARY KEY,
  body jsonb NOT NULL,
  day text GENERATED ALWAYS AS (body->>'day') STORED NOT NULL,
  outlet_id text GENERATED ALWAYS AS (body->>'outlet_id') STORED NOT NULL,
  depot text GENERATED ALWAYS AS (body->>'depot') STORED NOT NULL,
  vehicle_id text GENERATED ALWAYS AS (body->>'vehicle_id') STORED,
  status text GENERATED ALWAYS AS (body->>'status') STORED NOT NULL,
  version integer GENERATED ALWAYS AS ((body->>'version')::integer) STORED NOT NULL CHECK (version >= 0),
  CHECK (body->>'id' = id)
);
CREATE INDEX orders_day_idx ON orders(day);
CREATE INDEX orders_outlet_day_idx ON orders(outlet_id,day);
CREATE INDEX orders_depot_day_idx ON orders(depot,day);
CREATE INDEX orders_vehicle_day_idx ON orders(vehicle_id,day);
CREATE INDEX orders_status_day_idx ON orders(status,day);
CREATE TABLE plans (
  day text PRIMARY KEY,
  body jsonb NOT NULL,
  CHECK (body->>'day' = day)
);
CREATE TABLE events (
  id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id text NOT NULL,
  actor text NOT NULL REFERENCES users(id),
  kind text NOT NULL,
  created text NOT NULL,
  client_time text NOT NULL,
  detail jsonb NOT NULL
);
CREATE INDEX events_order_idx ON events(order_id,id DESC);
CREATE TABLE commands (
  id text NOT NULL,
  user_id text NOT NULL REFERENCES users(id),
  fingerprint text NOT NULL,
  response jsonb NOT NULL,
  PRIMARY KEY(id,user_id)
);
CREATE TABLE settings (key text PRIMARY KEY,value text NOT NULL);
CREATE TABLE proof_images (
  id text PRIMARY KEY,
  order_id text NOT NULL REFERENCES orders(id),
  kind text NOT NULL CHECK (kind IN ('photo','signature')),
  content_type text NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
  bytes bytea NOT NULL CHECK (octet_length(bytes) <= 1125000)
);
CREATE INDEX proof_images_order_idx ON proof_images(order_id);
CREATE TABLE login_attempts (
  email text NOT NULL,
  attempted_at bigint NOT NULL
);
CREATE INDEX login_attempts_email_time_idx ON login_attempts(email,attempted_at);
