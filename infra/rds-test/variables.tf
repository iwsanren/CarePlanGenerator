variable "db_username" {
  description = "Master username for the RDS PostgreSQL database"
  type        = string
  default     = "postgres"
}

variable "db_password" {
  description = "Master password for the RDS PostgreSQL database"
  type        = string
  sensitive   = true
}