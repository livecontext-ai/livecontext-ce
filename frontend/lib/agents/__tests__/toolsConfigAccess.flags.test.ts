import { describe, it, expect } from 'vitest';
import { buildToolsConfigPayload, isImageGenerationEnabled } from '../toolsConfigAccess';

/**
 * Image generation is an opt-in agent flag persisted on toolsConfig.imageGeneration.
 * The reader mirrors the backend (opt-in, tolerates boolean + object shapes), and the
 * payload builder must emit BOTH true and false so the merge-on-update backend can be
 * switched off - the original `=== true` filter silently dropped the off state.
 */
describe('toolsConfigAccess - imageGeneration / webSearch flags', () => {
  const base = {
    mode: 'all' as const,
    workflows: [],
    tables: [],
    interfaces: [],
    agents: [],
    applications: [],
  };

  describe('isImageGenerationEnabled (opt-in, both shapes)', () => {
    it('defaults to false when absent or toolsConfig is null', () => {
      expect(isImageGenerationEnabled(null)).toBe(false);
      expect(isImageGenerationEnabled(undefined)).toBe(false);
      expect(isImageGenerationEnabled({})).toBe(false);
    });

    it('reads the boolean shape', () => {
      expect(isImageGenerationEnabled({ imageGeneration: true })).toBe(true);
      expect(isImageGenerationEnabled({ imageGeneration: false })).toBe(false);
    });

    it('reads the object shape and treats an object without `enabled` as enabled', () => {
      expect(isImageGenerationEnabled({ imageGeneration: { enabled: true } })).toBe(true);
      expect(isImageGenerationEnabled({ imageGeneration: { enabled: false } })).toBe(false);
      expect(isImageGenerationEnabled({ imageGeneration: { provider: 'openai' } })).toBe(true);
    });
  });

  describe('buildToolsConfigPayload emits flags so OFF persists through the backend merge', () => {
    it('emits imageGeneration: true when enabled', () => {
      const payload = buildToolsConfigPayload({ ...base, imageGeneration: true });
      expect(payload.imageGeneration).toBe(true);
    });

    it('emits imageGeneration: false when disabled (so turning it off persists)', () => {
      const payload = buildToolsConfigPayload({ ...base, imageGeneration: false });
      expect(payload.imageGeneration).toBe(false);
    });

    it('omits imageGeneration entirely when the caller does not pass it', () => {
      const payload = buildToolsConfigPayload({ ...base });
      expect('imageGeneration' in payload).toBe(false);
    });

    it('emits webSearch for both true and false (re-enable must persist too)', () => {
      expect(buildToolsConfigPayload({ ...base, webSearch: true }).webSearch).toBe(true);
      expect(buildToolsConfigPayload({ ...base, webSearch: false }).webSearch).toBe(false);
      expect('webSearch' in buildToolsConfigPayload({ ...base })).toBe(false);
    });
  });
});
