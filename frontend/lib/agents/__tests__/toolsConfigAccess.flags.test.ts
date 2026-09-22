import { describe, it, expect } from 'vitest';
import { buildToolsConfigPayload, isGenerationEnabled, getToolsMode } from '../toolsConfigAccess';

/**
 * Generation is an opt-in agent flag persisted on toolsConfig.generation. The reader
 * mirrors the backend (opt-in, tolerates boolean + object shapes), and the payload
 * builder must emit BOTH true and false so the merge-on-update backend can be switched
 * off - the original `=== true` filter silently dropped the off state.
 */
describe('toolsConfigAccess - generation / webSearch flags', () => {
  const base = {
    mode: 'all' as const,
    workflows: [],
    tables: [],
    interfaces: [],
    agents: [],
    applications: [],
  };

  describe('buildToolsConfigPayload emits flags so OFF persists through the backend merge', () => {
    /**
     * The legacy imageGeneration grant is retired: the builder must not carry it, even
     * when a stale caller still passes it. Emitting it would re-persist a key nothing
     * honours, keeping a dead grant alive in the row forever.
     */
    it('never emits the retired imageGeneration key, whatever the caller passes', () => {
      // @ts-expect-error - the key is gone from the input type on purpose
      expect('imageGeneration' in buildToolsConfigPayload({ ...base, imageGeneration: true })).toBe(false);
      // @ts-expect-error - same, for the off state
      expect('imageGeneration' in buildToolsConfigPayload({ ...base, imageGeneration: false })).toBe(false);
      expect('imageGeneration' in buildToolsConfigPayload({ ...base })).toBe(false);
    });

    it('emits webSearch for both true and false (re-enable must persist too)', () => {
      expect(buildToolsConfigPayload({ ...base, webSearch: true }).webSearch).toBe(true);
      expect(buildToolsConfigPayload({ ...base, webSearch: false }).webSearch).toBe(false);
      expect('webSearch' in buildToolsConfigPayload({ ...base })).toBe(false);
    });
  });
});

describe('isGenerationEnabled (opt-in, independent of the image grant)', () => {
  it('defaults to disabled when absent, because it spends the customer credits', () => {
    expect(isGenerationEnabled(null)).toBe(false);
    expect(isGenerationEnabled(undefined)).toBe(false);
    expect(isGenerationEnabled({})).toBe(false);
    expect(isGenerationEnabled({ generation: false })).toBe(false);
  });

  it('accepts both the boolean and the object shape', () => {
    expect(isGenerationEnabled({ generation: true })).toBe(true);
    expect(isGenerationEnabled({ generation: { enabled: true } })).toBe(true);
    // An object without an explicit flag means enabled, matching the backend.
    expect(isGenerationEnabled({ generation: {} })).toBe(true);
    expect(isGenerationEnabled({ generation: { enabled: false } })).toBe(false);
  });

  it('is NOT inherited from the retired image grant', () => {
    // A row still carrying the retired grant must not silently gain video: a
    // per-second video model spends an order of magnitude more per call.
    expect(isGenerationEnabled({ imageGeneration: true })).toBe(false);
    expect(isGenerationEnabled({ imageGeneration: { enabled: true } })).toBe(false);
  });

  it('sends an explicit false so a grant can actually be revoked', () => {
    // The backend MERGES toolsConfig on update, so omitting the flag would keep
    // the stored value and an agent that had it on could never be switched off.
    const base = {
      mode: 'all' as const,
      workflows: [], tables: [], interfaces: [], agents: [], applications: [],
    };
    expect(buildToolsConfigPayload({ ...base, generation: false }).generation).toBe(false);
    expect(buildToolsConfigPayload({ ...base, generation: true }).generation).toBe(true);
    expect('generation' in buildToolsConfigPayload({ ...base })).toBe(false);
  });
});

/**
 * `off` means "no tools at all" and is a member of the ToolsMode union, but the reader
 * collapsed everything that was not 'none' or 'custom' into 'all'. A tool-less agent
 * therefore rendered as "All tools" in the fleet view, which is the opposite of the truth.
 */
describe('getToolsMode', () => {
  it('reports off as off, not as all', () => {
    expect(getToolsMode({ mode: 'off' })).toBe('off');
  });

  it('still reports the other modes unchanged', () => {
    expect(getToolsMode({ mode: 'none' })).toBe('none');
    expect(getToolsMode({ mode: 'custom' })).toBe('custom');
    expect(getToolsMode({ mode: 'all' })).toBe('all');
  });

  it('defaults to all when the mode is absent or unreadable', () => {
    expect(getToolsMode({})).toBe('all');
    expect(getToolsMode(null)).toBe('all');
    expect(getToolsMode({ mode: 'nonsense' })).toBe('all');
  });
});
