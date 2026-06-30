import { describe, it, expect } from 'vitest';
import {
  ipv4ToInt,
  isBlockedIpv4,
  isBlockedIpv6,
  isBlockedIp,
  expandIpv6,
  assertOutboundUrlSafe,
} from '@/lib/security/ssrfGuard';

/**
 * Regression: the external-proxy route fetched any user-supplied URL with only a `new URL()`
 * format check - an unauthenticated open SSRF proxy that could reach loopback, RFC1918, and the
 * cloud-metadata endpoint (169.254.169.254). assertOutboundUrlSafe must reject those, including a
 * public hostname that RESOLVES to an internal IP (DNS-rebinding). Each blocked case below passes
 * on the pre-fix code (which had no guard at all).
 */
describe('ssrfGuard', () => {
  describe('ipv4ToInt', () => {
    it('parses dotted-quad to a 32-bit int', () => {
      expect(ipv4ToInt('0.0.0.0')).toBe(0);
      expect(ipv4ToInt('255.255.255.255')).toBe(0xffffffff);
      expect(ipv4ToInt('192.168.1.1')).toBe(0xc0a80101);
    });
    it('rejects out-of-range / non-IPv4 input', () => {
      expect(ipv4ToInt('256.0.0.1')).toBeNull();
      expect(ipv4ToInt('not.an.ip')).toBeNull();
      expect(ipv4ToInt('1.2.3')).toBeNull();
    });
  });

  describe('isBlockedIpv4', () => {
    it('blocks private / loopback / link-local / special ranges', () => {
      for (const ip of [
        '0.0.0.0',
        '10.1.2.3',
        '100.64.0.1',
        '127.0.0.1',
        '169.254.169.254', // cloud metadata
        '172.16.5.4',
        '172.31.255.255',
        '192.168.0.1',
        '198.18.0.1',
        '255.255.255.255',
      ]) {
        expect(isBlockedIpv4(ip), `${ip} must be blocked`).toBe(true);
      }
    });
    it('allows public addresses', () => {
      for (const ip of ['8.8.8.8', '1.1.1.1', '93.184.216.34', '172.32.0.1', '11.0.0.1']) {
        expect(isBlockedIpv4(ip), `${ip} must be allowed`).toBe(false);
      }
    });
  });

  describe('expandIpv6', () => {
    it('expands :: compression and dotted-quad tail to 8 hextets', () => {
      expect(expandIpv6('::1')).toEqual([0, 0, 0, 0, 0, 0, 0, 1]);
      expect(expandIpv6('::')).toEqual([0, 0, 0, 0, 0, 0, 0, 0]);
      expect(expandIpv6('::ffff:127.0.0.1')).toEqual([0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001]);
      expect(expandIpv6('2606:4700:4700::1111')).toEqual([0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1111]);
    });
    it('returns null for malformed input', () => {
      expect(expandIpv6('1:2:3')).toBeNull(); // too few, no ::
      expect(expandIpv6('::ffff::1')).toBeNull(); // double ::
    });
  });

  describe('isBlockedIpv6', () => {
    it('blocks loopback, unspecified, ULA, link-local', () => {
      for (const ip of ['::1', '::', 'fc00::1', 'fd12:3456::1', 'fe80::1', 'fec0::1']) {
        expect(isBlockedIpv6(ip), `${ip} must be blocked`).toBe(true);
      }
    });
    it('blocks IPv4-mapped internal in dotted form', () => {
      expect(isBlockedIpv6('::ffff:127.0.0.1')).toBe(true);
      expect(isBlockedIpv6('::ffff:10.0.0.1')).toBe(true);
    });
    it('blocks IPv4-mapped internal in the HEX-NORMALIZED form (the URL-parser bypass)', () => {
      // The WHATWG URL parser rewrites [::ffff:127.0.0.1] → ::ffff:7f00:1 etc. These must still block.
      expect(isBlockedIpv6('::ffff:7f00:1')).toBe(true); // 127.0.0.1 loopback
      expect(isBlockedIpv6('::ffff:a9fe:a9fe')).toBe(true); // 169.254.169.254 cloud metadata
      expect(isBlockedIpv6('::ffff:0a00:0001')).toBe(true); // 10.0.0.1 RFC1918
    });
    it('blocks 6to4 and NAT64 embedding an internal IPv4', () => {
      expect(isBlockedIpv6('2002:7f00:1::')).toBe(true); // 6to4 wrapping 127.0.0.1
      expect(isBlockedIpv6('64:ff9b::a9fe:a9fe')).toBe(true); // NAT64 wrapping 169.254.169.254
    });
    it('allows public IPv6 and IPv4-mapped public addresses (incl. hex form)', () => {
      expect(isBlockedIpv6('2606:4700:4700::1111')).toBe(false);
      expect(isBlockedIpv6('::ffff:8.8.8.8')).toBe(false);
      expect(isBlockedIpv6('::ffff:808:808')).toBe(false); // 8.8.8.8 hex form
    });
  });

  describe('isBlockedIp', () => {
    it('dispatches by version and returns false for non-IPs', () => {
      expect(isBlockedIp('127.0.0.1')).toBe(true);
      expect(isBlockedIp('::1')).toBe(true);
      expect(isBlockedIp('8.8.8.8')).toBe(false);
      expect(isBlockedIp('example.com')).toBe(false);
    });
  });

  describe('assertOutboundUrlSafe', () => {
    const publicResolver = async () => ['93.184.216.34'];

    it('rejects empty / non-string input', async () => {
      expect((await assertOutboundUrlSafe('')).safe).toBe(false);
      expect((await assertOutboundUrlSafe(undefined)).safe).toBe(false);
      expect((await assertOutboundUrlSafe(123 as unknown)).safe).toBe(false);
    });

    it('rejects non-http(s) schemes', async () => {
      for (const url of ['ftp://example.com', 'file:///etc/passwd', 'gopher://x', 'data:text/plain,x']) {
        const verdict = await assertOutboundUrlSafe(url, publicResolver);
        expect(verdict.safe, url).toBe(false);
      }
    });

    it('rejects blocked hostnames', async () => {
      const verdict = await assertOutboundUrlSafe('http://localhost:8080/x', publicResolver);
      expect(verdict.safe).toBe(false);
    });

    it('rejects IP-literal targets in private/metadata ranges', async () => {
      for (const url of [
        'http://169.254.169.254/latest/meta-data/',
        'http://127.0.0.1:5432',
        'http://10.0.0.5/internal',
        'http://[::1]/x',
      ]) {
        const verdict = await assertOutboundUrlSafe(url, publicResolver);
        expect(verdict.safe, url).toBe(false);
      }
    });

    it('rejects IPv4-mapped IPv6 literals through the URL parser (regression: the hex-normalization bypass)', async () => {
      // new URL('http://[::ffff:127.0.0.1]/').hostname === '[::ffff:7f00:1]' - must still block.
      for (const url of [
        'http://[::ffff:127.0.0.1]/',
        'http://[::ffff:169.254.169.254]/latest/meta-data/',
        'http://[::ffff:10.0.0.1]/',
      ]) {
        const verdict = await assertOutboundUrlSafe(url, publicResolver);
        expect(verdict.safe, url).toBe(false);
      }
    });

    it('rejects IPv4 alt-encodings (URL parser canonicalizes them to a private dotted-quad)', async () => {
      // Pins the guard's reliance on WHATWG URL normalization: decimal/hex/octal/short forms all
      // canonicalize to 127.0.0.1 → isIP=4 → blocked. publicResolver is never consulted (IP literal).
      for (const url of ['http://2130706433/', 'http://0x7f000001/', 'http://0177.0.0.1/', 'http://127.1/']) {
        const verdict = await assertOutboundUrlSafe(url, publicResolver);
        expect(verdict.safe, url).toBe(false);
      }
    });

    it('rejects a public hostname that resolves to an internal IP (DNS rebinding)', async () => {
      const rebinding = async () => ['10.0.0.1'];
      const verdict = await assertOutboundUrlSafe('https://evil.example.com', rebinding);
      expect(verdict.safe).toBe(false);
    });

    it('rejects when DNS resolution fails or returns nothing', async () => {
      const throwing = async () => {
        throw new Error('NXDOMAIN');
      };
      expect((await assertOutboundUrlSafe('https://nope.example.com', throwing)).safe).toBe(false);
      expect((await assertOutboundUrlSafe('https://empty.example.com', async () => [])).safe).toBe(false);
    });

    it('allows a public hostname resolving to a public IP', async () => {
      const verdict = await assertOutboundUrlSafe('https://example.com/api', publicResolver);
      expect(verdict.safe).toBe(true);
      if (verdict.safe) {
        expect(verdict.url.href).toBe('https://example.com/api');
      }
    });

    it('allows a public IP literal without resolving', async () => {
      const verdict = await assertOutboundUrlSafe('https://8.8.8.8/', async () => {
        throw new Error('resolver should not be called for an IP literal');
      });
      expect(verdict.safe).toBe(true);
    });
  });
});
