# FinFlow sur AWS : une démonstration réelle, dimensionnée au plus juste, détruite après usage.
# Coût et choix : docs/architecture/adr/0018-deploiement-aws.md. RIEN n'est créé tant que `terraform apply` n'est pas
# lancé, et il ne l'est que sur accord explicite (docs/exploitation/aws.md).

terraform {
  required_version = "~> 1.16.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.69.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7.0"
    }
  }

  # Compartiment créé par l'amorçage (infrastructure/aws/bootstrap.yml) ; nom passé à `terraform init`.
  # Verrou natif S3 (pas de table DynamoDB).
  backend "s3" {
    key          = "finflow/demo.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Projet        = "finflow"
      Environnement = var.environment
      GerePar       = "terraform"
      Depot         = "github.com/samsteeven/finflow"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name      = "finflow-${var.environment}"
  azs       = slice(data.aws_availability_zones.available.names, 0, 2)
  ecr       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  namespace = "finflow.internal"

  # Services métier : port d'écoute (celui par défaut de chaque service), taille Fargate, nombre d'instances.
  services = {
    "api-gateway"     = { port = 8080, cpu = 512, memory = 1024, count = var.gateway_desired_count }
    "tenant-service"  = { port = 8081, cpu = 512, memory = 1024, count = 1 }
    "account-service" = { port = 8082, cpu = 512, memory = 1024, count = 1 }
    "payment-service" = { port = 8083, cpu = 512, memory = 1024, count = 1 }
    "ledger-service"  = { port = 8084, cpu = 512, memory = 1024, count = 1 }
  }
  # Bases logiques d'un même cluster PostgreSQL : une par service, deux rôles chacune (ADR-005).
  databases = ["tenant", "account", "payment", "ledger"]

  # Images : celles de FinFlow portent l'empreinte git ; Kafka et le registre de schémas sont des copies (miroir)
  # d'images publiques, dans ECR, à version fixe (scripts/aws-miroir-images.sh).
  image = {
    for s in concat(keys(local.services), ["keycloak", "init-bases"]) : s => "${local.ecr}/finflow-${s}:${var.image_tag}"
  }
  third_party = {
    kafka           = "${local.ecr}/finflow-kafka:4.3.2"
    schema-registry = "${local.ecr}/finflow-schema-registry:8.1.0"
  }

  # Une seule adresse publique (CloudFront) : front, API (/api) et Keycloak (/auth).
  public_url = "https://${aws_cloudfront_distribution.main.domain_name}"
  # Noms DNS internes (Cloud Map) : les services se parlent sans passer par le répartiteur.
  dns = {
    keycloak        = "keycloak.${local.namespace}"
    kafka           = "kafka.${local.namespace}"
    schema-registry = "schema-registry.${local.namespace}"
    tenant          = "tenant-service.${local.namespace}"
    account         = "account-service.${local.namespace}"
    payment         = "payment-service.${local.namespace}"
    ledger          = "ledger-service.${local.namespace}"
  }
}
