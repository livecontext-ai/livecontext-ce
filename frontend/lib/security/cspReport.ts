/**
 * CSP violation report intake (LC-027), used by app/api/csp-report/route.ts.
 *
 * The Report-Only policy (lib/security/securityHeaders.mjs) points browsers here so the
 * stricter candidate policy can be measured before anyone enforces it. The endpoint is
 * unauthenticated by nature (browsers send reports without credentials), so everything it
 * records is attacker-controllable text:
 * - only the violated DIRECTIVE and the blocked resource's HOST (or a CSP keyword such as
 *   `inline`/`eval`) are kept: never the document URL, the full blocked URL (it can carry
 *   tokens or signed-URL signatures), the script sample or the referrer;
 * - both are reduced to a tight character set, so nothing can forge a log line;
 * - logging is rate-limited per process, identical (directive, host) pairs are counted
 *   rather than re-logged, so a flood cannot fill the logs.
 */

export interface CspViolationSummary {
  directive: string;
  blocked: string;
}

/** Largest request body read; anything bigger is dropped unread. */
export const MAX_REPORT_BYTES = 16 * 1024;

const DIRECTIVE_PATTERN = /^[a-z][a-z-]{0,40}$/;
const KEYWORD_PATTERN = /^[a-z][a-z-]{0,20}$/;
const HOST_PATTERN = /^[a-z0-9.-]{1,253}(?::\d{1,5})?$/;

function sanitizeDirective(raw: unknown): string {
  if (typeof raw !== 'string') return 'unknown';
  // "script-src-elem 'self'" (old violated-directive form) -> "script-src-elem"
  const name = raw.trim().split(/\s+/)[0]?.toLowerCase() ?? '';
  return DIRECTIVE_PATTERN.test(name) ? name : 'unknown';
}

/** Host of a blocked URL, or a CSP keyword (`inline`, `eval`, `data`, ...), never a full URL. */
export function sanitizeBlocked(raw: unknown): string {
  if (typeof raw !== 'string' || raw.trim() === '') return 'none';
  const value = raw.trim();
  try {
    const url = new URL(value);
    if (url.protocol === 'http:' || url.protocol === 'https:' || url.protocol === 'ws:' || url.protocol === 'wss:') {
      const host = url.host.toLowerCase();
      return HOST_PATTERN.test(host) ? host : 'other';
    }
    // data:, blob:, about: ... : the scheme is the useful part, the payload is not.
    const scheme = url.protocol.replace(/:$/, '').toLowerCase();
    return KEYWORD_PATTERN.test(scheme) ? scheme : 'other';
  } catch {
    const keyword = value.toLowerCase();
    return KEYWORD_PATTERN.test(keyword) ? keyword : 'other';
  }
}

function summarize(entry: Record<string, unknown>): CspViolationSummary {
  return {
    directive: sanitizeDirective(entry['effective-directive'] ?? entry.effectiveDirective ?? entry['violated-directive']),
    blocked: sanitizeBlocked(entry['blocked-uri'] ?? entry.blockedURL),
  };
}

/**
 * Both report formats: the legacy `report-uri` body `{"csp-report": {...}}` and the Reporting
 * API `report-to` body `[{type: "csp-violation", body: {...}}, ...]`. Unknown shapes yield [].
 */
export function parseCspReports(payload: unknown): CspViolationSummary[] {
  if (Array.isArray(payload)) {
    return payload
      .slice(0, 20)
      .filter((r) => r && typeof r === 'object' && (r as Record<string, unknown>).type === 'csp-violation')
      .map((r) => (r as Record<string, unknown>).body)
      .filter((b): b is Record<string, unknown> => !!b && typeof b === 'object')
      .map(summarize);
  }
  if (payload && typeof payload === 'object') {
    const legacy = (payload as Record<string, unknown>)['csp-report'];
    if (legacy && typeof legacy === 'object') return [summarize(legacy as Record<string, unknown>)];
  }
  return [];
}

/**
 * Per-process log limiter: at most `maxLinesPerWindow` lines per window, and one line per
 * distinct (directive, blocked) pair per window, with the number of repeats folded in.
 */
export class CspReportLogLimiter {
  private windowStart = 0;
  private linesInWindow = 0;
  private readonly seen = new Map<string, number>();

  constructor(
    private readonly maxLinesPerWindow = 30,
    private readonly windowMs = 60_000,
  ) {}

  /** Returns the line to log for this violation, or null when it must be dropped. */
  accept(summary: CspViolationSummary, now: number = Date.now()): string | null {
    if (now - this.windowStart >= this.windowMs) {
      this.windowStart = now;
      this.linesInWindow = 0;
      this.seen.clear();
    }
    const key = `${summary.directive}|${summary.blocked}`;
    const count = (this.seen.get(key) ?? 0) + 1;
    this.seen.set(key, count);
    if (count > 1 || this.linesInWindow >= this.maxLinesPerWindow) return null;
    this.linesInWindow += 1;
    return `[csp-report] directive=${summary.directive} blocked=${summary.blocked}`;
  }
}
