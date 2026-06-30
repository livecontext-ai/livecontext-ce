# Base images are ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub defaults keep local/CE builds working unauthenticated.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
# LiveContext Community Edition - Multi-stage Docker Image
# Build: docker compose -f docker/docker-compose-ce-monolith.yml up -d --build
# Or standalone: docker build -t livecontext-ce -f backend/monolith-service/Dockerfile backend/

# ---- Build stage ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /app

# Optional TLS-interception root CA (corporate proxy / AV HTTPS scanning, e.g.
# Norton Web Shield): hosts that intercept HTTPS break Maven Central downloads
# inside the build (the container JVM does not trust the interception root).
# Pass the PEM via --build-arg BUILD_EXTRA_CA="$(cat root.pem)" (the e2e slot
# compose forwards $BUILD_EXTRA_CA). Empty (default) = strict no-op, CI-safe.
ARG BUILD_EXTRA_CA=""
RUN if [ -n "$BUILD_EXTRA_CA" ]; then \
      printf '%s\n' "$BUILD_EXTRA_CA" > /tmp/build-extra-ca.pem \
      && keytool -importcert -noprompt -trustcacerts -alias build-extra-ca \
           -file /tmp/build-extra-ca.pem -cacerts -storepass changeit \
      && echo "Imported extra build CA into JVM truststore"; \
    fi

# Copy parent POM
COPY pom.xml .

# Copy ALL module POMs (Maven reactor requires them)
COPY common-lib/pom.xml common-lib/pom.xml
COPY common-security/pom.xml common-security/pom.xml
COPY common-storage-service/pom.xml common-storage-service/pom.xml
COPY agent-common/pom.xml agent-common/pom.xml
COPY shared-agent-lib/pom.xml shared-agent-lib/pom.xml
COPY shared-sse-lib/pom.xml shared-sse-lib/pom.xml
COPY datasource-client/pom.xml datasource-client/pom.xml
COPY agent-client/pom.xml agent-client/pom.xml
COPY interface-client/pom.xml interface-client/pom.xml
COPY trigger-client/pom.xml trigger-client/pom.xml
COPY publication-client/pom.xml publication-client/pom.xml
COPY auth-client/pom.xml auth-client/pom.xml
COPY storage-client/pom.xml storage-client/pom.xml
COPY credential-client/pom.xml credential-client/pom.xml
COPY conversation-client/pom.xml conversation-client/pom.xml
COPY notification-client/pom.xml notification-client/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY storage-service/pom.xml storage-service/pom.xml
COPY conversation-service/pom.xml conversation-service/pom.xml
COPY trigger-service/pom.xml trigger-service/pom.xml
COPY datasource-service/pom.xml datasource-service/pom.xml
COPY agent-service/pom.xml agent-service/pom.xml
COPY interface-service/pom.xml interface-service/pom.xml
COPY publication-service/pom.xml publication-service/pom.xml
COPY migration-service/pom.xml migration-service/pom.xml
COPY orchestrator-service/pom.xml orchestrator-service/pom.xml
COPY monolith-service/pom.xml monolith-service/pom.xml

# Download dependencies (cached layer unless POMs change)
RUN mvn -B -Pce -DskipTests dependency:go-offline || true

# Copy all source code
COPY common-lib common-lib
COPY common-security common-security
COPY common-storage-service common-storage-service
COPY agent-common agent-common
COPY shared-agent-lib shared-agent-lib
COPY shared-sse-lib shared-sse-lib
COPY datasource-client datasource-client
COPY agent-client agent-client
COPY interface-client interface-client
COPY trigger-client trigger-client
COPY publication-client publication-client
COPY auth-client auth-client
COPY storage-client storage-client
COPY credential-client credential-client
COPY conversation-client conversation-client
COPY notification-client notification-client
COPY catalog-service catalog-service
COPY auth-service auth-service
COPY storage-service storage-service
COPY conversation-service conversation-service
COPY trigger-service trigger-service
COPY datasource-service datasource-service
COPY agent-service agent-service
COPY interface-service interface-service
COPY publication-service publication-service
COPY migration-service migration-service
COPY orchestrator-service orchestrator-service
COPY monolith-service monolith-service

# Build the monolith fat JAR (CE profile: services produce regular JARs, monolith gets repackaged)
RUN mvn -B -Pce -Dmaven.test.skip=true clean package

# ---- Runtime stage ----
FROM ${JRE_IMAGE}

# Optional release tag stamped into released images (e.g. the GitHub release tag),
# passed by the release/publish build via --build-arg APP_VERSION=<tag>. Surfaced to
# the running app as the APP_VERSION env var, which VersionInfo prefers over the git
# commit sha for the displayed / version-checked build identity (GET /api/version).
# Empty (default) for from-source / local CE builds, which then fall back cleanly to
# the short git sha baked into git.properties.
ARG APP_VERSION=""
ENV APP_VERSION=${APP_VERSION}

ARG BUILD_EXTRA_CA=""

RUN if [ -n "$BUILD_EXTRA_CA" ]; then \
      printf '%s\n' "$BUILD_EXTRA_CA" > /tmp/build-extra-ca.pem \
      && cat /tmp/build-extra-ca.pem >> /etc/ssl/certs/ca-certificates.crt \
      && echo "Bootstrapped extra build CA for apk"; \
    fi

RUN apk add --no-cache ca-certificates bash nodejs npm python3 \
    && if [ -s /tmp/build-extra-ca.pem ]; then \
         mkdir -p /usr/local/share/ca-certificates \
         && cp /tmp/build-extra-ca.pem /usr/local/share/ca-certificates/build-extra-ca.crt \
         && update-ca-certificates; \
       fi \
    && NODE_EXTRA_CA_CERTS=/tmp/build-extra-ca.pem npm install -g tsx@4

RUN addgroup -S livecontext && adduser -S livecontext -G livecontext

WORKDIR /app

RUN mkdir -p /app/data/keys /app/data/files /app/logs \
    && chown -R livecontext:livecontext /app

COPY --from=build /app/monolith-service/target/app.jar /app/app.jar
COPY monolith-service/ce-entrypoint.sh /app/ce-entrypoint.sh

USER livecontext

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational"
ENV PORT=8080
ENV DB_HOST=localhost
ENV DB_PORT=5432
ENV DB_NAME=livecontext
ENV DB_USERNAME=postgres
ENV DB_PASSWORD=postgres
ENV STORAGE_PATH=/app/data/files
ENV AUTH_JWT_KEYS_PATH=/app/data/keys

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "/app/ce-entrypoint.sh"]
