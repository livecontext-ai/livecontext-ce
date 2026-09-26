import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';

import en from '@/messages/en.json';

/**
 * Every translation key the memory UI asks for actually exists.
 *
 * Locale PARITY does not cover this. Parity compares the locale files against
 * each other, so a key that is missing from all six is perfectly symmetric and
 * perfectly broken: `t('tabMemory')` renders the raw key in the interface and
 * next-intl logs MISSING_MESSAGE. That is exactly what shipped here - the Memory
 * tab's own label - and it survived a locale-parity check, a component test that
 * mocks `t` to the identity function, and an end-to-end test that reached the tab
 * by URL and so never read its label. It then happened a SECOND time, when a
 * script that reconciles this branch's keys onto a moved `origin/dev` did not
 * list that key among the ones it owns; this test caught that one in a second.
 *
 * The three defences are different: parity says the six files agree, this says
 * the code and the files agree, and neither says the wording is right.
 */

const FILES = [
  'components/MemoryTab.tsx',
  'components/memory/MemoryEditorModal.tsx',
  'components/views/AgentPageTabBar.tsx',
  // Two namespaces in one file (`modals.createAgent` and `chatConfig`), which is
  // why the scan below binds each translator VARIABLE to its own namespace rather
  // than assuming a file has one. Skipping this file is what would have let the
  // memory access-mode keys go missing.
  'components/chat/CreateAgentModal.tsx',
  // The agent & chat defaults panel, hosted by the Agents page "Settings" tab. Same
  // exposure as the Memory tab above: its own header text comes from a namespace no
  // other surface uses, and its component test mocks `t` to the identity function - so
  // this is the only unit-level guard that the keys exist at all.
  'components/settings/AgentChatDefaults.tsx',
];

/** `const t = useTranslations('a.b')` -> { t: 'a.b' }, for every translator in the file. */
function namespacesOf(source: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const m of source.matchAll(/const\s+(\w+)\s*=\s*useTranslations\(\s*['"]([^'"]+)['"]\s*\)/g)) {
    found.set(m[1], m[2]);
  }
  return found;
}

/**
 * Bare `<translator>('key')` calls. Template literals (`t(\`type.${x}\`)`) are
 * skipped on purpose: their key is only known at runtime, and guessing at it
 * would produce false failures rather than coverage.
 */
function keysFor(source: string, translator: string): string[] {
  const pattern = new RegExp(`\\b${translator}\\(\\s*'([A-Za-z][A-Za-z0-9_.]*)'`, 'g');
  return [...source.matchAll(pattern)].map((m) => m[1]);
}

function resolve(messages: unknown, dotted: string): unknown {
  return dotted.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    messages,
  );
}

describe('memory UI translation keys', () => {
  it.each(FILES)('%s asks only for keys that exist in en.json', (relative) => {
    const source = fs.readFileSync(path.join(process.cwd(), relative), 'utf8');
    const namespaces = namespacesOf(source);
    expect(namespaces.size, `${relative} declares no useTranslations`).toBeGreaterThan(0);

    const missing: string[] = [];
    for (const [translator, ns] of namespaces) {
      for (const key of keysFor(source, translator)) {
        const full = `${ns}.${key}`;
        if (typeof resolve(en, full) !== 'string') missing.push(full);
      }
    }

    expect(missing, `${relative} uses keys that no locale defines`).toEqual([]);
  });

  it('covers the two keys that went missing, by name', () => {
    // Named as well as scanned, so the guard survives a refactor that moves these
    // components out of the scanned list.
    expect(resolve(en, 'emptyState.agent.tabMemory')).toBe('Memory');
    expect(resolve(en, 'modals.createAgent.memoryAccessLabel')).toBe('Long-term memory');
  });
});
