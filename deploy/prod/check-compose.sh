#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 0 ]]; then
    printf 'check-compose.sh takes no arguments.\n' >&2
    exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
base_file="${VITRIN_CHECK_COMPOSE_FILE:-$script_dir/compose.yaml}"

export VITRIN_GATEWAY_IMAGE="${VITRIN_GATEWAY_IMAGE-ghcr.io/example/vitrin-gateway@sha256:0000000000000000000000000000000000000000000000000000000000000000}"
export VITRIN_CORE_IMAGE="${VITRIN_CORE_IMAGE-ghcr.io/example/vitrin-core@sha256:0000000000000000000000000000000000000000000000000000000000000000}"
export VITRIN_SEARCH_IMAGE="${VITRIN_SEARCH_IMAGE-ghcr.io/example/vitrin-search@sha256:0000000000000000000000000000000000000000000000000000000000000000}"
export VITRIN_MIGRATE_IMAGE="${VITRIN_MIGRATE_IMAGE-ghcr.io/example/vitrin-migrate@sha256:0000000000000000000000000000000000000000000000000000000000000000}"
export VITRIN_SECRETS_DIR="${VITRIN_SECRETS_DIR-/nonexistent}"
export VITRIN_SITE_ORIGIN="${VITRIN_SITE_ORIGIN-https://example.invalid}"

# Run each candidate: on Windows "python3" can be a store stub that exists but does not start.
python_bin=""
for candidate in python3 python; do
    if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info.major == 3 else 1)' \
        >/dev/null 2>&1; then
        python_bin="$candidate"
        break
    fi
done

if [[ -z "$python_bin" ]]; then
    printf 'check-compose.sh requires Python 3; neither python3 nor python runs.\n' >&2
    exit 1
fi

json_file="$(mktemp)"
trap 'rm -f -- "$json_file"' EXIT
trap 'exit 1' HUP INT TERM

docker compose \
    -f "$base_file" \
    --profile '*' \
    config --format json > "$json_file"

# Rehearsal intentionally relaxes production rules; only validate its merge.
docker compose \
    -f "$base_file" \
    -f "$script_dir/compose.rehearsal.yaml" \
    --profile '*' \
    config -q

# A file keeps the JSON separate from the embedded Python program.
"$python_bin" - "$json_file" <<'PY'
import json
import re
import sys
from decimal import Decimal, InvalidOperation

with open(sys.argv[1], encoding="utf-8") as stream:
    model = json.load(stream)

services = model.get("services") or {}
networks = model.get("networks") or {}
secrets = model.get("secrets") or {}
violations = []
memory_total = 0
memory_budget = 12 * 1024 ** 3
health_exceptions = {"otelcol", "tempo", "loki", "migrate"}


def report(service, message):
    violations.append(f"{service}: {message}")


def memory_bytes(value):
    if isinstance(value, bool):
        raise ValueError("boolean memory limit")
    if isinstance(value, int):
        if value <= 0:
            raise ValueError("non-positive memory limit")
        return value
    text = str(value).strip().lower()
    match = re.fullmatch(
        r"([0-9]+(?:\.[0-9]+)?)\s*(b|[kmgt](?:i?b)?)?", text
    )
    if not match:
        raise ValueError("invalid memory limit")
    unit = match.group(2) or "b"
    exponent = 0 if unit == "b" else "kmgt".index(unit[0]) + 1
    amount = Decimal(match.group(1)) * (1024 ** exponent)
    if amount <= 0 or amount != amount.to_integral_value():
        raise ValueError("invalid memory byte count")
    return int(amount)


