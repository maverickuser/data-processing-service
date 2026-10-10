#!/usr/bin/env bash
# Invoke the new migration version synchronously, then move live aliases to published versions.
set -euo pipefail

outputs_file="${1:?Pass terraform output -json from infra/application}"
invocation_file="$(mktemp)"
response_file="$(mktemp)"
alias_file="$(mktemp)"
trap 'rm -f "$invocation_file" "$response_file" "$alias_file"' EXIT

jq -e '
  .function_names.value as $names |
  .published_versions.value as $versions |
  .alias_name.value == "live" and
  ($names | type == "object" and has("migration")) and
  ($versions | type == "object" and (keys == ($names | keys))) and
  all($versions[]; type == "string" and test("^[0-9]+$"))
' "$outputs_file" >/dev/null

migration_name="$(jq -er '.function_names.value.migration' "$outputs_file")"
migration_version="$(jq -er '.published_versions.value.migration' "$outputs_file")"
aws lambda invoke \
  --function-name "$migration_name" \
  --qualifier "$migration_version" \
  --invocation-type RequestResponse \
  --cli-binary-format raw-in-base64-out \
  --payload '{}' \
  "$response_file" >"$invocation_file"

jq -e '.StatusCode == 200 and (has("FunctionError") | not)' "$invocation_file" >/dev/null
jq -e 'type == "string" and startswith("applied migrations=")' "$response_file" >/dev/null
echo "Migration version $migration_version succeeded."

jq -r '.function_names.value | keys[]' "$outputs_file" | while IFS= read -r name; do
  function_name="$(jq -er --arg name "$name" '.function_names.value[$name]' "$outputs_file")"
  version="$(jq -er --arg name "$name" '.published_versions.value[$name]' "$outputs_file")"
  aws lambda get-alias --function-name "$function_name" --name live >"$alias_file"
  jq -e '(.RoutingConfig.AdditionalVersionWeights // {}) | length == 0' "$alias_file" >/dev/null
  current="$(jq -er '.FunctionVersion' "$alias_file")"
  if [ "$current" = "$version" ]; then
    echo "$name already points to version $version."
    continue
  fi
  revision="$(jq -er '.RevisionId' "$alias_file")"
  aws lambda update-alias \
    --function-name "$function_name" \
    --name live \
    --function-version "$version" \
    --revision-id "$revision" >/dev/null
  echo "$name now points to version $version."
done
