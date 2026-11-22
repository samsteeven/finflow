output "url" {
  description = "Adresse publique de FinFlow (front, API sous /api, Keycloak sous /auth)."
  value       = local.public_url
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.main.id
}

output "web_bucket" {
  value = aws_s3_bucket.web.bucket
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "image_tag" {
  description = "Version en service (retour arrière : redéployer une étiquette précédente)."
  value       = var.image_tag
}

output "log_groups" {
  value = { for k, g in aws_cloudwatch_log_group.app : k => g.name }
}

output "demo_users_password_secret" {
  description = "Secret (Secrets Manager) qui contient le mot de passe des comptes de démonstration (alice, bruno, clara, plateforme)."
  value       = aws_secretsmanager_secret.app["demo-users-password"].name
}

output "db_instance" {
  value = aws_db_instance.main.identifier
}
