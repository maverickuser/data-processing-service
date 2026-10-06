module "network" {
  source = "../modules/network"
}

data "terraform_remote_state" "persistent" {
  backend = "s3"
  config = {
    bucket = var.persistent_state_bucket
    key    = var.persistent_state_key
    region = var.aws_region
  }
}

data "aws_caller_identity" "current" {}

locals {
  name       = "data-processing-service"
  account_id = data.aws_caller_identity.current.account_id
  persistent = data.terraform_remote_state.persistent.outputs
  domain     = "processing.kagent.app"

  security_details_queue_arn = "arn:aws:sqs:${var.aws_region}:${local.account_id}:${var.security_details_queue_name}"
  security_details_queue_url = "https://sqs.${var.aws_region}.amazonaws.com/${local.account_id}/${var.security_details_queue_name}"
}
