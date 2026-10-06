#!/usr/bin/env bash
# shellcheck disable=SC2329  # step_* functions are dispatched by name at the bottom.
set -euo pipefail

TRIVY_IMAGE="aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa"
GITLEAKS_IMAGE="zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"
ACTIONLINT_IMAGE="rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667"
SHELLCHECK_IMAGE="koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d"

ALL_STEPS=(lint secrets backend site images compose)

usage() {
  printf 'Usage: deploy/ci-local.sh [step ...]\n'
  printf '       deploy/ci-local.sh --list\n'
  printf 'Steps: %s\n' "${ALL_STEPS[*]}"
}

if [[ $# -eq 1 && "$1" == --list ]]; then
  printf '%s\n' "${ALL_STEPS[@]}"
  exit 0
fi

if [[ $# -eq 0 ]]; then
  steps=("${ALL_STEPS[@]}")
else
  steps=("$@")
fi

for step in "${steps[@]}"; do
  case "$step" in
    lint|secrets|backend|site|images|compose) ;;
    *)
      printf 'Unknown step: %s\n' "$step" >&2
      usage >&2
      exit 2
      ;;
  esac
done

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

if [[ "$(git rev-parse --is-inside-work-tree 2>/dev/null || true)" != true ]]; then
  printf 'Repository root is not a Git work tree: %s\n' "$ROOT" >&2
  exit 1
fi

DOCKER_ROOT="$ROOT"
USE_CYGPATH=0
if command -v cygpath >/dev/null 2>&1; then
  DOCKER_ROOT="$(cygpath -m "$ROOT")"
  USE_CYGPATH=1
fi

docker_call() {
  # Git Bash must not rewrite container paths.
  if (( USE_CYGPATH )); then
    MSYS_NO_PATHCONV=1 docker "$@"
  else
    docker "$@"
  fi
}

trivy_fs() {
  docker_call run --rm \
    -v "$DOCKER_ROOT:/repo" -w /repo \
    -v vitrin-trivy-cache:/root/.cache/ \
    "$TRIVY_IMAGE" fs --exit-code 1 \
    --severity CRITICAL,HIGH --scanners vuln /repo
}

scan_image() {
  local image="$1"
  docker_call run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$DOCKER_ROOT:/repo" -w /repo \
    -v vitrin-trivy-cache:/root/.cache/ \
    "$TRIVY_IMAGE" image --exit-code 1 \
    --severity CRITICAL,HIGH \
    --ignorefile /repo/.trivyignore.yaml \
    --scanners vuln "$image"
}

write_sbom() {
  local image="$1"
  local name="$2"
  docker_call run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$DOCKER_ROOT:/repo" -w /repo \
    -v vitrin-trivy-cache:/root/.cache/ \
    "$TRIVY_IMAGE" image --format cyclonedx \
    --output "/repo/build/sbom/$name.cdx.json" "$image"
}

step_lint() {
  local skipped=0
  local workflow=""
  local file
  local -a files=()

  if [[ -d .github/workflows ]]; then
    workflow="$(find .github/workflows -type f -print -quit)"
  fi
  if [[ -n "$workflow" ]]; then
    docker_call run --rm \
      -v "$DOCKER_ROOT:/repo" -w /repo \
      "$ACTIONLINT_IMAGE"
  else
    printf 'NOTICE: actionlint skipped; no workflow files exist.\n'
    skipped=1
  fi

  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(find deploy -type f -name '*.sh' -print0)
  if [[ -f .githooks/pre-commit ]]; then
    files+=(".githooks/pre-commit")
  fi
  if (( ${#files[@]} == 0 )); then
    printf 'No shell scripts found.\n' >&2
    return 1
  fi

  docker_call run --rm \
    -v "$DOCKER_ROOT:/repo" -w /repo \
    "$SHELLCHECK_IMAGE" "${files[@]}"

  if (( skipped )); then
    return 3
  fi
}

step_secrets() {
  docker_call run --rm \
    -v "$DOCKER_ROOT:/repo" -w /repo \
    "$GITLEAKS_IMAGE" git --redact --no-banner \
    --log-opts="--all" --config /repo/.gitleaks.toml /repo
}

step_backend() {
  ./gradlew build --no-build-cache --rerun-tasks --no-configuration-cache
}

step_site() {
  local skipped=0

  npm ci
  (
    cd content/tool
    npm run typecheck
    npm test
    if [[ -f ../profile.yaml ]]; then
      npm run check
    fi
  )
  (
    cd site
    npx playwright install chromium
    npm run typecheck
    npm test
  )

  # The npm workspace hoists the binary to the repository root.
  if [[ -f site/node_modules/.bin/lhci || -f node_modules/.bin/lhci ]]; then
    (
      cd site
      npx --no-install lhci autorun
    )
  else
    printf 'NOTICE: Lighthouse skipped; lhci is not installed (run npm ci).\n'
    skipped=1
  fi

  trivy_fs

  if (( skipped )); then
    return 3
  fi
}

step_images() {
  local name
  local image
  local revision
  local short_sha
  local source=""
  local skipped=0

  for name in gateway core search; do
    if [[ ! -f "$name/build/libs/$name.jar" ]]; then
      printf 'Missing %s/build/libs/%s.jar; run deploy/ci-local.sh backend first.\n' \
        "$name" "$name" >&2
      return 1
    fi
  done

  revision="$(git rev-parse HEAD)"
  short_sha="$(git rev-parse --short HEAD)"
  source="$(git config --get remote.origin.url || true)"
  mkdir -p build/sbom

  for name in gateway core search migrate; do
    image="vitrin-$name:$short_sha"
    if [[ "$name" == migrate ]]; then
      if [[ ! -f deploy/docker/migrate.Dockerfile ]]; then
        printf 'NOTICE: migrate image skipped; deploy/docker/migrate.Dockerfile is missing.\n'
        skipped=1
        continue
      fi
      docker_call build \
        -f deploy/docker/migrate.Dockerfile \
        --build-arg "REVISION=$revision" \
        --build-arg "SOURCE=$source" \
        -t "$image" deploy/postgres
    else
      docker_call build \
        -f deploy/docker/service.Dockerfile \
        --build-arg "SERVICE=$name" \
        --build-arg "REVISION=$revision" \
        --build-arg "SOURCE=$source" \
        -t "$image" "$name/build/libs"
    fi
    scan_image "$image"
    write_sbom "$image" "$name"
  done

  if (( skipped )); then
    return 3
  fi
}

step_compose() {
  if [[ ! -f deploy/prod/check-compose.sh ]]; then
    printf 'NOTICE: Compose check skipped; deploy/prod/check-compose.sh is missing.\n'
    return 3
  fi

  # No path protection here: the checker passes host paths to Compose, and Git Bash has to
  # convert those (it mounts nothing into a container).
  bash deploy/prod/check-compose.sh
}

results=()
durations=()
failed=0

for step in "${steps[@]}"; do
  printf '\n=== %s ===\n' "$step"
  started=$SECONDS

  # A plain subshell preserves errexit inside each check.
  set +e
  (
    set -euo pipefail
    "step_$step"
  )
  status=$?
  set -e

  duration=$((SECONDS - started))
  case "$status" in
    0) result=ok ;;
    3) result=skipped ;;
    *)
      result=FAILED
      failed=1
      ;;
  esac

  results+=("$result")
  durations+=("$duration")
  printf '%s: %s (%s seconds)\n' "$step" "$result" "$duration"
done

printf '\n%-12s %-10s %s\n' STEP RESULT SECONDS
for (( index=0; index<${#steps[@]}; index++ )); do
  printf '%-12s %-10s %s\n' \
    "${steps[$index]}" "${results[$index]}" "${durations[$index]}"
done

exit "$failed"
