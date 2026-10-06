#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

tool=downtime
url=''
interval=0.5
duration=''
stop_file=''
out=''
expect=''
clock_mode=seconds

usage() {
    printf 'Usage: %s --url <status-url> [--interval <seconds>] [--duration <seconds>] [--stop-file <path>] [--out <dir>] [--expect <text the body must contain>]\n' "$0"
    printf 'At least one of --duration and --stop-file is required.\n'
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
        --url|--interval|--duration|--stop-file|--out|--expect)
            (($# >= 2)) || usage_error "Missing value for $1."
            [[ -n "$2" ]] || usage_error "Empty value for $1."
            case "$1" in
                --url) url=$2 ;;
                --expect) expect=$2 ;;
                --interval) interval=$2 ;;
                --duration) duration=$2 ;;
                --stop-file) stop_file=$2 ;;
                --out) out=$2 ;;
            esac
            shift 2
            ;;
        *) usage_error "Unknown argument: $1" ;;
    esac
done

[[ -n "$url" ]] || usage_error "--url is required."
url_pattern='^https?://[^/?#@[:space:]]+([/?#][^[:space:]]*)?$'
[[ "$url" =~ $url_pattern ]] ||
    usage_error "--url must be an HTTP(S) URL without embedded credentials."
[[ -n "$duration" || -n "$stop_file" ]] ||
    usage_error "Supply --duration or --stop-file."
[[ "$interval" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    usage_error "--interval must be positive seconds."
awk -v n="$interval" 'BEGIN { exit !(n > 0) }' ||
    usage_error "--interval must be positive seconds."
if [[ -n "$duration" ]]; then
    [[ "$duration" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
        usage_error "--duration must be positive seconds."
    awk -v n="$duration" 'BEGIN { exit !(n > 0) }' ||
        usage_error "--duration must be positive seconds."
fi

for program in curl awk date sleep mkdir; do
    command -v "$program" >/dev/null 2>&1 || fail "Missing command: $program."
done

[[ -n "$out" ]] || out="./measurements/${tool}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -- "$out"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$out/date.txt"
printf 'interval_seconds\t%s\nduration_seconds\t%s\n' \
    "$interval" "${duration:-until-stop-file}" > "$out/settings.tsv"
printf 'epoch_seconds_with_millis\thttp_code\tstate\n' > "$out/samples.tsv"
body="$out/.body"

probe=$(date +%s.%N)
if [[ "$probe" =~ ^[0-9]+[.][0-9]+$ ]]; then
    clock_mode=fractional
else
    printf 'NOTE: date %%N is unavailable; timestamps fall back to whole seconds.\n' >&2
fi

now() {
    if [[ "$clock_mode" == fractional ]]; then
        date +%s.%N | awk '{ printf "%.3f\n", $1 }'
    else
        date +%s
    fi
}

printf 'Polling every %s seconds; only HTTP 200 counts as up.\n' "$interval"
printf 'Output: %s\n' "$out"
start=$(now)
deadline=''
if [[ -n "$duration" ]]; then
    deadline=$(awk -v t="$start" -v d="$duration" 'BEGIN {
        printf "%.9f", t+d
    }')
fi

while :; do
    [[ -z "$stop_file" || ! -e "$stop_file" ]] || break
    stamp=$(now)
    if [[ -n "$deadline" ]] &&
        awk -v t="$stamp" -v end="$deadline" 'BEGIN { exit !(t >= end) }'; then
        break
    fi

    if ! code=$(curl --silent --output "$body" --write-out '%{http_code}' \
        --max-time 2 "$url" 2>/dev/null); then
        code=000
    fi
    [[ "$code" =~ ^[0-9]{3}$ ]] || code=000
    state=down
    if [[ "$code" == 200 ]]; then
        # With --expect, HTTP 200 alone is not enough: a gateway that answers while a backend
        # is down reports "degraded" with status 200.
        if [[ -z "$expect" ]] || grep -qF -- "$expect" "$body"; then
            state=up
        else
            code=200-degraded
        fi
    fi
    printf '%s\t%s\t%s\n' "$stamp" "$code" "$state" >> "$out/samples.tsv"

    [[ -z "$stop_file" || ! -e "$stop_file" ]] || break
    finished=$(now)
    # Request latency consumes the interval instead of extending it.
    remainder=$(awk -v begin="$stamp" -v end="$finished" \
        -v interval="$interval" -v deadline="$deadline" 'BEGIN {
            wait=interval-(end-begin)
            if (deadline != "" && deadline-end < wait)
                wait=deadline-end
            # Parentheses matter: a bare ">" after printf is an output redirection in awk.
            printf "%.9f", (wait > 0 ? wait : 0)
        }')
    if awk -v n="$remainder" 'BEGIN { exit !(n > 0) }'; then
        sleep "$remainder"
    fi
done

awk -F '\t' '
    function finish_outage(end, elapsed) {
        elapsed=end-outage_start
        if (elapsed < 0) elapsed=0
        total+=elapsed
        if (elapsed > longest) longest=elapsed
    }
    NR == 1 { next }
    {
        samples++
        last=$1+0
        if ($2 == "429") limited++
        if ($3 == "down") {
            down_samples++
            if (!down) {
                outages++
                outage_start=last
                down=1
            }
        } else if (down) {
            finish_outage(last)
            down=0
        }
    }
    END {
        if (down) finish_outage(last)
        printf "Samples: %d; down samples: %d; outages: %d\n",
            samples, down_samples, outages
        if (limited > 0) {
            # A rejected poll says nothing about availability, and the poller is spending the
            # quota of the endpoint it measures.
            printf "Rate limited samples (429): %d\n", limited
            print "DECISION: invalid; the poll was rate limited: raise --interval above the quota of the endpoint"
            exit
        }
        printf "Longest outage: %.3f seconds%s\n",
            longest, down ? " (still down at end)" : ""
        printf "Total downtime: %.3f seconds\n", total
        if (outages == 0) {
            print "No outage observed; if a release was expected, the poll did not overlap a restart."
            print "DECISION: accepted; no outage observed"
        } else if (longest < 30) {
            print "DECISION: accepted"
        } else {
            print "DECISION: above 30 s: measure where the time goes (image pull, JVM start, health wait)"
        }
    }
' "$out/samples.tsv"
