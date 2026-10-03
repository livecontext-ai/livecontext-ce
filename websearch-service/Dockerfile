# Base image is ARG-overridable so CI can substitute the GHCR mirror
# (ghcr.io/<repo>/base/*) and dodge Docker Hub anonymous pull rate limits;
# the Docker Hub default keeps local/CE builds working unauthenticated.
# The defaults are pinned by digest (the multi-arch index digest, so every platform
# resolves). This ARG is the only copy of each digest: the GHCR mirror and the CI build
# read it from here (scripts/ci/base-images.sh), nothing to keep in sync elsewhere.
ARG PYTHON_IMAGE=python:3.12-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f
FROM ${PYTHON_IMAGE}

WORKDIR /app

# Install Google Chrome stable + Xvfb (virtual display for headed mode)
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget gnupg2 \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    google-chrome-stable \
    xvfb \
    # Fonts for proper page rendering
    fonts-liberation fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

# Python dependencies. browser-use 0.12.6 hard-pins versions this service must
# not run (pydantic==2.12.5, plus aiohttp, pillow, requests and mcp releases
# with known advisories; see requirements.in), so install the rest first
# (including the browser-use transitives we listed in requirements.txt - bubus,
# browser-use-sdk, inquirerpy, ollama, uuid7) and then layer browser-use with
# --no-deps on top, from its own hashed file. That gives runner.py a working
# `from browser_use import Agent` without breaking FastAPI. Both files carry sha256
# hashes, so pip refuses any file that differs from the lock (--require-hashes).
COPY requirements.txt requirements-browser-use.txt ./
RUN pip install --no-cache-dir --require-hashes -r requirements.txt \
 && pip install --no-cache-dir --no-deps --require-hashes -r requirements-browser-use.txt \
 && python -c "from browser_use import Agent, BrowserProfile; print('browser_use OK')"

COPY app/ ./app/

# Persistent Chrome profile for cookies
RUN mkdir -p /app/data/chrome_profile

# ── Drop root (LC-047) ──────────────────────────────────────────────────────
# This is the container that renders attacker-chosen pages, so a renderer
# escape lands on whatever uid this process holds. The CE composes start it
# under the `browser-agent` profile with no `user:` override and no
# podSecurityContext to fall back on, so this USER line is the only thing
# separating a crawl target from uid 0 in a self-hosted install.
#
# Running as root ALSO cost Chrome its own sandbox: Chrome refuses to start
# as uid 0 with it, and nodriver (app/services/browser_client.py passes no
# sandbox flag of its own) adds --no-sandbox automatically when it detects
# root. Under uid 1001 nodriver leaves the sandbox ON, so Chrome needs one of
# its two sandbox mechanisms to start at all:
#   1. the user-namespace sandbox: needs unprivileged user namespaces. The
#      compose grants seccomp:unconfined, but the HOST kernel can still refuse
#      them (Ubuntu >= 23.10 restricts them through AppArmor by default,
#      Debian/RHEL may set user.max_user_namespaces=0), and a self-hosted CE
#      host is whatever the user runs;
#   2. the setuid helper /opt/google/chrome/chrome-sandbox, which Chrome falls
#      back to when (1) is refused.
# The chmod 4755 below keeps (2) available. It is the upstream-supported way
# to run Chrome sandboxed without user namespaces: the helper is root-owned,
# shipped by the google-chrome package, and only sets up the sandbox and drops
# privileges. Removing it would make Chrome fail to start on those hosts, and
# the only way back would be --no-sandbox, which is strictly worse. Not
# verified by an image build in this change (the build needs dl.google.com):
# on the first CE browser-agent install, check the container runs as uid 1001
# and that a crawl succeeds.
# The chmod is conditional on purpose: the path is owned by the google-chrome
# package, so a package layout change must not turn this into a build break.
#
# HOME/XDG are pointed at the new user's own directory because Chrome writes
# its crash-dump, cache and singleton files under HOME on startup; leaving
# HOME=/root would make the browser fail to launch instead of failing closed.
# The per-run profile itself is a tempfile.mkdtemp under /tmp (see
# app/services/browser_client.py), which stays writable for any uid.
RUN if [ -f /opt/google/chrome/chrome-sandbox ]; then chmod 4755 /opt/google/chrome/chrome-sandbox; fi \
 && groupadd -g 1001 websearch \
 && useradd -u 1001 -g 1001 -m -d /home/websearch -s /usr/sbin/nologin websearch \
 && mkdir -p /tmp/.X11-unix \
 && chmod 1777 /tmp/.X11-unix \
 && chown -R 1001:1001 /app /home/websearch
ENV HOME=/home/websearch
ENV XDG_CACHE_HOME=/home/websearch/.cache
ENV XDG_CONFIG_HOME=/home/websearch/.config
USER 1001

# Xvfb display for headed (stealth) mode
ENV DISPLAY=:99
ENV BROWSER_HEADLESS=false

EXPOSE 8085

# Start Xvfb + uvicorn (headed Chrome needs a display)
CMD ["sh", "-c", "Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp &  uvicorn app.main:app --host 0.0.0.0 --port 8085 --workers 1"]
