# Screenshot renderer sidecar - minimal Playwright + Chromium service.
# Runs as its own container; the orchestrator reaches it over service DNS via
# SCREENSHOT_RENDERER_URL. See README.md.
#
# This file SHIPS PUBLICLY: it is the source behind the published
# livecontext-ce-screenshot-renderer image, exported to the public CE repo as
# screenshot-renderer/. Keep its comments English and free of internal paths,
# cluster namespaces and CI workflow names - the public tree excludes deploy/,
# infra/ and .github/, so a reference to any of them dangles there.

# Base image pinned to the EXACT playwright client version in the lockfile (1.60.0).
# The JS client and the image's Chromium binaries MUST match, otherwise
# "Executable doesn't exist at /ms-playwright/...". package.json pins 1.60.0
# (not ^) so a future npm cannot drift past this image.
FROM mcr.microsoft.com/playwright:v1.60.0-jammy

# CJK + emoji fonts so PDF/screenshot renders of non-latin interfaces (zh and friends) are not
# boxes-of-tofu. The base playwright image ships latin fonts only. ffmpeg backs the
# /internal/render/video webm->mp4 transcode (Playwright records webm only). Build runs as
# root; the runtime user is set by the k8s securityContext, so this stays least-privilege at
# run time.
#
# fonts-dejavu-core is REQUIRED, not cosmetic: it provides "DejaVu Sans", the default family
# the media `subtitles` operation burns captions in. libass never errors on a missing family,
# it silently substitutes one, so dropping this package would ship videos captioned in the
# wrong face with every request still answering 200. The operation cross-checks the family
# with fc-match at run time and refuses the render rather than substituting, which turns a
# missing package into a loud failure instead of a silent one.
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk fonts-noto-color-emoji fonts-dejavu-core ffmpeg \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# TLS-interception hosts (AV/corporate proxy): export the interception root PEM into
# $BUILD_EXTRA_CA so in-image npm downloads verify. Mirrors mcp/bridge/Dockerfile;
# a no-op (empty arg) on CI and normal hosts.
ARG BUILD_EXTRA_CA=""
RUN if [ -n "$BUILD_EXTRA_CA" ]; then \
      printf '%s\n' "$BUILD_EXTRA_CA" > /usr/local/share/ca-certificates/build-extra-ca.crt \
      && update-ca-certificates \
      && npm config set -g cafile /etc/ssl/certs/ca-certificates.crt \
      && echo "Installed extra build CA into the system store"; \
    fi

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# lib.js carries the pure request-validation + concurrency helpers, media.js the
# /internal/media multipart+ffmpeg glue - all three files MUST land in the image or
# `require('./lib')` / `require('./media')` fails at boot.
COPY server.js lib.js media.js ./

ENV PORT=8094
EXPOSE 8094

CMD ["node", "server.js"]
