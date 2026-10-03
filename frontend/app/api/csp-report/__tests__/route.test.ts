import { describe, it, expect, vi, afterEach } from 'vitest';
import { POST } from '../route';
import { MAX_REPORT_BYTES } from '@/lib/security/cspReport';

function streamOf(bytes: number): ReadableStream<Uint8Array> {
  let sent = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (sent >= bytes) {
        controller.close();
        return;
      }
      const size = Math.min(1024, bytes - sent);
      sent += size;
      controller.enqueue(new Uint8Array(size).fill(0x20));
    },
  });
}

const report = JSON.stringify({
  'csp-report': {
    'effective-directive': 'script-src',
    'blocked-uri': 'https://evil.example/x.js?token=secret',
  },
});

describe('POST /api/csp-report', () => {
  afterEach(() => vi.restoreAllMocks());

  it('stops reading a body without Content-Length once it exceeds the cap', async () => {
    // Pre-fix: request.text() buffered the whole body before the length check, so omitting
    // Content-Length forced an unbounded read. The stream here is 64x the cap; the handler must
    // cancel it and answer 204 without logging.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const body = streamOf(MAX_REPORT_BYTES * 64);
    const request = new Request('http://localhost/api/csp-report', {
      method: 'POST',
      body,
      // @ts-expect-error duplex is required by undici for streamed request bodies
      duplex: 'half',
    });

    const res = await POST(request);

    expect(res.status).toBe(204);
    expect(warn).not.toHaveBeenCalled();
  });

  it('logs a sanitized summary for a normal report, never the full URL', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const res = await POST(new Request('http://localhost/api/csp-report', {
      method: 'POST',
      body: report,
      headers: { 'content-type': 'application/csp-report' },
    }));

    expect(res.status).toBe(204);
    const logged = warn.mock.calls.map(c => String(c[0])).join('\n');
    expect(logged).toContain('script-src');
    expect(logged).not.toContain('token=secret');
  });
});
