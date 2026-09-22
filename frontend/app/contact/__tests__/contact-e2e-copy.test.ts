import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';

import enMessages from '@/messages/en.json';

/**
 * Every sentence the CE contact e2e spec asserts must still exist in `en.json`.
 *
 * <p><strong>Why this needs a test.</strong> `frontend/e2e/ce/ce-contact-form-ui.spec.ts`
 * asserts English copy as string literals, and Playwright specs run only against a Docker
 * slot, never in CI. So rewording a message in `en.json` leaves that suite asserting a
 * sentence that exists nowhere, and nothing says so until somebody claims a slot, which for a
 * marketing page can be months.
 *
 * <p>That is not hypothetical: it happened while writing the very change this file ships with.
 * `contact.unavailable` was reworded after the e2e assertion for it was written, and the
 * mismatch survived two reviews before a third caught it by reading both files side by side.
 * This test is five milliseconds of CI in place of that.
 *
 * <p>It checks one direction only, deliberately: every literal the spec asserts must be real
 * copy. It does not demand that every message be asserted, because most of them should not be.
 */

const SPEC = join(__dirname, '..', '..', '..', 'e2e', 'ce', 'ce-contact-form-ui.spec.ts');

/** Every leaf string under `contact` in the English dictionary. */
function contactCopy(): string[] {
  const out: string[] = [];
  const walk = (node: unknown) => {
    if (typeof node === 'string') {
      out.push(node);
      return;
    }
    if (node && typeof node === 'object') Object.values(node).forEach(walk);
  };
  walk((enMessages as Record<string, unknown>).contact);
  expect(out.length, 'contact block is empty - the import is wrong, not the copy').toBeGreaterThan(20);
  return out;
}

/**
 * The user-facing sentences the spec asserts: the literal inside a `getByText('...')`, and the
 * `message` argument of `submitAndExpectApiError(page, code, message)`. Test titles and error
 * CODES are excluded - a title is not copy, and a code is not shown to anyone.
 */
function assertedSentences(): string[] {
  const source = readFileSync(SPEC, 'utf8');
  const found = new Set<string>();

  for (const m of source.matchAll(/getByText\('([^']+)'\)/g)) found.add(m[1]);
  // The helper is called both on one line and spread over several.
  for (const m of source.matchAll(/submitAndExpectApiError\(\s*page,\s*'[^']+',\s*'([^']+)',?\s*\)/g)) {
    found.add(m[1]);
  }

  const sentences = [...found].filter((text) => text.includes(' '));
  expect(sentences.length, 'no asserted copy found - the extraction is broken, not the spec')
    .toBeGreaterThan(5);
  return sentences;
}

describe('CE contact e2e spec asserts copy that still exists', () => {
  it('finds every asserted sentence in the English contact dictionary', () => {
    const copy = contactCopy();
    const orphans = assertedSentences().filter((sentence) => !copy.some((value) => {
      // getByText matches substrings, so either direction counts as found.
      if (value.includes(sentence) || sentence.includes(value)) return true;
      // A message carrying ICU placeholders is asserted with them filled in ("{count} / {max}"
      // appears as "5 / 5000"), so compare against the message's fixed parts only. Matching on
      // a literal prefix instead would pass nothing for "{count} / {max}", whose fixed part
      // does not start the string.
      if (!value.includes('{')) return false;
      const pattern = value
        .split(/\{[^}]*\}/)
        .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, String.fromCharCode(92) + '$&'))
        .join('.+');
      return new RegExp(String.fromCharCode(94) + pattern + '$').test(sentence);
    }));

    expect(
      orphans,
      'asserted by frontend/e2e/ce/ce-contact-form-ui.spec.ts but present in no contact message '
        + 'in messages/en.json. That suite runs only on a Docker slot, so the mismatch is '
        + 'invisible in CI: it will simply be red the next time anyone claims one. Realign the '
        + 'spec with the copy (or the copy with the spec):'
        + String.fromCharCode(10) + '  '
        + orphans.join(String.fromCharCode(10) + '  '),
    ).toEqual([]);
  });
});
