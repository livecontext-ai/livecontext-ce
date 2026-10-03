import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const FILTER_PATH = join(__dirname, '..', 'stripInternalResponseHeaders.cjs');
const { isInternalRewriteDisclosure, installInternalHeaderFilter } = require(FILTER_PATH) as {
  isInternalRewriteDisclosure: (name: unknown, value: unknown) => boolean;
  installInternalHeaderFilter: (http: unknown) => boolean;
};

/**
 * Regression (CASA DAST): every `/api/proxy/*` response carried
 * `x-middleware-rewrite: http://livecontext-livecontext-gateway:8080/api/...`, the in-cluster
 * gateway address, because Next copies a middleware rewrite target into the client response.
 */
describe('isInternalRewriteDisclosure', () => {
  it.each([
    ['x-middleware-rewrite', 'http://livecontext-livecontext-gateway:8080/api/auth/me'],
    ['X-Middleware-Rewrite', 'https://gateway.internal/api/x'],
    ['x-middleware-rewrite', ['http://10.0.0.5:8080/api/x']],
  ])('flags %s with an absolute target', (name, value) => {
    expect(isInternalRewriteDisclosure(name, value)).toBe(true);
  });

  it.each([
    // Same-origin rewrites: Next's client router reads these on RSC navigations.
    ['x-middleware-rewrite', '/_not-found'],
    ['x-middleware-rewrite', '/en/docs/agents?x=1'],
    ['content-type', 'http://looks-like-a-url.example'],
    ['location', 'https://livecontext.ai/en'],
  ])('leaves %s=%s alone', (name, value) => {
    expect(isInternalRewriteDisclosure(name, value)).toBe(false);
  });
});

describe('installInternalHeaderFilter', () => {
  it('drops only the disclosing header and installs once', () => {
    const written: Record<string, unknown> = {};
    class FakeResponse {
      setHeader(name: string, value: unknown) { written[name] = value; return this; }
    }
    const fakeHttp = { ServerResponse: FakeResponse };

    expect(installInternalHeaderFilter(fakeHttp)).toBe(true);
    expect(installInternalHeaderFilter(fakeHttp)).toBe(false);

    const res = new FakeResponse();
    res.setHeader('x-middleware-rewrite', 'http://livecontext-livecontext-gateway:8080/api/x');
    res.setHeader('x-middleware-rewrite-relative', '/kept');
    res.setHeader('content-type', 'application/json');

    expect(written).not.toHaveProperty('x-middleware-rewrite');
    expect(written['content-type']).toBe('application/json');
    expect(written['x-middleware-rewrite-relative']).toBe('/kept');
  });

  it('is effective when preloaded with node --require on a real HTTP server', () => {
    // The exact launch form of the runtime image: a real ServerResponse, real header list.
    const script = `
      const http = require('node:http');
      const server = http.createServer((req, res) => {
        res.setHeader('x-middleware-rewrite', 'http://livecontext-livecontext-gateway:8080/api/x');
        res.setHeader('x-kept', '/relative');
        res.end('ok');
      });
      server.listen(0, '127.0.0.1', () => {
        http.get({ host: '127.0.0.1', port: server.address().port, path: '/' }, (r) => {
          process.stdout.write(JSON.stringify(r.headers));
          r.resume();
          r.on('end', () => server.close());
        });
      });`;
    const out = execFileSync(process.execPath, ['--require', FILTER_PATH, '-e', script], {
      encoding: 'utf8',
      timeout: 10000,
    });
    const headers = JSON.parse(out) as Record<string, string>;
    expect(headers['x-middleware-rewrite']).toBeUndefined();
    expect(headers['x-kept']).toBe('/relative');
  });

  it('is wired into the runtime image launch command', () => {
    const dockerfile = readFileSync(join(__dirname, '..', '..', '..', 'Dockerfile'), 'utf8');
    expect(dockerfile).toMatch(
      /COPY --from=build \/app\/lib\/security\/stripInternalResponseHeaders\.cjs \.\/strip-internal-response-headers\.cjs/,
    );
    expect(dockerfile).toMatch(/CMD \["node", "--require", "\.\/strip-internal-response-headers\.cjs", "server\.js"\]/);
  });
});
