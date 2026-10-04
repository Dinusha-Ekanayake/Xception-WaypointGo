-- The schema gets an owner that is not a superuser (issue #5, SEC-28).
--
--     Until now every table belonged to whoever ran migrate, and in the Docker
--     deployments that is the image's bootstrap account: a superuser, which
--     ignores row-level security even where it is forced and may do anything to
--     the cluster. Ownership moves to waypoint_migrator, which can create roles
--     and nothing else unusual. It is not a superuser and holds no BYPASSRLS.
--
--     From here on migrate runs every file as waypoint_migrator (Migrator.java
--     switches once public.schema_migrations belongs to it), so:
--       - a migration cannot do what only a superuser can. It cannot give a
--         role SUPERUSER or BYPASSRLS, or even name those attributes in ALTER
--         ROLE, and it cannot install an untrusted extension;
--       - row-level security applies to migrations too. A backfill of a table
--         with FORCE ROW LEVEL SECURITY has to lift the force around itself.
--
--     The bootstrap account cannot be handed over with REASSIGN OWNED, because
--     it also owns the system catalogs, so each object is altered by name. The
--     list is read from the catalog rather than written out: a migration with
--     an earlier name that merges later is covered without editing this file.
--
--     Everything here is one transaction with the rest of the run. If any
--     statement fails, nothing has moved.
-- ---------------------------------------------------------------------

-- Changing an owner takes each table's strongest lock. A deploy migrates while
-- the previous backend is still serving, and a request queued behind a lock
-- that never arrives would stall every other request for that table. Give up
-- instead, change nothing, and let the deploy be run again.
SET LOCAL lock_timeout = '15s';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'waypoint_migrator') THEN
        CREATE ROLE waypoint_migrator NOLOGIN;
    END IF;
END $$;

-- Roles are cluster-wide, so the attributes are asserted, not assumed (SEC-17).
-- CREATEROLE is what lets a later migration add a module role and grant it to
-- waypoint_app. LOGIN is left as it is: the role is created without one, and a
-- deployment that gives it a password has made a decision this file must not undo.
ALTER ROLE waypoint_migrator
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOREPLICATION NOINHERIT CREATEROLE;

DO $$
DECLARE
    previous oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    migrator oid := (SELECT oid FROM pg_roles WHERE rolname = 'waypoint_migrator');
    schemas  oid[];
    item     record;
