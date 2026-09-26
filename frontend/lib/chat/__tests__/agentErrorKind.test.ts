import { describe, it, expect } from 'vitest';
import { classifyAgentError, SEND_FAILED_CODE } from '../agentErrorKind';

// Messages below are verbatim failure texts seen in production chat streams
// (agent-service logs), so each kind is pinned to what users actually hit.
describe('classifyAgentError: production failure texts', () => {
  it.each([
    ["HTTP 400: Role 'function' is not supported. Please use a valid role: SYSTEM, USER, MODEL.", 'providerRejected'],
    ['HTTP 400: Function call is missing a thought_signature in functionCall parts.', 'providerRejected'],
    ['Function tools with reasoning_effort are not supported for gpt-6-luna in /v1/chat/completions.', 'providerRejected'],
    ['HTTP 404 - This model models/gemini-2.0-flash-lite is no longer available. Please update your code.', 'modelUnavailable'],
    ['HTTP 400 - models/gemini-3.1-flash-live-preview only supports real-time bidirectional streaming via WebSocket', 'modelUnavailable'],
    ['deepseek API error: 404 NOT_FOUND - {"error":{"message":"The model mock-model does not exist or you do not have access to it."}}', 'modelUnavailable'],
    ['Model not found: gpt-9', 'modelUnavailable'],
    ['anthropic API error: 400 BAD_REQUEST - Your credit balance is too low to access the Anthropic API.', 'providerBilling'],
    ['You exceeded your current quota, please check your plan and billing details. insufficient_quota', 'providerBilling'],
    ['Rate limit exceeded (retry-after: 20s)', 'rateLimit'],
    ['HTTP 429: RESOURCE_EXHAUSTED', 'rateLimit'],
    ['Error during deepseek streaming: Connection reset', 'network'],
    ['Stream timeout after 10 minutes of inactivity', 'network'],
    ['anthropic API error: 529 - Overloaded', 'providerUnavailable'],
    ['HTTP 503 Service Unavailable', 'providerUnavailable'],
    ['deepseek API error: 500 INTERNAL_SERVER_ERROR', 'providerUnavailable'],
    ["This model's maximum context length is 128000 tokens. However, your messages resulted in 140000 tokens.", 'contextTooLong'],
    ['prompt is too long: 210000 tokens > 200000 maximum', 'contextTooLong'],
    ['Response was blocked by the safety filters', 'contentBlocked'],
    ['Service restarting - partial response saved', 'interrupted'],
    ['Something odd happened', 'unknown'],
  ])('%s -> %s', (message, kind) => {
    expect(classifyAgentError(message)).toBe(kind);
  });
});

describe('classifyAgentError: ordering and false positives', () => {
  it('a 400 that names the context window is a context problem, not a generic rejection', () => {
    expect(classifyAgentError('HTTP 400: context length exceeded')).toBe('contextTooLong');
  });

  it('insufficient_quota is a provider billing problem, not a rate limit', () => {
    expect(classifyAgentError('HTTP 429 insufficient_quota')).toBe('providerBilling');
  });

  it.each([
    // A bare 5xx number inside a validation message is not an outage.
    ['HTTP 400: max_tokens: 550 > 512', 'providerRejected'],
    ['HTTP 400: top_p 0.500 out of range', 'providerRejected'],
    // "does not exist" about something else than the model is not a model problem.
    ['Tool report_x does not exist', 'unknown'],
    // A proxy block is not a provider safety refusal.
    ['Request blocked by Cloudflare', 'unknown'],
    // A word such as "socket" or "billing" alone does not decide the kind.
    ['The socket tool returned an empty list', 'unknown'],
    ['Open the billing page to see invoices', 'unknown'],
    // A parameter error that merely mentions the model is a rejection, not a missing model.
    ['HTTP 400: model gpt-x: parameter functions is deprecated', 'providerRejected'],
    ['model gpt-4-32k was decommissioned', 'modelUnavailable'],
  ])('%s -> %s', (message, kind) => {
    expect(classifyAgentError(message)).toBe(kind);
  });
});

describe('classifyAgentError: codes', () => {
  it('the INTERRUPTED code wins over the text', () => {
    expect(classifyAgentError('whatever', 'INTERRUPTED')).toBe('interrupted');
  });

  it('a failed send is never blamed on the provider', () => {
    expect(classifyAgentError('HTTP 500: Internal Server Error', SEND_FAILED_CODE)).toBe('sendFailed');
    expect(classifyAgentError('HTTP 400: Bad Request', SEND_FAILED_CODE)).toBe('sendFailed');
    expect(classifyAgentError('Too many requests', SEND_FAILED_CODE)).toBe('sendFailed');
    expect(classifyAgentError('', SEND_FAILED_CODE)).toBe('sendFailed');
  });

  it('a failed send caused by the connection is a network failure', () => {
    expect(classifyAgentError('Failed to fetch', SEND_FAILED_CODE)).toBe('network');
    expect(classifyAgentError('Request timed out', SEND_FAILED_CODE)).toBe('network');
  });

  it('an empty, blank or missing message is unknown', () => {
    expect(classifyAgentError('')).toBe('unknown');
    expect(classifyAgentError('   ')).toBe('unknown');
    expect(classifyAgentError(undefined)).toBe('unknown');
    expect(classifyAgentError(null)).toBe('unknown');
  });
});
