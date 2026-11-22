# Réseau : deux zones de disponibilité. Sous-réseaux publics pour les tâches (adresse publique pour sortir, entrée
# fermée sauf depuis le répartiteur et entre services) ; sous-réseaux privés, sans sortie Internet, pour le
# répartiteur interne, PostgreSQL et le disque de Kafka. Pas de passerelle NAT (≈ 35 $/mois) : voir l'ADR-018.

# Pas de journaux de flux du VPC (coût de stockage pour un environnement détruit après usage).
#trivy:ignore:AWS-0178
resource "aws_vpc" "main" {
  cidr_block           = "10.50.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index + 10)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${local.name}-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Sous-réseaux privés : table de routage par défaut du VPC, sans route vers Internet.

# Pare-feu par défaut du VPC : tout fermé (les ressources utilisent leurs propres groupes).
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
}

# --- Groupes de sécurité ---------------------------------------------------------------------------------------------

locals {
  # Ports d'écoute entre tâches : services métier, registre de schémas, Keycloak, Kafka.
  internal_ports = {
    tenant          = 8081
    account         = 8082
    payment         = 8083
    ledger          = 8084
    schema-registry = 8085
    keycloak        = 8180
    kafka           = 9092
  }
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Repartiteur : HTTP depuis CloudFront seulement"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "CloudFront, par l'origine VPC (adresses d'origine publiees par AWS)"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

# Gateway (8080), Keycloak (8180) et son port de gestion (9000, santé).
resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  for_each                     = toset(["8080", "8180", "9000"])
  security_group_id            = aws_security_group.alb.id
  description                  = "Vers les taches, port ${each.key}"
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
}

resource "aws_security_group" "tasks" {
  name        = "${local.name}-tasks"
  description = "Taches ECS : entree depuis le repartiteur et entre services seulement"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  for_each                     = toset(["8080", "8180", "9000"])
  security_group_id            = aws_security_group.tasks.id
  description                  = "Repartiteur, port ${each.key}"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
}

# Entre tâches : la Gateway appelle les services, Payment appelle Account et Ledger, tous joignent Kafka, le registre
# et Keycloak (clés publiques des jetons). Un seul groupe pour toutes les tâches : le détail par service serait
# l'étape suivante (voir l'ADR-018).
resource "aws_vpc_security_group_ingress_rule" "tasks_from_tasks" {
  for_each                     = local.internal_ports
  security_group_id            = aws_security_group.tasks.id
  description                  = "Entre taches : ${each.key}"
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_tasks" {
  for_each                     = local.internal_ports
  security_group_id            = aws_security_group.tasks.id
  description                  = "Entre taches : ${each.key}"
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

# Sortie HTTPS : ECR, Secrets Manager, CloudWatch Logs. Des points de terminaison privés (≈ 30 $/mois pour les
# quatre) permettraient de la restreindre ; choix de coût, voir l'ADR-018.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "tasks_https" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_db" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_efs" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.efs.id
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
}

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "PostgreSQL : depuis les taches seulement"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_tasks" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_security_group" "efs" {
  name        = "${local.name}-efs"
  description = "Disque de Kafka : depuis les taches seulement"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "efs_from_tasks" {
  security_group_id            = aws_security_group.efs.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
}
