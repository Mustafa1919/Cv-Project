#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s [--close-ssh] [--help]\n' "${0##*/}"
}

close_ssh=false
for argument in "$@"; do
    case "$argument" in
        --close-ssh)
            close_ssh=true
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

if [[ $EUID -ne 0 ]]; then
    printf 'setup.sh must be run as root.\n' >&2
    exit 1
fi

if [[ ! -r /etc/os-release ]]; then
    printf 'Cannot identify the operating system.\n' >&2
    exit 1
fi

# shellcheck disable=SC1091
source /etc/os-release
if [[ ${ID-} != ubuntu || ${VERSION_ID-} != 24.04 ]]; then
    printf 'setup.sh supports only Ubuntu 24.04.\n' >&2
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C
umask 022

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
trap 'exit 1' HUP INT TERM

header() {
    printf '\n== %s ==\n' "$1"
}

report() {
    printf '%s: %s\n' "$1" "$2"
}

write_file() {
    local destination="$1"
    local mode="$2"
    local show_diff="${3:-false}"
    local temporary_file
    local difference_status

    temporary_file="$(mktemp "$work_dir/file.XXXXXX")"
    cat > "$temporary_file"
    FILE_CHANGED=false

    if [[ -f "$destination" ]] &&
       cmp -s -- "$temporary_file" "$destination" &&
       [[ $(stat -c '%a:%u:%g' -- "$destination") == "$mode:0:0" ]]; then
        return
    fi

    if "$show_diff" && [[ -f "$destination" ]]; then
        if diff -u -- "$destination" "$temporary_file"; then
            :
        else
            difference_status=$?
            if [[ $difference_status -gt 1 ]]; then
                return "$difference_status"
            fi
        fi
    fi

    install -D -o root -g root -m "$mode" -- "$temporary_file" "$destination"
    FILE_CHANGED=true
}

packages_installed() {
    local package
    for package in "$@"; do
        if [[ $(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true) != \
              'install ok installed' ]]; then
            return 1
        fi
    done
}

step_packages() {
    header 'Packages'
    local changed=false
    local simulation
    local packages=(
        ca-certificates curl gnupg ufw unattended-upgrades chrony openssl
    )

    apt-get update
    simulation="$(apt-get --simulate full-upgrade)"
    if grep -Eq '^(Inst|Remv) ' <<< "$simulation"; then
        changed=true
    fi
    apt-get -y full-upgrade

    if ! packages_installed "${packages[@]}"; then
        changed=true
    fi
    apt-get -y install "${packages[@]}"

    if "$changed"; then
        report packages changed
    else
        report packages unchanged
    fi
}

step_automatic_updates() {
    header 'Automatic security updates'
    write_file /etc/apt/apt.conf.d/20auto-upgrades 644 <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF
    if "$FILE_CHANGED"; then
        report automatic-updates changed
    else
        report automatic-updates unchanged
    fi
}

step_time() {
    header 'Time synchronisation'
    local changed=false
    local deadline
    local tracking

    if ! systemctl is-enabled --quiet chrony; then
        systemctl enable chrony
        changed=true
    fi
    if ! systemctl is-active --quiet chrony; then
        systemctl start chrony
        changed=true
    fi

    # Token validity depends on a synchronised clock.
    deadline=$((SECONDS + 30))
    while true; do
        if tracking="$(chronyc tracking 2>/dev/null)" &&
           awk -F: '
               /^Stratum[[:space:]]*:/ {
                   if (($2 + 0) > 0) stratum = 1
               }
               /^Leap status[[:space:]]*:/ {
                   if ($2 ~ /^[[:space:]]*Normal[[:space:]]*$/) normal = 1
               }
               END { exit !(stratum && normal) }
           ' <<< "$tracking"; then
            break
        fi
        if [[ $SECONDS -ge $deadline ]]; then
            printf 'chrony did not report a synchronised source within 30 seconds.\n' >&2
            exit 1
        fi
        sleep 1
    done

    if "$changed"; then
        report chrony changed
    else
        report chrony unchanged
    fi
}

has_authorized_key() {
    local account="$1"
    local record
    local home

    if ! record="$(getent passwd "$account")"; then
        return 1
    fi
    IFS=: read -r _ _ _ _ _ home _ <<< "$record"
    if [[ ! -s "$home/.ssh/authorized_keys" ]]; then
        return 1
    fi

    # Parsing a real public key is safer than accepting a non-empty file.
    ssh-keygen -l -f "$home/.ssh/authorized_keys" >/dev/null 2>&1
}

step_ssh() {
    header 'SSH'
    local destination=/etc/ssh/sshd_config.d/10-vitrin.conf
    local had_original=false
    local backup="$work_dir/sshd-original"

    if ! has_authorized_key root; then
        if [[ -z ${SUDO_USER-} ]] || ! has_authorized_key "$SUDO_USER"; then
            printf 'Refusing SSH changes: no usable authorized key for root or SUDO_USER.\n' >&2
            exit 1
        fi
    fi

    if [[ -e "$destination" ]]; then
        cp -p -- "$destination" "$backup"
        had_original=true
    fi

    write_file "$destination" 644 <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
X11Forwarding no
CONF

    if ! sshd -t; then
        if "$had_original"; then
            cp -p -- "$backup" "$destination"
        else
            rm -f -- "$destination"
        fi
        printf 'SSH validation failed; the previous drop-in was restored.\n' >&2
        exit 1
    fi

    if "$FILE_CHANGED"; then
        systemctl reload ssh
        report ssh changed
    else
        report ssh unchanged
    fi
}

