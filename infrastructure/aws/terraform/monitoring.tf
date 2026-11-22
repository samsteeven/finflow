# Surveillance : alarmes CloudWatch envoyées par e-mail, budget. Les métriques Prometheus et les traces restent dans la
# pile locale (profil observability) : pas de Prometheus ni de collecteur gérés sur AWS (coût, voir l'ADR-018).

# Sujet non chiffré : CloudWatch ne peut pas publier vers un sujet chiffré par la clé gérée par AWS, et une clé
# dédiée coûterait plus que l'enjeu (des messages d'alarme, sans données).
#trivy:ignore:AWS-0095
resource "aws_sns_topic" "alerts" {
  name = "${local.name}-alertes"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alb_dimensions = { LoadBalancer = aws_lb.main.arn_suffix }
  gateway_dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.gateway.arn_suffix
  }
  keycloak_dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.keycloak.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "gateway_5xx" {
  alarm_name          = "${local.name}-gateway-5xx"
  alarm_description   = "Plus de 10 réponses 5xx de la Gateway en 5 min : voir les journaux /${local.name}/api-gateway."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = local.gateway_dimensions
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "gateway_latency" {
  alarm_name          = "${local.name}-gateway-latence"
  alarm_description   = "p95 du temps de réponse de la Gateway au-dessus de 2 s pendant 10 min."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = local.gateway_dimensions
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  threshold           = 2
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "gateway_unhealthy" {
  alarm_name          = "${local.name}-gateway-instances"
  alarm_description   = "Moins de ${var.gateway_desired_count} instances saines de la Gateway pendant 2 min."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = local.gateway_dimensions
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = var.gateway_desired_count
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "keycloak_unhealthy" {
  alarm_name          = "${local.name}-keycloak-instance"
  alarm_description   = "Keycloak n'est plus sain : plus personne ne peut se connecter."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = local.keycloak_dimensions
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  alarm_name          = "${local.name}-db-cpu"
  alarm_description   = "PostgreSQL au-dessus de 80 % de CPU pendant 10 min."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "db_storage" {
  alarm_name          = "${local.name}-db-stockage"
  alarm_description   = "Moins de 2 Go libres sur PostgreSQL."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2 * 1024 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

# Erreurs dans les journaux des services (niveau ERROR de Spring Boot) : un compteur par service, une alarme pour
# les services qui portent l'argent (Payment, Ledger) et les messages (outbox, saga).
resource "aws_cloudwatch_log_metric_filter" "errors" {
  for_each       = toset(["payment-service", "ledger-service", "account-service"])
  name           = "${local.name}-${each.key}-erreurs"
  log_group_name = aws_cloudwatch_log_group.app[each.key].name
  pattern        = "\" ERROR \""
  metric_transformation {
    name          = "ErreursJournal"
    namespace     = "FinFlow/${var.environment}"
    value         = "1"
    default_value = "0"
    dimensions    = {}
  }
}

resource "aws_cloudwatch_metric_alarm" "service_errors" {
  for_each            = toset(["payment-service", "ledger-service", "account-service"])
  alarm_name          = "${local.name}-${each.key}-erreurs"
  alarm_description   = "Plus de 5 erreurs dans les journaux de ${each.key} en 10 min (saga, outbox, base, Kafka)."
  namespace           = "FinFlow/${var.environment}"
  metric_name         = "ErreursJournal"
  statistic           = "Sum"
  period              = 600
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  depends_on          = [aws_cloudwatch_log_metric_filter.errors]
}

# Budget du compte entier (un filtre par étiquette exigerait d'activer les étiquettes de répartition des coûts) :
# alertes à 50, 80 et 100 % du montant mensuel, sur les coûts réels et prévus.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-mensuel"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.alert_email]
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
