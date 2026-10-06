#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

tool=disk
out=''
dir=''
runtime=60
test_file=''

usage() {
    printf 'Usage: %s --dir <path> [--runtime <seconds>] [--out <dir>]\n' "$0"
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
cleanup() {
    if [[ -n "$test_file" ]]; then
        rm -f -- "$test_file" || :
    fi
}
trap cleanup EXIT
trap 'fail "A required command failed."' ERR

while (($#)); do
    case "$1" in
        --help) usage; exit 0 ;;
        --dir|--runtime|--out)
            (($# >= 2)) || usage_error "Missing value for $1."
            [[ -n "$2" ]] || usage_error "Empty value for $1."
            case "$1" in
                --dir) dir=$2 ;;
                --runtime) runtime=$2 ;;
                --out) out=$2 ;;
            esac
            shift 2
            ;;
        *) usage_error "Unknown argument: $1" ;;
    esac
done

[[ -n "$dir" ]] || usage_error "--dir is required."
[[ "$runtime" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    usage_error "--runtime must be positive seconds."
awk -v n="$runtime" 'BEGIN { exit !(n > 0) }' ||
    usage_error "--runtime must be positive seconds."
[[ -d "$dir" && -w "$dir" ]] || fail "--dir must be a writable directory."

for program in fio df awk date mkdir mktemp rm; do
    command -v "$program" >/dev/null 2>&1 || fail "Missing command: $program."
done

[[ -n "$out" ]] || out="./measurements/${tool}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -- "$out"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$out/date.txt"
fio --version > "$out/fio-version.txt"
df -PT -- "$dir" > "$out/filesystem.txt"
df -Pk -- "$dir" > "$out/free-space.txt"

free_kib=$(awk 'NR > 1 { value=$4 } END {
    if (value !~ /^[0-9]+$/) exit 1
    print value
}' "$out/free-space.txt")
awk -v n="$free_kib" 'BEGIN { exit !(n >= 3 * 1024 * 1024) }' ||
    fail "The target disk has less than 3 GiB free."

# A unique file prevents overwriting existing data.
test_file=$(mktemp "$dir/.vitrin-disk.XXXXXX")

printf 'Disk test: three sequential jobs, %s seconds each.\n' "$runtime"
printf 'Output: %s\n' "$out"

run_job() {
    local name=$1 rw=$2 bs=$3 depth=$4
    fio "--name=$name" "--filename=$test_file" --size=2G \
        --direct=1 --ioengine=libaio --time_based "--runtime=$runtime" \
        --group_reporting --output-format=json "--rw=$rw" "--bs=$bs" \
        "--iodepth=$depth" "--output=$out/$name.json" >/dev/null
}

run_job random-read randread 4k 32
run_job random-write randwrite 4k 32
run_job sequential-write write 1M 8

extract() {
    local file=$1 section=$2
    awk -v section="$section" '
        function number(line) {
            sub(/^[^:]*:[[:space:]]*/, "", line)
            sub(/,.*/, "", line)
            gsub(/[[:space:]]/, "", line)
            return line
        }
        /^[[:space:]]*"(read|write)"[[:space:]]*:/ {
            if (active) exit
            if ($0 ~ ("\"" section "\"[[:space:]]*:")) active=1
        }
        active && !have_bw && /^[[:space:]]*"bw_bytes"[[:space:]]*:/ {
            bw=number($0); have_bw=1
        }
        active && !have_iops && /^[[:space:]]*"iops"[[:space:]]*:/ {
            iops=number($0); have_iops=1
        }
        active && have_bw && have_iops { exit }
        END {
            numeric="^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$"
            if (!have_bw || !have_iops || bw !~ numeric || iops !~ numeric)
                exit 1
            printf "%.9f\t%.9f\n", iops, bw
        }
    ' "$file"
}

read -r read_iops read_bw < <(extract "$out/random-read.json" read)
[[ -n "${read_iops:-}" && -n "${read_bw:-}" ]] ||
    fail "Could not extract random-read metrics."
read -r write_iops write_bw < <(extract "$out/random-write.json" write)
[[ -n "${write_iops:-}" && -n "${write_bw:-}" ]] ||
    fail "Could not extract random-write metrics."
read -r sequential_iops sequential_bw < <(extract "$out/sequential-write.json" write)
[[ -n "${sequential_iops:-}" && -n "${sequential_bw:-}" ]] ||
    fail "Could not extract sequential-write metrics."

awk -v r="$read_iops" -v w="$write_iops" -v b="$sequential_bw" '
    BEGIN {
        mb=b/1000000
        printf "Random read: %.2f IOPS\n", r
        printf "Random write: %.2f IOPS\n", w
        printf "Sequential write: %.2f MB/s\n", mb
        if (r >= 3000 && mb >= 100) {
            print "DECISION: sufficient"
        } else {
            text=""
            if (r < 3000)
                text=sprintf("random read %.2f IOPS < 3000", r)
            if (mb < 100)
                text=text (text == "" ? "" : "; ") \
                    sprintf("sequential write %.2f MB/s < 100", mb)
            print "DECISION: below threshold: " text
        }
    }
'
