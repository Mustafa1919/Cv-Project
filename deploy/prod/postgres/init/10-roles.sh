#!/usr/bin/env bash
set -euo pipefail

migrate_password_file=/run/secrets/postgres-migrate-password
app_password_file=/run/secrets/postgres-app-password

for secret_file in "$migrate_password_file" "$app_password_file"; do
    if [[ ! -f "$secret_file" || ! -r "$secret_file" || ! -s "$secret_file" ]]; then
        printf 'Required secret is missing, unreadable, or empty: %s\n' "$secret_file" >&2
        exit 1
    fi
done

migrate_password="$(cat -- "$migrate_password_file")"
app_password="$(cat -- "$app_password_file")"

if [[ -z "$migrate_password" || -z "$app_password" ]]; then
    printf 'Database role passwords must not be empty.\n' >&2
    exit 1
fi

# psql quotes password literals rather than interpolating shell SQL.
psql \
    -v ON_ERROR_STOP=1 \
    -v migrate_password="$migrate_password" \
    -v app_password="$app_password" \
    --username="${POSTGRES_USER:?POSTGRES_USER is required}" \
    --dbname=vitrin <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    'vitrin_migrate',
    :'migrate_password'
)
\gexec

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    'vitrin_app',
    :'app_password'
)
\gexec

ALTER DATABASE vitrin OWNER TO vitrin_migrate;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE vitrin FROM PUBLIC;
GRANT CONNECT ON DATABASE vitrin TO vitrin_app;
SQL

unset migrate_password app_password
