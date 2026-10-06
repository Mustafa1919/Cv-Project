#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'apply: %s\n' "$*" >&2
    exit 1
}

usage='usage: apply.sh <sha> [--allow-local-images] [--previous <sha>|none]'
(( $# >= 1 )) || die "$usage"

sha=$1
shift
allow_local=0
# Unset: the release being replaced becomes "previous". A rollback passes the value instead,
# because the release it replaces is the one that just failed and must not be a rollback target.
previous_override=''
while (( $# > 0 )); do
    case $1 in
        --allow-local-images) allow_local=1 ;;
        --previous)
            (( $# >= 2 )) || die "$usage"
            previous_override=$2
            [[ $previous_override == none || $previous_override =~ ^[0-9a-f]{7,40}$ ]] ||
                die '--previous must be a sha or "none"'
            shift
            ;;
        *) die "$usage" ;;
    esac
    shift
done
[[ $sha =~ ^[0-9a-f]{7,40}$ ]] || die 'sha must be 7 to 40 lowercase hex characters'

home=${VITRIN_HOME:-/opt/vitrin}
project=${VITRIN_PROJECT:-vitrin}
wait_timeout=${VITRIN_WAIT_TIMEOUT:-180}
[[ $wait_timeout =~ ^[1-9][0-9]*$ ]] || die 'VITRIN_WAIT_TIMEOUT must be a positive integer'

releases=$home/releases
bundle=$home/bundles/$sha
env_file=$releases/$sha.env
[[ -f $env_file ]] || die "missing release environment: $env_file"
[[ -f $bundle/prod/compose.yaml ]] || die "missing Compose file: $bundle/prod/compose.yaml"
[[ -f $bundle/server/secrets.sh ]] || die "missing secrets checker: $bundle/server/secrets.sh"

declare -A values=()
value_bad_re="[[:space:]\"']"
while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ -z $line || $line == \#* ]] && continue
    [[ $line =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] ||
        die 'release environment must contain KEY=value lines'
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    [[ ! $value =~ $value_bad_re ]] ||
        die "whitespace or quotes in release environment key: $key"
    [[ ${values[$key]+present} != present ]] ||
        die "duplicate release environment key: $key"
    values[$key]=$value
done < "$env_file"

required=(
    VITRIN_GATEWAY_IMAGE
    VITRIN_CORE_IMAGE
    VITRIN_SEARCH_IMAGE
    VITRIN_MIGRATE_IMAGE
    VITRIN_SECRETS_DIR
    VITRIN_SITE_ORIGIN
    COMPOSE_PROFILES
)
for key in "${required[@]}"; do
    [[ ${values[$key]+present} == present ]] || die "missing environment key: $key"
    if [[ $key != COMPOSE_PROFILES ]]; then
        [[ -n ${values[$key]} ]] || die "empty environment key: $key"
    fi
done

image_keys=(
    VITRIN_GATEWAY_IMAGE
    VITRIN_CORE_IMAGE
    VITRIN_SEARCH_IMAGE
    VITRIN_MIGRATE_IMAGE
)
if (( ! allow_local )); then
    for key in "${image_keys[@]}"; do
        [[ ${values[$key]} =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
            die "$key must be a GHCR image reference by digest"
    done
fi

# Shell variables otherwise take precedence over --env-file.
for key in "${required[@]}"; do
    export "$key=${values[$key]}"
done

# Compose runs from a fixed directory, not from the bundle of the release: a bind mount whose
# host path changed would make Compose recreate PostgreSQL and the observability stack on
# every release.
active=$home/active
override=${VITRIN_COMPOSE_OVERRIDE:-}
[[ -z $override || $override =~ ^[A-Za-z0-9._-]+\.yaml$ ]] ||
    die 'VITRIN_COMPOSE_OVERRIDE must be a file name inside prod/'
[[ -z $override || -f $bundle/prod/$override ]] || die 'missing Compose override'
compose=(
    docker compose
    --project-name "$project"
    --env-file "$env_file"
    -f "$active/prod/compose.yaml"
)
if [[ -n $override ]]; then
    compose+=(-f "$active/prod/$override")
fi

lock=$releases/.lock
lock_owned=0
current_tmp=''
cleanup() {
    [[ -z $current_tmp ]] || rm -f -- "$current_tmp"
    if (( lock_owned )); then
        rm -f -- "$lock/started"
        # Only an empty lock owned by this process is removed.
        rm -d -- "$lock" || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! mkdir -- "$lock"; then
    now=$(date +%s)
    started=''
    if [[ -f $lock/started ]]; then
        IFS= read -r started < "$lock/started" || true
    fi
    if [[ $started =~ ^[0-9]+$ ]] && (( now >= started )); then
        die "apply lock exists; age=$((now - started)) seconds; not removed"
    fi
    die 'apply lock exists; age=unknown; not removed'
fi
lock_owned=1
total_start=$(date +%s)
printf '%s\n' "$total_start" > "$lock/started"

secrets_dir=${values[VITRIN_SECRETS_DIR]}
profiles=${values[COMPOSE_PROFILES]}
if (( allow_local )) &&
    [[ ! $profiles =~ (^|,)edge(,|$) ]] &&
    [[ ! -f $secrets_dir/tunnel-token ]]; then
    # The checker cannot exclude the unused local tunnel secret.
    printf 'notice: skipping secrets.sh --check: local images, no edge profile, missing tunnel-token\n'
else
    bash "$bundle/server/secrets.sh" --check "$secrets_dir" ||
        die 'secret file check failed'
fi

# Copied in place (same paths, same directory inodes) under the lock.
mkdir -p -- "$active/prod"
cp -R -- "$bundle/prod/." "$active/prod/"

# Compose only notices a changed image or definition. This label makes it recreate the
# observability services when the content of their configuration files changed, and only then.
VITRIN_OBSERVABILITY_CONFIG_HASH=$(
    cd -- "$active/prod/observability" &&
        find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16
)
[[ $VITRIN_OBSERVABILITY_CONFIG_HASH =~ ^[0-9a-f]{16}$ ]] ||
    die 'could not hash the observability configuration'
export VITRIN_OBSERVABILITY_CONFIG_HASH

pull_seconds=0
migrate_seconds=0
up_seconds=0

diagnostics() {
    printf '%s\n' '--- compose ps -a ---'
    "${compose[@]}" ps -a || true
    printf '%s\n' '--- last 40 log lines for stopped or unhealthy services ---'
    "${compose[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}' |
        while IFS='|' read -r service state health; do
            [[ -n $service ]] || continue
            if [[ $state != running || ( -n $health && $health != healthy ) ]]; then
                printf '%s\n' "--- $service ---"
                "${compose[@]}" logs --tail 40 "$service" || true
            fi
        done || true
}

failed_step() {
    local step=$1
    local ended timestamp
    ended=$(date +%s)
    timestamp=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
    printf '%s failed %s step=%s pull=%s migrate=%s up=%s total=%s\n' \
        "$timestamp" "$sha" "$step" "$pull_seconds" "$migrate_seconds" \
        "$up_seconds" "$((ended - total_start))" >> "$releases/history.log"
    printf 'apply: step %s failed\n' "$step" >&2
    diagnostics
    exit 1
}

printf '%s\n' '--- pull ---'
step_start=$(date +%s)
if (( allow_local )); then
    printf '%s\n' 'notice: pull skipped for local images'
else
    if ! "${compose[@]}" --profile migrate pull; then
        step_end=$(date +%s)
        pull_seconds=$((step_end - step_start))
        failed_step pull
    fi
fi
step_end=$(date +%s)
pull_seconds=$((step_end - step_start))
printf 'pull: %s seconds\n' "$pull_seconds"

printf '%s\n' '--- migrate ---'
step_start=$(date +%s)
if ! "${compose[@]}" --profile migrate run --rm migrate; then
    step_end=$(date +%s)
    migrate_seconds=$((step_end - step_start))
    failed_step migrate
fi
step_end=$(date +%s)
migrate_seconds=$((step_end - step_start))
printf 'migrate: %s seconds\n' "$migrate_seconds"

printf '%s\n' '--- up ---'
step_start=$(date +%s)
if ! "${compose[@]}" up -d --wait --wait-timeout "$wait_timeout" --remove-orphans; then
    step_end=$(date +%s)
    up_seconds=$((step_end - step_start))
    failed_step up
fi
step_end=$(date +%s)
up_seconds=$((step_end - step_start))
printf 'up: %s seconds\n' "$up_seconds"

old_current=''
if [[ -f $releases/current ]]; then
    IFS= read -r old_current < "$releases/current" || true
fi
if [[ $previous_override == none ]]; then
    rm -f -- "$releases/previous"
elif [[ -n $previous_override ]]; then
    printf '%s\n' "$previous_override" > "$releases/previous"
elif [[ -f $releases/current && $old_current != "$sha" ]]; then
    cp -- "$releases/current" "$releases/previous"
fi
current_tmp=$(mktemp "$releases/.current.XXXXXX")
printf '%s\n' "$sha" > "$current_tmp"
mv -- "$current_tmp" "$releases/current"
current_tmp=''

total_end=$(date +%s)
timestamp=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
printf '%s applied %s pull=%s migrate=%s up=%s total=%s\n' \
    "$timestamp" "$sha" "$pull_seconds" "$migrate_seconds" "$up_seconds" \
    "$((total_end - total_start))" >> "$releases/history.log"
printf 'applied %s\n' "$sha"
