#!/usr/bin/env bash
# Copie dans ECR les images publiques de Kafka et du registre de schémas, à la version et à l'empreinte épinglées
# (ADR-018) : Fargate ne tire alors rien de Docker Hub (limites de débit anonymes), et l'image déployée est celle qui a
# été relue. Idempotent : les étiquettes ECR sont immuables, une image déjà copiée est laissée telle quelle.
#   ECR_REGISTRY=123456789012.dkr.ecr.eu-west-3.amazonaws.com scripts/aws-miroir-images.sh
# Les versions doivent rester identiques à infrastructure/aws/terraform/main.tf (locals.third_party).
set -euo pipefail

: "${ECR_REGISTRY:?ECR_REGISTRY requis (hôte ECR du compte)}"

miroir() { # $1 dépôt ECR, $2 étiquette, $3 image source avec empreinte
  if aws ecr describe-images --repository-name "$1" --image-ids imageTag="$2" > /dev/null 2>&1; then
    echo "$1:$2 existe déjà"
    return
  fi
  docker pull -q "$3" > /dev/null
  docker tag "$3" "$ECR_REGISTRY/$1:$2"
  docker push -q "$ECR_REGISTRY/$1:$2" > /dev/null
  echo "$1:$2 copiée"
}

miroir finflow-kafka 4.3.2 \
  apache/kafka@sha256:852a0d49fc4e656b022e7f23785411802c9a088c78a7c5e39f33969de9c227a7
miroir finflow-schema-registry 8.1.0 \
  confluentinc/cp-schema-registry@sha256:385c157f9cef42aa65e5c95050d85a439bcb0c897826aeabb3e10d36127ea223
