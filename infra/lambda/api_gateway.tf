resource "aws_apigatewayv2_api" "careplan" {
  name          = "${var.project_name}-api"
  protocol_type = "HTTP"

  cors_configuration {
    allow_headers = ["content-type"]
    allow_methods = ["GET", "POST", "OPTIONS"]
    allow_origins = ["*"]
  }

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-api"
  })
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.careplan.id
  name        = "$default"
  auto_deploy = true

  tags = merge(local.common_tags, {
    Name = "${var.project_name}-api-default-stage"
  })
}

resource "aws_apigatewayv2_integration" "create_order" {
  api_id                 = aws_apigatewayv2_api.careplan.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.careplan["create_order"].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_integration" "get_order" {
  api_id                 = aws_apigatewayv2_api.careplan.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.careplan["get_order"].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "create_order" {
  api_id    = aws_apigatewayv2_api.careplan.id
  route_key = "POST /orders"
  target    = "integrations/${aws_apigatewayv2_integration.create_order.id}"
}

resource "aws_apigatewayv2_route" "get_order" {
  api_id    = aws_apigatewayv2_api.careplan.id
  route_key = "GET /orders/{id}"
  target    = "integrations/${aws_apigatewayv2_integration.get_order.id}"
}

resource "aws_lambda_permission" "allow_api_gateway_create_order" {
  statement_id  = "AllowApiGatewayCreateOrder"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.careplan["create_order"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.careplan.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_api_gateway_get_order" {
  statement_id  = "AllowApiGatewayGetOrder"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.careplan["get_order"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.careplan.execution_arn}/*/*"
}
