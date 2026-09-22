import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The workflow card's footer meta row, which must stay ONE line.
 *
 * It accumulates: a modified date, a run count, a spending figure, a live dot, a
 * review or rejected badge, a shared globe or a private lock, and - at the right
 * edge - a group holding the relations button and the resource-info button.
 * Nothing stopped the text inside each segment from wrapping, so on a narrow card
 * the date alone took two lines and pushed the rest of the row down.
 *
 * The mechanism is `truncate` on the date and `shrink-0` on every other DIRECT
 * child, so the date is the only thing that gives. That is a whole-row
 * invariant: it holds only if every child declares it, and the first version of
 * this fix missed the relations button, a 28px control that then shared the
 * shrinking with the date and lost its square before the date had finished
 * truncating.
 *
 * Asserted over the source, because jsdom computes no widths - the same way the
 * pickers pin their stacking order - and because rendering this table means
 * standing up folders, favorites, pagination and four API clients for a claim
 * about class names.
 */

const SOURCE = fs.readFileSync(
  path.join(process.cwd(), 'components/WorkflowTable.tsx'),
  'utf8'
);

/**
 * The row, found by a STABLE anchor and not by its own class string.
 *
 * Anchoring on the container's className would make every assertion below
 * depend on it: change one class and the finder misses, the guard trips, and
 * the failure names the wrong thing.
 *
 * Missing the anchor THROWS rather than yielding an empty slice. An earlier
 * version returned `''` and let three of the four invariant tests pass
 * vacuously (`expect([]).toEqual([])`), leaving one guard between a reworded
 * comment and a silently unenforced layout rule.
 */
const ROW_MARKER = '{/* ONE line, always.';

function rowSource(): string {
  const start = SOURCE.indexOf(ROW_MARKER);
  if (start === -1) {
    throw new Error(
      `WorkflowTable.metaRow: the marker "${ROW_MARKER}" is gone from WorkflowTable.tsx. ` +
        'Re-anchor this test on the row it describes rather than deleting it.'
    );
  }
  // Take the closing `</div>` of the controls group WITH it. Without the tail the
  // slice stopped short of the LAST child - the very element whose missing
  // `shrink-0` this test exists for - and restoring that bug left the suite green.
  const lastControl = SOURCE.indexOf('<ResourceInfoPopover', start);
  if (lastControl === -1) throw new Error('WorkflowTable.metaRow: the row no longer ends on the controls group.');
  const end = SOURCE.indexOf('</div>', SOURCE.indexOf('/>', lastControl)) + '</div>'.length;
  if (end <= start + 1) throw new Error('WorkflowTable.metaRow: the controls group is not closed.');
  return SOURCE.slice(start, end);
}

const ROW = rowSource();

/** The container's own class list: the first className after the marker. */
const ROW_OPEN = ROW.match(/<div className="([^"]+)"/)?.[1] ?? '';

/**
 * The row's flex ITEMS: the elements that actually become children of the flex
 * container, whatever JSX wrapping sits between them and it.
 *
 * <p>Neither indentation nor a flat className scan gets this right. Most
 * segments live inside `{condition && (<>...</>)}`, so they are indented deeper
 * than a direct child while still being flex items; and the icons inside a
 * `shrink-0` span are indented like siblings while being grandchildren, on
 * which `shrink-0` does nothing. An earlier version of this test demanded the
 * class from those icons and could not see a child written with no className at
 * all - wrong in both directions at once.
 *
 * <p>So: walk the named tags and track element depth. Fragments and
 * `{...}` expressions carry no tag, so they are transparent, which is exactly
 * what they are to the flex layout.
 */
function flexItems(row: string): { tag: string; className: string | null }[] {
  const body = row.slice(row.indexOf('>', row.indexOf('<div className=')) + 1);
  const items: { tag: string; className: string | null }[] = [];
  let depth = 0;

  // A tag ends on the first `>` that is NOT inside a JSX expression. Reading
  // attributes as "anything that is not a tag delimiter" is what an earlier
  // version did, and it cut the tag short at the `>` of the arrow in
  // `loadEditors={() => ...}`: everything after it, className included, fell
  // outside the match, so that child was reported as carrying no class - a
  // failure naming the wrong cause. Tracking brace depth is what makes an
  // inline handler transparent, the way it is to the layout.
  //
  // Deliberately NOT quote-aware beyond that: an earlier version was, and broke
  // on the first apostrophe inside a `//` comment between attributes ("the
  // row's ONLY control"), silently dropping the element this test exists for.
  for (let i = 0; i < body.length; i++) {
    if (body[i] !== '<') continue;
    const nameMatch = /^<(\/?)([A-Za-z][\w.]*)/.exec(body.slice(i));
    if (!nameMatch) continue;
    const [head, closing, tag] = nameMatch;

    let braces = 0;
    let end = -1;
    for (let j = i + head.length; j < body.length; j++) {
      const ch = body[j];
      if (ch === '{') braces += 1;
      else if (ch === '}') braces -= 1;
      else if (ch === '>' && braces === 0) { end = j; break; }
    }
    if (end === -1) break;

    const attrs = body.slice(i + head.length, end);
    const selfClosing = attrs.trimEnd().endsWith('/');
    i = end;

    if (closing) {
      depth -= 1;
      continue;
    }
    if (depth === 0) {
      // Either quote style. A single-quoted className read as "no className"
      // would be reported as shrinkable: a failure for the wrong reason.
      items.push({ tag, className: attrs.match(/className=["']([^"']+)["']/)?.[1] ?? null });
    }
    if (!selfClosing) depth += 1;
  }
  return items;
}

const CHILDREN = flexItems(ROW);

