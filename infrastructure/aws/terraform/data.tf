# Données : PostgreSQL (RDS), disque de Kafka (EFS) et secrets (Secrets Manager).

# --- Secrets ---------------------------------------------------------------------------------------------------------
# Générés par Terraform : ils figurent dans l'état, chiffré dans un compartiment privé (voir l'ADR-018). Les tâches
# les reçoivent de Secrets Manager au démarrage ; ils n'apparaissent ni dans les définitions de tâches ni dans les
# journaux.

resource "random_password" "db_master" {
  length  = 32
  special = false
}

resource "random_password" "db_owner" {
  for_each = toset(local.databases)
  length   = 32
  special  = false
}

resource "random_password" "db_app" {
  for_each = toset(local.databases)
  length   = 32
  special  = false
}

resource "random_password" "keycloak_db" {
  length  = 32
  special = false
}

resource "random_password" "keycloak_admin" {
  length  = 32
  special = false
}

resource "random_password" "payment_client" {
  length  = 48
  special = false
}

resource "random_password" "demo_users" {
  length  = 20
  special = false
}

locals {
  secrets = merge(
    { for d in local.databases : "${d}-db-owner-password" => random_password.db_owner[d].result },
    { for d in local.databases : "${d}-db-app-password" => random_password.db_app[d].result },
    {
      "db-master-password"            = random_password.db_master.result
      "keycloak-db-password"          = random_password.keycloak_db.result
      "keycloak-admin-password"       = random_password.keycloak_admin.result
      "payment-service-client-secret" = random_password.payment_client.result
      "demo-users-password"           = random_password.demo_users.result
    },
  )
}

resource "aws_secretsmanager_secret" "app" {
  for_each                = local.secrets
  name                    = "${local.name}/${each.key}"
  recovery_window_in_days = 0 # environnement de démonstration : suppression immédiate à la destruction
}

resource "aws_secretsmanager_secret_version" "app" {
  for_each      = local.secrets
  secret_id     = aws_secretsmanager_secret.app[each.key].id
  secret_string = each.value
}

# --- PostgreSQL ------------------------------------------------------------------------------------------------------
# Une instance, cinq bases logiques (quatre services et Keycloak), créées par la tâche d'initialisation (compute.tf).

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "main" {
  name   = local.name
  family = "postgres18"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  # Requêtes lentes dans les journaux (plus de 500 ms).
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

# Authentification par mot de passe (un rôle propriétaire et un rôle applicatif par service, mots de passe dans Secrets
# Manager) : l'authentification IAM demande un jeton renouvelé toutes les 15 minutes par le pilote (ADR-018). Pas de
# protection contre la suppression : l'environnement est détruit après usage.
#trivy:ignore:AWS-0176
#trivy:ignore:AWS-0177
resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "18"
  instance_class = var.db_instance_class

  db_name  = "postgres"
  username = "finflow"
  password = random_password.db_master.result

  allocated_storage     = 20
  max_allocated_storage = 50
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  # Sauvegardes quotidiennes et restauration à la seconde près sur 7 jours.
  backup_retention_period  = 7
  backup_window            = "02:00-02:30"
  maintenance_window       = "sun:03:00-sun:03:30"
  copy_tags_to_snapshot    = true
  delete_automated_backups = true

  auto_minor_version_upgrade = true
  apply_immediately          = true
  # Démonstration : destruction sans instantané final ni protection. En production : l'inverse.
  deletion_protection = false
  skip_final_snapshot = true
}

# --- Disque de Kafka (EFS) -------------------------------------------------------------------------------------------
# Un seul courtier Kafka sur Fargate : ses données vivent sur un disque réseau qui survit au remplacement de la tâche.
# L'alternative gérée (MSK) est chiffrée dans l'ADR-018.

# Chiffré par la clé gérée par AWS : une clé dédiée (≈ 1 $/mois) n'apporte rien ici.
#trivy:ignore:AWS-0116
resource "aws_efs_file_system" "kafka" {
  creation_token = "${local.name}-kafka"
  encrypted      = true
  tags           = { Name = "${local.name}-kafka" }
}

resource "aws_efs_mount_target" "kafka" {
  count           = 2
  file_system_id  = aws_efs_file_system.kafka.id
  subnet_id       = aws_subnet.private[count.index].id
  security_groups = [aws_security_group.efs.id]
}

# Point d'accès : le courtier tourne sous l'utilisateur 1000 de l'image Kafka et n'a accès qu'à ce répertoire.
resource "aws_efs_access_point" "kafka" {
  file_system_id = aws_efs_file_system.kafka.id
  posix_user {
    uid = 1000
    gid = 1000
  }
  root_directory {
    path = "/kafka"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0750"
    }
  }
}
