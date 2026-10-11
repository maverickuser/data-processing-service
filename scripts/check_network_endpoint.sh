#!/usr/bin/env bash
# Refuse migration until the applied shared VPC has a private Secrets Manager path.
set -euo pipefail

: "${AWS_REGION:?AWS_REGION is required}"
# The same state infra/modules/network reads (its variable defaults), so the endpoint checked here is in
# the VPC Terraform places the functions in. Change both together.
network_bucket="${NETWORK_STATE_BUCKET:-cloud-platform-network-terraform-state}"
network_key="${NETWORK_STATE_KEY:-network/terraform.tfstate}"
state_file="$(mktemp)"
endpoint_file="$(mktemp)"
trap 'rm -f "$state_file" "$endpoint_file"' EXIT

aws s3api get-object --bucket "$network_bucket" --key "$network_key" "$state_file" >/dev/null
vpc_id="$(jq -er '.outputs.vpc_id.value | select(type == "string" and startswith("vpc-"))' "$state_file")"
endpoint_id="$(jq -er '.outputs.interface_endpoint_ids.value.secretsmanager | select(type == "string" and startswith("vpce-"))' "$state_file")"
# The migration needs only the endpoint. A NAT gateway added for another consumer is not a reason to refuse.
if ! jq -e '.outputs.nat_gateway_ids_by_az.value // {} | length == 0' "$state_file" >/dev/null; then
  echo "::warning::The shared network has a NAT gateway; the migration still uses the Secrets Manager endpoint."
fi

aws ec2 describe-vpc-endpoints --vpc-endpoint-ids "$endpoint_id" >"$endpoint_file"
jq -e --arg vpc "$vpc_id" --arg region "$AWS_REGION" --arg id "$endpoint_id" '
  .VpcEndpoints | length == 1 and
  .[0].VpcEndpointId == $id and
  .[0].VpcId == $vpc and
  .[0].VpcEndpointType == "Interface" and
  .[0].ServiceName == ("com.amazonaws." + $region + ".secretsmanager") and
  .[0].PrivateDnsEnabled == true and
  .[0].State == "available"
' "$endpoint_file" >/dev/null

echo "Secrets Manager endpoint $endpoint_id is available with private DNS in $vpc_id."
