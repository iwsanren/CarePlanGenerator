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

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

resource "aws_security_group" "rds" {
  name        = "careplan-day15-rds-sg"
  description = "Security group for Day 15 RDS PostgreSQL test"
  vpc_id      = data.aws_vpc.default.id

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-rds"
  }
}

resource "aws_db_subnet_group" "careplan" {
  name       = "careplan-day15-db-subnet-group"
  subnet_ids = data.aws_subnets.default.ids

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-rds"
  }
}

resource "aws_db_instance" "careplan" {
  identifier = "careplan-day15-postgres"

  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t3.micro"

  allocated_storage     = 20
  storage_type          = "gp2"
  db_name               = "careplan"
  username              = var.db_username
  password              = var.db_password
  parameter_group_name  = "default.postgres16"

  db_subnet_group_name   = aws_db_subnet_group.careplan.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible = false
  multi_az            = false

  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false

  tags = {
    Project = "careplan-generator"
    Purpose = "terraform-day15-rds"
  }
}