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
      Root    = "bootstrap"
    }
  }
}
