/**
 * Classifies a failed chat / agent turn into a kind the error modal can explain.
 *
 * The stream error event carries the verbatim failure text (usually the model
 * provider's own message, e.g. "HTTP 400: Role 'function' is not supported") and a
 * generic code (STREAM_ERROR / INTERRUPTED), so the kind is derived from the text.
 * The cases that already own a dedicated modal (credits, storage, missing API key,
 * CE cloud relay) are routed BEFORE this classifier and never reach it.
 *
 * Order matters: the most specific signals come first (a 400 that says "context
 * length" is a context problem, not a generic rejection; "insufficient_quota" is a
 * provider billing problem, not a rate limit). Patterns are anchored to the way
 * providers and our HTTP layer phrase them ("HTTP 503", "error: 529", "model ... does
 * not exist"), so a bare number or a common word elsewhere in a message
 * ("max_tokens: 550", "tool does not exist") does not change the explanation.
 */
export type AgentErrorKind =
  | 'interrupted'
  | 'providerBilling'
  | 'rateLimit'
  | 'contextTooLong'
  | 'contentBlocked'
  | 'modelUnavailable'
  | 'providerUnavailable'
  | 'network'
  | 'providerRejected'
  | 'sendFailed'
  | 'unknown';

/** Code StreamingContext gives a failure of the send request itself (no stream started). */
export const SEND_FAILED_CODE = 'SEND_FAILED';

const HTTP_STATUS = (codes: string) => new RegExp(`(?:http|status|error|api error)[^a-z0-9]{0,3}(?:${codes})\\b`);

const NETWORK = /connection reset|connection refused|connection closed|econnreset|econnrefused|broken pipe|socket hang up|timed out|read timeout|stream timeout|of inactivity|failed to fetch|networkerror|network error/;

const RULES: ReadonlyArray<[AgentErrorKind, RegExp]> = [
  ['interrupted', /service restarting|partial response saved/],
  ['providerBilling', /credit balance is too low|insufficient_quota|exceeded your current quota|purchase credits|billing details/],
  ['rateLimit', /rate.?limit|too many requests|resource_exhausted|quota exceeded/],
  ['rateLimit', HTTP_STATUS('429')],
  ['contextTooLong', /context.?length|context window|maximum context|too many tokens|prompt is too long|input is too long|exceeds? the (?:token|context) limit|request too large/],
  ['contentBlocked', /safety (?:filter|setting|system)|content.?policy|content_filter|flagged by (?:the )?moderation|prohibited content/],
  // "model <id> ..." with the verdict right after the id, so a parameter error that merely
  // mentions a model ("model gpt-x: parameter 'functions' is deprecated") stays a rejection.
  ['modelUnavailable', /model not found|model[^\n]{0,80}?(?:no longer available|does not exist)|models?\/?\s?[\w./:-]+ (?:is|was) (?:not supported|decommissioned|deprecated)|unsupported model|only supports real-time|not found for api version/],
  ['providerUnavailable', /overloaded|service unavailable|bad gateway|temporarily unavailable/],
  ['providerUnavailable', HTTP_STATUS('5\\d\\d')],
  ['network', NETWORK],
  ['providerRejected', /bad.?request|invalid_request|invalid argument|(?:is|are) not supported/],
  ['providerRejected', HTTP_STATUS('400|404|422')],
];

export function classifyAgentError(message?: string | null, code?: string | null): AgentErrorKind {
  if (code === 'INTERRUPTED') return 'interrupted';
  const text = (message ?? '').toLowerCase();
  // A failed SEND never reached a model: the failure is ours or the connection's, never the
  // provider's, so provider explanations ("choose another model") would be wrong here.
  if (code === SEND_FAILED_CODE) return NETWORK.test(text) ? 'network' : 'sendFailed';
  if (!text.trim()) return 'unknown';
  for (const [kind, pattern] of RULES) {
    if (pattern.test(text)) return kind;
  }
  return 'unknown';
}
