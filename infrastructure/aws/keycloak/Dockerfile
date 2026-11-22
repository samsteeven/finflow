# Keycloak de l'environnement AWS (ADR-018) : realm importé au premier démarrage, base PostgreSQL (RDS), servi sous /auth
# derrière CloudFront. Les ${VARIABLES} du realm sont remplacées par Keycloak à l'import (voir generer-realm.py).
#   docker build -f infrastructure/aws/keycloak/Dockerfile -t finflow/keycloak infrastructure/aws/keycloak
FROM quay.io/keycloak/keycloak:26.8.2 AS build
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_HTTP_RELATIVE_PATH=/auth
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2
COPY --from=build --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 finflow-realm-cloud.json /opt/keycloak/data/import/finflow-realm.json
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_HTTP_RELATIVE_PATH=/auth KC_HTTP_ENABLED=true \
    KC_HTTP_PORT=8180 KC_HOSTNAME_STRICT=true KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