BEGIN
    -- The application's schemas are the ones this account created. public stays
    -- with the database, and the system schemas belong to the bootstrap account
    -- on every cluster, so both are left out by name.
    SELECT coalesce(array_agg(n.oid), '{}')
      INTO schemas
      FROM pg_namespace n
     WHERE n.nspowner = previous
       AND n.nspname NOT IN ('public', 'information_schema')
       AND n.nspname NOT LIKE 'pg\_%';

    FOR item IN SELECT n.nspname FROM pg_namespace n WHERE n.oid = ANY (schemas) LOOP
        EXECUTE format('ALTER SCHEMA %I OWNER TO waypoint_migrator', item.nspname);
    END LOOP;

    -- Tables, partitions (each is its own table), views and free-standing
    -- sequences, plus the migration ledger in public. A sequence that belongs to
    -- a column follows its table and may not be altered on its own.
    FOR item IN
        SELECT c.oid::regclass AS name, c.relkind
          FROM pg_class c
         WHERE c.relowner = previous
           AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
           AND (c.relnamespace = ANY (schemas)
                OR c.oid = to_regclass('public.schema_migrations'))
           AND NOT (c.relkind = 'S' AND EXISTS (
                   SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                      AND d.refobjsubid > 0 AND d.deptype IN ('a', 'i')))
           AND NOT EXISTS (
                   SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                      AND d.deptype = 'e')
    LOOP
        EXECUTE format(
            'ALTER %s %s OWNER TO waypoint_migrator',
            CASE item.relkind
                WHEN 'v' THEN 'VIEW'
                WHEN 'm' THEN 'MATERIALIZED VIEW'
                WHEN 'S' THEN 'SEQUENCE'
                WHEN 'f' THEN 'FOREIGN TABLE'
                ELSE 'TABLE'
            END,
            item.name);
    END LOOP;

    -- Functions. A SECURITY DEFINER function runs as its owner, so from here
    -- integration.ensure_audit_partition creates partitions as waypoint_migrator,
    -- which owns the parent, and no function runs as a superuser any more.
    FOR item IN
        SELECT p.oid::regprocedure AS name
          FROM pg_proc p
         WHERE p.proowner = previous
           AND p.pronamespace = ANY (schemas)
           AND NOT EXISTS (
                   SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                      AND d.deptype = 'e')
    LOOP
        EXECUTE format('ALTER ROUTINE %s OWNER TO waypoint_migrator', item.name);
    END LOOP;

    -- Types declared on their own. A table's row type follows its table.
    FOR item IN
        SELECT t.oid::regtype AS name
          FROM pg_type t
         WHERE t.typowner = previous
           AND t.typnamespace = ANY (schemas)
           AND (t.typtype IN ('e', 'd', 'r')
                OR (t.typtype = 'c'
                    AND (SELECT c.relkind FROM pg_class c WHERE c.oid = t.typrelid) = 'c'))
           AND NOT EXISTS (
                   SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid
                      AND d.deptype = 'e')
    LOOP
        EXECUTE format('ALTER TYPE %s OWNER TO waypoint_migrator', item.name);
    END LOOP;

    -- Default privileges belong to the role that creates the object. The ones
    -- earlier migrations declared are what give a module role its grants on a
    -- new table; without the same defaults for the new owner, the next table a
    -- migration adds would be readable by nobody.
    FOR item IN
        SELECT n.nspname AS schema_name,
               CASE d.defaclobjtype
                   WHEN 'r' THEN 'TABLES'
                   WHEN 'S' THEN 'SEQUENCES'
                   WHEN 'f' THEN 'FUNCTIONS'
                   WHEN 'T' THEN 'TYPES'
               END AS kind,
               a.privilege_type,
               a.is_grantable,
               CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(g.rolname) END AS grantee
          FROM pg_default_acl d
          JOIN pg_namespace n ON n.oid = d.defaclnamespace
         CROSS JOIN LATERAL aclexplode(d.defaclacl) a
          LEFT JOIN pg_roles g ON g.oid = a.grantee
         WHERE d.defaclrole = previous
           AND d.defaclnamespace = ANY (schemas)
           AND d.defaclobjtype IN ('r', 'S', 'f', 'T')
           AND a.grantee <> previous
    LOOP
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE waypoint_migrator IN SCHEMA %I GRANT %s ON %s TO %s%s',
            item.schema_name,
            item.privilege_type,
            item.kind,
            item.grantee,
            CASE WHEN item.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
    END LOOP;

    -- The roles the application runs as: waypoint_app and every module role it
    -- may adopt. ADMIN is what lets a migration grant a module role or change
    -- its attributes. Neither INHERIT nor SET: the owner needs no module's
    -- privileges, and must not be able to act as one.
    FOR item IN
        SELECT r.rolname
          FROM pg_roles r
         WHERE NOT r.rolsuper
           AND r.oid <> migrator
           AND (r.rolname = 'waypoint_app'
                OR r.oid IN (
                    SELECT m.roleid
                      FROM pg_auth_members m
                      JOIN pg_roles app ON app.oid = m.member
                     WHERE app.rolname = 'waypoint_app'))
    LOOP
        EXECUTE format(
            'GRANT %I TO waypoint_migrator WITH ADMIN TRUE, INHERIT FALSE, SET FALSE',
            item.rolname);
    END LOOP;

    -- A new module needs a schema; a trusted extension needs CREATE on the
    -- database. The ledger lives in public.
    EXECUTE format('GRANT CREATE ON DATABASE %I TO waypoint_migrator', current_database());
    GRANT USAGE, CREATE ON SCHEMA public TO waypoint_migrator;

    -- Fail rather than leave the schema with two owners. Nothing has committed.
    SELECT c.oid::regclass AS name
      INTO item
      FROM pg_class c
     WHERE c.relnamespace = ANY (schemas)
       AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
       AND c.relowner <> migrator
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION '% is still not owned by waypoint_migrator', item.name;
    END IF;
END $$;

COMMENT ON ROLE waypoint_migrator IS
  'Owns the application''s schemas, tables and functions. Not a superuser. Only migrate acts as it.';

SET LOCAL lock_timeout = DEFAULT;
