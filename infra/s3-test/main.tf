terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region  = "eu-west-1"
  profile = "careplan-dev"
}

resource "aws_s3_bucket" "test_bucket" {
  bucket        = "careplan-test-bucket-hehe-eu-west-1-20260728"
  force_destroy = true

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-test"
  }
}

resource "aws_s3_bucket_public_access_block" "test_bucket" {
  bucket = aws_s3_bucket.test_bucket.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
