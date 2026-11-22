# Tâche ponctuelle d'initialisation des bases (ADR-018) : lancée par Terraform à chaque déploiement, rejouable.
#   docker build -t finflow/init-bases infrastructure/aws/init-bases
FROM postgres:18-alpine
COPY init-bases.sh /usr/local/bin/init-bases.sh
USER postgres
ENTRYPOINT ["sh", "/usr/local/bin/init-bases.sh"]
