#!/usr/bin/env bash
# Lance la tâche d'initialisation des bases et attend sa fin ; échoue si elle ne se termine pas avec le code 0.
# Appelé par Terraform (terraform_data.init_bases). Variables : AWS_REGION, CLUSTER, TASK_DEFINITION, SUBNETS, SECURITY_GROUP.
set -euo pipefail

task=$(aws ecs run-task \
  --region "$AWS_REGION" --cluster "$CLUSTER" --task-definition "$TASK_DEFINITION" --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SECURITY_GROUP],assignPublicIp=ENABLED}" \
  --query 'tasks[0].taskArn' --output text)
if [ -z "$task" ] || [ "$task" = "None" ]; then
  echo "La tâche d'initialisation n'a pas pu être lancée." >&2
  exit 1
fi
echo "Initialisation des bases : $task"

aws ecs wait tasks-stopped --region "$AWS_REGION" --cluster "$CLUSTER" --tasks "$task"

code=$(aws ecs describe-tasks --region "$AWS_REGION" --cluster "$CLUSTER" --tasks "$task" \
  --query 'tasks[0].containers[0].exitCode' --output text)
if [ "$code" != "0" ]; then
  reason=$(aws ecs describe-tasks --region "$AWS_REGION" --cluster "$CLUSTER" --tasks "$task" \
    --query 'tasks[0].[stoppedReason,containers[0].reason]' --output text)
  echo "Échec de l'initialisation des bases (code $code) : $reason" >&2
  echo "Journaux : groupe CloudWatch /${CLUSTER}/init-bases" >&2
  exit 1
fi
echo "Bases initialisées."
