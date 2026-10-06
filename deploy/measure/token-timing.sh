#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

tool=token-timing
api=''
origin=''
requests=1000
rate=100
out=''
retries=0
response=''
cookie=''
status=''
clock_mode=seconds

usage() {
    printf 'Usage: %s --api <origin> --origin <site-origin> [--requests <n>] [--rate <requests/minute>] [--out <dir>]\n' "$0"
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
        --api|--origin|--requests|--rate|--out)
            (($# >= 2)) || usage_error "Missing value for $1."
            [[ -n "$2" ]] || usage_error "Empty value for $1."
            case "$1" in
                --api) api=$2 ;;
                --origin) origin=$2 ;;
                --requests) requests=$2 ;;
                --rate) rate=$2 ;;
                --out) out=$2 ;;
            esac
            shift 2
            ;;
        *) usage_error "Unknown argument: $1" ;;
    esac
done

[[ -n "$api" && -n "$origin" ]] || usage_error "--api and --origin are required."
origin_pattern='^https?://[^/?#@[:space:]]+/?$'
[[ "$api" =~ $origin_pattern && "$origin" =~ $origin_pattern ]] ||
    usage_error "--api and --origin must be HTTP(S) origins without credentials."
api=${api%/}
origin=${origin%/}
[[ "$requests" =~ ^[0-9]+$ ]] ||
    usage_error "--requests must be a positive integer."
awk -v n="$requests" 'BEGIN { exit !(n >= 1 && n <= 2147483647) }' ||
    usage_error "--requests must be a positive integer no greater than 2147483647."
