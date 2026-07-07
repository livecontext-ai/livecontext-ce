/**
 * Enable commands for the deployment's OPTIONAL heavy components (self-hosted
 * docker). Single source shared by the builder inspector banners and the
 * Settings > Information "Optional components" card - the two surfaces must
 * always show the same command. Literal shell commands, never translated.
 */

/** Interface screenshot/PDF renderer sidecar (Playwright/Chromium). */
export const RENDERER_ENABLE_COMMAND =
  'docker compose --env-file docker/.env.ce.renderer up -d';

/** Browser agent + web search stack (Chromium browser-use + SearXNG). */
export const BROWSER_AGENT_ENABLE_COMMAND =
  'docker compose --env-file docker/.env.ce.browser-agent up -d';
