# Base images are ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub defaults keep local/CE builds working unauthenticated.
#
# PINNED to a patch tag on purpose. The floating `node:20-alpine` moved to Node
# 20.19 upstream, where `npm ci` dies on this lockfile with the (uninformative)
# "npm error Exit handler never called!" after ~80s - reproducible in a bare
# container with nothing but package.json + package-lock.json, and gone on
# 20.18. That broke every local and e2e-slot image build while CI kept working
# off its older GHCR mirror, which is the worst shape a base-image regression
# can take. Bump this tag deliberately, after checking `npm ci` on it.
ARG NODE_IMAGE=node:20.18-alpine
# Deliberately NOT pinned to $BUILDPLATFORM (unlike the monolith's Maven stage, which
# emits an architecture-independent jar). `next build` resolves and traces NATIVE
# node_modules into .next/standalone - @next/swc-linux-<arch>-musl and friends - so a
# build stage running on amd64 would bake amd64 binaries into an arm64 runtime image
# and the server would fail to start. Cross-building this stage is therefore a
# correctness bug, not an optimisation: on a multi-arch build the whole Next build runs
# once per target platform (emulated under QEMU when there is no native runner, which
# is the slowest part of a multi-arch CE release).
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
# reCAPTCHA v3 site key for the public /contact form (public by design - it ships in the
# client bundle; the matching secret lives on auth-service as RECAPTCHA_SECRET_KEY).
# Empty by default -> the form renders disabled with a "use email instead" notice, rather
# than accepting a message that grecaptcha.execute() would then reject.
ARG NEXT_PUBLIC_RECAPTCHA_SITE_KEY=
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
ENV NEXT_PUBLIC_RECAPTCHA_SITE_KEY=$NEXT_PUBLIC_RECAPTCHA_SITE_KEY

# CE image parity with the public CE repo: the hosted-SaaS legal pages
# (app/legal, which name a natural person for legal reasons) must not be
# compiled into the CE image, and the unused landing media (CE bypasses the
# marketing landing page) is dead weight. The public repo export already
# excludes app/legal (exclude-paths.txt); without this, `COPY . .` from the
# PRIVATE repo would bake it into the published image anyway. The landing media
# is a different case: the public REPO keeps landing/screenshots, because a fork
# building the frontend from source resolves EDITION to "cloud" and renders the
# landing page (GATE F pins that). Here the edition IS ce, so the page redirects
# to /app/chat and the media is unreachable, so dropping it only shrinks the
# image. Only string hrefs point at /legal/* (no imports), so the build is
# unaffected - the public-tree build (gate D) already proves Next builds
# without app/legal. publish-ce.yml's image content gate pins the legal-page
# absence pre-push.
RUN if [ "$NEXT_PUBLIC_APP_EDITION" = "ce" ]; then \
      rm -rf app/legal public/landing/screenshots \
      && rm -f public/landing/videos/builder-built-by-chat.webm; \
    fi

# Next's production build outgrew V8's default heap on the 8 GB build node
# (2026-09-02: "Reached heap limit Allocation failed" on three main builds in a
# row). Give the build step its own ceiling; the runtime stage is untouched.
RUN NODE_OPTIONS="--max-old-space-size=6144" npm run build

# ---- Runtime stage ----
FROM ${NODE_IMAGE}
WORKDIR /app

# The standalone tree is owned by the runtime user (1001, podSecurityContext in the
# helm chart), not root: Next writes revalidated ISR pages back into
# .next/server/app/*.html|.rsc|.meta at runtime. Copied as root, every revalidation
# re-rendered, logged "Failed to update prerender cache ... EACCES" and persisted
# nothing, on every pod. --chown on the COPY rather than a later `chown -R`, which
# would duplicate the whole server tree in a second layer. Trade-off, accepted: the
# runtime user can now write its own server code too (the shape of Next's reference
# Dockerfile). Rejected alternative: copy the tree as root, then re-COPY only
# .next with --chown; narrower, but it ships the .next tree twice in two layers.
COPY --chown=1001:1001 --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
COPY --from=build /app/messages ./messages

RUN mkdir -p .next/cache && chown -R 1001:1001 .next/cache

EXPOSE 3000
ENV HOSTNAME="0.0.0.0"
CMD ["node", "server.js"]
