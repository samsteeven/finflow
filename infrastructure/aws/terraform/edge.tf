# Bord : un seul domaine, celui de CloudFront. Il sert le front (S3, privé), /api/* vers la Gateway et /auth/* vers
# Keycloak, par une origine VPC jusqu'à un répartiteur interne. Le front appelle /api et s'authentifie sur /auth en
# relatif : même origine, pas de CORS, et le même build fonctionne quel que soit le domaine.

# --- Répartiteur de charge -------------------------------------------------------------------------------------------

resource "random_password" "origin_header" {
  length  = 40
  special = false
}

# Répartiteur interne, dans les sous-réseaux privés : CloudFront l'atteint par une origine VPC, sans qu'il soit
# joignable depuis Internet.
resource "aws_lb" "main" {
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = true
  subnets                    = aws_subnet.private[*].id
  security_groups            = [aws_security_group.alb.id]
  idle_timeout               = 60
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "gateway" {
  name                 = "${local.name}-gw"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15

  # « Prêt » : la Gateway répond. Une instance malade sort du répartiteur.
  health_check {
    path                = "/actuator/health/readiness"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }
}

resource "aws_lb_target_group" "keycloak" {
  name                 = "${local.name}-kc"
  port                 = 8180
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15

  # La santé de Keycloak est sur son port de gestion.
  health_check {
    port                = "9000"
    path                = "/auth/health/ready"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 5
    matcher             = "200"
  }
}

# HTTP : le trafic CloudFront → répartiteur passe par l'origine VPC, dans le réseau d'AWS ; HTTPS demanderait
# un domaine et son certificat (ADR-018).
#trivy:ignore:AWS-0054
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # Par défaut : refusé. Seules les requêtes portant l'en-tête secret posé par CloudFront passent.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Acces direct refuse"
      status_code  = "403"
    }
  }
}

# La console d'administration de Keycloak et le realm master ne sont jamais exposés : l'administration passe par
# Terraform et le realm importé, pas par un navigateur sur Internet.
resource "aws_lb_listener_rule" "keycloak_admin_blocked" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 5

  condition {
    path_pattern {
      values = ["/auth/admin", "/auth/admin/*", "/auth/realms/master", "/auth/realms/master/*"]
    }
  }

  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Interdit"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "gateway" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_header.result]
    }
  }
  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

resource "aws_lb_listener_rule" "keycloak" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 20

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_header.result]
    }
  }
  condition {
    path_pattern {
      values = ["/auth", "/auth/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keycloak.arn
  }
}

# --- Front : S3 privé ------------------------------------------------------------------------------------------------

# Chiffrement par clé gérée par AWS (SSE-S3) : une clé KMS dédiée (≈ 1 $/mois) n'apporte rien à des fichiers
# publics par nature.
# Pas de versions : le contenu est reconstruit à chaque déploiement depuis la CI.
#trivy:ignore:AWS-0090
#trivy:ignore:AWS-0132
resource "aws_s3_bucket" "web" {
  bucket        = "${local.name}-web-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.name}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Lecture du compartiment par CETTE distribution seulement.
resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.web.arn}/*"
      Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.main.arn } }
    }]
  })
}

# --- CloudFront ------------------------------------------------------------------------------------------------------

# Application à page unique : /accounts/123 sert index.html, Angular route ensuite (/api et /auth ne passent pas ici).
resource "aws_cloudfront_function" "spa" {
  name    = "${local.name}-spa"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      if (request.uri.indexOf('.') === -1) request.uri = '/index.html';
      return request;
    }
  JS
}

# En-têtes de sécurité du front.
resource "aws_cloudfront_response_headers_policy" "security" {
  name = "${local.name}-securite"
  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      override                   = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    content_security_policy {
      content_security_policy = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
      override                = true
    }
  }
}

# Pour /auth et /api : pas de politique de contenu imposée ici. Keycloak envoie la sienne (ses pages de connexion
# utilisent des scripts en ligne qu'une politique stricte casserait) ; l'API ne sert pas de pages.
resource "aws_cloudfront_response_headers_policy" "backend" {
  name = "${local.name}-securite-backend"
  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      override                   = true
    }
    content_type_options {
      override = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = false
    }
  }
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# Origine VPC : CloudFront entre dans le VPC jusqu'au répartiteur interne.
resource "aws_cloudfront_vpc_origin" "alb" {
  vpc_origin_endpoint_config {
    name                   = "${local.name}-alb"
    arn                    = aws_lb.main.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"
    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# Pas de WAF (≈ 6 $/mois avec les règles gérées) : le quota par organisation est dans la Gateway (ADR-017) ; un WAF
# est la première chose à ajouter avant un vrai trafic.
# Pas de journaux d'accès CloudFront (un compartiment de plus à garder) : les métriques du répartiteur et les journaux
# des services suffisent pour une démonstration.
#trivy:ignore:AWS-0011
#trivy:ignore:AWS-0010
resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  comment             = "FinFlow (${var.environment})"
  default_root_object = "index.html"
  price_class         = "PriceClass_100" # Europe et Amérique du Nord
  http_version        = "http2and3"

  origin {
    origin_id                = "web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = "alb"
    domain_name = aws_lb.main.dns_name
    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.alb.id
    }
    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_header.result
    }
  }

  default_cache_behavior {
    target_origin_id           = "web"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = ["/api/*", "/auth/*"]
    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = "alb"
      viewer_protocol_policy     = "https-only"
      allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = true
      cache_policy_id            = data.aws_cloudfront_cache_policy.disabled.id
      origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.backend.id
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
