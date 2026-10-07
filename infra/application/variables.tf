variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

variable "hosted_zone_id" {
  type        = string
  description = "The existing public kagent.app zone; this root manages only the processing.kagent.app records."
}

variable "deployment_commit" {
  type        = string
  description = "The commit whose package is deployed, read from releases/{commit}/ in the package bucket."
  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.deployment_commit))
    error_message = "deployment_commit must be a full lowercase 40-character commit SHA."
  }
}

variable "package_sha256" {
  type        = string
  description = "Base64-encoded SHA-256 of the package, so a changed object is never deployed silently."
  validation {
    condition     = can(regex("^[A-Za-z0-9+/]{43}=$", var.package_sha256))
    error_message = "package_sha256 must be a base64-encoded SHA-256 digest."
  }
}

variable "package_bucket" {
  type    = string
  default = "data-processing-service-artifacts"
}

variable "persistent_state_bucket" {
  type    = string
  default = "data-processing-service-terraform-state"
}

variable "persistent_state_key" {
  type    = string
  default = "persistent/terraform.tfstate"
}

# Owned by data-fetch-service. Plain values, not its remote state, so this service can be applied
# before the fetch service, which reads this root's outputs.
variable "source_bucket" {
  type        = string
  default     = "data-fetch-service-artifacts"
  description = "The fetch service's artifact bucket, the only bucket the worker reads."
}

variable "source_key_prefix" {
  type        = string
  default     = "runs/"
  description = "The only key prefix the worker may read in the source bucket."
  validation {
    condition     = can(regex("^[a-z0-9-]+/$", var.source_key_prefix))
    error_message = "source_key_prefix must be one non-empty path segment ending in a slash."
  }
}

variable "security_details_queue_name" {
  type        = string
  default     = "data-fetch-service-ingress"
  description = "The fetch service's queue for security-details events, in this account and region."
}

variable "event_source" {
  type    = string
  default = "urn:bond-platform:structured-file-processing:prod"
}

variable "alert_email" {
  type        = string
  default     = null
  description = "Recipient of alarm notifications; none is subscribed until it is set."
}

# LLD section 23.1. Reserved concurrency needs the account's unreserved concurrency to stay at
# least 100 after these are subtracted.
variable "reserved_concurrency" {
  type = object({
    read_api       = number
    submission_api = number
  })
  default = { read_api = 10, submission_api = 5 }
}

variable "read_throttle" {
  type        = object({ rate_limit = number, burst_limit = number })
  default     = { rate_limit = 20, burst_limit = 40 }
  description = "API Gateway throttling for the read routes, per second."
}

variable "submission_throttle" {
  type    = object({ rate_limit = number, burst_limit = number })
  default = { rate_limit = 5, burst_limit = 10 }
}

variable "read_api_caller_role_arns" {
  type        = list(string)
  default     = []
  description = "IAM roles the read function serves, such as the smoke test role. Each also needs execute-api:Invoke on read_route_arns. Empty serves nobody. A name or path holding a comma is refused, since the function receives the list comma-separated."
  validation {
    condition     = alltrue([for arn in var.read_api_caller_role_arns : can(regex("^arn:aws[a-z-]*:iam::[0-9]{12}:role/([\\w+=.@-]+/)*[\\w+=.@-]{1,64}$", arn))])
    error_message = "read_api_caller_role_arns must hold only IAM role ARNs."
  }
}
