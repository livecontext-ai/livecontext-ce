/**
 * Compaction SUMMARISER-model pair (compactionModelProvider/Name) plumbing:
 *
 *  - Conversation tier: NESTED under chatConfig.compaction.{modelProvider,modelName}
 *    (read by configFromConversation, written by buildConversationPatch and the
 *    new-conversation draft seed).
 *  - Agent tier: FLAT keys on the PUT /agents body (read from the flat AgentEntity
 *    columns by configFromAgent, written by buildAgentPatch). Both '' = clear.
 *  - Both-or-neither everywhere: a partial pair reads as unset and is never emitted,
 *    matching the backend 400 invalid_compaction_model guard.
 *  - User-default tier: FLAT keys on /v3/chat/defaults; stripUnsetCompactionModelPair
 *    drops a blank/partial pair so PUT (which replaces the stored config) clears it.
 */
import { describe, it, expect } from 'vitest';
import {
  buildAgentPatch,
  buildConversationPatch,
  buildDraftChatConfigBody,
  configFromAgent,
  configFromConversation,
  stripUnsetCompactionModelPair,
} from '../useChatConfig';
import type { Agent } from '@/lib/api/orchestrator/types';

describe('configFromConversation - nested chatConfig.compaction model pair', () => {
  it('reads modelProvider/modelName from the compaction block', () => {
    const config = configFromConversation({
      chatConfig: { compaction: { enabled: true, modelProvider: 'openai', modelName: 'gpt-5-mini' } },
    });
    expect(config.compactionModelProvider).toBe('openai');
    expect(config.compactionModelName).toBe('gpt-5-mini');
    expect(config.compactionEnabled).toBe(true);
  });

  it('treats a PARTIAL stored pair as unset (both undefined)', () => {
    const providerOnly = configFromConversation({
      chatConfig: { compaction: { modelProvider: 'openai' } },
    });
    expect(providerOnly.compactionModelProvider).toBeUndefined();
    expect(providerOnly.compactionModelName).toBeUndefined();

    const blankName = configFromConversation({
      chatConfig: { compaction: { modelProvider: 'openai', modelName: '  ' } },
    });
    expect(blankName.compactionModelProvider).toBeUndefined();
    expect(blankName.compactionModelName).toBeUndefined();
  });

  it('leaves the pair undefined when the compaction block is absent', () => {
    const config = configFromConversation({ chatConfig: {} });
    expect(config.compactionModelProvider).toBeUndefined();
    expect(config.compactionModelName).toBeUndefined();
  });
});

describe('configFromAgent - flat AgentEntity columns', () => {
  it('reads the flat compactionModelProvider/Name columns', () => {
    const config = configFromAgent({
      compactionModelProvider: 'anthropic',
      compactionModelName: 'claude-haiku-4-5',
    } as Agent);
    expect(config.compactionModelProvider).toBe('anthropic');
    expect(config.compactionModelName).toBe('claude-haiku-4-5');
  });

  it('treats a partial/blank stored pair as unset', () => {
    const providerOnly = configFromAgent({ compactionModelProvider: 'anthropic' } as Agent);
    expect(providerOnly.compactionModelProvider).toBeUndefined();
    expect(providerOnly.compactionModelName).toBeUndefined();

    const blankPair = configFromAgent({ compactionModelProvider: '', compactionModelName: '' } as Agent);
    expect(blankPair.compactionModelProvider).toBeUndefined();
    expect(blankPair.compactionModelName).toBeUndefined();
  });
});

