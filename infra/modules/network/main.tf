# Reads the shared network owned by maverickuser/cloud-platform-network and exposes only what
# this service needs. It defines no network resources: the VPC, subnets, endpoints, and Lambda
# security groups belong to that repository. The network has no NAT gateway, so this service's
# Lambdas reach only the VPC, S3, and SQS.

terraform {
  required_version = "~> 1.16.4"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.61.0"
    }
  }
}

variable "state_bucket" {
  type        = string
  default     = "cloud-platform-network-terraform-state"
  description = "Bucket holding the shared network's Terraform state."
}

variable "state_key" {
  type        = string
  default     = "network/terraform.tfstate"
  description = "Key of the shared network's Terraform state."
}

variable "state_region" {
  type    = string
  default = "ap-south-1"
}

variable "consumer" {
  type        = string
  default     = "processing"
  description = "This service's entry in the network's lambda_security_group_ids."
}

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_key
    region = var.state_region
  }
}

locals {
  network         = data.terraform_remote_state.network.outputs
  subnet_ids      = local.network.private_subnet_ids_by_az
  security_groups = local.network.lambda_security_group_ids
}

data "aws_subnet" "private" {
  for_each = local.subnet_ids
  id       = each.value

  lifecycle {
    postcondition {
      condition     = self.vpc_id == local.network.vpc_id
      error_message = "Every private subnet must be in the shared VPC."
    }
    postcondition {
      condition     = self.availability_zone == each.key
      error_message = "Every private subnet must be in the zone it is listed under."
    }
    postcondition {
      condition     = !self.map_public_ip_on_launch
      error_message = "A private subnet must not assign public addresses."
    }
  }
}

data "aws_security_group" "lambda" {
  id = lookup(local.security_groups, var.consumer, "")

  lifecycle {
    precondition {
      condition     = contains(keys(local.security_groups), var.consumer)
      error_message = "The shared network has no Lambda security group for this service; add it to lambda_security_groups in cloud-platform-network."
    }
    postcondition {
      condition     = self.vpc_id == local.network.vpc_id
      error_message = "This service's Lambda security group must belong to the shared VPC."
    }
  }
}

output "vpc_id" {
  value = local.network.vpc_id
}

output "vpc_cidr" {
  value = local.network.vpc_cidr
}

output "private_subnet_ids" {
  description = "The shared private subnets, ordered by zone."
  value       = [for zone in sort(keys(data.aws_subnet.private)) : data.aws_subnet.private[zone].id]

  precondition {
    condition     = length(local.subnet_ids) >= 2
    error_message = "The shared network must have private subnets in at least two zones; RDS subnet groups need two."
  }
}

output "lambda_security_group_id" {
  value = data.aws_security_group.lambda.id
}

output "database_zone" {
  description = "The zone of the network's interface endpoints, where the single-zone database goes: losing that zone then takes down both together, never only the endpoints."
  value       = try(local.network.interface_endpoint_zones[0], "")

  precondition {
    condition     = contains(keys(local.subnet_ids), try(local.network.interface_endpoint_zones[0], ""))
    error_message = "The shared network must report its interface_endpoint_zones, the first in a private subnet zone; use cloud-platform-network v1 or later."
  }
}
