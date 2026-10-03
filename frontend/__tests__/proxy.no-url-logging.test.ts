// @vitest-environment node
import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { NextRequest } from 'next/server';

vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import { proxy } from '../proxy';

/**
 * LC-067, LIVE half. `proxy.ts` is the proxy that serves every `/api/proxy/*` request except
 * `external-proxy` (see the header of `app/api/proxy/[...path]/route.ts`), so the redaction
 * list in route.ts only protects that one path. What protects everything else is that this
 * middleware logs NOTHING: a signed URL (`files/proxy-signed?...&sig=...`) is a working
 * anonymous download link, and so is any query the gateway treats as a capability. This file
 * pins that absence, behaviourally and at the source.
 */
const SECRET = 'CAPABILITY-SIG-VALUE';
const CONSOLE_METHODS = ['log', 'info', 'warn', 'error', 'debug', 'trace'] as const;

describe('proxy.ts does not log request URLs (LC-067)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('writes nothing to the console while proxying a signed capability URL', () => {
    const spies = CONSOLE_METHODS.map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));

    proxy(new NextRequest(`http://localhost:3000/api/proxy/files/proxy-signed?key=k&exp=1&sig=${SECRET}`));
    proxy(new NextRequest(`http://localhost:3000/api/proxy/some/path?token=${SECRET}`, { method: 'OPTIONS' }));

    for (const spy of spies) {
      const logged = spy.mock.calls.map((call) => call.map(String).join(' ')).join('\n');
      expect(logged).not.toContain(SECRET);
    }
  });

  it('contains no console call at all, so a future log line is a reviewed change', () => {
    const source = readFileSync(join(__dirname, '..', 'proxy.ts'), 'utf8');

    expect(source).not.toMatch(/\bconsole\.(log|info|warn|error|debug|trace)\s*\(/);
  });
});
