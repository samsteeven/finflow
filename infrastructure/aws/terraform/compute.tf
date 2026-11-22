# Calcul : ECS Fargate. Cinq services Spring Boot, Keycloak, un courtier Kafka et son registre de schémas, plus une
# tâche ponctuelle qui crée les bases. Les services se trouvent par DNS (Cloud Map, finflow.internal).

resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "disabled" # ≈ 10 $/mois ; journaux et métriques du répartiteur suffisent ici
  }
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = local.namespace
  description = "Noms internes de FinFlow"
  vpc         = aws_vpc.main.id
}

resource "aws_service_discovery_service" "main" {
  for_each = local.dns
  name     = split(".", each.value)[0]

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
  health_check_custom_config {}
}

locals {
  log_groups = concat(keys(local.services), ["keycloak", "kafka", "schema-registry", "init-bases"])
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = toset(local.log_groups)
  name              = "/${local.name}/${each.key}"
  retention_in_days = 14
}

# --- Rôles -----------------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Rôle d'exécution : ce dont ECS a besoin pour démarrer une tâche (images, journaux, secrets de CE projet).
resource "aws_iam_role" "execution" {
  name               = "finflow-${var.environment}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "secrets-du-projet"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [for s in aws_secretsmanager_secret.app : s.arn]
    }]
  })
}

# Rôle des tâches : aucune application n'appelle un service AWS ; aucun droit.
resource "aws_iam_role" "task" {
  name               = "finflow-${var.environment}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# --- Configuration commune -------------------------------------------------------------------------------------------

locals {
  secret = { for k, s in aws_secretsmanager_secret.app : k => s.arn }

  log_config = { for k, g in aws_cloudwatch_log_group.app : k => {
    logDriver = "awslogs"
    options = {
      awslogs-group         = g.name
      awslogs-region        = var.region
      awslogs-stream-prefix = k
    }
  } }

  issuer_public = "${local.public_url}/auth/realms/finflow"
  # Les clés publiques des jetons sont lues chez Keycloak par le réseau interne (l'émetteur reste l'adresse publique).
  keycloak_internal = "http://${local.dns.keycloak}:8180/auth/realms/finflow"

  oidc_env = [
    { name = "KEYCLOAK_ISSUER", value = local.issuer_public },
    { name = "KEYCLOAK_JWKS", value = "${local.keycloak_internal}/protocol/openid-connect/certs" },
  ]
  messaging_env = [
    { name = "KAFKA_BOOTSTRAP", value = "${local.dns.kafka}:9092" },
    { name = "SCHEMA_REGISTRY_URL", value = "http://${local.dns.schema-registry}:8085" },
    { name = "MESSAGING_ENABLED", value = "true" },
  ]
  # TLS imposé par RDS ; le certificat n'est pas encore vérifié (sslmode=require, voir l'ADR-018).
  db_env = { for d in local.databases : d => [
    { name = "${upper(d)}_DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/finflow_${d}?sslmode=require" },
  ] }
  db_secrets = { for d in local.databases : d => [
    { name = "${upper(d)}_DB_APP_PASSWORD", valueFrom = local.secret["${d}-db-app-password"] },
    { name = "${upper(d)}_DB_OWNER_PASSWORD", valueFrom = local.secret["${d}-db-owner-password"] },
  ] }

  service_env = {
    "api-gateway" = concat(local.oidc_env, [
      { name = "TENANT_SERVICE_URL", value = "http://${local.dns.tenant}:8081" },
      { name = "ACCOUNT_SERVICE_URL", value = "http://${local.dns.account}:8082" },
      { name = "PAYMENT_SERVICE_URL", value = "http://${local.dns.payment}:8083" },
      { name = "LEDGER_SERVICE_URL", value = "http://${local.dns.ledger}:8084" },
      { name = "CORS_ALLOWED_ORIGINS", value = local.public_url },
    ])
    "tenant-service"  = concat(local.oidc_env, local.db_env["tenant"])
    "account-service" = concat(local.oidc_env, local.db_env["account"], local.messaging_env)
    "ledger-service" = concat(local.oidc_env, local.db_env["ledger"], local.messaging_env, [
      { name = "ACCOUNT_SERVICE_URL", value = "http://${local.dns.account}:8082" },
    ])
    "payment-service" = concat(local.oidc_env, local.db_env["payment"], local.messaging_env, [
      { name = "ACCOUNT_SERVICE_URL", value = "http://${local.dns.account}:8082" },
      { name = "LEDGER_SERVICE_URL", value = "http://${local.dns.ledger}:8084" },
      { name = "KEYCLOAK_TOKEN_URI", value = "${local.keycloak_internal}/protocol/openid-connect/token" },
      { name = "SAGA_RECOVERY_ENABLED", value = "true" },
    ])
  }
  service_secrets = {
    "api-gateway"     = []
    "tenant-service"  = local.db_secrets["tenant"]
    "account-service" = local.db_secrets["account"]
    "ledger-service"  = local.db_secrets["ledger"]
    "payment-service" = concat(local.db_secrets["payment"], [
      { name = "PAYMENT_CLIENT_SECRET", valueFrom = local.secret["payment-service-client-secret"] },
    ])
  }
}

# --- Services FinFlow ------------------------------------------------------------------------------------------------
# Système de fichiers en lecture seule, hors /tmp (volume éphémère) ; utilisateur non root (dans l'image).

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.services
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode([{
    name                   = each.key
    image                  = local.image[each.key]
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = each.value.port, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    environment            = local.service_env[each.key]
    secrets                = local.service_secrets[each.key]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:${each.value.port}/actuator/health/readiness | grep -q UP"]
      interval    = 15
      timeout     = 5
      retries     = 5
      startPeriod = 90
    }
    logConfiguration = local.log_config[each.key]
  }])
}

