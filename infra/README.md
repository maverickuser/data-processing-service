# Infrastructure

Terraform for the production environment in `ap-south-1`, pinned to Terraform `~> 1.16.4` and AWS provider `6.61.0` with committed lock files. Nothing here has been applied; see [implementation status](../docs/plans/implementation-status.md).

| Directory | Owns |
|---|---|
| `bootstrap/` | The Lambda package bucket `data-processing-service-artifacts` |
| `modules/network/` | Nothing: a read-only view of the shared network state for this service |

The network (VPC, subnets, NAT gateway, endpoints, Lambda security groups) is owned by [cloud-platform-network](https://github.com/maverickuser/cloud-platform-network). Never define network resources here.

## State

The deploy workflow creates the state bucket `data-processing-service-terraform-state` (versioned, encrypted, no public access) before any Terraform runs, because a root cannot keep its state in a bucket it creates. State is locked with an S3 lock file. Each root has its own key:

```sh
terraform -chdir=infra/bootstrap init \
  -backend-config=bucket=data-processing-service-terraform-state \
  -backend-config=key=bootstrap/terraform.tfstate \
  -backend-config=region=ap-south-1 \
  -backend-config=use_lockfile=true
```

## Shared network module

`modules/network` reads `cloud-platform-network-terraform-state` / `network/terraform.tfstate` and outputs `vpc_id`, `vpc_cidr`, `private_subnet_ids` (in zone order), and `lambda_security_group_id` (the `processing` entry). The plan fails when there are fewer than two private subnets, when the network has no `processing` Lambda security group, or when a subnet or the group is outside the shared VPC or a subnet assigns public addresses.

## Local checks

`make check-infra TERRAFORM=/path/to/terraform` runs `fmt -check`, then `init -backend=false`, `validate`, and the mocked `terraform test` suite in every directory. It downloads the provider but needs no AWS credentials. CI runs it as stage 7.
