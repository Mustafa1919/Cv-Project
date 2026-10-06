#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s [--check] <directory>\n' "${0##*/}" >&2
}

check_only=false
if [[ ${1-} == --check ]]; then
    check_only=true
    shift
fi

if [[ $# -ne 1 || -z $1 || $1 == --* ]]; then
    usage
    exit 2
fi

if ! command -v openssl >/dev/null 2>&1; then
    printf 'secrets.sh requires openssl.\n' >&2
    exit 1
fi

directory="$1"
files=(
    signing-key.pem
    address-hash.key
    redis-password
    redis.acl
    postgres-superuser-password
    postgres-migrate-password
    postgres-app-password
    grafana-admin-password
    tunnel-token
)

read_password() {
    # A sentinel preserves trailing newlines so comparisons use the actual value.
    redis_password="$(cat -- "$directory/redis-password"; printf '.')"
    redis_password="${redis_password%.}"
}

acl_matches() {
    local acl
    read_password
    acl="$(cat -- "$directory/redis.acl")"
    [[ "$acl" == "user default on >${redis_password} ~* &* +@all" ]]
}

if "$check_only"; then
    failed=0
    for name in "${files[@]}"; do
        path="$directory/$name"
        if [[ ! -f "$path" || ! -r "$path" || ! -s "$path" ]]; then
            printf '%s: missing (manual)\n' "$name"
            printf '%s: required file is missing, unreadable, or empty.\n' "$name" >&2
            failed=1
        else
            printf '%s: kept\n' "$name"
        fi
    done

    if [[ -f "$directory/redis-password" &&
          -r "$directory/redis-password" &&
          -s "$directory/redis-password" &&
          -f "$directory/redis.acl" &&
          -r "$directory/redis.acl" &&
          -s "$directory/redis.acl" ]]; then
        if ! acl_matches; then
            printf 'redis.acl: password does not match redis-password.\n' >&2
            failed=1
        fi
    fi

    exit "$failed"
fi

umask 077
mkdir -p -- "$directory"
chmod 0700 -- "$directory"

temporary_files=()
cleanup() {
    if [[ ${#temporary_files[@]} -gt 0 ]]; then
        rm -f -- "${temporary_files[@]}"
    fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

for name in "${files[@]}"; do
    path="$directory/$name"
    if [[ -L "$path" ]]; then
        printf '%s: refusing a symbolic link.\n' "$name" >&2
        exit 1
    fi
    if [[ -e "$path" && ( ! -f "$path" || ! -r "$path" || ! -s "$path" ) ]]; then
        printf '%s: existing file must be regular, readable, and non-empty.\n' "$name" >&2
        exit 1
    fi
done

if [[ -e "$directory/redis.acl" && ! -e "$directory/redis-password" ]]; then
    printf 'redis-password: cannot recover it from an existing redis.acl.\n' >&2
    exit 1
fi

for name in "${files[@]}"; do
    path="$directory/$name"

    if [[ -e "$path" ]]; then
        # Containers use different uids; the host directory protects these files.
        chmod 0444 -- "$path"
        printf '%s: kept\n' "$name"
        continue
    fi

    if [[ "$name" == tunnel-token ]]; then
        printf '%s: missing (manual)\n' "$name"
        printf 'Copy tunnel-token from the Cloudflare dashboard into %s.\n' "$path" >&2
        continue
    fi

    temporary_file="$(mktemp "$directory/.vitrin-secret.XXXXXX")"
    temporary_files+=("$temporary_file")

    case "$name" in
        signing-key.pem)
            openssl genpkey \
                -algorithm EC \
                -pkeyopt ec_paramgen_curve:P-256 \
                -out "$temporary_file"
            # Derive first, then append: a command must not read the file it is writing.
            public_key="$(openssl pkey -in "$temporary_file" -pubout)"
            printf '%s\n' "$public_key" >> "$temporary_file"
            ;;
        address-hash.key)
            value="$(openssl rand -hex 32)"
            printf '%s' "$value" > "$temporary_file"
            unset value
            ;;
        redis.acl)
            read_password
            if [[ -z "$redis_password" ||
                  "$redis_password" == *$'\n'* ||
                  "$redis_password" == *$'\r'* ]]; then
                printf 'redis-password: must be a non-empty single-line value.\n' >&2
                exit 1
            fi
            printf 'user default on >%s ~* &* +@all\n' "$redis_password" > "$temporary_file"
            ;;
        *)
            value="$(openssl rand -hex 24)"
            printf '%s' "$value" > "$temporary_file"
            unset value
            ;;
    esac

    chmod 0444 -- "$temporary_file"
    mv -n -- "$temporary_file" "$path"

    if [[ -e "$temporary_file" ]]; then
        printf '%s: kept\n' "$name"
    else
        printf '%s: created\n' "$name"
    fi
done

if ! acl_matches; then
    printf 'redis.acl: password does not match redis-password; nothing was overwritten.\n' >&2
    exit 1
fi

unset redis_password
