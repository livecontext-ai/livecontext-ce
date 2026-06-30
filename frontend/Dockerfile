# Base images are ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub defaults keep local/CE builds working unauthenticated.
ARG NODE_IMAGE=node:20-alpine
FROM ${NODE_IMAGE} AS build
WORKDIR /app

# Optional TLS-interception root CA (corporate proxy / AV HTTPS scanning):
# next/font fetches Google Fonts at build time and Node does not trust the
# interception root. Pass the PEM via --build-arg BUILD_EXTRA_CA="..." (the
# e2e slot compose forwards $BUILD_EXTRA_CA). Empty (default) = no-op (Node
# only warns about the empty extra-CA file).
ARG BUILD_EXTRA_CA=""
# Two trust stores needed: NODE_EXTRA_CA_CERTS for Node fetches, AND the
# system store for Turbopack's native (Rust) HTTP client which ignores the
# Node variable (next/font Google Fonts fetch goes through it).
# No apk here: the package CDN is itself unreachable behind the interception,
# so the PEM is appended straight to the pre-installed system bundle (read by
# rustls-native-certs on Linux).
RUN printf '%s\n' "$BUILD_EXTRA_CA" > /tmp/build-extra-ca.pem \
    && if grep -q 'BEGIN CERTIFICATE' /tmp/build-extra-ca.pem; then \
         cat /tmp/build-extra-ca.pem >> /etc/ssl/certs/ca-certificates.crt \
         && echo "Appended extra build CA to the system bundle"; \
       fi
ENV NODE_EXTRA_CA_CERTS=/tmp/build-extra-ca.pem

COPY package.json package-lock.json* ./
RUN npm ci && test -x node_modules/.bin/next

COPY . .

# NEXT_PUBLIC_* vars must be present at build time (baked into client JS bundle)
ARG NEXT_PUBLIC_APP_EDITION=cloud
ARG NEXT_PUBLIC_AUTH_MODE=oidc
ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8180
ARG NEXT_PUBLIC_KEYCLOAK_REALM=livecontext
ARG NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=livecontext-frontend
ARG NEXT_PUBLIC_GATEWAY_WS_URL=http://localhost:8080
ARG NEXT_PUBLIC_SPRING_BASE_URL=http://localhost:8080
# PostHog product analytics. Empty by default → the bundle ships with analytics
# OFF (isAnalyticsConfigured() === false) for CE/local builds. Cloud passes the
# publishable write-only ingest key as a build-arg. Host defaults to EU (GDPR).
ARG NEXT_PUBLIC_POSTHOG_KEY=
ARG NEXT_PUBLIC_POSTHOG_HOST=https://eu.i.posthog.com
# Browser API key for the Google Drive Picker (referrer-restricted, public by design - it ships
# in the client bundle). Empty by default → the Drive picker shows an error until configured.
ARG NEXT_PUBLIC_GOOGLE_PICKER_API_KEY=
ENV NEXT_PUBLIC_APP_EDITION=$NEXT_PUBLIC_APP_EDITION
ENV NEXT_PUBLIC_AUTH_MODE=$NEXT_PUBLIC_AUTH_MODE
ENV NEXT_PUBLIC_KEYCLOAK_URL=$NEXT_PUBLIC_KEYCLOAK_URL
ENV NEXT_PUBLIC_KEYCLOAK_REALM=$NEXT_PUBLIC_KEYCLOAK_REALM
ENV NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=$NEXT_PUBLIC_KEYCLOAK_CLIENT_ID
ENV NEXT_PUBLIC_GATEWAY_WS_URL=$NEXT_PUBLIC_GATEWAY_WS_URL
ENV NEXT_PUBLIC_SPRING_BASE_URL=$NEXT_PUBLIC_SPRING_BASE_URL
ENV NEXT_PUBLIC_POSTHOG_KEY=$NEXT_PUBLIC_POSTHOG_KEY
ENV NEXT_PUBLIC_POSTHOG_HOST=$NEXT_PUBLIC_POSTHOG_HOST
ENV NEXT_PUBLIC_GOOGLE_PICKER_API_KEY=$NEXT_PUBLIC_GOOGLE_PICKER_API_KEY

RUN npm run build

# ---- Runtime stage ----
FROM ${NODE_IMAGE}
WORKDIR /app

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
COPY --from=build /app/messages ./messages

RUN mkdir -p .next/cache && chown -R 1001:1001 .next/cache

EXPOSE 3000
ENV HOSTNAME="0.0.0.0"
CMD ["node", "server.js"]
