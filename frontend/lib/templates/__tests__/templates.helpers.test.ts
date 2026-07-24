/**
 * Small helpers with real edge cases: naming collisions, the `t.raw` fallback,
 * and the icon lookup. Each of these fails quietly rather than loudly, which is
 * why they are pinned rather than left to inspection.
 */

import { describe, expect, it } from 'vitest';

import { templateIcon, TEMPLATE_ICONS } from '@/components/templates/templateIcons';

import { uniqueName } from '../instantiate';
import { templateCopy, type Translate } from '../hydrate';
import { getTemplates } from '../index';

describe('uniqueName', () => {
  it('leaves a free name alone', () => {
    expect(uniqueName('Hello workflow', ['Something else'])).toBe('Hello workflow');
  });

  it('suffixes on a case-insensitive collision', () => {
    expect(uniqueName('Hello workflow', ['hello WORKFLOW'])).toBe('Hello workflow (2)');
  });

  it('ignores surrounding whitespace on BOTH sides of the comparison', () => {
    // The stored names are trimmed before comparison, so the candidate must be
    // too, otherwise "  Hello  " reads as free against an existing "Hello".
    expect(uniqueName('  Hello workflow  ', ['Hello workflow'])).toBe('  Hello workflow   (2)');
  });

  it('keeps climbing past an already-suffixed name', () => {
    expect(uniqueName('Hello workflow', ['Hello workflow', 'Hello workflow (2)'])).toBe(
      'Hello workflow (3)',
    );
  });

  it('does not hand back a duplicate on a long collision run', () => {
    const taken = ['Hello workflow', ...Array.from({ length: 120 }, (_, i) => `Hello workflow (${i + 2})`)];
    const result = uniqueName('Hello workflow', taken);
    // A capped loop used to fall through and return the colliding base name.
    expect(taken.map((n) => n.toLowerCase())).not.toContain(result.toLowerCase());
  });

  it('handles an empty existing list', () => {
    expect(uniqueName('Hello workflow', [])).toBe('Hello workflow');
  });
});

describe('templateCopy tolerates a translator without .raw', () => {
  it('falls back to the callable form instead of throwing', () => {
    // Hand-rolled useTranslations stubs in component tests omit `.raw`. A
    // missing helper must not crash the render of a whole page.
    const bare = ((key: string) => `echo:${key}`) as unknown as Translate;
    const meta = getTemplates('workflow')[0].meta;

    expect(() => templateCopy(meta, bare)).not.toThrow();
    expect(templateCopy(meta, bare).title).toContain('echo:');
  });
});

describe('templateIcon', () => {
  it('resolves every icon name the registry actually uses', () => {
    const used = [...getTemplates('workflow'), ...getTemplates('agent')].map((e) => e.meta.icon);
    for (const name of used) {
      expect(Object.keys(TEMPLATE_ICONS), `${name} is not in TEMPLATE_ICONS`).toContain(name);
    }
  });

  it('falls back to a real component for an unmapped name', () => {
    expect(templateIcon('NoSuchIcon')).toBeDefined();
  });
});
