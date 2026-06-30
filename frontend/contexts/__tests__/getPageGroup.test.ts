import { describe, it, expect } from 'vitest';
import { getPageGroup } from '../SidePanelContext';

/**
 * getPageGroup decides whether a route change closes the side panel: the
 * navigation effect keeps the panel open only when the previous and next paths
 * map to the SAME group.
 *
 * Regression: completing the first message of a new chat navigates
 * /app/chat → /app/c/{id} (useMessageHandlersV2). With the plain 4-segment rule
 * those are different groups, so the panel auto-closed on every first reply.
 * The chat area (/app, /app/chat, /app/c/*) must collapse to one group.
 */
describe('getPageGroup - chat area is one group', () => {
  it('treats /app/chat and /app/c/{id} as the same group (panel stays open on first reply)', () => {
    expect(getPageGroup('/en/app/chat')).toBe(getPageGroup('/en/app/c/conv-123'));
  });

  it('treats bare /app (new-chat home) as the same group as /app/c/{id}', () => {
    expect(getPageGroup('/en/app')).toBe(getPageGroup('/en/app/c/conv-123'));
  });

  it('keeps the panel open when switching between two conversations', () => {
    expect(getPageGroup('/en/app/c/conv-a')).toBe(getPageGroup('/en/app/c/conv-b'));
  });

  it('works for every locale, not only the ones stripLocale knows (de/pt/zh)', () => {
    // stripLocale only strips en/fr/es; the segment-based grouping must still
    // unify the chat area for de/pt/zh users.
    expect(getPageGroup('/de/app/chat')).toBe(getPageGroup('/de/app/c/conv-123'));
    expect(getPageGroup('/zh/app/c/a')).toBe(getPageGroup('/zh/app/c/b'));
  });

  it('handles a locale-less chat path', () => {
    expect(getPageGroup('/app/chat')).toBe(getPageGroup('/app/c/conv-123'));
  });

  it('returns the literal __chat__ group (guards against a degenerate all-same regression)', () => {
    // Without this, a future getPageGroup that returns one constant for EVERY
    // path would still satisfy the relative-equality assertions above.
    expect(getPageGroup('/en/app/chat')).toBe('__chat__');
  });
});

describe('getPageGroup - non-chat routes keep 4-segment grouping', () => {
  it('keeps the panel open across workflow sub-routes (edit → run)', () => {
    expect(getPageGroup('/en/app/workflow/abc')).toBe(
      getPageGroup('/en/app/workflow/abc/run/123'),
    );
  });

  it('treats two different workflows as different groups (closes the panel)', () => {
    expect(getPageGroup('/en/app/workflow/abc')).not.toBe(
      getPageGroup('/en/app/workflow/xyz'),
    );
  });

  it('treats chat and a workflow as different groups', () => {
    expect(getPageGroup('/en/app/chat')).not.toBe(getPageGroup('/en/app/workflow/abc'));
  });

  it('does NOT collapse a non-chat section that merely starts with "c" (strict equality)', () => {
    // The guard matches the section === 'c' exactly, so 'community'/'catalog'
    // must keep their own group and not leak into the chat group.
    expect(getPageGroup('/en/app/community/x')).not.toBe(getPageGroup('/en/app/c/x'));
  });

  it('returns empty string for a null path', () => {
    expect(getPageGroup(null)).toBe('');
  });
});
