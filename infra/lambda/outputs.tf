output "lambda_function_names" {
  description = "Names of the Lambda functions created by this Terraform module."
  value       = { for key, fn in aws_lambda_function.careplan : key => fn.function_name }
}

output "lambda_function_arns" {
  description = "ARNs of the Lambda functions created by this Terraform module."
  value       = { for key, fn in aws_lambda_function.careplan : key => fn.arn }
}

output "lambda_execution_role_name" {
  description = "IAM role shared by the three Lambda functions."
  value       = aws_iam_role.lambda_execution_role.name
}

output "lambda_artifact_bucket_name" {
  description = "S3 bucket used only for the Java Lambda deployment zip."
  value       = aws_s3_bucket.lambda_artifacts.bucket
}

output "sqs_queue_url" {
  description = "URL of the SQS queue used by create_order and generate_careplan."
  value       = aws_sqs_queue.careplan.url
}

output "sqs_dlq_url" {
  description = "URL of the SQS dead letter queue."
  value       = aws_sqs_queue.careplan_dlq.url
}

output "rds_endpoint" {
  description = "Endpoint of the PostgreSQL RDS instance."
  value       = aws_db_instance.careplan.endpoint
}

output "rds_database_name" {
  description = "Name of the PostgreSQL database."
  value       = aws_db_instance.careplan.db_name
}

output "api_gateway_id" {
  description = "ID of the API Gateway HTTP API."
  value       = aws_apigatewayv2_api.careplan.id
}

output "api_gateway_endpoint" {
  description = "Base endpoint of the API Gateway HTTP API."
  value       = aws_apigatewayv2_api.careplan.api_endpoint
}
