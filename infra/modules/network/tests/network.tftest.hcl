# Mocked: no AWS credentials and no real network state.
mock_provider "aws" {}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id   = "vpc-0123456789abcdef0"
      vpc_cidr = "10.20.0.0/16"
      private_subnet_ids_by_az = {
        "ap-south-1a" = "subnet-0aaaaaaaaaaaaaaaa"
        "ap-south-1b" = "subnet-0bbbbbbbbbbbbbbbb"
      }
      lambda_security_group_ids = {
        fetch      = "sg-0fffffffffffffff0"
        processing = "sg-0123456789abcdef0"
      }
    }
  }
}

override_data {
  target = data.aws_subnet.private["ap-south-1a"]
  values = { id = "subnet-0aaaaaaaaaaaaaaaa", vpc_id = "vpc-0123456789abcdef0", availability_zone = "ap-south-1a", map_public_ip_on_launch = false }
}

override_data {
  target = data.aws_subnet.private["ap-south-1b"]
  values = { id = "subnet-0bbbbbbbbbbbbbbbb", vpc_id = "vpc-0123456789abcdef0", availability_zone = "ap-south-1b", map_public_ip_on_launch = false }
}

override_data {
  target = data.aws_security_group.lambda
  values = { id = "sg-0123456789abcdef0", vpc_id = "vpc-0123456789abcdef0" }
}

run "exposes_the_processing_view_of_the_shared_network" {
  command = plan

  assert {
    condition     = output.vpc_id == "vpc-0123456789abcdef0" && output.vpc_cidr == "10.20.0.0/16"
    error_message = "The VPC must come from the shared network state."
  }

  assert {
    condition     = output.private_subnet_ids == ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
    error_message = "Private subnets must be listed in zone order."
  }

  assert {
    condition     = output.lambda_security_group_id == "sg-0123456789abcdef0"
    error_message = "The processing Lambda security group must be selected, not the fetch one."
  }
}

run "refuses_a_network_with_one_private_subnet" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                    = "vpc-0123456789abcdef0"
        vpc_cidr                  = "10.20.0.0/16"
        private_subnet_ids_by_az  = { "ap-south-1a" = "subnet-0aaaaaaaaaaaaaaaa" }
        lambda_security_group_ids = { processing = "sg-0123456789abcdef0" }
      }
    }
  }

  expect_failures = [output.private_subnet_ids]
}

run "refuses_a_network_without_a_processing_security_group" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id   = "vpc-0123456789abcdef0"
        vpc_cidr = "10.20.0.0/16"
        private_subnet_ids_by_az = {
          "ap-south-1a" = "subnet-0aaaaaaaaaaaaaaaa"
          "ap-south-1b" = "subnet-0bbbbbbbbbbbbbbbb"
        }
        lambda_security_group_ids = { fetch = "sg-0fffffffffffffff0" }
      }
    }
  }

  expect_failures = [data.aws_security_group.lambda]
}

run "refuses_a_subnet_outside_the_shared_vpc" {
  command = plan

  override_data {
    target = data.aws_subnet.private["ap-south-1b"]
    values = { id = "subnet-0bbbbbbbbbbbbbbbb", vpc_id = "vpc-0999999999999999f", availability_zone = "ap-south-1b", map_public_ip_on_launch = false }
  }

  expect_failures = [data.aws_subnet.private]
}

run "refuses_a_subnet_listed_under_the_wrong_zone" {
  command = plan

  override_data {
    target = data.aws_subnet.private["ap-south-1b"]
    values = { id = "subnet-0bbbbbbbbbbbbbbbb", vpc_id = "vpc-0123456789abcdef0", availability_zone = "ap-south-1a", map_public_ip_on_launch = false }
  }

  expect_failures = [data.aws_subnet.private]
}

run "refuses_a_public_subnet" {
  command = plan

  override_data {
    target = data.aws_subnet.private["ap-south-1a"]
    values = { id = "subnet-0aaaaaaaaaaaaaaaa", vpc_id = "vpc-0123456789abcdef0", availability_zone = "ap-south-1a", map_public_ip_on_launch = true }
  }

  expect_failures = [data.aws_subnet.private]
}

run "refuses_a_security_group_outside_the_shared_vpc" {
  command = plan

  override_data {
    target = data.aws_security_group.lambda
    values = { id = "sg-0123456789abcdef0", vpc_id = "vpc-0999999999999999f" }
  }

  expect_failures = [data.aws_security_group.lambda]
}
