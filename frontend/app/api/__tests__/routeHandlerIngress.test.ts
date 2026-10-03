import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Every Next route handler under `app/api` is either carved out of the cloud ingress or
 * deliberately answered by something else. This test forces that choice to be made.
 *
 * The cloud ingress sends `/api` to the GATEWAY and carves out only the specific prefixes
 * that belong to the frontend. A handler that needs one and does not have it is not an
 * error anywhere: the gateway answers its own 404, the client degrades to "no data", and
 * the feature is simply absent in production while every test, every CE install and every
 * local run stay green - CE serves Next directly, so the topology that breaks is the one
 * nothing exercises.
 *
 * It has now happened twice. `/api/pricing-event` on 2026-08-25 (silently no price
 * window), and `/api/status` on 2026-09-05: `/status` renders server-side and looks
 * perfect, while `useServiceStatus` reads `/api/status` from the browser and got the
 * gateway's 404, so the user-menu dot and the in-app incident strip never showed anything.
 *
 * `lib/billing/__tests__/pricing-event-ingress.test.ts` pins the first case and the
 * longest-prefix-match reasoning behind the carve-out. This one generalises it: it
 * discovers the handlers instead of listing them, so a NEW route handler cannot be added
 * without classifying it here.
 */

const VALUES_FILES = [
  'values.yaml',
  'values-prod.yaml',
  'values-preprod.yaml',
  'values-staging-cx33.yaml',
  'values-staging.example.yaml',
];

/** Handlers the cloud ingress must route to the frontend. */
const FRONTEND_CARVE_OUTS = [
  '/api/pricing-event',
  '/api/proxy',
  '/api/status',
  '/api/csp-report',
];

/**
 * Handlers whose path the GATEWAY owns in cloud. Each of these is a pass-through mirror
 * of the identical backend endpoint (it forwards to `NEXT_PUBLIC_SPRING_BASE_URL`), so
 * letting the ingress reach the gateway directly is the same answer with one hop less.
 * They exist for CE, where Next is served directly and there is no ingress.
 */
const GATEWAY_OWNED = [
  '/api/workflows',
  '/api/workflow-inspector',
];

/**
 * Handlers cloud never calls. `/api/runtime-config` is read only by `fetchGatewayRuntimeConfig`,
 * which is gated on `IS_CE` (see lib/websocket/gatewayUrl.ts): cloud resolves the gateway
 * origin from the build-time value plus the same-origin fallback and never fetches it.
 */
const CE_ONLY = ['/api/runtime-config'];

/**
 * Handlers that must NOT be routed from the ingress, because nothing outside the app calls
 * them. `/api/external-proxy` is invoked in process by `/api/proxy/external-proxy` (see
 * `callExternalProxyLocally`), and it is a URL fetcher: authenticated and SSRF-guarded, but
 * there is no reason to publish it. It was reachable by nobody in cloud until 2026-09-05
 * (the proxy re-fetched it over the public origin and got the gateway's 404 - the MCP "Test"
 * tab failed in cloud and worked in CE); the fix removed the hop rather than the guard, so
 * the absence of a carve-out is now a property to keep, not a gap to close.
 */
const NOT_PUBLICLY_ROUTED = ['/api/external-proxy'];

function readValues(file: string): string {
  return readFileSync(join(process.cwd(), '..', 'deploy', 'helm', 'livecontext', file), 'utf8');
}

/** The `service:` a path block declares, or null when the path is absent. */
function serviceForPath(yaml: string, path: string): string | null {
  const lines = yaml.split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim() === `- path: ${path}`);
  if (start === -1) return null;
  for (const line of lines.slice(start + 1, start + 4)) {
    const match = line.match(/^\s*service:\s*(\S+)/);
    if (match) return match[1];
    if (line.trim().startsWith('- path:')) break;
  }
  return null;
}

/**
 * The prefix the ingress actually matches for a handler: its path up to the first dynamic
 * segment. `app/api/workflows/[id]/runs/route.ts` is reached under `/api/workflows`, and a
 * carve-out could not name the `[id]` anyway - ingress-nginx matches literal prefixes.
 */
function ingressPrefixFor(segments: string[]): string {
  const stable: string[] = [];
  for (const segment of segments) {
    if (segment.startsWith('[')) break;
    stable.push(segment);
  }
  return `/api/${stable.join('/')}`;
}

/**
 * An ingress rule is a PREFIX rule, so one entry covers everything nested under it:
 * `/api/workflows` answers for `app/api/workflows/runs/[runId]/steps` too. Classification
 * therefore matches on segment boundaries, never on a bare `startsWith` (which would let
 * `/api/status` claim a hypothetical `/api/status-page`).
 */
function isCoveredBy(handlerPrefix: string, entry: string): boolean {
  return handlerPrefix === entry || handlerPrefix.startsWith(`${entry}/`);
}

function discoverHandlerPrefixes(): string[] {
  const root = join(process.cwd(), 'app', 'api');
  const found = new Set<string>();

  const walk = (dir: string, segments: string[]) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) {
        if (entry.name === '__tests__') continue;
        walk(join(dir, entry.name), [...segments, entry.name]);
      } else if (entry.name === 'route.ts' || entry.name === 'route.tsx') {
        found.add(ingressPrefixFor(segments));
      }
    }
  };

  walk(root, []);
  return [...found].sort();
}

describe('Next route handlers versus the cloud ingress', () => {
  const handlers = discoverHandlerPrefixes();

  it('finds the route handlers at all (a silent zero would make every case below vacuous)', () => {
    expect(handlers.length).toBeGreaterThanOrEqual(5);
    expect(handlers).toContain('/api/status');
  });

  it('classifies every handler, so a new one cannot be added without deciding', () => {
    const classified = [
      ...FRONTEND_CARVE_OUTS,
      ...GATEWAY_OWNED,
      ...CE_ONLY,
      ...NOT_PUBLICLY_ROUTED,
    ];
    const unclassified = handlers.filter(
      (prefix) => !classified.some((entry) => isCoveredBy(prefix, entry)),
    );

    // If this fails, the new handler is not broken yet - it is undecided. Either the
    // browser calls it (add a carve-out to every values file and list it in
    // FRONTEND_CARVE_OUTS), or the gateway/CE owns it (list it in the matching bucket).
    expect(unclassified).toEqual([]);
  });

  it.each(FRONTEND_CARVE_OUTS)('%s is a real handler, not a stale entry', (entry) => {
    expect(handlers.some((prefix) => isCoveredBy(prefix, entry))).toBe(true);
  });

  describe.each(VALUES_FILES)('%s', (file) => {
    it.each(FRONTEND_CARVE_OUTS)('routes %s to the frontend', (prefix) => {
      expect(serviceForPath(readValues(file), prefix)).toBe('frontend');
    });

    it('still sends the rest of /api to the gateway', () => {
      expect(serviceForPath(readValues(file), '/api')).toBe('gateway');
    });

    it.each(NOT_PUBLICLY_ROUTED)('does not publish %s (it is called in process)', (prefix) => {
      expect(serviceForPath(readValues(file), prefix)).toBeNull();
    });
  });

  it('carves out strictly longer prefixes than the catch-all they escape', () => {
    // ingress-nginx resolves Prefix rules longest-match-first, which is the only reason a
    // carve-out wins over "/api". An equal or shorter path would be shadowed and the 404
    // would come straight back.
    for (const prefix of FRONTEND_CARVE_OUTS) {
      expect(prefix.startsWith('/api/')).toBe(true);
      expect(prefix.length).toBeGreaterThan('/api'.length);
    }
  });
});
