variable "region" {
  description = "Région AWS (Paris par défaut)."
  type        = string
  default     = "eu-west-3"
}

variable "environment" {
  description = "Nom de l'environnement, dans les noms et les étiquettes."
  type        = string
  default     = "demo"
}

variable "image_tag" {
  description = "Étiquette (empreinte git) des images FinFlow : services, Keycloak et initialisation des bases."
  type        = string
}

variable "gateway_desired_count" {
  description = "Instances de la Gateway (2 : une panne d'instance ne coupe pas le service ; le quota par organisation est alors appliqué par instance)."
  type        = number
  default     = 2
}

variable "alert_email" {
  description = "Adresse qui reçoit les alarmes et les alertes de budget (confirmation par e-mail d'AWS)."
  type        = string
}

variable "monthly_budget_usd" {
  description = "Budget mensuel ; alertes à 50, 80 et 100 %."
  type        = number
  default     = 30
}

variable "db_instance_class" {
  description = "Classe de l'instance PostgreSQL (une instance, cinq bases : quatre services et Keycloak)."
  type        = string
  default     = "db.t4g.small"
}
