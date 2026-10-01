-- A timestamp can be shared by several attempts. Count attempts in insertion
-- order so a successful PIN cannot hide later failures recorded at that time.
ALTER TABLE iam.pin_attempts
    ADD COLUMN attempt_id bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE iam.pin_attempts
    ADD CONSTRAINT pin_attempts_pkey PRIMARY KEY (attempt_id);

CREATE INDEX ix_pin_attempts_user_order
    ON iam.pin_attempts (user_id, attempt_id DESC);