resource "aws_ecs_service" "service" {
  for_each                          = local.services
  name                              = each.key
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.service[each.key].arn
  desired_count                     = each.value.count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = each.key == "api-gateway" ? 90 : null
  wait_for_steady_state             = true

  # Une nouvelle version qui ne devient pas saine est retirée et la précédente remise en place, sans intervention.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  dynamic "load_balancer" {
    for_each = each.key == "api-gateway" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.gateway.arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  dynamic "service_registries" {
    for_each = each.key == "api-gateway" ? [] : [1]
    content {
      registry_arn = aws_service_discovery_service.main[trimsuffix(each.key, "-service")].arn
    }
  }

  # Les services démarrent quand leurs dépendances sont saines : bases créées, Keycloak, Kafka, registre.
  depends_on = [
    terraform_data.init_bases,
    aws_ecs_service.keycloak,
    aws_ecs_service.kafka,
    aws_ecs_service.schema_registry,
    aws_lb_listener.http,
  ]
}

# --- Initialisation des bases (tâche ponctuelle) ---------------------------------------------------------------------

resource "aws_ecs_task_definition" "init_bases" {
  family                   = "${local.name}-init-bases"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "init-bases"
    image     = local.image["init-bases"]
    essential = true
    environment = [
      { name = "PGHOST", value = aws_db_instance.main.address },
      { name = "PGUSER", value = aws_db_instance.main.username },
      { name = "PGSSLMODE", value = "require" },
    ]
    secrets = concat(
      [
        { name = "PGPASSWORD", valueFrom = local.secret["db-master-password"] },
        { name = "KEYCLOAK_PASSWORD", valueFrom = local.secret["keycloak-db-password"] },
      ],
      [for d in local.databases : { name = "${upper(d)}_OWNER_PASSWORD", valueFrom = local.secret["${d}-db-owner-password"] }],
      [for d in local.databases : { name = "${upper(d)}_APP_PASSWORD", valueFrom = local.secret["${d}-db-app-password"] }],
    )
    logConfiguration = local.log_config["init-bases"]
  }])
}

# Lancée à chaque déploiement (l'étiquette d'image change), rejouable sans effet de bord. Exige l'AWS CLI, présente dans
# GitHub Actions. Une échec arrête le déploiement avant que les services ne démarrent.
resource "terraform_data" "init_bases" {
  triggers_replace = [aws_ecs_task_definition.init_bases.arn, aws_db_instance.main.id]

  provisioner "local-exec" {
    command = "bash ${path.module}/lancer-tache.sh"
    environment = {
      AWS_REGION      = var.region
      CLUSTER         = aws_ecs_cluster.main.name
      TASK_DEFINITION = aws_ecs_task_definition.init_bases.arn
      SUBNETS         = join(",", aws_subnet.public[*].id)
      SECURITY_GROUP  = aws_security_group.tasks.id
    }
  }
}

# --- Keycloak ---------------------------------------------------------------------------------------------------------
# Servi sous /auth derrière CloudFront. La console d'administration et le realm master ne sont pas exposés (edge.tf).

resource "aws_ecs_task_definition" "keycloak" {
  family                   = "${local.name}-keycloak"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "keycloak"
    image     = local.image["keycloak"]
    essential = true
    portMappings = [
      { containerPort = 8180, protocol = "tcp" },
      { containerPort = 9000, protocol = "tcp" },
    ]
    environment = [
      { name = "KC_DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/finflow_keycloak?sslmode=require" },
      { name = "KC_DB_USERNAME", value = "finflow_keycloak" },
      { name = "KC_HOSTNAME", value = "${local.public_url}/auth" },
      { name = "KC_BOOTSTRAP_ADMIN_USERNAME", value = "admin" },
      { name = "FINFLOW_PUBLIC_URL", value = local.public_url },
    ]
    secrets = [
      { name = "KC_DB_PASSWORD", valueFrom = local.secret["keycloak-db-password"] },
      { name = "KC_BOOTSTRAP_ADMIN_PASSWORD", valueFrom = local.secret["keycloak-admin-password"] },
      { name = "PAYMENT_CLIENT_SECRET", valueFrom = local.secret["payment-service-client-secret"] },
      { name = "DEMO_PASSWORD", valueFrom = local.secret["demo-users-password"] },
    ]
    healthCheck = {
      # L'image n'a ni curl ni wget : requête HTTP à la main sur le port de gestion.
      command     = ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /auth/health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
      interval    = 15
      timeout     = 5
      retries     = 5
      startPeriod = 180
    }
    logConfiguration = local.log_config["keycloak"]
  }])
}

