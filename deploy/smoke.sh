#!/usr/bin/env bash
set -euo pipefail

usage_error() {
    printf 'smoke: %s\n' "$*" >&2
    printf '%s\n' \
        'usage: smoke.sh --api <origin> --site <origin> [--only api|site] [--origin <origin>] [--timeout <seconds>]' >&2
    exit 2
}

api=''
site=''
origin=''
only=''
timeout=10
while (( $# )); do
    case $1 in
        --api|--site|--only|--origin|--timeout)
            (( $# >= 2 )) || usage_error "missing value for $1"
            [[ -n $2 ]] || usage_error "empty value for $1"
            case $1 in
                --api) api=$2 ;;
                --site) site=$2 ;;
                --only) only=$2 ;;
                --origin) origin=$2 ;;
                --timeout) timeout=$2 ;;
            esac
            shift 2
            ;;
        *) usage_error "unknown argument: $1" ;;
    esac
done

[[ -z $only || $only == api || $only == site ]] || usage_error '--only must be api or site'
[[ $timeout =~ ^[1-9][0-9]*([.][0-9]+)?$ ]] || usage_error '--timeout must be positive'

run_api=1
run_site=1
[[ $only != api ]] || run_site=0
[[ $only != site ]] || run_api=0

(( ! run_api )) || [[ -n $api ]] || usage_error '--api is required'
(( ! run_site )) || [[ -n $site ]] || usage_error '--site is required'
origin=${origin:-$site}
(( ! run_api )) || [[ -n $origin ]] || usage_error '--origin or --site is required for API checks'

