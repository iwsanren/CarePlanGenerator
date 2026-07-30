resource "aws_sqs_queue" "careplan_dlq" {
  name = "${var.project_name}-careplan-dlq"

  tags = merge(local.common_tags, {
    Name    = "${var.project_name}-careplan-dlq"
    Purpose = "careplan-dlq"
  })
}

resource "aws_sqs_queue" "careplan" {
  name                       = "${var.project_name}-careplan-queue"
  visibility_timeout_seconds = 180

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.careplan_dlq.arn
    maxReceiveCount     = 3
  })

  tags = merge(local.common_tags, {
    Name    = "${var.project_name}-careplan-queue"
    Purpose = "careplan-work-queue"
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "careplan_dlq" {
  queue_url = aws_sqs_queue.careplan_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.careplan.arn]
  })
}

resource "aws_lambda_event_source_mapping" "generate_careplan_from_sqs" {
  event_source_arn = aws_sqs_queue.careplan.arn
  function_name    = aws_lambda_function.careplan["generate_careplan"].arn

  batch_size              = 10
  function_response_types = ["ReportBatchItemFailures"]

  depends_on = [
    aws_iam_role_policy.lambda_sqs_access
  ]
}
