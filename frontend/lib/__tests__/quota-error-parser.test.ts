import { describe, it, expect } from 'vitest';
import {
  parseQuotaCapExceeded,
  formatQuotaMessage,
  type QuotaCapExceededInfo,
} from '../quota-error-parser';

/**
 * PR11c - pure parser unit tests. Closes audit-3 + audit-4 concern that
 * the parser was "dead code". The parser is intentionally un-wired in v1
 * (wire-up location deferred to PR11e per plan.md §11.bis PR11e block),
 * but it MUST be correct for that wire-up to land safely. These tests
 * pin the parser's contract against the backend wire format
 * `QUOTA_CAP_EXCEEDED:<dim>:<consumed>/<cap>` emitted by
 * `CreditService.CreditConsumeResult.quotaCapExceeded`.
 */
describe('quota-error-parser', () => {
  describe('parseQuotaCapExceeded', () => {
    it('parses canonical wire format with BigDecimal trailing zeros', () => {
      const r = parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:credits:99.0000/100.0000');
      expect(r).toEqual<QuotaCapExceededInfo>({
        dimension: 'credits',
        consumed: 99,
        cap: 100,
      });
    });

    it('parses storage dimension', () => {
      const r = parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:storage:5368709120/10737418240');
      expect(r?.dimension).toBe('storage');
      expect(r?.consumed).toBe(5368709120);
      expect(r?.cap).toBe(10737418240);
    });

    it('parses tokens dimension', () => {
      const r = parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:tokens:999/1000');
      expect(r?.dimension).toBe('tokens');
    });

    it('returns null on missing prefix (caller falls through to generic 402)', () => {
      expect(parseQuotaCapExceeded('Insufficient credits: balance=0, required=5')).toBeNull();
      expect(parseQuotaCapExceeded('No active subscription')).toBeNull();
    });

    it('returns null on null / undefined / non-string input (defensive)', () => {
      expect(parseQuotaCapExceeded(null)).toBeNull();
      expect(parseQuotaCapExceeded(undefined)).toBeNull();
      // @ts-expect-error - testing defensive runtime behavior
      expect(parseQuotaCapExceeded(123)).toBeNull();
    });

    it('returns null on malformed payload (no colon after prefix)', () => {
      expect(parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:nocolon')).toBeNull();
    });

    it('returns null on missing slash (no consumed/cap separator)', () => {
      expect(parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:credits:99100')).toBeNull();
    });

    it('returns null on non-numeric values', () => {
      expect(parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:credits:abc/def')).toBeNull();
      expect(parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:credits:99/abc')).toBeNull();
    });

    it('handles zero consumed (edge: first consume hits cap)', () => {
      const r = parseQuotaCapExceeded('QUOTA_CAP_EXCEEDED:credits:0.0000/0.0001');
      expect(r?.consumed).toBe(0);
      expect(r?.cap).toBe(0.0001);
    });
  });

  describe('formatQuotaMessage', () => {
    // Mock the next-intl translator. Returns a deterministic templated string
    // so we can pin which key was selected.
    const tStub = (key: string, values?: Record<string, unknown>) =>
      `${key}[${JSON.stringify(values ?? {})}]`;

    it('routes credits dimension to capExceededCredits key', () => {
      const msg = formatQuotaMessage(
        { dimension: 'credits', consumed: 99, cap: 100 },
        tStub,
      );
      expect(msg).toContain('capExceededCredits');
      expect(msg).toContain('"consumed":99');
      expect(msg).toContain('"cap":100');
    });

    it('routes storage dimension to capExceededStorage key', () => {
      const msg = formatQuotaMessage(
        { dimension: 'storage', consumed: 100, cap: 200 },
        tStub,
      );
      expect(msg).toContain('capExceededStorage');
    });

    it('routes tokens dimension to capExceededTokens key', () => {
      const msg = formatQuotaMessage(
        { dimension: 'tokens', consumed: 999, cap: 1000 },
        tStub,
      );
      expect(msg).toContain('capExceededTokens');
    });

    it('falls back to capExceededGeneric for unknown dimensions (forward-compat)', () => {
      const msg = formatQuotaMessage(
        { dimension: 'custom-future-dim', consumed: 1, cap: 10 },
        tStub,
      );
      expect(msg).toContain('capExceededGeneric');
      expect(msg).toContain('"dimension":"custom-future-dim"');
    });
  });
});
