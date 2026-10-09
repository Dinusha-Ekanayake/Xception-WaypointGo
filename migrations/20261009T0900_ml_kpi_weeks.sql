-- Roadmap B-05: the business KPI baseline. Each depot's weekly figures from
-- before and during the pilot, as the depot recorded them (paper run sheets,
-- phone logs, Finance's write-off sheet), so the pilot can show what changed.
-- The figures the app can compute itself are computed from its own records and
-- shown beside these; nothing here overwrites them.
--
-- Append only: a figure is never edited or deleted. A correction is a new row
-- for the same depot, week and KPI; the newest is the current one, and the
-- trail shows who entered each and when (decisions are recorded).

CREATE TABLE ml.kpi_entries (
    entry_id    uuid          PRIMARY KEY,
    depot_code  text          NOT NULL,
    week_start  date          NOT NULL CHECK (extract(isodow FROM week_start) = 1),
    kpi         text          NOT NULL CHECK (kpi IN (
                    'fresh_on_time_pct', 'fresh_before_8_pct', 'deferrals_per_run', 'repeat_deferrals',
                    'disputes_per_100', 'write_off_lkr', 'calls_per_trip', 'adoption_pct')),
    value       numeric(14,2) NOT NULL CHECK (value >= 0),
    note        text          CHECK (note IS NULL OR length(note) <= 500),
    entered_by  uuid          NOT NULL,
    entered_at  timestamptz   NOT NULL
);

CREATE INDEX kpi_entries_week ON ml.kpi_entries (depot_code, week_start, kpi, entered_at);

GRANT SELECT, INSERT ON ml.kpi_entries TO waypoint_ml;

ALTER TABLE ml.kpi_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.kpi_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY kpi_entries_scope ON ml.kpi_entries
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_has_depot(depot_code));
