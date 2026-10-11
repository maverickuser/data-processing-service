#!/usr/bin/env bash
# Smoke cases S-01 and S-02 (testing strategy): the deployed API answers and reaches PostgreSQL.
# Requests are unsigned and need no AWS credentials. Nothing is created or asserted about data.
set -euo pipefail

base_url="${1:?Pass the deployed base URL, such as https://processing.kagent.app}"
base_url="${base_url%/}"
attempts="${SMOKE_ATTEMPTS:-5}"
delay="${SMOKE_RETRY_DELAY_SECONDS:-10}"
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

# Prints "<status> <content type>" and leaves the body in $body_file. A connection failure or a 5xx
# is retried: right after a release, a cold function's first database connection can be slow.
request() {
  local attempt result=""
  for attempt in $(seq 1 "$attempts"); do
    if result="$(curl --silent --show-error --max-time 30 --output "$body_file" \
      --write-out '%{http_code} %{content_type}' "$@")" && [ "${result%% *}" -lt 500 ]; then
      echo "$result"
      return 0
    fi
    echo "Attempt $attempt/$attempts: ${result:-no response}" >&2
    if [ "$attempt" -lt "$attempts" ]; then
      sleep "$delay"
    fi
  done
  echo "${result:-000 }"
}

failures=0

result="$(request "$base_url/v1/securities/INE000000Z06")"
if [ "${result%% *}" = 404 ] && [[ "${result#* }" == application/problem+json* ]] &&
  jq -e '.status == 404 and .code == "NOT_FOUND"' "$body_file" >/dev/null 2>&1; then
  echo "S-01 passed: unsigned read reached the database and answered 404 problem+json."
else
  echo "::error::S-01 failed: GET /v1/securities/INE000000Z06 answered '$result'"
  failures=$((failures + 1))
fi

result="$(request --request POST --header 'Content-Type: application/json' --data '{}' \
  "$base_url/v1/event-ingestions")"
if [ "${result%% *}" = 403 ]; then
  echo "S-02 passed: unsigned submission was rejected with 403."
else
  echo "::error::S-02 failed: unsigned POST /v1/event-ingestions answered '$result'"
  failures=$((failures + 1))
fi

exit "$failures"
