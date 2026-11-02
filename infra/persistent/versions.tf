terraform {
  required_version = "~> 1.16.4"

  # Backend settings come from `terraform init -backend-config`; see infra/README.md.
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.61.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Service = "data-processing-service"
      Root    = "persistent"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

# Set only when the database is created: the deploy workflow passes the final snapshot of a torn-down
# database (scripts/choose_database_source.sh). Null creates an empty database.
variable "restore_snapshot_identifier" {
  type        = string
  default     = null
  description = "Manual DB snapshot to create the database from; ignored once the database exists."
  validation {
    condition     = var.restore_snapshot_identifier == null || can(regex("^[a-z][a-z0-9-]{0,254}$", var.restore_snapshot_identifier))
    error_message = "restore_snapshot_identifier must be a manual DB snapshot identifier."
  }
}

module "network" {
  source = "../modules/network"
}
