/**
 * buildDraftChatConfigBody must seed `inactivityTimeout` into a new conversation's
 * chatConfig (it is now one of the whitelisted flat keys), and must pass the 0
 * "disabled" sentinel through verbatim (0 !== undefined), so a draft default that
 * disables the watchdog actually reaches the created conversation.
 */
import { describe, it, expect } from 'vitest';
import {
  buildDraftChatConfigBody,
  configFromAgent,
  configFromConversation,
  buildAgentPatch,
  buildConversationPatch,
} from '../useChatConfig';
import type { Agent } from '@/lib/api/orchestrator/types';

describe('buildDraftChatConfigBody - inactivityTimeout seed', () => {
  it('carries a custom inactivityTimeout into the conversation chatConfig body', () => {
    const body = buildDraftChatConfigBody({ inactivityTimeout: 120 });
    expect(body).toEqual({ inactivityTimeout: 120 });
  });

  it('passes the 0 disabled sentinel through (not stripped as falsy)', () => {
    const body = buildDraftChatConfigBody({ inactivityTimeout: 0 });
    expect(body).toEqual({ inactivityTimeout: 0 });
  });

  it('omits inactivityTimeout when the draft does not set it', () => {
    const body = buildDraftChatConfigBody({ executionTimeout: 1800 });
    expect(body).toEqual({ executionTimeout: 1800 });
    expect(body && 'inactivityTimeout' in body).toBe(false);
  });

  it('returns undefined for an empty draft', () => {
    expect(buildDraftChatConfigBody(null)).toBeUndefined();
    expect(buildDraftChatConfigBody({})).toBeUndefined();
  });
});

describe('inactivityTimeout read mappers', () => {
  it('configFromAgent reads the agent column (incl. 0 = disabled)', () => {
    expect(configFromAgent({ inactivityTimeout: 90 } as Agent).inactivityTimeout).toBe(90);
    expect(configFromAgent({ inactivityTimeout: 0 } as Agent).inactivityTimeout).toBe(0);
    expect(configFromAgent({} as Agent).inactivityTimeout).toBeUndefined();
  });

  it('configFromConversation reads chatConfig.inactivityTimeout (incl. 0 = disabled)', () => {
    expect(configFromConversation({ chatConfig: { inactivityTimeout: 45 } }).inactivityTimeout).toBe(45);
    expect(configFromConversation({ chatConfig: { inactivityTimeout: 0 } }).inactivityTimeout).toBe(0);
    expect(configFromConversation({ chatConfig: {} }).inactivityTimeout).toBeUndefined();
  });
});

describe('inactivityTimeout write mappers', () => {
  const agent = { name: 'A' } as Agent;

  it('buildAgentPatch forwards inactivityTimeout under its own key (PUT /agents)', () => {
    expect(buildAgentPatch(agent, { inactivityTimeout: 120 }).inactivityTimeout).toBe(120);
    // 0 must reach the agent column verbatim (disable), not be dropped as falsy.
    expect(buildAgentPatch(agent, { inactivityTimeout: 0 }).inactivityTimeout).toBe(0);
    // Untouched -> absent (patch semantics, leaves the column unchanged).
    expect('inactivityTimeout' in buildAgentPatch(agent, {})).toBe(false);
  });

  it('buildConversationPatch writes chatConfig.inactivityTimeout (PUT /conversations)', () => {
    const patched = buildConversationPatch({}, { inactivityTimeout: 60 }) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.inactivityTimeout).toBe(60);
    const disabled = buildConversationPatch({}, { inactivityTimeout: 0 }) as { chatConfig: Record<string, unknown> };
    expect(disabled.chatConfig.inactivityTimeout).toBe(0);
  });

  it('buildConversationPatch keeps the current value when the edit does not touch it', () => {
    const patched = buildConversationPatch({ inactivityTimeout: 300 }, { temperature: 0.5 }) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.inactivityTimeout).toBe(300);
  });
});