for endpoint in "$api" "$site" "$origin"; do
    [[ -n $endpoint ]] || continue
    [[ $endpoint =~ ^https?://[^[:space:]]+$ ]] || usage_error 'origins must be HTTP or HTTPS URLs'
done
api=${api%/}
site=${site%/}
origin=${origin%/}

tmp=$(mktemp -d)
cleanup() {
    rm -rf -- "$tmp"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

headers=$tmp/headers
body=$tmp/body
checks=0
failed=0
http_status=000

pass() {
    checks=$((checks + 1))
    printf 'ok   %s\n' "$1"
}

fail() {
    checks=$((checks + 1))
    failed=$((failed + 1))
    printf 'FAIL %s: %s\n' "$1" "$2"
}

check_equal() {
    local name=$1 expected=$2 seen=$3
    if [[ $seen == "$expected" ]]; then
        pass "$name"
    else
        fail "$name" "expected $expected; seen ${seen:-empty}"
    fi
}

check_match() {
    local name=$1 text=$2 pattern=$3 expectation=$4
    if [[ $text =~ $pattern ]]; then
        pass "$name"
    else
        # Bodies and cookie values must never appear in diagnostics.
        fail "$name" "expected $expectation; seen missing or nonmatching value"
    fi
}

request() {
    local method=$1 url=$2
    shift 2
    : > "$headers"
    : > "$body"
    if http_status=$(curl --silent --show-error --max-time "$timeout" \
        --request "$method" --dump-header "$headers" --output "$body" \
        --write-out '%{http_code}' "$@" "$url" 2> "$tmp/curl-error"); then
        :
    else
        http_status=000
    fi
    tr -d '\r' < "$headers" > "$tmp/headers-clean"
    mv -- "$tmp/headers-clean" "$headers"
}

header() {
    local wanted=$1
    awk -v wanted="$wanted" '
        {
            colon = index($0, ":")
            if (colon && tolower(substr($0, 1, colon - 1)) == tolower(wanted)) {
                value = substr($0, colon + 1)
                sub(/^[ \t]+/, "", value)
                sub(/[ \t]+$/, "", value)
                if (found++) printf "\n"
                printf "%s", value
            }
        }
        END { if (found) printf "\n" }
    ' "$headers"
}

lower_header() {
    header "$1" | tr '[:upper:]' '[:lower:]'
}

cookie_header() {
    local wanted=$1
    header set-cookie | awk -v wanted="$wanted" '
        index($0, wanted "=") == 1 { print; exit }
    '
}

check_cookie() {
    local name=$1 cookie=$2 path=$3
    local lower path_re
    lower=$(printf '%s' "$cookie" | tr '[:upper:]' '[:lower:]')
    check_match "session $name present" "$cookie" \
        "^$name=[^;[:space:]]+" "a nonempty $name cookie"
    path_re="(^|;)[[:space:]]*path=$path([[:space:]]*;|[[:space:]]*$)"
    check_match "session $name Path" "$lower" "$path_re" "Path=$path"
    check_match "session $name Secure" "$lower" \
        '(^|;)[[:space:]]*secure([[:space:]]*;|[[:space:]]*$)' 'Secure attribute'
    check_match "session $name HttpOnly" "$lower" \
        '(^|;)[[:space:]]*httponly([[:space:]]*;|[[:space:]]*$)' 'HttpOnly attribute'
    check_match "session $name SameSite" "$lower" \
        '(^|;)[[:space:]]*samesite=strict([[:space:]]*;|[[:space:]]*$)' 'SameSite=Strict'
    if [[ -n $cookie && ! $lower =~ (^|;)[[:space:]]*domain[[:space:]]*= ]]; then
        pass "session $name no Domain"
    else
        fail "session $name no Domain" 'expected no Domain attribute; seen Domain attribute or missing cookie'
    fi
}

check_redirect() {
    local name=$1 https_origin=$2
    request GET "http://${https_origin#https://}/"
    if [[ $http_status == 301 || $http_status == 308 ]]; then
        pass "$name status"
    else
        fail "$name status" "expected 301 or 308; seen $http_status"
    fi
    check_match "$name Location" "$(header location)" '^https://' 'Location starting with https://'
}

role_pattern='"role"[[:space:]]*:[[:space:]]*"guest_company"'
if (( run_api )); then
    request GET "$api/v1/public/status"
    check_equal 'API status HTTP' 200 "$http_status"
    check_match 'API status cache-control' "$(lower_header cache-control)" \
        '(^|[[:space:],])no-store([[:space:],]|$)' 'no-store'
    check_match 'API status body' "$(cat "$body")" \
        '"status"[[:space:]]*:[[:space:]]*"ok"' '"status":"ok"'

    request POST "$api/v1/session" \
        --header "Origin: $origin" --header 'X-Requested-With: vitrin'
    creation_status=$http_status
    creation_body=$(cat "$body")
    access_cookie=$(cookie_header __Host-vitrin_at)
    refresh_cookie=$(cookie_header __Secure-vitrin_rt)

    check_equal 'session creation HTTP' 201 "$creation_status"
    check_match 'session creation role' "$creation_body" "$role_pattern" 'role guest_company'
    check_match 'session creation cache-control' "$(lower_header cache-control)" \
        '(^|[[:space:],])no-store([[:space:],]|$)' 'no-store'
    check_match 'session creation trace ID' "$(header x-trace-id)" \
        '^[0-9a-fA-F]{32}$' '32 hex characters'
    check_cookie __Host-vitrin_at "$access_cookie" /
    check_cookie __Secure-vitrin_rt "$refresh_cookie" /v1/session/refresh

    token=${access_cookie#*=}
    token=${token%%;*}
    session_ready=0
    if [[ $creation_status == 201 && $creation_body =~ $role_pattern &&
        $access_cookie == __Host-vitrin_at=* && -n $token &&
        ! $token =~ [[:space:]] ]]; then
        session_ready=1
    fi

    if (( session_ready )); then
        request GET "$api/v1/session" --header "Cookie: __Host-vitrin_at=$token"
        check_equal 'session read HTTP' 200 "$http_status"
        check_match 'session read role' "$(cat "$body")" "$role_pattern" 'role guest_company'
        check_match 'session read Server-Timing' "$(lower_header server-timing)" \
            'auth;dur=' 'auth;dur='

        request GET "$api/v1/session" --header "Cookie: __Host-vitrin_at=${token}x"
        check_equal 'tampered token HTTP' 401 "$http_status"
        check_match 'tampered token content-type' "$(lower_header content-type)" \
            '^application/problem\+json([[:space:]]*;|[[:space:]]*$)' 'application/problem+json'
        check_match 'tampered token deniedBy' "$(cat "$body")" \
            '"deniedBy"[[:space:]]*:[[:space:]]*"gateway"' '"deniedBy":"gateway"'
    else
        for name in \
            'session read HTTP' \
            'session read role' \
            'session read Server-Timing' \
            'tampered token HTTP' \
            'tampered token content-type' \
            'tampered token deniedBy'; do
            fail "$name" 'skipped, no session'
        done
    fi
    unset token access_cookie refresh_cookie creation_body

    request POST "$api/v1/session"
    check_equal 'missing origin headers HTTP' 403 "$http_status"

    request GET "$api/internal/jwks"
    check_equal 'internal JWKS hidden' 404 "$http_status"

    request GET "$api/actuator/health"
    check_equal 'actuator health hidden' 404 "$http_status"

    if [[ $api == https://* ]]; then
        check_redirect 'API HTTP to HTTPS' "$api"
    fi
fi

if (( run_site )); then
    request GET "$site/"
    check_equal 'site / HTTP' 200 "$http_status"
    check_match 'site / content-type' "$(lower_header content-type)" \
        '^text/html([[:space:]]*;|[[:space:]]*$)' 'text/html'
    check_match 'site / language' "$(cat "$body")" '<html lang="tr"' '<html lang="tr"'
    csp=$(lower_header content-security-policy)
    check_match 'site CSP default-src' "$csp" \
        "(^|;)[[:space:]]*default-src[[:space:]]+'none'([[:space:]]*;|[[:space:]]*$)" \
        "default-src 'none'"
    check_match 'site CSP frame-ancestors' "$csp" \
        "(^|;)[[:space:]]*frame-ancestors[[:space:]]+'none'([[:space:]]*;|[[:space:]]*$)" \
        "frame-ancestors 'none'"
    check_match 'site HSTS' "$(lower_header strict-transport-security)" \
        '(^|;)[[:space:]]*max-age=31536000([[:space:]]*;|[[:space:]]*$)' 'max-age=31536000'
    check_equal 'site x-content-type-options' nosniff "$(lower_header x-content-type-options)"
    check_equal 'site referrer-policy' strict-origin-when-cross-origin "$(lower_header referrer-policy)"
    check_equal 'site x-frame-options' deny "$(lower_header x-frame-options)"

    request GET "$site/en/"
    check_equal 'site /en/ HTTP' 200 "$http_status"
    check_match 'site /en/ language' "$(cat "$body")" '<html lang="en"' '<html lang="en"'

    for pdf in /cv.pdf /en/cv.pdf; do
        request GET "$site$pdf"
        check_equal "site $pdf HTTP" 200 "$http_status"
        check_match "site $pdf content-type" "$(lower_header content-type)" \
            '^application/pdf([[:space:]]*;|[[:space:]]*$)' 'application/pdf'
        if head -c 5 "$body" | grep -q '^%PDF-'; then
            pass "site $pdf signature"
        else
            fail "site $pdf signature" 'expected leading %PDF-; seen missing or different signature'
        fi
    done

    request GET "$site/cv/"
    check_match 'site /cv/ robots' "$(lower_header x-robots-tag)" \
        '(^|[[:space:],])noindex([[:space:],]|$)' 'noindex'

    if [[ $site == https://* ]]; then
        check_redirect 'site HTTP to HTTPS' "$site"
    fi
fi

printf 'smoke: %s checks, %s failed\n' "$checks" "$failed"
if (( failed )); then
    exit 1
fi
