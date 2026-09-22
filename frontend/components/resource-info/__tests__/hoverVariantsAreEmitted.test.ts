/**
 * The hover states of the three controls this change touched must be written in a form
 * Tailwind actually emits.
 *
 * <p>`bg-theme-*` / `text-theme-*` are hand-written CSS in `@layer components`, not Tailwind
 * theme colours, so v4 emits the base class and NEVER a variant of it. `hover:bg-theme-secondary`
 * therefore produces no CSS at all: the source reads as styled, the reader gets no hover, and
 * nothing fails - not a test, not the build, not the type checker. The project measured 465
 * such dead occurrences across 179 files.
 *
 * <p>Two of the three controls here (the relations menu, the crumb's favourite star) had
 * exactly that bug and were fixed in the same pass as the new one, so that a row of adjacent
 * buttons does not have one that lights up and two that do not. Nothing pinned the fix, and
 * reverting all three left 241 tests green - which is what this file is for.
 *
 * <p>The working form is an arbitrary value: `hover:bg-[var(--bg-secondary)]`.
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/** The files whose hover states this change is responsible for. */
const FILES = [
  'components/resource-info/ResourceInfoPopover.tsx',
  'components/workflow/relations/WorkflowRelationsMenu.tsx',
  'components/ui/breadcrumb.tsx',
];

/** `hover:`, `focus:`, `focus-visible:` or `group-hover:` applied to a hand-written theme class. */
const DEAD_VARIANT = /(?:hover|focus|focus-visible|active|group-hover(?:\/[\w-]+)?):(?:bg|text|border)-theme-[\w-]+/g;

/**
 * Strip comments, block and line, before scanning. The fixes in these files are EXPLAINED by comments that
 * quote the dead form, so a raw scan flags the explanation and not the code - and the natural
 * way to make that pass is to delete the explanation, which is the opposite of the point.
 */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

describe.each(FILES)('%s', (file) => {
  it('writes no hover state that Tailwind will silently drop', () => {
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');

    const dead = code(source).match(DEAD_VARIANT) ?? [];

    expect(
      dead,
      `these produce no CSS - rewrite as an arbitrary value, e.g. hover:bg-[var(--bg-secondary)]`,
    ).toEqual([]);
  });
});

describe('the guard itself', () => {
  it('recognises the dead form it is looking for', () => {
    // Without this the regex could quietly stop matching and every file above would pass for
    // the wrong reason - the exact failure mode the files are being checked against.
    expect('hover:bg-theme-secondary'.match(DEAD_VARIANT)).toEqual(['hover:bg-theme-secondary']);
    expect('group-hover/crumb:text-theme-primary'.match(DEAD_VARIANT))
      .toEqual(['group-hover/crumb:text-theme-primary']);
    // ...and leaves the working form alone.
    expect('hover:bg-[var(--bg-secondary)]'.match(DEAD_VARIANT)).toBeNull();
    // ...and does not object to a plain, non-variant theme class, which emits fine.
    expect('text-theme-muted'.match(DEAD_VARIANT)).toBeNull();
  });
});
