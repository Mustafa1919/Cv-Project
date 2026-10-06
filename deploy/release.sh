#!/usr/bin/env bash
# shellcheck disable=SC2029  # Remote commands are built from locally quoted values on purpose.
set -euo pipefail

die() {
    local code=$1
    shift
    printf 'release: %s\n' "$*" >&2
    exit "$code"
}

usage() {
    die 2 'usage: release.sh <sha> | --rollback | --local <sha> | --local --rollback | --status'
}

script_path=${BASH_SOURCE[0]}
if [[ $script_path == */* ]]; then
    script_dir=$(cd -- "${script_path%/*}" && pwd)
else
    script_dir=$(pwd)
fi
repo_root=$(cd -- "$script_dir/.." && pwd)

local_mode=0
mode=release
sha=''
case $# in
    1)
        case $1 in
            --rollback) mode=rollback ;;
            --status) mode=status ;;
            --*) usage ;;
            *) sha=$1 ;;
        esac
        ;;
    2)
        [[ $1 == --local ]] || usage
        local_mode=1
        case $2 in
            --rollback) mode=rollback ;;
            --*) usage ;;
            *) sha=$2 ;;
        esac
        ;;
    *) usage ;;
esac

bad_value_re="[[:space:]\"']"
declare -A config=()
if (( ! local_mode )); then
    conf=$script_dir/release.conf
    [[ -f $conf ]] || die 2 "missing configuration file: $conf"
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        [[ -z $line || $line == \#* ]] && continue
        [[ $line =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] ||
            die 2 'release.conf must contain KEY=value lines'
        key=${BASH_REMATCH[1]}
        value=${BASH_REMATCH[2]}
        case $key in
            SSH_TARGET|GITHUB_REPOSITORY|IMAGE_OWNER|SITE_ORIGIN|API_ORIGIN|REMOTE_HOME|SECRETS_DIR|COMPOSE_PROFILES) ;;
            *) die 2 "unknown configuration key: $key" ;;
        esac
        [[ ! $value =~ $bad_value_re ]] ||
            die 2 "whitespace or quotes in configuration key: $key"
        [[ ${config[$key]+present} != present ]] ||
            die 2 "duplicate configuration key: $key"
        config[$key]=$value
    done < "$conf"

    for key in SSH_TARGET GITHUB_REPOSITORY IMAGE_OWNER SITE_ORIGIN API_ORIGIN; do
        [[ -n ${config[$key]:-} ]] || die 2 "missing configuration key: $key"
    done
    ssh_target=${config[SSH_TARGET]}
    github_repository=${config[GITHUB_REPOSITORY]}
    image_owner=${config[IMAGE_OWNER]}
    site_origin=${config[SITE_ORIGIN]}
    api_origin=${config[API_ORIGIN]}
    remote_home=${config[REMOTE_HOME]:-/opt/vitrin}
    secrets_dir=${config[SECRETS_DIR]:-/opt/vitrin/secrets}
    profiles=${config[COMPOSE_PROFILES]:-edge,observability}

    [[ $ssh_target =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
        die 2 'SSH_TARGET must be an SSH host alias'
    [[ $github_repository =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
        die 2 'GITHUB_REPOSITORY must be owner/name'
    [[ $image_owner =~ ^[a-z0-9][a-z0-9._-]*$ ]] ||
        die 2 'IMAGE_OWNER must be lowercase'
    [[ $remote_home == /* && $secrets_dir == /* ]] ||
        die 2 'REMOTE_HOME and SECRETS_DIR must be absolute paths'
    [[ $site_origin =~ ^https?://[^[:space:]]+$ &&
        $api_origin =~ ^https?://[^[:space:]]+$ ]] ||
        die 2 'SITE_ORIGIN and API_ORIGIN must be HTTP or HTTPS origins'
else
    : "${VITRIN_REHEARSAL_HOME:?VITRIN_REHEARSAL_HOME is required}"
    home=$VITRIN_REHEARSAL_HOME
    mkdir -p -- "$home"
    home=$(cd -- "$home" && pwd)
    if command -v cygpath > /dev/null 2>&1; then
        home=$(cygpath -m "$home")
    fi
    site_origin=${VITRIN_REHEARSAL_SITE_ORIGIN:-http://127.0.0.1:4173}
    gateway_port=${VITRIN_REHEARSAL_GATEWAY_PORT:-18080}
    [[ $gateway_port =~ ^[0-9]{1,5}$ ]] || die 2 'invalid rehearsal gateway port'
    port_number=$((10#$gateway_port))
    (( port_number >= 1 && port_number <= 65535 )) || die 2 'invalid rehearsal gateway port'
    api_origin=http://127.0.0.1:$gateway_port
    secrets_dir=$home/secrets
    profiles=observability
    [[ ! $home =~ $bad_value_re && ! $site_origin =~ $bad_value_re ]] ||
        die 2 'rehearsal paths and origins cannot contain whitespace or quotes'
    [[ $site_origin =~ ^https?://[^[:space:]]+$ ]] ||
        die 2 'invalid rehearsal site origin'
    export VITRIN_HOME=$home
fi

# Quote remote arguments without relying on the remote login shell being Bash.
shell_quote() {
    printf "'%s'" "${1//\'/\'\\\'\'}"
}

read_release() {
    local name=$1 path quoted result
    if (( local_mode )); then
        path=$home/releases/$name
        result=''
        if [[ -f $path ]]; then
            IFS= read -r result < "$path" || true
        fi
    else
        path=$remote_home/releases/$name
        quoted=$(shell_quote "$path")
        result=$(ssh "$ssh_target" "if [ -f $quoted ]; then cat $quoted; fi") || return 1
    fi
    if [[ -n $result && ! $result =~ ^[0-9a-f]{7,40}$ ]]; then
        printf 'release: invalid %s release record\n' "$name" >&2
        return 1
    fi
    printf '%s' "$result"
}

if [[ $mode == status ]]; then
    q_current=$(shell_quote "$remote_home/releases/current")
    q_previous=$(shell_quote "$remote_home/releases/previous")
    q_history=$(shell_quote "$remote_home/releases/history.log")
    ssh "$ssh_target" "
        printf 'current: '
        if [ -f $q_current ]; then cat $q_current; else printf '(none)\n'; fi
        printf 'previous: '
        if [ -f $q_previous ]; then cat $q_previous; else printf '(none)\n'; fi
        printf 'history:\n'
        if [ -f $q_history ]; then tail -n 10 $q_history; fi
    "
    exit 0
fi

if [[ $mode == release ]]; then
    if (( local_mode )); then
        [[ $sha =~ ^[0-9a-f]{7,40}$ ]] ||
            die 2 'local sha must be 7 to 40 lowercase hex characters'
    else
        [[ $sha =~ ^[0-9a-f]{40}$ ]] ||
            die 2 'sha must be exactly 40 lowercase hex characters'
        git -C "$repo_root" merge-base --is-ancestor "$sha" origin/main ||
            die 1 'sha is not an ancestor of origin/main'
    fi
fi

tmp=$(mktemp -d)
cleanup() {
    rm -rf -- "$tmp"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

release_started=$(date +%s)
step_number=0
declare -A durations=()
step_order=()
declare -A images=()
previous_record=''
older_record=''

run_step() {
    local name=$1 started ended rc
    shift
    step_number=$((step_number + 1))
    step_order+=("$name")
    printf '\n[%s] %s\n' "$step_number" "$name"
    started=$(date +%s)
    if "$@"; then
        rc=0
    else
        rc=$?
    fi
    ended=$(date +%s)
    durations[$name]=$((ended - started))
    printf '%s: %s seconds' "$name" "${durations[$name]}"
    if (( rc )); then
        printf ' (failed)\n'
    else
        printf '\n'
    fi
    return "$rc"
}

summary() {
    local ended name
    ended=$(date +%s)
    printf '\nrelease: %s\n' "$sha"
    for name in "${step_order[@]}"; do
        printf '%s: %s seconds\n' "$name" "${durations[$name]}"
    done
    printf 'total: %s seconds\n' "$((ended - release_started))"
}

gate_step() {
    local conclusion
    conclusion=$(gh api "repos/$github_repository/commits/$sha/check-runs" \
        --jq '[.check_runs[] | select(.name=="gate")] | first | .conclusion') || return 1
    if [[ $conclusion != success ]]; then
        printf 'release: gate must conclude success; seen %s\n' "${conclusion:-empty}" >&2
        return 1
    fi
}

images_step() {
    local name digest reference escaped_repository identity
    if (( local_mode )); then
        for name in gateway core search migrate; do
            reference=vitrin-$name:$sha
            docker image inspect "$reference" > /dev/null || return 1
            images[$name]=$reference
        done
        return 0
    fi

    escaped_repository=$(printf '%s' "$github_repository" |
        sed 's/[][\\.^$*+?(){}|]/\\&/g') || return 1
    identity="^https://github\\.com/$escaped_repository/\\.github/workflows/ci\\.yml@refs/heads/main$"
    for name in gateway core search migrate; do
        reference=ghcr.io/$image_owner/vitrin-$name
        digest=$(docker buildx imagetools inspect "$reference:$sha" \
            --format '{{json .Manifest.Digest}}') || return 1
        if [[ $digest == \"*\" ]]; then
            digest=${digest#\"}
            digest=${digest%\"}
        fi
        [[ $digest =~ ^sha256:[0-9a-f]{64}$ ]] || {
            printf 'release: invalid or unresolved digest for vitrin-%s\n' "$name" >&2
            return 1
        }
        cosign verify \
            --certificate-identity-regexp "$identity" \
            --certificate-oidc-issuer https://token.actions.githubusercontent.com \
            "$reference@$digest" > /dev/null 2>&1 || {
                printf 'release: signature verification failed for vitrin-%s\n' "$name" >&2
                return 1
            }
        images[$name]=$reference@$digest
    done
}

write_env() {
    local path=$1 value
    for value in \
        "${images[gateway]}" "${images[core]}" "${images[search]}" "${images[migrate]}" \
        "$secrets_dir" "$site_origin" "$profiles"; do
        [[ ! $value =~ $bad_value_re ]] || {
            printf 'release: environment values cannot contain whitespace or quotes\n' >&2
            return 1
        }
    done
    printf '%s\n' \
        "VITRIN_GATEWAY_IMAGE=${images[gateway]}" \
        "VITRIN_CORE_IMAGE=${images[core]}" \
        "VITRIN_SEARCH_IMAGE=${images[search]}" \
        "VITRIN_MIGRATE_IMAGE=${images[migrate]}" \
        "VITRIN_SECRETS_DIR=$secrets_dir" \
        "VITRIN_SITE_ORIGIN=$site_origin" \
        "COMPOSE_PROFILES=$profiles" > "$path" || return 1
}

bundle_step() {
    local bundle releases q_bundle q_releases
    write_env "$tmp/$sha.env" || return 1
    if (( local_mode )); then
        bundle=$home/bundles/$sha
        releases=$home/releases
        mkdir -p -- "$bundle" "$releases" || return 1
        [[ -d $script_dir/prod && -d $script_dir/server ]] || {
            printf 'release: missing local deploy/prod or deploy/server\n' >&2
            return 1
        }
        # Replacing the trees avoids retaining stale working-tree files.
        rm -rf -- "$bundle/prod" "$bundle/server" || return 1
        cp -R -- "$script_dir/prod" "$script_dir/server" "$bundle/" || return 1
        cp -- "$tmp/$sha.env" "$releases/$sha.env" || return 1
    else
        bundle=$remote_home/bundles/$sha
        releases=$remote_home/releases
        q_bundle=$(shell_quote "$bundle") || return 1
        q_releases=$(shell_quote "$releases") || return 1
        git -C "$repo_root" archive "$sha" deploy/prod deploy/server |
            ssh "$ssh_target" \
                "mkdir -p $q_bundle && tar -x --strip-components=1 -C $q_bundle" ||
            return 1
        # Tar sets 0644 without depending on a remote chmod command.
        tar --mode=0644 -cf - -C "$tmp" "$sha.env" |
            ssh "$ssh_target" "mkdir -p $q_releases && tar -xf - -C $q_releases" ||
            return 1
    fi
    previous_record=$(read_release current) || return 1
    # Restored after an automatic rollback, so that "previous" never names the failed release.
    older_record=$(read_release previous) || return 1
}

apply_release() {
    local target=$1 previous=${2:-} bundle q_home q_script q_sha
    local -a previous_args=()
    if [[ -n $previous ]]; then
        previous_args=(--previous "$previous")
    fi
    if (( local_mode )); then
        bundle=$home/bundles/$target
        # Git Bash must not rewrite the paths Docker receives. Scoped to this call: exported
        # for the whole script it breaks curl's temporary file paths in the smoke test.
        MSYS_NO_PATHCONV=1 \
        VITRIN_HOME=$home \
        VITRIN_PROJECT=vitrin-rehearsal \
        VITRIN_COMPOSE_OVERRIDE=compose.rehearsal.yaml \
            bash "$bundle/server/apply.sh" "$target" --allow-local-images "${previous_args[@]}"
    else
        q_home=$(shell_quote "$remote_home") || return 1
        q_script=$(shell_quote "$remote_home/bundles/$target/server/apply.sh") || return 1
        q_sha=$(shell_quote "$target") || return 1
        # The option values are a sha or the word "none": safe to pass unquoted.
        ssh "$ssh_target" "VITRIN_HOME=$q_home bash $q_script $q_sha ${previous_args[*]}"
    fi
}

smoke_api() {
    bash "$script_dir/smoke.sh" --only api --api "$api_origin" --origin "$site_origin"
}

print_site_command() {
    local target=$1
    printf 'gh workflow run deploy-site.yml --repo %s -f sha=%s\n' \
        "$github_repository" "$target"
}

automatic_rollback() {
    local apply_ok=0 smoke_ok=0
    if [[ -z $previous_record || $previous_record == "$sha" ]]; then
        printf '%s\n' 'release: no different previous release is available; cannot roll back' >&2
        exit 1
    fi
    printf 'release: rolling backend back to %s\n' "$previous_record"
    if run_step rollback apply_release "$previous_record" "${older_record:-none}"; then
        apply_ok=1
    fi
    if run_step 'smoke rollback' smoke_api; then
        smoke_ok=1
    fi
    if (( apply_ok && smoke_ok )); then
        printf 'release: rollback to %s succeeded\n' "$previous_record"
    else
        printf 'release: rollback to %s failed; inspect the backend\n' "$previous_record" >&2
    fi
    exit 1
}

site_step() {
    local triggered deadline now run_id filter
    triggered=$(date -u +'%Y-%m-%dT%H:%M:%SZ') || return 1
    deadline=$(date +%s) || return 1
    deadline=$((deadline + 60))
    gh workflow run deploy-site.yml --repo "$github_repository" -f "sha=$sha" || return 1
    # Inclusive seconds avoid missing a run created in the trigger's second.
    filter="map(select(.createdAt >= \"$triggered\" and (.displayTitle | contains(\"$sha\")))) | sort_by(.createdAt) | .[0].databaseId // empty"
    run_id=''
    while :; do
        now=$(date +%s) || return 1
        (( now <= deadline )) || break
        run_id=$(gh run list --workflow deploy-site.yml --repo "$github_repository" \
            --limit 5 --json databaseId,createdAt,displayTitle,status,conclusion \
            --jq "$filter") || return 1
        if [[ $run_id =~ ^[0-9]+$ ]]; then
            break
        fi
        run_id=''
        (( now < deadline )) || break
        sleep 2 || return 1
    done
    [[ -n $run_id ]] || {
        printf 'release: deploy-site run not found within 60 seconds\n' >&2
        return 1
    }
    gh run watch "$run_id" --repo "$github_repository" --exit-status
}

smoke_site() {
    bash "$script_dir/smoke.sh" --only site --site "$site_origin"
}

if [[ $mode == rollback ]]; then
    sha=$(read_release previous) || die 1 'could not read previous release'
    [[ -n $sha ]] || die 1 'no previous release is available'
    # After a manual rollback there is no known-good release left to fall back to.
    if ! run_step apply apply_release "$sha" none; then
        die 1 'rollback apply failed'
    fi
    if ! run_step 'smoke api' smoke_api; then
        die 1 'rollback API smoke failed'
    fi
    if (( local_mode )); then
        printf '%s\n' 'notice: local rehearsal skips site deployment and site smoke'
    else
        printf '%s\n' 'Redeploy the matching site:'
        print_site_command "$sha"
    fi
    summary
    exit 0
fi

if (( local_mode )); then
    printf '%s\n' 'notice: local rehearsal skips GitHub gate and signature verification'
else
    run_step gate gate_step || die 1 'gate failed; nothing sent to the server'
fi

run_step images images_step || die 1 'image checks failed; nothing sent to the server'
run_step bundle bundle_step || die 1 'bundle preparation or transfer failed'

if ! run_step apply apply_release "$sha"; then
    automatic_rollback
fi
if ! run_step 'smoke api' smoke_api; then
    automatic_rollback
fi

if (( local_mode )); then
    printf '%s\n' 'notice: local rehearsal skips site deployment and site smoke'
    summary
    exit 0
fi

if ! run_step site site_step; then
    printf '%s\n' 'release: site deployment failed; backend remains on the new release' >&2
    printf '%s\n' 'Rerun the site workflow:'
    print_site_command "$sha"
    exit 1
fi

if ! run_step 'smoke site' smoke_site; then
    printf '%s\n' 'release: site smoke failed; backend remains on the new release' >&2
    if [[ -n $previous_record ]]; then
        printf '%s\n' 'Redeploy the previous site:'
        print_site_command "$previous_record"
    else
        printf '%s\n' 'release: no previous site sha was recorded' >&2
    fi
    exit 1
fi

summary
