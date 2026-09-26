import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';

import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Every key this panel asks for must EXIST in the namespace it reads.
 *
 * The locale-parity test compares the six files to each other, so a key added to the wrong
 * namespace in all six is perfect parity and passes. That is exactly how a raw key path
 * shipped into the accessible name of every row of this panel, in six languages at once: the
 * insert was anchored on a key name that appears more than once in the file and it landed in
 * an unrelated section. Nothing else could catch it either, because every test of this panel
 * injects an echo translator, which by construction cannot miss a message.
 *
 * The panel receives `t` from `useTranslations("aiProviders")`, so a `t("modelConfig.x")`
 * call resolves `aiProviders.modelConfig.x`.
 */

const LOCALES = { en, fr, de, es, pt, zh } as Record<string, Record<string, unknown>>;

const PANEL = path.join(__dirname, '..', 'ModelManagementPanel.tsx');
/** Rendered inside the panel with the same `t`, so it reads the same namespace. */
const RETIRED_PANEL = path.join(__dirname, '..', 'RetiredModelsPanel.tsx');

/** Literal keys only: a template with an interpolation is resolved at run time. */
function keysUsedBy(file: string): string[] {
  const source = readFileSync(file, 'utf8');
  const found = [...source.matchAll(/\bt\(\s*["'`]([A-Za-z0-9_.]+)["'`]/g)].map((m) => m[1]);
  return [...new Set(found)];
}

function lookup(messages: unknown, dottedPath: string): unknown {
  return dottedPath
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages);
}

describe('ModelManagementPanel i18n keys', () => {
  const keys = [...new Set([...keysUsedBy(PANEL), ...keysUsedBy(RETIRED_PANEL)])];

  it('asks for keys at all, so a broken extraction fails loudly instead of passing empty', () => {
    expect(keys.length).toBeGreaterThan(20);
  });

  it.each(Object.keys(LOCALES))('resolves every key under aiProviders in %s', (locale) => {
    const missing = keys.filter(
      (key) => typeof lookup((LOCALES[locale] as Record<string, unknown>).aiProviders, key) !== 'string',
    );

    expect(missing, `missing in ${locale}.json under aiProviders`).toEqual([]);
  });
});