/** The right-edge controls group: its own class list, and the controls inside it. */
const CONTROLS_GROUP = ROW.match(/<div className="([^"]*ml-auto[^"]*)"/)?.[1] ?? '';
const CONTROLS = flexItems(ROW.slice(ROW.indexOf('<div className="' + CONTROLS_GROUP)));

describe('the workflow card footer stays on one line', () => {
  it('found the row it is about, and all of it', () => {
    expect(ROW_OPEN, 'no container class list found after the marker').not.toBe('');
    expect(ROW).toContain('BudgetChip');
    expect(ROW).toContain('WorkflowRelationsMenu');
    expect(ROW).toContain('ResourceInfoPopover');
    // Every optional segment, so a test that stops seeing one says so.
    for (const marker of ['runCount', 'workflow.live', 'PENDING_REVIEW', 'REJECTED']) {
      expect(ROW, `the row no longer carries ${marker}`).toContain(marker);
    }
    expect(CHILDREN.length).toBeGreaterThan(6);
    // Named explicitly, because these are the ones a scanner drops first: the chip
    // carries no className, and the controls group closes the row and is written
    // across several lines with comments between its attributes. A slice or a regex
    // that loses either turns the test below into a weaker claim without failing.
    expect(CHILDREN.map((child) => child.tag)).toEqual(
      expect.arrayContaining(['BudgetChip', 'div'])
    );
    // The controls are one level down now, inside that group - so the group is what
    // the row's shrink invariant applies to, and CONTROLS below is what covers them.
    expect(CONTROLS.map((child) => child.tag)).toEqual(
      ['WorkflowRelationsMenu', 'ResourceInfoPopover']
    );
  });

  it('keeps both controls in ONE group that carries the right-edge push', () => {
    // `ml-auto` belongs to the GROUP, not to either button. On a card with no
    // relations the info button would otherwise be the one carrying it on some
    // cards and not others, and the row's right edge would jitter across a grid.
    expect(CONTROLS_GROUP, 'the controls group lost its right-edge push').toContain('ml-auto');
    expect(CONTROLS_GROUP, 'the controls group can be squeezed').toContain('shrink-0');
    for (const control of CONTROLS) {
      expect(control.className ?? '', `<${control.tag}> should not carry ml-auto`).not.toContain('ml-auto');
      // Inside the group's own flex row the same rule applies: a 28px square that
      // shrinks stops being a square.
      expect(control.className ?? '', `<${control.tag}> can still be squeezed`).toContain('shrink-0');
    }
  });

  /**
   * A child may hold the contract itself instead of being told it here, and one
   * does: `<BudgetChip />` is rendered with no className and declares its own
   * `shrink-0` (pinned in BudgetChip.test.tsx). Passing the class again at this
   * call site would be inert and would read as load-bearing, so the exemption is
   * VERIFIED rather than assumed: the component's source has to still say it.
   */
  const SELF_DECLARING: Record<string, string> = {
    BudgetChip: 'components/budget/BudgetChip.tsx',
  };

  it.each(Object.entries(SELF_DECLARING))('%s still declares its own shrink-0', (tag, file) => {
    // On the ELEMENT it renders, not anywhere in the file. `shrink-0` also
    // appears in that file inside a comment and on the coin icon, so a
    // whole-file substring held even with the class deleted from the control.
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    const button = source.slice(source.indexOf('<button'), source.indexOf('</button>'));
    expect(button.length, `${tag} renders no button to carry the class`).toBeGreaterThan(0);
    expect(button, `${tag} no longer refuses to shrink, so the row must say it`).toContain('shrink-0');
  });

  it('lets nothing but the date give up width', () => {
    // The exact bug: `ml-auto` on the relations button with no `shrink-0`.
    // Flex distributes shrinkage by base size, so it started losing width at the
    // same time as the date, not after it.
    //
    // A child with NO className and no entry in SELF_DECLARING fails this,
    // deliberately: that is how a future segment added without the class gets
    // caught, which a flat className scan could never do.
    const shrinkable = CHILDREN.filter(
      (child) =>
        !SELF_DECLARING[child.tag] &&
        !/(^|\s)(shrink-0|truncate)(\s|$)/.test(child.className ?? '')
    ).map((child) => `<${child.tag} className=${JSON.stringify(child.className)}>`);
    expect(shrinkable, 'these row children can still be squeezed').toEqual([]);
  });

  it('truncates the date rather than wrapping it, which is the whole mechanism', () => {
    // `truncate` is both halves: it sets `white-space: nowrap` AND lets the
    // segment's automatic minimum size resolve to 0, which is what allows it to
    // shrink at all. Without the second, the ellipsis never fires.
    const truncating = CHILDREN.filter((child) =>
      (child.className ?? '').split(/\s+/).includes('truncate')
    );
    expect(truncating).toHaveLength(1);
    expect(truncating[0].tag).toBe('span');
  });

  it('does not clip the row, which would cut the spending chip\'s focus ring', () => {
    // `overflow-hidden` here looks like the tidy answer and is not: the
    // BudgetChip's focus ring is drawn 3px outside its box and is that
    // control's only keyboard affordance. The card root clips anyway, so this
    // buys no protection against a row that cannot fit; it only moves the clip
    // out to where the ring survives it.
    expect(ROW_OPEN).not.toContain('overflow-hidden');
  });

  it('carries no class that does nothing', () => {
    // `display: flex` is `flex-wrap: nowrap` already, and `min-width` is 0 by
    // initial value on a block box like this container, which is not itself a
    // flex item. Both were in earlier versions of this row, presented as part
    // of the reason the line holds.
    expect(ROW_OPEN).not.toContain('flex-nowrap');
    expect(ROW_OPEN).not.toContain('min-w-0');
  });
});
