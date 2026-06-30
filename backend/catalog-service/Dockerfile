# syntax=docker/dockerfile:1.7-labs
# Base images are ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub defaults keep local/CE builds working unauthenticated.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
# ---- Build stage ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /app

# POMs-first dependency caching: copy parent + module POMs (--parents keeps
# the tree), resolve deps INTO the layer (NO ~/.m2 cache mount - mounts are
# not exported to the registry cache, so they're empty on ephemeral CI
# runners). Keyed on the POM set only ⇒ unchanged POMs hit the registry
# cache and skip the ~1min dependency download. Sources copied after, so a
# code change only re-runs the compile. See gateway/Dockerfile for detail.
COPY pom.xml ./
COPY --parents */pom.xml ./
RUN mvn -B -DskipTests -pl catalog-service -am dependency:go-offline

COPY . .
RUN mvn -B -DskipTests -pl catalog-service -am clean package

# ---- Runtime stage ----
FROM ${JRE_IMAGE}
RUN apk add --no-cache curl
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup
WORKDIR /app

COPY --from=build /app/catalog-service/target/app.jar /app/app.jar

USER appuser
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
