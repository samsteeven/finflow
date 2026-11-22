# Image d'un service FinFlow (ADR-018). Le jar est construit avant (./mvnw package), pas dans l'image :
# la CI le construit une fois, les tests le valident, et c'est ce même jar qui est empaqueté.
#   docker build --build-arg SERVICE=ledger-service -t finflow/ledger-service backend
# Image de base épinglée par empreinte (Temurin 25, JRE seul, Alpine).
FROM eclipse-temurin:25-jre-alpine@sha256:69c2a26e9894016e274c880fa542956d005b3755f84db765145185e87405bd89

ARG SERVICE
RUN test -n "$SERVICE" || (echo "ARG SERVICE obligatoire" >&2; exit 1) \
 && addgroup -S -g 10001 finflow && adduser -S -u 10001 -G finflow finflow

WORKDIR /app
COPY --chown=10001:10001 ${SERVICE}/target/${SERVICE}-*-SNAPSHOT.jar /app/app.jar

USER 10001:10001
# Mémoire : 70 % de ce que le conteneur reçoit (Fargate 1 Go ⇒ ≈ 700 Mo de tas) ; la JVM lit la limite du cgroup.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
HEALTHCHECK NONE
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
