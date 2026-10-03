/**
 * LC-027: the Report-Only CSP reports to /api/csp-report so violations are measured before the
 * strict policy is enforced. The endpoint is unauthenticated, so what it logs must be a
 * sanitized, rate-limited summary: directive + blocked HOST only, never a full URL.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { CspReportLogLimiter, parseCspReports, sanitizeBlocked } from '../cspReport';
import { POST } from '../../../app/api/csp-report/route';

const legacy = {
  'csp-report': {
    'document-uri': 'https://livecontext.ai/app/chat?token=SECRET_DOC',
    'effective-directive': 'script-src-elem',
    'violated-directive': 'script-src-elem',
    'blocked-uri': 'https://cdn.example.com/lib.js?sig=SECRET_SIG',
    'script-sample': 'alert(document.cookie)',
  },
};
const reportingApi = [
  { type: 'csp-violation', body: { effectiveDirective: 'img-src', blockedURL: 'https://bucket.r2.dev/a.png?X-Amz-Signature=SECRET' } },
  { type: 'deprecation', body: { id: 'x' } },
  { type: 'csp-violation', body: { effectiveDirective: 'script-src', blockedURL: 'inline' } },
];

describe('parseCspReports', () => {
  it('reads the legacy report-uri body and keeps only directive + host', () => {
    expect(parseCspReports(legacy)).toEqual([{ directive: 'script-src-elem', blocked: 'cdn.example.com' }]);
  });

  it('reads the Reporting API array, ignoring non-CSP reports', () => {
    expect(parseCspReports(reportingApi)).toEqual([
      { directive: 'img-src', blocked: 'bucket.r2.dev' },
      { directive: 'script-src', blocked: 'inline' },
    ]);
  });

  it('never lets log-forging text through', () => {
    const [s] = parseCspReports({ 'csp-report': { 'effective-directive': 'img-src\n[csp-report] fake', 'blocked-uri': 'evil\nline' } });
    expect(s).toEqual({ directive: 'img-src', blocked: 'other' });
    expect(parseCspReports({ 'csp-report': { 'effective-directive': '<script>' } })[0].directive).toBe('unknown');
  });

  it('reduces non-http schemes to the scheme and garbage to "other"', () => {
    expect(sanitizeBlocked('data:image/png;base64,AAAA')).toBe('data');
    expect(sanitizeBlocked('blob:https://livecontext.ai/uuid')).toBe('blob');
    expect(sanitizeBlocked('eval')).toBe('eval');
    expect(sanitizeBlocked('%%%')).toBe('other');
    expect(sanitizeBlocked(undefined)).toBe('none');
  });

  it('returns nothing for an unknown shape', () => {
    expect(parseCspReports({ hello: 1 })).toEqual([]);
    expect(parseCspReports('x')).toEqual([]);
  });
});

describe('CspReportLogLimiter', () => {
  it('logs a (directive, host) pair once per window and caps lines per window', () => {
    const limiter = new CspReportLogLimiter(2, 1000);
    const a = { directive: 'img-src', blocked: 'a.com' };
    expect(limiter.accept(a, 0)).toBe('[csp-report] directive=img-src blocked=a.com');
    expect(limiter.accept(a, 10)).toBeNull();
    expect(limiter.accept({ directive: 'img-src', blocked: 'b.com' }, 20)).not.toBeNull();
    expect(limiter.accept({ directive: 'img-src', blocked: 'c.com' }, 30)).toBeNull();
    // A new window starts over.
    expect(limiter.accept(a, 1000)).not.toBeNull();
  });
});

describe('POST /api/csp-report', () => {
  afterEach(() => vi.restoreAllMocks());

  it('answers 204 and logs only the summary, never a URL, token or sample', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const res = await POST(new Request('http://localhost/api/csp-report', {
      method: 'POST',
      headers: { 'content-type': 'application/csp-report' },
      body: JSON.stringify(legacy),
    }));
    expect(res.status).toBe(204);
    const logged = warn.mock.calls.map((c) => c.join(' ')).join('\n');
    expect(logged).toContain('directive=script-src-elem blocked=cdn.example.com');
    expect(logged).not.toMatch(/SECRET|alert|document-uri|https:/);
  });

  it('drops an oversized or unparseable body without logging', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const big = await POST(new Request('http://localhost/api/csp-report', { method: 'POST', body: 'x'.repeat(20_000) }));
    const junk = await POST(new Request('http://localhost/api/csp-report', { method: 'POST', body: '{not json' }));
    expect(big.status).toBe(204);
    expect(junk.status).toBe(204);
    expect(warn).not.toHaveBeenCalled();
  });
});