def entries(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    return value


def environment_items(value):
    if isinstance(value, dict):
        return value.items()
    result = []
    for item in entries(value):
        key, separator, val = str(item).partition("=")
        result.append((key, val if separator else None))
    return result


backend = networks.get("backend") or {}
if backend.get("internal") is not True:
    report("backend", "network must be internal")

for name, service in services.items():
    if "ports" in service:
        report(name, "ports must not be declared")

    image = str(service.get("image") or "")
    if not re.search(r"@sha256:[0-9a-fA-F]{64}$", image):
        report(name, "image must be pinned to a 64-character SHA-256 digest")

    if service.get("mem_limit") is None:
        report(name, "mem_limit must be set")
    else:
        try:
            memory_total += memory_bytes(service["mem_limit"])
        except (ValueError, InvalidOperation, TypeError):
            report(name, "mem_limit must be a positive valid memory size")

    if service.get("read_only") is not True:
        report(name, "read_only must be true")

    cap_drop = {str(cap).upper() for cap in entries(service.get("cap_drop"))}
    if "ALL" not in cap_drop:
        report(name, "cap_drop must contain ALL")

    security_opt = entries(service.get("security_opt"))
    if "no-new-privileges:true" not in security_opt:
        report(name, "security_opt must contain no-new-privileges:true")

    if service.get("privileged") is True:
        report(name, "privileged must not be true")

    if service.get("network_mode") == "host":
        report(name, "host networking is forbidden")

    for volume in entries(service.get("volumes")):
        if isinstance(volume, dict):
            source = str(volume.get("source") or "")
            target = str(volume.get("target") or "")
        else:
            parts = str(volume).split(":")
            source = parts[0]
            target = parts[1] if len(parts) > 1 else parts[0]
        if "docker.sock" in source or "docker.sock" in target:
            report(name, "Docker socket mounts are forbidden")

    user = str(service.get("user") or "").strip()
    uid = user.split(":", 1)[0].strip()
    if not uid:
        report(name, "an explicit non-root user must be set")
    elif uid.lower() == "root" or (uid.isdecimal() and int(uid) == 0):
        report(name, "user must not have root uid")

    required_restart = "no" if name == "migrate" else "unless-stopped"
    if service.get("restart") != required_restart:
        report(name, f"restart must be {required_restart}")

    if name not in health_exceptions:
        health = service.get("healthcheck") or {}
        test = health.get("test")
        disabled_test = (
            isinstance(test, list)
            and bool(test)
            and str(test[0]).upper() == "NONE"
        ) or (
            isinstance(test, str)
            and test.strip().upper() == "NONE"
        )
        if not test or health.get("disable") is True or disabled_test:
            report(name, "an enabled health check must be set")

    for key, value in environment_items(service.get("environment")):
        key = str(key)
        if any(word in key.upper() for word in ("PASSWORD", "SECRET", "TOKEN", "KEY")):
            file_name = key.endswith("_FILE") or key.endswith("__FILE")
            file_value = isinstance(value, str) and value.startswith("/run/secrets/")
            if not (file_name and file_value):
                report(name, f"environment {key} must reference a /run/secrets/ file")

    for secret in entries(service.get("secrets")):
        source = secret.get("source") if isinstance(secret, dict) else secret
        declaration = secrets.get(source)
        if (
            not isinstance(declaration, dict)
            or not isinstance(declaration.get("file"), str)
            or not declaration["file"]
        ):
            report(name, f"secret {source} must have a top-level file source")

    logging = service.get("logging") or {}
    options = logging.get("options") or {}
    if logging.get("driver") != "json-file":
        report(name, "logging.driver must be json-file")
    for option in ("max-size", "max-file"):
        if option not in options or options[option] in (None, ""):
            report(name, f"logging.options must set {option}")

    attached = service.get("networks") or {}
    attached_names = list(attached) if isinstance(attached, (dict, list)) else []
    if "backend" not in attached_names:
        report(name, "must be attached to backend")
    for network_name in attached_names:
        declaration = networks.get(network_name) or {}
        if declaration.get("internal") is not True and name != "cloudflared":
            report(name, f"non-internal network {network_name} is forbidden")

if memory_total > memory_budget:
    report(
        "all services",
        f"memory limits total {memory_total} bytes, exceeding 12 GiB",
    )

if violations:
    for violation in violations:
        print(violation, file=sys.stderr)
    sys.exit(1)

print(f"compose.yaml: {len(services)} services checked, 0 violations")
PY
