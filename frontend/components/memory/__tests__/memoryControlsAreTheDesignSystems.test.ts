import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';

/**
 * The Memory surfaces use the app's own controls, and nothing hand-rolled.
 *
 * <p>They shipped with four browser-default controls and a private pill class: a
 * native `select` for the type, a native checkbox for the pin, a bare `textarea`
 * for the body, hand-written `button` elements for the row actions and for the
 * type filter, all inside a `fixed inset-0` overlay that re-declared the
 * backdrop, the radius, the focus trap and the Escape handling that `Dialog`
 * already owns. Nothing about that was broken, which is exactly why it survives
 * review: it only reads as a different product, one screen away from the
 * agenda's dialog and the agents list.
 *
 * <p>This is a source-level guard on purpose, in the genre of
 * `ui/__tests__/solidButtonFill.test.ts`: a rendering test would have to pin a
 * class name to catch a hand-rolled control, and a class name is the one thing a
 * restyle is allowed to change. The elements below are not. The radius ladder in
 * `components/ui/README.md` is the other half of the rule and is checked here
 * too, because `rounded-md` on a card and `rounded` on a control are the two
 * slips that put this feature a rung off the rest of the app.
 */

const SURFACES = [
  'components/MemoryTab.tsx',
  'components/memory/MemoryEditorModal.tsx',
];

/** The control, and the component that already exists for it. */
const HAND_ROLLED: Array<{ pattern: RegExp; instead: string }> = [
  { pattern: /<select\b/, instead: 'Select from @/components/ui/select' },
  { pattern: /<textarea\b/, instead: 'Textarea from @/components/ui/textarea' },
  { pattern: /type="checkbox"/, instead: 'Switch from @/components/ui/switch' },
  // No exemption for `type="text"`: a hand-rolled text input is precisely the
  // control `Input` exists for, so exempting it would have opened the hole this
  // list is meant to close.
  { pattern: /<input\b/, instead: 'Input from @/components/ui/input' },
  { pattern: /<button\b/, instead: 'Button from @/components/ui/button' },
  // The overlay, the backdrop and the focus trap belong to Dialog. A dialog that
  // paints its own is also the one that forgets to trap focus in it.
  { pattern: /fixed inset-0/, instead: 'Dialog from @/components/ui/dialog' },
];

/**
 * The file with its comments removed.
 *
 * <p>Comments are where these components EXPLAIN what they no longer do ("the
 * `fixed inset-0` overlay this replaces..."), so scanning them would fail the
 * file for describing its own fix, and the obvious workaround - never naming the
 * old shape - would delete the reason the rule exists.
 */
function read(relative: string): string {
  return fs.readFileSync(path.join(process.cwd(), relative), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '');
}

describe('the memory UI is built from the design system', () => {
  it.each(SURFACES)('%s hand-rolls no control the app already has', (relative) => {
    const source = read(relative);
    const found = HAND_ROLLED
      .filter(({ pattern }) => pattern.test(source))
      .map(({ pattern, instead }) => `${pattern} -> use ${instead}`);

    expect(found, `${relative} hand-rolls a control`).toEqual([]);
  });

  it.each(SURFACES)('%s stays on the radius ladder', (relative) => {
    const source = read(relative);

    // `rounded-` with nothing after it is the 4px default, which is below every
    // rung on the ladder; `rounded-md` is the rung for a small non-interactive
    // label, so it may not carry a border (that shape is a card or a control).
    //
    // Scanned over the whole source rather than inside `className="..."`: a
    // conditional class lives in a TEMPLATE literal, and every radius this
    // feature got wrong (the card, the pin button, the eye button) was in one.
    // Anchored on a word boundary so `rounded-md`/`rounded-xl` and the CSS
    // property `borderRadius` are not mistaken for it.
    const bareRadius = [...source.matchAll(/\brounded(?=[\s"'`}])/g)].map((m) => m.index);
    expect(bareRadius, `${relative} uses the 4px default radius`).toEqual([]);

    const mdWithBorder = [...source.matchAll(/\brounded-md\b[^"`]*\bborder\b/g)].map((m) => m[0]);
    expect(mdWithBorder, `${relative} draws a bordered box at label radius`).toEqual([]);
  });

  it('the editor opens on the platform Dialog rather than its own overlay', () => {
    const source = read('components/memory/MemoryEditorModal.tsx');

    // Named rather than merely implied by the absence of `fixed inset-0`: the
    // point is which component owns Escape, the backdrop and the focus trap, and
    // an overlay can be re-hand-rolled under a different class string.
    expect(source).toMatch(/from '@\/components\/ui\/dialog'/);
    // Matched without its closing bracket so adding a className to the tag does
    // not fail the build.
    expect(source).toMatch(/<DialogFooter\b/);
    // Escape is the dialog's, not a document-level listener of this component's
    // own - one that outlives its owner fires for every Escape on the page after.
    expect(source).not.toMatch(/addEventListener\('keydown'/);
  });
});
