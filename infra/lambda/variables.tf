variable "aws_region" {
  description = "AWS region used for the Day 15 Lambda practice resources."
  type        = string
  default     = "eu-west-1"
}

variable "aws_profile" {
  description = "Local AWS CLI profile Terraform should use."
  type        = string
  default     = "careplan-dev"
}

variable "project_name" {
  description = "Prefix used for Lambda and IAM resource names."
  type        = string
  default     = "careplan-day15"
}

variable "lambda_zip_path" {
  description = "Path to the Java Lambda deployment zip, relative to this Terraform directory."
  type        = string
  default     = "../../backend/target/backend-0.0.1-SNAPSHOT-aws-lambda.zip"
}

variable "db_name" {
  description = "Database name for the CarePlan PostgreSQL RDS instance."
  type        = string
  default     = "careplan"
}

variable "db_username" {
  description = "Master username for the CarePlan PostgreSQL RDS instance."
  type        = string
}

variable "db_password" {
  description = "Master password for the CarePlan PostgreSQL RDS instance."
  type        = string
  sensitive   = true

  validation {
    condition = (
      length(var.db_password) >= 8
      && !strcontains(var.db_password, "/")
      && !strcontains(var.db_password, "\"")
      && !strcontains(var.db_password, "@")
    )
    error_message = "db_password must be at least 8 characters and cannot contain '/', '\"', or '@'."
  }
}

variable "db_instance_class" {
  description = "RDS instance class for the Day 15 practice database."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "Allocated RDS storage in GB."
  type        = number
  default     = 20
}
