import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';

/**
 * SSRF guard for server-side outbound requests made on behalf of a user-supplied URL
 * (e.g. the developer "test this API" proxy). Rejects non-http(s) schemes and any host that
 * is - or resolves to - a private / loopback / link-local / cloud-metadata address.
 *
 * Runs in the Node.js runtime only (uses node:dns / node:net). The resolver is injectable so
 * the logic is unit-testable without real DNS.
 */

export interface SsrfVerdict {
  /** true when the URL passed every check and is safe to fetch */
  safe: boolean;
  /** the parsed URL - present when {@link safe} is true */
  url?: URL;
  /** human-readable rejection reason - present when {@link safe} is false */
  reason?: string;
}

const ALLOWED_PROTOCOLS = new Set(['http:', 'https:']);

// Hostnames that must never be proxied even before DNS (defence in depth).
const BLOCKED_HOSTNAMES = new Set(['localhost', 'metadata', 'metadata.google.internal']);

/** Parse a dotted-quad IPv4 string into a 32-bit unsigned int, or null if not valid IPv4. */
export function ipv4ToInt(ip: string): number | null {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip);
  if (!match) return null;
  const octets = match.slice(1).map((part) => Number(part));
  if (octets.some((octet) => octet > 255)) return null;
  return (((octets[0] << 24) >>> 0) + (octets[1] << 16) + (octets[2] << 8) + octets[3]) >>> 0;
}

/** True for IPv4 addresses that must never be the target of an outbound proxy request. */
export function isBlockedIpv4(ip: string): boolean {
  const value = ipv4ToInt(ip);
  if (value === null) return false;
  const inRange = (cidrBase: string, bits: number): boolean => {
    const base = ipv4ToInt(cidrBase);
    if (base === null) return false;
    const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
    return (value & mask) >>> 0 === (base & mask) >>> 0;
  };
  return (
    inRange('0.0.0.0', 8) || // "this" network / 0.0.0.0
    inRange('10.0.0.0', 8) || // RFC1918 private
    inRange('100.64.0.0', 10) || // CGNAT
    inRange('127.0.0.0', 8) || // loopback
    inRange('169.254.0.0', 16) || // link-local (incl. 169.254.169.254 cloud metadata)
    inRange('172.16.0.0', 12) || // RFC1918 private
    inRange('192.0.0.0', 24) || // IETF protocol assignments
    inRange('192.168.0.0', 16) || // RFC1918 private
    inRange('198.18.0.0', 15) || // benchmarking
    value === 0xffffffff // 255.255.255.255 broadcast
  );
}

/**
 * Expand an IPv6 string into its 8 16-bit hextets, or null if unparseable. Handles `::`
 * compression and a trailing dotted-quad (IPv4-mapped/embedded) form. Parsing by VALUE - not by
 * string shape - is required because the WHATWG URL parser normalizes `[::ffff:127.0.0.1]` to the
 * hex form `::ffff:7f00:1`, which a dotted-decimal regex would miss (an SSRF bypass to loopback /
 * 169.254.169.254 metadata / RFC1918).
 */
export function expandIpv6(ip: string): number[] | null {
  let addr = ip.toLowerCase().split('%')[0]; // strip zone id
  // Convert a trailing dotted-quad (e.g. ::ffff:127.0.0.1) into two hex hextets.
  const v4tail = /^(.*:)((?:\d{1,3}\.){3}\d{1,3})$/.exec(addr);
  if (v4tail) {
    const v4 = ipv4ToInt(v4tail[2]);
    if (v4 === null) return null;
    addr = `${v4tail[1]}${((v4 >>> 16) & 0xffff).toString(16)}:${(v4 & 0xffff).toString(16)}`;
  }
  const halves = addr.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  let hextets: string[];
  if (halves.length === 2) {
    const fill = 8 - head.length - tail.length;
    if (fill < 0) return null;
    hextets = [...head, ...new Array(fill).fill('0'), ...tail];
  } else {
    hextets = head;
  }
  if (hextets.length !== 8) return null;
  const out: number[] = [];
  for (const h of hextets) {
    if (h === '' || h.length > 4) return null;
    const n = parseInt(h, 16);
    if (Number.isNaN(n) || n < 0 || n > 0xffff) return null;
    out.push(n);
  }
  return out;
}

