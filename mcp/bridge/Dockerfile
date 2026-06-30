FROM node:20-alpine

WORKDIR /app

ARG BUILD_EXTRA_CA=""

# TLS-interception hosts (AV/corporate proxy): export the interception root PEM
# into $BUILD_EXTRA_CA so npm/pip downloads work during image build.
RUN if [ -n "$BUILD_EXTRA_CA" ]; then \
      printf '%s\n' "$BUILD_EXTRA_CA" > /tmp/build-extra-ca.pem \
      && cat /tmp/build-extra-ca.pem >> /etc/ssl/certs/ca-certificates.crt \
      && echo "Bootstrapped extra build CA for apk"; \
    fi

RUN apk add --no-cache ca-certificates python3 py3-pip \
    && if [ -s /tmp/build-extra-ca.pem ]; then \
         mkdir -p /usr/local/share/ca-certificates \
         && cp /tmp/build-extra-ca.pem /usr/local/share/ca-certificates/build-extra-ca.crt \
         && update-ca-certificates \
         && echo "Installed extra build CA into the system store"; \
       fi

# Point npm AND pip at the COMBINED system CA bundle (public roots + the extra
# build CA just appended via update-ca-certificates), not the extra CA alone.
# Both npm's `cafile` and pip's `global.cert` REPLACE the trust store (they do
# not append), so pointing them at the interception root alone made
# MITM-intercepted hosts (registry / PyPI metadata) validate while NON-intercepted
# CDN tarballs failed with UNABLE_TO_GET_ISSUER_CERT_LOCALLY. The combined bundle
# trusts both the public roots and the extra CA, so every host validates.
RUN if [ -s /tmp/build-extra-ca.pem ]; then \
      npm config set cafile /etc/ssl/certs/ca-certificates.crt \
      && pip config set global.cert /etc/ssl/certs/ca-certificates.crt; \
    fi

# Node.js CLIs (Claude Code, Gemini CLI, Codex)
RUN npm install -g @anthropic-ai/claude-code
RUN npm install -g @google/gemini-cli
RUN npm install -g @openai/codex

# Python for Mistral Vibe
RUN pip3 install --break-system-packages mistral-vibe

# Install root MCP dependencies (@modelcontextprotocol/sdk for agent-cli-server.mjs)
COPY package.json package-lock.json ./

# Copy source. agent-cli-server.mjs statically imports its top-level mcp siblings
# (repo-tool.mjs, shell-tool.mjs) - every sibling it imports MUST be copied here or
# the MCP subprocess dies with ERR_MODULE_NOT_FOUND at first agent run. The lane-2
# systemd tar ships the same set; dockerfileBridgeCopy.test.mjs pins this contract.
COPY agent-cli-server.mjs ./
COPY repo-tool.mjs ./
COPY shell-tool.mjs ./

# --no-audit --no-fund: the audit request hits the registry over HTTPS, and a
# TLS-interception proxy (corporate/AV MITM) makes that request fail TLS
# verification, which npm 10.8.x turns into a hard "Exit handler never called!"
# crash (intermittent) instead of degrading. The install does not need audit, so
# skipping it makes the build deterministic in CI and robust behind a MITM proxy.
RUN npm ci --omit=dev --no-audit --no-fund \
    && node --input-type=module -e "await import('diff'); await import('@modelcontextprotocol/sdk/server/index.js')"

COPY bridge/ ./bridge/

# Install after copying the source so the final image layer always owns node_modules.
RUN cd bridge && npm ci --omit=dev --no-audit --no-fund \
    && test -f node_modules/express/index.js \
    && test -f node_modules/ioredis/built/index.js

EXPOSE 8093

WORKDIR /app/bridge
CMD ["node", "server.mjs"]
