ALTER TABLE users ADD COLUMN enabled boolean NOT NULL DEFAULT true;
CREATE TABLE account_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  account_id text NOT NULL REFERENCES users(id),
  operator text NOT NULL,
  action text NOT NULL,
  created timestamptz NOT NULL DEFAULT now()
);
