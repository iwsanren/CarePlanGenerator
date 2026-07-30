provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile
}

locals {
  lambda_zip_path = abspath("${path.module}/${var.lambda_zip_path}")
  lambda_zip_hash = filebase64sha256(local.lambda_zip_path)
  lambda_zip_key  = "lambda/${filemd5(local.lambda_zip_path)}.zip"

  common_tags = {
    Project = "careplan-generator"
    Day     = "15"
    Managed = "terraform"
  }

  lambda_functions = {
    create_order = {
      function_name = "${var.project_name}-create-order"
      handler       = "com.page24.backend.aws.lambda.CreateOrderHandler::handleRequest"
      description   = "Creates an order and sends a CarePlan generation task to SQS."
      timeout       = 30
      memory_size   = 1024
    }

    generate_careplan = {
      function_name = "${var.project_name}-generate-careplan"
      handler       = "com.page24.backend.aws.lambda.GenerateCarePlanHandler::handleRequest"
      description   = "Generates a CarePlan from SQS messages and updates RDS."
      timeout       = 120
      memory_size   = 1024
    }

    get_order = {
      function_name = "${var.project_name}-get-order"
      handler       = "com.page24.backend.aws.lambda.GetOrderHandler::handleRequest"
      description   = "Gets an order and CarePlan status from RDS."
      timeout       = 30
      memory_size   = 1024
    }
  }
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "lambda_artifacts" {
  bucket = "${var.project_name}-lambda-artifacts-${data.aws_caller_identity.current.account_id}-${var.aws_region}"

  force_destroy = true

  tags = merge(local.common_tags, {
    Name    = "${var.project_name}-lambda-artifacts"
    Purpose = "lambda-deployment-package"
  })
}

resource "aws_s3_bucket_public_access_block" "lambda_artifacts" {
  bucket = aws_s3_bucket.lambda_artifacts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_object" "lambda_zip" {
  bucket      = aws_s3_bucket.lambda_artifacts.id
  key         = local.lambda_zip_key
  source      = local.lambda_zip_path
  source_hash = local.lambda_zip_hash

  tags = merge(local.common_tags, {
    Name = "careplan-lambda-zip"
  })
}

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "lambda_execution_role" {
  name               = "${var.project_name}-lambda-execution-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "lambda_basic_logs" {
  role       = aws_iam_role.lambda_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "careplan" {
  for_each = local.lambda_functions

  function_name    = each.value.function_name
  description      = each.value.description
  s3_bucket        = aws_s3_bucket.lambda_artifacts.id
  s3_key           = aws_s3_object.lambda_zip.key
  source_code_hash = local.lambda_zip_hash
  role             = aws_iam_role.lambda_execution_role.arn
  handler          = each.value.handler
  runtime          = "java17"
  architectures    = ["x86_64"]
  memory_size      = each.value.memory_size
  timeout          = each.value.timeout

  environment {
    variables = merge(
      {
        DB_PASSWORD           = var.db_password
        DB_USERNAME           = var.db_username
        LLM_MOCK_ENABLED      = "true"
        SPRING_DATASOURCE_URL = "jdbc:postgresql://${aws_db_instance.careplan.address}:${aws_db_instance.careplan.port}/${var.db_name}"
      },
      each.key == "create_order" ? {
        SQS_QUEUE_URL = aws_sqs_queue.careplan.url
      } : {}
    )
  }

  vpc_config {
    subnet_ids         = data.aws_subnets.default.ids
    security_group_ids = [aws_security_group.lambda.id]
  }

  tags = merge(local.common_tags, {
    Name = each.value.function_name
  })

  depends_on = [
    aws_iam_role_policy_attachment.lambda_basic_logs,
    aws_iam_role_policy_attachment.lambda_vpc_access,
    aws_s3_object.lambda_zip
  ]
}
