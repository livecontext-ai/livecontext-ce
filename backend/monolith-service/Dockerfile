# syntax=docker/dockerfile:1.7-labs
# Base images are ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub defaults keep local/CE builds working unauthenticated.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
# LiveContext Community Edition - Multi-stage Docker Image
# Build: docker compose -f docker/docker-compose-ce-monolith.yml up -d --build
# Or standalone: docker build -t livecontext-ce -f backend/monolith-service/Dockerfile backend/

# ---- Build stage ----
# Pinned to $BUILDPLATFORM (the builder's own architecture), NOT the target platform.
# The output is a Java fat jar, which is architecture-independent: no module declares a
# native classifier and none of the poms use os-maven-plugin / ${os.detected.*}, so the
# same jar is correct for every target. Without this pin, a `--platform linux/arm64`
# build would run the whole Maven reactor under QEMU emulation on an amd64 runner -
# tens of minutes of emulated compilation for a byte-identical artifact. Only the
# runtime stage below is genuinely per-architecture (JRE, apk packages, tsx binary).
FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build
WORKDIR /app

# Optional TLS-interception root CA (corporate proxy / AV HTTPS scanning, e.g.
# Norton Web Shield): hosts that intercept HTTPS break Maven Central downloads
# inside the build (the container JVM does not trust the interception root).
# Pass the PEM via --build-arg BUILD_EXTRA_CA="$(cat root.pem)" (the e2e slot
# compose forwards $BUILD_EXTRA_CA). Empty (default) = strict no-op, CI-safe.
ARG BUILD_EXTRA_CA=""
# keytool -importcert reads only the FIRST certificate of a PEM file, so a
# bundle carrying several interception roots has to be split and imported one
# by one - otherwise every root but the first is silently dropped and the build
# dies on a PKIX error that names none of this.
RUN if [ -n "$BUILD_EXTRA_CA" ]; then \
      printf '%s\n' "$BUILD_EXTRA_CA" > /tmp/build-extra-ca.pem \
      && mkdir -p /tmp/build-extra-ca.d \
      && awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/build-extra-ca.d/ca-" n ".pem")}' /tmp/build-extra-ca.pem \
      && for cert in /tmp/build-extra-ca.d/*.pem; do \
           keytool -importcert -noprompt -trustcacerts \
             -alias "build-extra-ca-$(basename "$cert" .pem)" \
             -file "$cert" -cacerts -storepass changeit; \
         done \
      && echo "Imported $(ls /tmp/build-extra-ca.d | wc -l) extra build CA(s) into JVM truststore"; \
    fi

# Copy parent POM
COPY pom.xml .

# Copy ALL module POMs (Maven reactor requires them).
#
# test-support is a reactor module of the parent POM. Maven READS every declared module
# to build the reactor, even under -Dmaven.test.skip, so omitting one fails the image with
# "Child module /app/test-support of /app/pom.xml does not exist" before a single class is
# compiled. It was added to the parent POM by 5cc4005200 and never mirrored here, which is
# why the CE monolith image stopped building, and with it every local e2e slot.
#
# The wildcard is the reason it cannot happen again. This was 30 hand-maintained COPY lines,
# one per module, so a module added to the parent POM was simply absent here. The 13 service
# Dockerfiles already use this form, which is why only the CE image broke while CI stayed
# green. Naming the next module by hand would fix this instance and leave the next one.
COPY --parents */pom.xml ./

# Download dependencies (cached layer unless POMs change)
RUN mvn -B -Pce -DskipTests dependency:go-offline || true

# Copy all source code. Same reason as the POM wildcard above: an explicit list
# omits a new module silently, and the reactor read fails before any compile.
COPY . .

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

# Release-time model seed bundle, baked by CI (publish-ce build-images) into
# monolith-service/catalog-seeds/ BEFORE this build. SeedBundleBootstrap reads
# /app/catalog-seeds/model-bundle.json at first boot so a never-linked install
# gets the release-day model refresh. The dir always exists (.gitkeep); on
# local/from-source builds it holds only .gitkeep and the app falls back to the
# classpath model seed. Owned by root but world-readable (the app only reads it).
COPY monolith-service/catalog-seeds/ /app/catalog-seeds/

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
