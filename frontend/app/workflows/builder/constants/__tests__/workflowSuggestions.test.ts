import { describe, it, expect } from 'vitest';
import { getDisplayedSuggestions, WORKFLOW_SUGGESTIONS } from '../workflowSuggestions';

/**
 * Regression: the new-workflow ("How can I help you?") suggestion chips used to
 * carry hardcoded French label/prompt strings, so non-French users saw French.
 * The constant now holds STATIC METADATA ONLY (id/icon/triggerType); label and
 * prompt are resolved from i18n at render time. These tests pin that contract so
 * a future edit can't silently re-introduce hardcoded user-facing text here.
 */
describe('workflowSuggestions - metadata only (i18n-resolved text)', () => {
  it('every suggestion entry exposes id/icon/triggerType and NO hardcoded label/prompt', () => {
    expect(WORKFLOW_SUGGESTIONS.length).toBeGreaterThan(0);
    for (const s of WORKFLOW_SUGGESTIONS) {
      expect(typeof s.id).toBe('string');
      expect(s.id.length).toBeGreaterThan(0);
      expect(s.icon).toBeTruthy(); // a lucide icon component
      expect(['schedule', 'webhook', 'table', 'chat', 'manual']).toContain(s.triggerType);
      // The whole point of the fix: text is NOT stored on the constant anymore.
      expect('label' in s).toBe(false);
      expect('prompt' in s).toBe(false);
    }
  });

  it('suggestion ids are unique (so i18n keys never collide)', () => {
    const ids = WORKFLOW_SUGGESTIONS.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('getDisplayedSuggestions returns one per trigger type plus extras, as metadata only', () => {
    const picked = getDisplayedSuggestions();
    // 5 trigger types (one each) + 3 random extras
    expect(picked.length).toBe(8);
    // all 5 trigger families are represented
    expect(new Set(picked.map((p) => p.triggerType)).size).toBe(5);
    // still metadata only - no hardcoded text leaked back in
    for (const p of picked) {
      expect('label' in p).toBe(false);
      expect('prompt' in p).toBe(false);
    }
  });
});
