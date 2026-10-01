import { describe, expect, it } from 'vitest';
import { parsePublishAgentError, bytesToMb } from '@/components/marketplace/publishAgentError';

/**
 * Parser for the structured 422 publish-agent refusals (grant=all violations,
 * snapshot size caps). Regression: the modal used to show err.message verbatim,
 * which for structured bodies meant an unreadable blob; the parser guarantees a
 * typed union the modal renders as plain language, never raw JSON.
 */
describe('parsePublishAgentError', () => {
  const apiError = (code: string, details: Record<string, unknown>) => ({
    name: 'ApiError',
    message: (details.message as string) ?? 'boom',
    status: 422,
    code,
    details: { error: code, ...details },
  });

  it('parses an all-access refusal into typed violations (root + sub-agent chain)', () => {
    const err = apiError('AGENT_ALL_ACCESS_NOT_PUBLISHABLE', {
      message: 'refused',
      violations: [
        { agentId: 'a1', agentName: 'Support Copilot', root: true, families: ['tables', 'interfaces'] },
        {
          agentId: 'a2',
          agentName: 'Research Helper',
          root: false,
          referencedVia: ['Support Copilot'],
          families: ['agents'],
        },
      ],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed.kind).toBe('allAccess');
    if (parsed.kind !== 'allAccess') return;
    expect(parsed.violations).toHaveLength(2);
    expect(parsed.violations[0]).toMatchObject({
      agentId: 'a1',
      agentName: 'Support Copilot',
      root: true,
      families: ['tables', 'interfaces'],
    });
    expect(parsed.violations[1].referencedVia).toEqual(['Support Copilot']);
  });

  it('drops malformed violation entries and falls back to generic when none survive', () => {
    const err = apiError('AGENT_ALL_ACCESS_NOT_PUBLISHABLE', {
      message: 'refused',
      violations: [null, 'garbage', { agentId: 'x', families: [] }],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed).toEqual({ kind: 'generic', message: 'refused' });
  });

  it('parses a too-large refusal with sizes and breakdown', () => {
    const err = apiError('AGENT_SNAPSHOT_TOO_LARGE', {
      message: 'too big',
      sizeBytes: 34_000_000,
      maxBytes: 15_728_640,
      breakdown: [{ type: 'datasource', id: '142', name: 'Leads', items: 82000, approxBytes: 21_000_000 }],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed.kind).toBe('tooLarge');
    if (parsed.kind !== 'tooLarge') return;
    expect(parsed.sizeBytes).toBe(34_000_000);
    expect(parsed.maxBytes).toBe(15_728_640);
    expect(parsed.breakdown[0]).toMatchObject({ type: 'datasource', name: 'Leads', items: 82000 });
  });

  it('parses the row-cap variant (no sizeBytes, maxTableRows present)', () => {
    const err = apiError('AGENT_SNAPSHOT_TOO_LARGE', {
      message: 'table too big',
      maxTableRows: 5000,
      breakdown: [{ type: 'datasource', id: '142', name: 'Leads', items: 82000 }],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed.kind).toBe('tooLarge');
    if (parsed.kind !== 'tooLarge') return;
    expect(parsed.sizeBytes).toBeUndefined();
    expect(parsed.maxTableRows).toBe(5000);
    expect(parsed.breakdown[0].items).toBe(82000);
  });

  it('parses a custom-API refusal into the typed APIs the modal lists', () => {
    const err = apiError('CUSTOM_API_NOT_PUBLISHABLE', {
      message: 'Custom APIs cannot be shared.',
      customApis: [
        { apiSlug: 'my-private-api', apiName: 'My Private API', toolIdentifiers: ['my-private-api/do-thing'] },
        { apiSlug: 'other-api', apiName: 'Other API' },
      ],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed.kind).toBe('customApi');
    if (parsed.kind !== 'customApi') return;
    expect(parsed.customApis).toHaveLength(2);
    expect(parsed.customApis[0]).toEqual({
      apiSlug: 'my-private-api',
      apiName: 'My Private API',
      toolIdentifiers: ['my-private-api/do-thing'],
    });
    // A missing tool list is normalised to an empty array, never undefined:
    // the modal maps over it unconditionally.
    expect(parsed.customApis[1].toolIdentifiers).toEqual([]);
  });

  it('falls back to the slug when a custom-API entry has no name', () => {
    const err = apiError('CUSTOM_API_NOT_PUBLISHABLE', {
      message: 'refused',
      customApis: [{ apiSlug: 'my-private-api' }],
    });

    const parsed = parsePublishAgentError(err, 'fallback');

    expect(parsed.kind).toBe('customApi');
    if (parsed.kind !== 'customApi') return;
    expect(parsed.customApis[0].apiName).toBe('my-private-api');
  });

  it('degrades a custom-API refusal with no usable detail to generic (never an empty list screen)', () => {
    expect(parsePublishAgentError(
      apiError('CUSTOM_API_NOT_PUBLISHABLE', { message: 'refused' }), 'fallback',
    )).toEqual({ kind: 'generic', message: 'refused' });

    expect(parsePublishAgentError(
      apiError('CUSTOM_API_NOT_PUBLISHABLE', { message: 'refused', customApis: [] }), 'fallback',
    )).toEqual({ kind: 'generic', message: 'refused' });
  });

  it('returns generic with the error message for unknown codes, and the fallback when no message', () => {
    expect(parsePublishAgentError({ message: 'plain failure' }, 'fallback')).toEqual({
      kind: 'generic',
      message: 'plain failure',
    });
    expect(parsePublishAgentError(null, 'fallback')).toEqual({ kind: 'generic', message: 'fallback' });
  });
});

describe('bytesToMb', () => {
  it('rounds to one decimal', () => {
    expect(bytesToMb(15_728_640)).toBe(15);
    expect(bytesToMb(34_000_000)).toBe(32.4);
  });
});
