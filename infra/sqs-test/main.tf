terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region  = "eu-west-1"
  profile = "careplan-dev"
}

resource "aws_sqs_queue" "test_dlq" {
  name = "careplan-day15-test-dlq"

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-dlq"
  }
}

resource "aws_sqs_queue" "test_queue" {
  name = "careplan-day15-test-queue"

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.test_dlq.arn
    maxReceiveCount     = 3
  })

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-test"
  }
}

resource "aws_sqs_queue_redrive_allow_policy" "test_dlq_allow_policy" {
  queue_url = aws_sqs_queue.test_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.test_queue.arn]
  })
}