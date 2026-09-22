# screenshot-renderer

Optional sidecar that turns an interface page into a PNG/JPEG screenshot or a PDF, and
transcodes recorded video. A small Node service wrapping Playwright and Chromium, with no
database and no secrets: HTML in, bytes out.

It exists as a separate container because embedding Playwright in the backend would drag
roughly 250 MB of Chromium into the JVM image for a feature most installs never enable.

**This directory is the corresponding source of the published
`ghcr.io/livecontext-ai/livecontext-ce-screenshot-renderer` image.** You do not
need to build it to run LiveContext: the `renderer` Compose profile pulls the prebuilt
image.

## Enabling it in a LiveContext install

From the repository root:

```bash
docker compose --env-file docker/.env.ce.renderer up -d
```

That env file starts this container and points the backend at it in one step. Without it
the interface node simply produces no screenshot or PDF output, and workflows still run.

## Running it on its own

```bash
cd screenshot-renderer
npm ci
node server.js          # listens on $PORT, default 8094
npm test                # unit tests for the request-validation and media helpers
```

Health check: `GET /internal/health`.

## Building the image

```bash
docker build -t livecontext-ce-screenshot-renderer .
```

The base image is pinned to the exact Playwright version in `package-lock.json`; the JS
client and the image's bundled Chromium have to match, so bump both together or the browser
fails to launch.