step_docker() {
    header 'Docker'
    local changed=false
    local daemon_changed=false
    local packages=(
        docker-ce docker-ce-cli containerd.io docker-compose-plugin
    )

    install -d -o root -g root -m 0755 /etc/apt/keyrings

    if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
            -o "$work_dir/docker.asc"
        gpg --batch --show-keys "$work_dir/docker.asc" >/dev/null
        install -o root -g root -m 0644 \
            "$work_dir/docker.asc" /etc/apt/keyrings/docker.asc
        changed=true
    elif [[ $(stat -c '%a:%u:%g' /etc/apt/keyrings/docker.asc) != 644:0:0 ]]; then
        chown root:root /etc/apt/keyrings/docker.asc
        chmod 0644 /etc/apt/keyrings/docker.asc
        changed=true
    fi

    write_file /etc/apt/sources.list.d/docker.sources 644 <<'CONF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
CONF
    if "$FILE_CHANGED"; then
        changed=true
    fi

    if "$changed" || ! packages_installed "${packages[@]}"; then
        apt-get update
        apt-get -y install "${packages[@]}"
        changed=true
    fi

    write_file /etc/docker/daemon.json 644 true <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  },
  "live-restore": true,
  "userland-proxy": false,
  "no-new-privileges": true
}
JSON
    if "$FILE_CHANGED"; then
        daemon_changed=true
        changed=true
    fi

    if ! systemctl is-enabled --quiet docker; then
        systemctl enable docker
        changed=true
    fi

    if "$daemon_changed"; then
        systemctl restart docker
    elif ! systemctl is-active --quiet docker; then
        systemctl start docker
        changed=true
    fi

    if "$changed"; then
        report docker changed
    else
        report docker unchanged
    fi
}

step_directories() {
    header 'Directories'
    local path
    local mode
    local changed

    while read -r path mode; do
        changed=false
        if [[ ! -d "$path" ]]; then
            install -d -o root -g root -m "$mode" -- "$path"
            changed=true
        elif [[ $(stat -c '%a:%u:%g' -- "$path") != "$mode:0:0" ]]; then
            chown root:root -- "$path"
            chmod "$mode" -- "$path"
            changed=true
        fi
        if "$changed"; then
            report "$path" changed
        else
            report "$path" unchanged
        fi
    done <<'DIRS'
/opt/vitrin 755
/opt/vitrin/deploy 755
/opt/vitrin/secrets 700
/opt/vitrin/releases 755
DIRS
}

step_secrets() {
    header 'Secrets'
    local output

    output="$(bash "$script_dir/secrets.sh" /opt/vitrin/secrets)"
    printf '%s\n' "$output"

    if grep -q ': created$' <<< "$output"; then
        report secrets changed
    else
        report secrets unchanged
    fi
}

step_firewall() {
    header 'Firewall'
    local changed=false
    local container_state
    local added_rules
    local status
    local number
    local rule_numbers=()

    # Docker-published ports bypass ufw; this deployment publishes none.
    if "$close_ssh"; then
        if ! container_state="$(docker inspect \
            --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
            vitrin-cloudflared-1 2>/dev/null)" ||
           [[ "$container_state" != 'true healthy' ]]; then
            printf 'Refusing to close SSH: vitrin-cloudflared-1 is not running and healthy.\n' >&2
            exit 1
        fi
    fi

    if ! grep -Eq '^DEFAULT_INPUT_POLICY="DROP"$' /etc/default/ufw; then
        ufw default deny incoming
        changed=true
    fi
    if ! grep -Eq '^DEFAULT_OUTPUT_POLICY="ACCEPT"$' /etc/default/ufw; then
        ufw default allow outgoing
        changed=true
    fi

    status="$(ufw status)"
    if ! grep -q '^Status: active$' <<< "$status"; then
        if "$close_ssh"; then
            printf 'Refusing to close SSH while ufw is inactive; run setup.sh without flags first.\n' >&2
            exit 1
        fi
    fi

    if "$close_ssh"; then
        status="$(ufw status numbered)"
        mapfile -t rule_numbers < <(
            awk '
                /^\[/ &&
                /\][[:space:]]+(22(\/tcp)?|OpenSSH)([[:space:]]|$)/ {
                    line = $0
                    sub(/^\[[[:space:]]*/, "", line)
                    sub(/\].*$/, "", line)
                    print line + 0
                }
            ' <<< "$status" | sort -rn
        )
        for number in "${rule_numbers[@]}"; do
            ufw --force delete "$number"
            changed=true
        done

        status="$(ufw status)"
        if grep -Eq '[[:space:]](ALLOW|LIMIT)[[:space:]]+IN[[:space:]]' <<< "$status"; then
            printf 'Unexpected inbound allow rules remain; review ufw before declaring the server closed.\n' >&2
            exit 1
        fi
    else
        added_rules="$(ufw show added)"
        if ! grep -Fxq 'ufw limit 22/tcp' <<< "$added_rules"; then
            ufw limit 22/tcp
            changed=true
        fi
        if ! grep -q '^Status: active$' <<< "$status"; then
            ufw --force enable
            changed=true
        fi
    fi

    if "$changed"; then
        report firewall changed
    else
        report firewall unchanged
    fi
}

step_summary() {
    header 'Summary'
    docker --version
    docker compose version
    ufw status verbose
    ss -ltn

    printf '\nManual: copy and verify the tunnel token, configure DNS, and configure the Access policy.\n'
    printf 'Manual: test the provider console once as the tunnel-down recovery path.\n'
    if "$close_ssh"; then
        printf 'Inbound SSH is closed. Host SSH through a separate tunnel must already be verified.\n'
    else
        printf 'Inbound SSH remains rate-limited on 22/tcp.\n'
        printf 'The host SSH tunnel is not installed by this script; verify it before --close-ssh.\n'
    fi
}

step_packages
step_automatic_updates
step_time
step_ssh
step_docker
step_directories
step_secrets
step_firewall
step_summary