requests=$((10#$requests))
[[ "$rate" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    usage_error "--rate must be positive and no greater than 110."
awk -v n="$rate" 'BEGIN { exit !(n > 0 && n <= 110) }' ||
    usage_error "--rate must be positive and no greater than 110."

for program in curl awk sort date sleep mkdir tr; do
    command -v "$program" >/dev/null 2>&1 || fail "Missing command: $program."
done

[[ -n "$out" ]] || out="./measurements/${tool}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -- "$out"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$out/date.txt"
printf 'requests\t%s\nrate_per_minute\t%s\nwarmup_policy\tfirst read of every session excluded\n' \
    "$requests" "$rate" > "$out/settings.tsv"
printf 'n\tauth_ms\tcore_ms\ttotal_ms\tfirst\n' > "$out/samples.tsv"

probe=$(date +%s.%N)
if [[ "$probe" =~ ^[0-9]+[.][0-9]+$ ]]; then
    clock_mode=fractional
else
    printf 'NOTE: date %%N is unavailable; pacing uses whole-second timestamps.\n' >&2
fi

now() {
    if [[ "$clock_mode" == fractional ]]; then
        date +%s.%N
    else
        date +%s
    fi
}
header() {
    local name=$1
    printf '%s\n' "$response" | tr -d '\r' | awk -v name="$name" '
        /^HTTP\// { value="" }
        {
            colon=index($0, ":")
            if (colon && tolower(substr($0, 1, colon-1)) == tolower(name)) {
                value=substr($0, colon+1)
                sub(/^[[:space:]]+/, "", value)
                sub(/[[:space:]]+$/, "", value)
            }
        }
        END { print value }
    '
}
get_status() {
    printf '%s\n' "$response" | awk '
        /^MEASURE_STATUS:/ { sub(/^MEASURE_STATUS:/, ""); code=$0 }
        END { print code }
    '
}
backoff() {
    local reset delay
    retries=$((retries + 1))
    ((retries <= 20)) || fail "More than 20 rate-limit retries."
    reset=$(header RateLimit-Reset)
    if [[ ! "$reset" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        reset=5
    fi
    delay=$(awk -v n="$reset" 'BEGIN { printf "%.6f", n+1 }')
    printf 'Rate limited; sleeping %s seconds (retry %s/20).\n' "$delay" "$retries"
    sleep "$delay"
}
new_session() {
    local candidate
    while :; do
        # Headers stay in memory because they contain the token.
        if ! response=$(curl --silent --output /dev/null --dump-header - \
            --write-out $'\nMEASURE_STATUS:%{http_code}\n' --max-time 30 \
            --request POST --header "Origin: $origin" \
            --header 'X-Requested-With: vitrin' "$api/v1/session" 2>/dev/null); then
            fail "Session creation transport failed."
        fi
        status=$(get_status)
        if [[ "$status" == 429 ]]; then
            backoff
            continue
        fi
        [[ "$status" == 201 ]] || fail "Session creation did not return 201."
        candidate=$(printf '%s\n' "$response" | tr -d '\r' | awk '
            /^HTTP\// { token="" }
            {
                colon=index($0, ":")
                if (tolower(substr($0, 1, colon-1)) == "set-cookie") {
                    value=substr($0, colon+1)
                    sub(/^[[:space:]]+/, "", value)
                    split(value, parts, ";")
                    if (parts[1] ~ /^__Host-vitrin_at=/)
                        token=parts[1]
                }
            }
            END { print token }
        ')
        [[ "$candidate" =~ ^__Host-vitrin_at=[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]] ||
            fail "Session creation did not provide a valid JWT cookie."
        cookie=$candidate
        response=''
        return
    done
}
timings() {
    local value
    value=$(header Server-Timing)
    printf '%s\n' "$value" | awk '
        {
            count=split($0, entries, ",")
            for (i=1; i<=count; i++) {
                parts=split(entries[i], fields, ";")
                name=fields[1]
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
                if (name != "auth" && name != "core" && name != "total")
                    continue
                for (j=2; j<=parts; j++) {
                    field=fields[j]
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", field)
                    if (field ~ /^dur[[:space:]]*=/) {
                        sub(/^dur[[:space:]]*=[[:space:]]*/, "", field)
                        gsub(/^"|"$/, "", field)
                        if (field !~ /^[0-9]+([.][0-9]+)?$/) exit 1
                        value[name]=field
                        found[name]=1
                    }
                }
            }
        }
        END {
            if (!found["auth"] || !found["core"] || !found["total"]) exit 1
            printf "%s\t%s\t%s\n", value["auth"], value["core"], value["total"]
        }
    '
}

interval=$(awk -v r="$rate" 'BEGIN { printf "%.9f", 60/r }')
awk -v n="$requests" -v r="$rate" 'BEGIN {
    printf "Expected duration: approximately %.1f minutes at %s reads/minute, plus session creation and any backoff.\n", n/r, r
}'
printf 'Output: %s\n' "$out"

next_start=0
session_reads=50
n=1
while ((n <= requests)); do
    if ((session_reads == 50)); then
        new_session
        session_reads=0
    fi
    while :; do
        current=$(now)
        wait=$(awk -v due="$next_start" -v t="$current" 'BEGIN {
            # Parentheses matter: a bare ">" after printf is an output redirection in awk.
            printf "%.9f", (due > t ? due-t : 0)
        }')
        if awk -v n="$wait" 'BEGIN { exit !(n > 0) }'; then
            sleep "$wait"
        fi
        started=$(now)
        next_start=$(awk -v t="$started" -v d="$interval" 'BEGIN {
            printf "%.9f", t+d
        }')
        if ! response=$(curl --silent --output /dev/null --dump-header - \
            --write-out $'\nMEASURE_STATUS:%{http_code}\n' --max-time 30 \
            --header "Cookie: $cookie" "$api/v1/session" 2>/dev/null); then
            fail "Session read transport failed."
        fi
        status=$(get_status)
        if [[ "$status" == 429 ]]; then
            backoff
            continue
        fi
        [[ "$status" == 200 ]] || fail "Session read did not return 200."
        values=$(timings) || fail "Missing or invalid Server-Timing durations."
        first=0
        ((session_reads != 0)) || first=1
        printf '%s\t%s\t%s\n' "$n" "$values" "$first" >> "$out/samples.tsv"
        response=''
        session_reads=$((session_reads + 1))
        break
    done
    if ((n % 100 == 0)); then
        printf 'Progress: %s/%s requests.\n' "$n" "$requests"
    fi
    n=$((n + 1))
done
cookie=''

awk -F '\t' 'NR > 1 && $5 == 0 { print $2 }' "$out/samples.tsv" |
    sort -n > "$out/auth-sorted.txt"
awk -F '\t' 'NR > 1 && $5 == 0 { print $4 }' "$out/samples.tsv" |
    sort -n > "$out/total-sorted.txt"
eligible=$(awk 'END { print NR+0 }' "$out/auth-sorted.txt")
((eligible > 0)) || fail "No non-warm-up samples are available for statistics."

awk -v all="$requests" '
    function rank(p, n, x, k) {
        x=p*n/100
        k=int(x)
        return k < x ? k+1 : k
    }
    FNR == NR { auth[++n]=$1; next }
    { total[++m]=$1 }
    END {
        if (n == 0 || m != n) exit 1
        p95=auth[rank(95,n)]
        printf "Reads: %d; statistics n: %d; warm-up excluded: %d\n", all, n, all-n
        printf "Auth (ms): min %.6f; p50 %.6f; p95 %.6f; p99 %.6f; max %.6f\n",
            auth[1], auth[rank(50,n)], p95, auth[rank(99,n)], auth[n]
        printf "Total (ms): p50 %.6f; p95 %.6f\n",
            total[rank(50,m)], total[rank(95,m)]
        if (p95 <= 1.0)
            print "DECISION: recorded"
        else
            print "DECISION: recorded; p95 above 1 ms: inspect the key cache and verification path"
    }
' "$out/auth-sorted.txt" "$out/total-sorted.txt"
