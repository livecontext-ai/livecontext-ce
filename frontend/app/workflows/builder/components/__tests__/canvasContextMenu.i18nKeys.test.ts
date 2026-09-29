/**
 * Every label the canvas context menu asks for exists, in every locale.
 *
 * The node menu picks some labels with a condition (`t(stepped ? 'rerunStep' : 'rerunStepAuto')`),
 * and one branch named a key that existed only under another namespace: a right-click on a node
 * of an automatic run printed the raw key "workflowBuilder.contextMenu.rerunStepAuto". Read from
 * the source, so a new label, or a new branch of a ternary, is checked the day it is written.
 */
import { describe, expect, it } from 'vitest';
import fs from 'fs';
import path from 'path';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

const LOCALES: Record<string, unknown> = { en, fr, de, es, pt, zh };
const SOURCE = fs.readFileSync(path.resolve(__dirname, '../CanvasContextMenu.tsx'), 'utf8');

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    messages,
  );
}

/** Literal keys passed to a translator: `fn('a')` and `fn(cond ? 'a' : 'b')`. */
function keysOf(fn: string): string[] {
  const keys = new Set<string>();
  for (const call of SOURCE.matchAll(new RegExp(`(?<![\\w.])${fn}\\(([^()]*)\\)`, 'g'))) {
    for (const literal of call[1].matchAll(/'([\w.]+)'/g)) keys.add(literal[1]);
  }
  return [...keys];
}

const USED = [
  ...keysOf('t').map(k => `workflowBuilder.contextMenu.${k}`),
  ...keysOf('tCanvas').map(k => `workflowBuilder.canvas.${k}`),
];

describe('canvas context menu labels', () => {
  it('reads its keys from the source (the guard is not checking an empty list)', () => {
    expect(USED).toContain('workflowBuilder.contextMenu.rerunStep');
    // The automatic-run wording, the branch that printed a raw key.
    expect(USED).toContain('workflowBuilder.canvas.rerunStepAuto');
  });

  it.each(Object.keys(LOCALES))('has every label in %s', (locale) => {
    const missing = USED.filter(key => typeof lookup(LOCALES[locale], key) !== 'string');
    expect(missing).toEqual([]);
  });
});
