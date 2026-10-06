#!/bin/sh
set -eu

: "${FLYWAY_URL:?FLYWAY_URL is required}"
: "${FLYWAY_USER:?FLYWAY_USER is required}"
: "${FLYWAY_PASSWORD_FILE:?FLYWAY_PASSWORD_FILE is required}"

if [ ! -f "$FLYWAY_PASSWORD_FILE" ] ||
   [ ! -r "$FLYWAY_PASSWORD_FILE" ] ||
   [ ! -s "$FLYWAY_PASSWORD_FILE" ]; then
    printf 'Required password file is missing, unreadable, or empty: %s\n' \
        "$FLYWAY_PASSWORD_FILE" >&2
    exit 1
fi

password="$(cat "$FLYWAY_PASSWORD_FILE")"
if [ -z "$password" ]; then
    printf 'Flyway password must not be empty.\n' >&2
    exit 1
fi

# Restrict permissions before writing any credentials.
umask 077
config_file="$(mktemp /tmp/vitrin-flyway.XXXXXX)"
trap 'rm -f "$config_file"' 0
trap 'exit 1' HUP INT TERM

# Properties escaping preserves backslashes, whitespace, and embedded newlines.
escape_properties() {
    printf '%s' "$1" | awk '
        NR > 1 { printf "%s", "\\n" }
        {
            for (i = 1; i <= length($0); i++) {
                c = substr($0, i, 1)
                if (c == "\\") {
                    printf "%s", "\\\\"
                } else if (c == " ") {
                    printf "%s", "\\ "
                } else if (c == "\t") {
                    printf "%s", "\\t"
                } else if (c == "\r") {
                    printf "%s", "\\r"
                } else if (c == "\f") {
                    printf "%s", "\\f"
                } else {
                    printf "%s", c
                }
            }
        }
    '
}

{
    printf 'flyway.url='
    escape_properties "$FLYWAY_URL"
    printf '\nflyway.user='
    escape_properties "$FLYWAY_USER"
    printf '\nflyway.password='
    escape_properties "$password"
    printf '\n'
    printf '%s\n' \
        'flyway.schemas=flyway' \
        'flyway.defaultSchema=flyway' \
        'flyway.createSchemas=true' \
        'flyway.cleanDisabled=true' \
        'flyway.validateMigrationNaming=true' \
        'flyway.connectRetries=30'
} > "$config_file"

unset password FLYWAY_PASSWORD_FILE

if [ "$#" -eq 0 ]; then
    set -- migrate
fi

# Do not exec: the exit trap must remove the credential file.
flyway "-configFiles=$config_file" "$@"
