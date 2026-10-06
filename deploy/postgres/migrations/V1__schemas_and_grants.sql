-- Forward-only migrations: no undo files.
-- The application role must never create or alter objects anywhere.

CREATE SCHEMA identity AUTHORIZATION vitrin_migrate;
CREATE SCHEMA taxonomy AUTHORIZATION vitrin_migrate;
CREATE SCHEMA profile AUTHORIZATION vitrin_migrate;
CREATE SCHEMA sync AUTHORIZATION vitrin_migrate;
CREATE SCHEMA lab AUTHORIZATION vitrin_migrate;

GRANT USAGE ON SCHEMA identity, taxonomy, profile, sync, lab TO vitrin_app;

ALTER DEFAULT PRIVILEGES FOR ROLE vitrin_migrate
    IN SCHEMA identity, taxonomy, profile, sync, lab
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vitrin_app;

ALTER DEFAULT PRIVILEGES FOR ROLE vitrin_migrate
    IN SCHEMA identity, taxonomy, profile, sync, lab
    GRANT USAGE, SELECT ON SEQUENCES TO vitrin_app;