resource "aws_ecs_service" "keycloak" {
  name                              = "keycloak"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.keycloak.arn
  desired_count                     = 1
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 240
  wait_for_steady_state             = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  # Une seule instance : l'ancienne s'arrête avant que la nouvelle ne démarre.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.keycloak.arn
    container_name   = "keycloak"
    container_port   = 8180
  }

  service_registries {
    registry_arn = aws_service_discovery_service.main["keycloak"].arn
  }

  depends_on = [terraform_data.init_bases, aws_lb_listener.http]
}

# --- Kafka (un courtier KRaft) et registre de schémas ----------------------------------------------------------------

resource "aws_ecs_task_definition" "kafka" {
  family                   = "${local.name}-kafka"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "kafka-data"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.kafka.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.kafka.id
      }
    }
  }
  container_definitions = jsonencode([{
    name         = "kafka"
    image        = local.third_party.kafka
    essential    = true
    portMappings = [{ containerPort = 9092, protocol = "tcp" }]
    mountPoints  = [{ sourceVolume = "kafka-data", containerPath = "/var/lib/kafka/data", readOnly = false }]
    environment = [
      { name = "KAFKA_NODE_ID", value = "1" },
      { name = "KAFKA_PROCESS_ROLES", value = "broker,controller" },
      { name = "KAFKA_LISTENERS", value = "PLAINTEXT://:9092,CONTROLLER://:9093" },
      { name = "KAFKA_ADVERTISED_LISTENERS", value = "PLAINTEXT://${local.dns.kafka}:9092" },
      { name = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" },
      { name = "KAFKA_CONTROLLER_LISTENER_NAMES", value = "CONTROLLER" },
      { name = "KAFKA_CONTROLLER_QUORUM_VOTERS", value = "1@localhost:9093" },
      { name = "KAFKA_INTER_BROKER_LISTENER_NAME", value = "PLAINTEXT" },
      { name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", value = "1" },
      { name = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", value = "1" },
      { name = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", value = "1" },
      { name = "KAFKA_NUM_PARTITIONS", value = "3" },
      # Les topics sont déclarés par les services (Topics.java), jamais créés par une faute de frappe.
      { name = "KAFKA_AUTO_CREATE_TOPICS_ENABLE", value = "false" },
      { name = "KAFKA_HEAP_OPTS", value = "-Xms256m -Xmx512m" },
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1"]
      interval    = 15
      timeout     = 10
      retries     = 10
      startPeriod = 60
    }
    logConfiguration = local.log_config["kafka"]
  }])
}

resource "aws_ecs_service" "kafka" {
  name                  = "kafka"
  cluster               = aws_ecs_cluster.main.id
  task_definition       = aws_ecs_task_definition.kafka.arn
  desired_count         = 1
  launch_type           = "FARGATE"
  wait_for_steady_state = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  # Un seul courtier sur un seul disque : jamais deux à la fois.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.main["kafka"].arn
  }

  depends_on = [aws_efs_mount_target.kafka]
}

resource "aws_ecs_task_definition" "schema_registry" {
  family                   = "${local.name}-schema-registry"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "schema-registry"
    image        = local.third_party["schema-registry"]
    essential    = true
    portMappings = [{ containerPort = 8085, protocol = "tcp" }]
    environment = [
      { name = "SCHEMA_REGISTRY_HOST_NAME", value = local.dns.schema-registry },
      { name = "SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", value = "${local.dns.kafka}:9092" },
      { name = "SCHEMA_REGISTRY_LISTENERS", value = "http://0.0.0.0:8085" },
      { name = "KAFKA_HEAP_OPTS", value = "-Xms64m -Xmx256m" },
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "curl -sf http://localhost:8085/subjects > /dev/null"]
      interval    = 15
      timeout     = 5
      retries     = 10
      startPeriod = 60
    }
    logConfiguration = local.log_config["schema-registry"]
  }])
}

resource "aws_ecs_service" "schema_registry" {
  name                  = "schema-registry"
  cluster               = aws_ecs_cluster.main.id
  task_definition       = aws_ecs_task_definition.schema_registry.arn
  desired_count         = 1
  launch_type           = "FARGATE"
  wait_for_steady_state = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.main["schema-registry"].arn
  }

  depends_on = [aws_ecs_service.kafka]
}
