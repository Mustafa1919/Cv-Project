#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

tool=memory
project=vitrin
out=''

usage() {
    printf 'Usage: %s [--project <name>] [--out <dir>]\n' "$0"
}
usage_error() {
    printf 'ERROR: %s\n' "$1" >&2
    usage >&2
    exit 2
}
fail() {
    printf 'ERROR: %s\n' "$1" >&2
    printf 'DECISION: measurement failed\n'
    exit 1
}
trap 'fail "A required command failed."' ERR

while (($#)); do
    case "$1" in
        --help) usage; exit 0 ;;
        --project|--out)
            (($# >= 2)) || usage_error "Missing value for $1."
            [[ -n "$2" ]] || usage_error "Empty value for $1."
            case "$1" in
                --project) project=$2 ;;
                --out) out=$2 ;;
            esac
            shift 2
            ;;
        *) usage_error "Unknown argument: $1" ;;
    esac
done

[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] ||
    usage_error "--project must be a valid Compose project name."

for program in docker awk sort date mkdir; do
    command -v "$program" >/dev/null 2>&1 || fail "Missing command: $program."
done

[[ -n "$out" ]] || out="./measurements/${tool}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -- "$out"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$out/date.txt"
printf 'project\t%s\n' "$project" > "$out/settings.tsv"

docker ps --filter "label=com.docker.compose.project=$project" \
    --format '{{.ID}}\t{{.Label "com.docker.compose.service"}}' \
    > "$out/containers.tsv"
[[ -s "$out/containers.tsv" ]] || fail "The project has no running containers."

host_memory=0
if [[ -r /proc/meminfo ]]; then
    host_memory=$(awk '$1 == "MemTotal:" {
        printf "%.0f\n", $2*1024
        found=1
        exit
    }
    END { if (!found) exit 1 }' /proc/meminfo)
fi
printf 'host_memory_bytes\t%s\n' "$host_memory" >> "$out/settings.tsv"

printf 'container\tservice\tmem_usage\tconfigured_limit_bytes\tstarted_at\n' > "$out/raw.tsv"
printf 'service\tused_mib\tlimit_mib\tpercent\tnote\tage_minutes\tcontainer\n' > "$out/metrics.tsv"
current_epoch=$(date +%s)

while IFS=$'\t' read -r id service; do
    [[ "$id" =~ ^[a-f0-9]+$ && "$service" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
        fail "Invalid container ID or Compose service label."

    usage_line=$(docker stats --no-stream --format '{{.MemUsage}}' "$id")
    limit=$(docker inspect --format '{{.HostConfig.Memory}}' "$id")
    started=$(docker inspect --format '{{.State.StartedAt}}' "$id")
    [[ "$limit" =~ ^[0-9]+$ ]] || fail "Invalid configured memory limit."
    [[ "$usage_line" != *$'\n'* && "$started" != *$'\n'* ]] ||
        fail "Unexpected multiline container metadata."
    start_epoch=$(date -d "$started" +%s) ||
        fail "Could not parse a container start time."

    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$id" "$service" "$usage_line" "$limit" "$started" >> "$out/raw.tsv"

    # Inspect distinguishes real limits from Docker's displayed host-memory limit.
    awk -v service="$service" -v id="$id" -v usage="$usage_line" \
        -v limit="$limit" -v host="$host_memory" \
        -v started="$start_epoch" -v now="$current_epoch" '
        BEGIN {
            split(usage, halves, "/")
            used=halves[1]
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", used)
            if (used !~ /^[0-9]+([.][0-9]+)?(B|KiB|MiB|GiB)$/) exit 1
            unit=used
            sub(/^[0-9]+([.][0-9]+)?/, "", unit)
            amount=used
            sub(/(KiB|MiB|GiB|B)$/, "", amount)
            factor=1
            if (unit == "KiB") factor=1024
            if (unit == "MiB") factor=1048576
            if (unit == "GiB") factor=1073741824
            used_mib=amount*factor/1048576
            age=(now-started)/60
            if (age < 0) age=0

            unlimited=(limit == 0 || (host > 0 && limit == host))
            if (unlimited) {
                printf "%s\t%.9f\t-\t-\tno limit\t%.9f\t%s\n",
                    service, used_mib, age, id
            } else {
                limit_mib=limit/1048576
                percent=used_mib/limit_mib*100
                printf "%s\t%.9f\t%.9f\t%.9f\t%s\t%.9f\t%s\n",
                    service, used_mib, limit_mib, percent,
                    (percent > 80 ? "above 80 %" : ""), age, id
            }
        }
    ' >> "$out/metrics.tsv" || fail "Could not parse container memory usage."
done < "$out/containers.tsv"

awk 'NR > 1' "$out/metrics.tsv" |
    sort -t $'\t' -k1,1 -k7,7 > "$out/metrics-sorted.tsv"

printf 'Output: %s\n' "$out"
awk -F '\t' '
    BEGIN {
        print "| Service | Used MiB | Limit MiB | Percent of limit | Note |"
        print "|---|---:|---:|---:|---|"
    }
    {
        count++
        used_total+=$2
        if ($3 == "-") {
            unlimited++
            printf "| %s | %.1f | — | — | %s |\n", $1, $2, $5
        } else {
            limit_total+=$3
            limited_used+=$2
            printf "| %s | %.1f | %.1f | %.1f %% | %s |\n",
                $1, $2, $3, $4, $5
        }

        if ($5 != "" && !seen[$1]++) {
            review=review (review == "" ? "" : ", ") $1
        }
        if (count == 1 || $6 < youngest) youngest=$6
        if (count == 1 || $6 > oldest) oldest=$6
    }
    END {
        if (count == 0) exit 1
        printf "\nTotal used: %.1f MiB across %d containers.\n", used_total, count
        printf "Total configured finite limits: %.1f MiB; containers without limits: %d.\n",
            limit_total, unlimited
        if (limit_total > 0)
            printf "Usage of limited containers / their combined limits: %.1f %%.\n",
                limited_used/limit_total*100
        printf "Oldest container uptime: %.1f minutes.\n", oldest
        printf "Youngest container uptime: %.1f minutes.\n", youngest
        if (youngest < 60)
            printf "NOTE: youngest container is %.1f minutes old; the rule expects 60\n", youngest
        if (review == "")
            print "DECISION: within limits"
        else
            print "DECISION: review: " review
    }
' "$out/metrics-sorted.tsv"
