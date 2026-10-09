-- R-PLN-44: a trip whose truck has left is frozen. Planning learns of a release
-- from Loading's trip.released event (it never reads Loading's tables) and
-- keeps the trip here, so publishing a revision can refuse to change it.
-- Insert only: a release is a fact, never undone.

CREATE TABLE planning.released_trips (
    trip_id      uuid        PRIMARY KEY,
    plan_id      uuid        NOT NULL,
    depot_code   text        NOT NULL,
    service_date date        NOT NULL,
    vehicle_id   text        NOT NULL,
    released_at  timestamptz NOT NULL
);

CREATE INDEX released_trips_day ON planning.released_trips (depot_code, service_date);

GRANT SELECT, INSERT ON planning.released_trips TO waypoint_planning;

ALTER TABLE planning.released_trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.released_trips FORCE ROW LEVEL SECURITY;
CREATE POLICY released_trips_scope ON planning.released_trips FOR ALL TO waypoint_planning
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));