function isBlockedEmbeddedV4(hi: number, lo: number): boolean {
  return isBlockedIpv4(`${(hi >>> 8) & 0xff}.${hi & 0xff}.${(lo >>> 8) & 0xff}.${lo & 0xff}`);
}

/** True for IPv6 addresses that must never be the target of an outbound proxy request. */
export function isBlockedIpv6(ip: string): boolean {
  const h = expandIpv6(ip);
  if (!h) {
    const addr = ip.toLowerCase().split('%')[0];
    return addr === '::1' || addr === '::'; // conservative fallback for an unparseable literal
  }
  const allZeroHi = h[0] === 0 && h[1] === 0 && h[2] === 0 && h[3] === 0 && h[4] === 0;
  if (h.every((x) => x === 0)) return true; // :: unspecified
  if (allZeroHi && h[5] === 0 && h[6] === 0 && h[7] === 1) return true; // ::1 loopback
  if (allZeroHi && h[5] === 0xffff) return isBlockedEmbeddedV4(h[6], h[7]); // ::ffff:a.b.c.d mapped
  if (allZeroHi && h[5] === 0) return isBlockedEmbeddedV4(h[6], h[7]); // ::a.b.c.d compatible (deprecated)
  if (h[0] === 0x2002) return isBlockedEmbeddedV4(h[1], h[2]); // 2002::/16 6to4 embedded v4
  if (h[0] === 0x0064 && h[1] === 0xff9b) return isBlockedEmbeddedV4(h[6], h[7]); // 64:ff9b::/96 NAT64
  if ((h[0] & 0xfe00) === 0xfc00) return true; // fc00::/7 unique-local
  if ((h[0] & 0xffc0) === 0xfe80) return true; // fe80::/10 link-local
  if ((h[0] & 0xffc0) === 0xfec0) return true; // fec0::/10 deprecated site-local
  return false;
}

/** True for any IP literal (v4 or v6) that must never be proxied. Non-IP input → false. */
export function isBlockedIp(ip: string): boolean {
  const version = isIP(ip);
  if (version === 4) return isBlockedIpv4(ip);
  if (version === 6) return isBlockedIpv6(ip);
  return false;
}

async function defaultResolver(host: string): Promise<string[]> {
  const records = await lookup(host, { all: true });
  return records.map((record) => record.address);
}

/**
 * Validates a user-supplied URL for an outbound proxy request. Only http(s) is allowed, and the
 * host must not be - or resolve to - a private/loopback/link-local/metadata address (so a public
 * hostname pointing at an internal IP is rejected too). Returns the parsed URL when safe.
 *
 * @param raw       the user-supplied URL (validated to be a non-empty string)
 * @param resolver  DNS resolver returning all addresses for a host (injectable for tests)
 */
export async function assertOutboundUrlSafe(
  raw: unknown,
  resolver: (host: string) => Promise<string[]> = defaultResolver,
): Promise<SsrfVerdict> {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return { safe: false, reason: 'URL is required' };
  }

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { safe: false, reason: 'Invalid URL format' };
  }

  if (!ALLOWED_PROTOCOLS.has(url.protocol)) {
    return { safe: false, reason: `Blocked URL scheme: ${url.protocol}` };
  }

  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, ''); // unwrap IPv6 brackets
  if (BLOCKED_HOSTNAMES.has(host)) {
    return { safe: false, reason: 'Blocked host' };
  }

  // IP literal: check directly, no DNS.
  if (isIP(host)) {
    return isBlockedIp(host)
      ? { safe: false, reason: 'Blocked private/loopback address' }
      : { safe: true, url };
  }

  // Hostname: resolve and reject if ANY resolved address is blocked.
  let addresses: string[];
  try {
    addresses = await resolver(host);
  } catch {
    return { safe: false, reason: 'DNS resolution failed' };
  }
  if (addresses.length === 0) {
    return { safe: false, reason: 'DNS resolution returned no addresses' };
  }
  if (addresses.some(isBlockedIp)) {
    return { safe: false, reason: 'Host resolves to a private/loopback address' };
  }
  return { safe: true, url };
}