describe('buildConversationPatch - NESTED compaction block (PUT /conversations)', () => {
  it('writes the pair under chatConfig.compaction, not as flat keys', () => {
    const patched = buildConversationPatch({}, {
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    }) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.compaction).toEqual({ modelProvider: 'openai', modelName: 'gpt-5-mini' });
    // Flat keys are an AGENT-tier shape; they must not leak into the conversation body.
    expect('compactionModelProvider' in patched.chatConfig).toBe(false);
    expect('compactionModelName' in patched.chatConfig).toBe(false);
  });

  it('merges the pair with the enable/cadence overrides (single-field edit keeps the rest)', () => {
    const patched = buildConversationPatch(
      { compactionEnabled: true, compactionAfterTurns: 8, compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini' },
      { compactionAfterTurns: 12 },
    ) as { chatConfig: { compaction: Record<string, unknown> } };
    expect(patched.chatConfig.compaction).toEqual({
      enabled: true,
      afterTurns: 12,
      modelProvider: 'openai',
      modelName: 'gpt-5-mini',
    });
  });

  it('a blank ("") pair in the edit CLEARS the override (block rewritten without it)', () => {
    const patched = buildConversationPatch(
      { compactionEnabled: true, compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini' },
      { compactionModelProvider: '', compactionModelName: '' },
    ) as { chatConfig: { compaction: Record<string, unknown> } };
    expect(patched.chatConfig.compaction).toEqual({ enabled: true });
  });

  it('a partial pair is treated as unset (never emitted half-set)', () => {
    const patched = buildConversationPatch({}, {
      compactionModelProvider: 'openai',
      compactionModelName: '',
    }) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.compaction).toBeUndefined();
  });

  it('round-trips through configFromConversation', () => {
    const patched = buildConversationPatch({}, {
      compactionEnabled: true,
      compactionModelProvider: 'google',
      compactionModelName: 'gemini-2.5-flash',
    }) as { chatConfig: Record<string, unknown> };
    const readBack = configFromConversation(patched);
    expect(readBack.compactionEnabled).toBe(true);
    expect(readBack.compactionModelProvider).toBe('google');
    expect(readBack.compactionModelName).toBe('gemini-2.5-flash');
  });
});

describe('buildAgentPatch - FLAT keys (PUT /agents)', () => {
  const agent = { name: 'A' } as Agent;

  it('forwards the pair as flat top-level keys (no nested compaction block)', () => {
    const patch = buildAgentPatch(agent, {
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    });
    expect(patch.compactionModelProvider).toBe('openai');
    expect(patch.compactionModelName).toBe('gpt-5-mini');
    expect('compaction' in patch).toBe(false);
  });

  it('sends both "" to clear (backend blank pair = reset to inherit)', () => {
    const patch = buildAgentPatch(agent, { compactionModelProvider: '', compactionModelName: '' });
    expect(patch.compactionModelProvider).toBe('');
    expect(patch.compactionModelName).toBe('');
  });

  it('untouched pair → both keys absent (patch semantics leave the columns as-is)', () => {
    const patch = buildAgentPatch(agent, { compactionEnabled: true });
    expect('compactionModelProvider' in patch).toBe(false);
    expect('compactionModelName' in patch).toBe(false);
  });

  it('never emits a partial pair (skipped so the backend 400 guard cannot trip)', () => {
    const halfDefined = buildAgentPatch(agent, { compactionModelProvider: 'openai' });
    expect('compactionModelProvider' in halfDefined).toBe(false);
    expect('compactionModelName' in halfDefined).toBe(false);

    const halfBlank = buildAgentPatch(agent, { compactionModelProvider: 'openai', compactionModelName: '' });
    expect('compactionModelProvider' in halfBlank).toBe(false);
    expect('compactionModelName' in halfBlank).toBe(false);
  });
});

describe('buildDraftChatConfigBody - new-conversation seed', () => {
  it('seeds the pair NESTED under compaction (the conversation shape)', () => {
    const body = buildDraftChatConfigBody({
      compactionModelProvider: 'openai',
      compactionModelName: 'gpt-5-mini',
    });
    expect(body).toEqual({ compaction: { modelProvider: 'openai', modelName: 'gpt-5-mini' } });
  });

  it('does not seed a partial pair', () => {
    const body = buildDraftChatConfigBody({ compactionModelProvider: 'openai' });
    expect(body).toBeUndefined();
  });
});

describe('stripUnsetCompactionModelPair - user-default save path (flat keys)', () => {
  it('keeps a fully-set pair verbatim', () => {
    const config = { webSearch: true, compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini' };
    expect(stripUnsetCompactionModelPair(config)).toEqual(config);
  });

  it('drops a blank pair (PUT replaces the stored config, so omission clears it)', () => {
    expect(
      stripUnsetCompactionModelPair({ webSearch: true, compactionModelProvider: '', compactionModelName: '' }),
    ).toEqual({ webSearch: true });
  });

  it('drops a partial pair (both keys removed, never sent half-set)', () => {
    expect(
      stripUnsetCompactionModelPair({ compactionModelProvider: 'openai', compactionModelName: '' }),
    ).toEqual({});
    expect(
      stripUnsetCompactionModelPair({ compactionModelName: 'gpt-5-mini' }),
    ).toEqual({});
  });
});
