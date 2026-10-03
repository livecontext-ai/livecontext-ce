# Pinned by DIGEST (LC-046): node:22-alpine alone is a moving target. The tag is kept as
# documentation. Bumping it is a deliberate commit, and it also fixes the CPython/musl ABI that
# bridge/requirements.txt was hash-locked against: read that file's header first.
FROM node:22.23.2-alpine@sha256:b6f26b36c8ff49624cfdac716b8ea1138d606df02586a77d364bb5536a634f85

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

# Node.js CLIs (Claude Code, Gemini CLI, Codex) - EXACT versions from a committed manifest
# (LC-046). They used to be "npm install -g <pkg>" with no version, so a compromised publish
# landed in the next build with no git diff, inside the process holding the provider keys.
# The loop REFUSES anything that is not an exact version and any line without an explicit
# install-script policy. "< /dev/null" keeps npm from eating the manifest on stdin, and the
# "tr -d '\r'" handles a CRLF checkout (core.autocrlf=true on Windows dev hosts / e2e slots).
#
# arm64 note: each of these ships prebuilt native binaries via optional dependencies
# (ripgrep for Claude Code, a Rust binary for Codex). They install on linux/arm64 musl,
# but this is the image most likely to break first if an upstream CLI drops an arm64
# musl build - check this step first when a multi-arch CE release fails.
# A third manifest column pins the tarball's sha512 integrity: the build then `npm pack`s the
# tarball, refuses it unless the hash matches, and installs that verified file. `-` = not pinned
# yet; BRIDGE_CLI_REQUIRE_INTEGRITY=1 turns such a line into a build failure.
ARG BRIDGE_CLI_REQUIRE_INTEGRITY=1
COPY bridge/agent-cli-versions.txt /tmp/agent-cli-versions.txt
RUN set -eu; \
    tr -d '\r' < /tmp/agent-cli-versions.txt > /tmp/agent-cli-versions.lf; \
    mkdir -p /tmp/agent-cli-pkgs; \
    while read -r spec policy integrity comment; do \
      case "$spec" in ''|\#*) continue ;; esac; \
      case "$spec" in \
        *@[0-9]*.[0-9]*.[0-9]*) ;; \
        *) echo "agent-cli-versions.txt: '$spec' is not pinned to an exact version" >&2; exit 1 ;; \
      esac; \
      case "$policy" in \
        ignore-scripts) scripts_flag=--ignore-scripts ;; \
        run-scripts)    scripts_flag= ;; \
        *) echo "agent-cli-versions.txt: '$spec' declares no install-script policy" >&2; exit 1 ;; \
      esac; \
      case "$integrity" in \
        sha512-*) \
          tgz="$(cd /tmp/agent-cli-pkgs && npm pack "$spec" --silent < /dev/null | tail -n 1)"; \
          actual="$(node -e 'const c=require("crypto"),f=require("fs");process.stdout.write("sha512-"+c.createHash("sha512").update(f.readFileSync(process.argv[1])).digest("base64"))' "/tmp/agent-cli-pkgs/$tgz")"; \
          if [ "$actual" != "$integrity" ]; then \
            echo "agent-cli-versions.txt: $spec tarball integrity mismatch (expected $integrity, got $actual)" >&2; exit 1; \
          fi; \
          echo "installing $spec from a verified tarball ($policy)"; \
          npm install -g $scripts_flag "/tmp/agent-cli-pkgs/$tgz" < /dev/null ;; \
        *) \
          if [ "$BRIDGE_CLI_REQUIRE_INTEGRITY" = "1" ]; then \
            echo "agent-cli-versions.txt: $spec has no integrity pin and BRIDGE_CLI_REQUIRE_INTEGRITY=1" >&2; exit 1; \
          fi; \
          echo "installing $spec by exact version, integrity NOT pinned ($policy)"; \
          npm install -g $scripts_flag "$spec" < /dev/null ;; \
      esac; \
    done < /tmp/agent-cli-versions.lf; \
    rm -rf /tmp/agent-cli-versions.txt /tmp/agent-cli-versions.lf /tmp/agent-cli-pkgs

# Python for Mistral Vibe - fully pinned, hash-verified closure (LC-046): --require-hashes
# refuses any artifact whose sha256 is not in bridge/requirements.txt, and --no-deps is safe
# because the file IS the complete resolved closure. Read its header before regenerating.
# arm64 note: tree-sitter (a mistral-vibe dependency) publishes musllinux wheels for
# x86_64 ONLY, so on this alpine image pip falls back to building it from source on
# aarch64 - and the image carries no C toolchain, so the step died with
# "error: [Errno 2] No such file or directory: 'cc'" on the first multi-arch release.
# The toolchain is a virtual apk package installed and removed inside the SAME layer, so
# neither published arch ships it. Every other dependency resolved to a wheel on both
# arches; if a future one needs Rust instead, this is the step that will say so.
COPY bridge/requirements.txt /tmp/requirements.txt
RUN apk add --no-cache --virtual .py-build-deps build-base python3-dev \
    && tr -d '\r' < /tmp/requirements.txt > /tmp/requirements.lf \
    && pip3 install --break-system-packages --require-hashes --no-deps -r /tmp/requirements.lf \
    && apk del .py-build-deps \
    && rm -f /tmp/requirements.txt /tmp/requirements.lf

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

# Container topology (LC-001). Inside a container the listener must bind the container
# interface to be reachable from the app container on the compose network (no shipped compose
# file publishes the port to the host). Gateway HMAC enforcement is left in its AUTO mode
# (BRIDGE_REQUIRE_GATEWAY_AUTH unset): enforced whenever a shared secret is available. The CE
# compose files provide one through the livecontext_bridge_secret volume
# (BRIDGE_SHARED_SECRET_FILE); an older compose file without it keeps working unauthenticated,
# restricted, with a WARN at boot.
ENV BRIDGE_BIND_ADDRESS=0.0.0.0

WORKDIR /app/bridge

# Drop root for the runtime (LC-046). Everything above is installed as root and stays
# root-owned, so /app and the global CLI trees are read-only to this user: the agent the bridge
# spawns (same uid) can no longer rewrite the bridge code or the CLIs it executes. node:22-alpine
# ships "node" (uid 1000, HOME=/home/node), /tmp is world-writable, and the runtime writes only
# under os.tmpdir() (mkdtemp per run) and the CLIs' own HOME dirs. 8093 is unprivileged.
USER node

CMD ["node", "server.mjs"]
