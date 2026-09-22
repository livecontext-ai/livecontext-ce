/**
 * Column layout of the Home highlights row.
 *
 * The row sits next to a collapsible sidebar, so the space it actually gets is
 * NOT the viewport width: a viewport-breakpoint grid asks for 4 columns while the
 * row only has room for 3. The tiers below are therefore CONTAINER queries,
 * measured on the row itself (its wrapper carries `@container`).
 */

/**
 * Columns for ONE width tier, so the last row is never a lonely single card.
 *
 * `max` is how many cards the tier fits. We take the widest count that does not
 * strand a single card on the last row, down to 2 columns: 4 cards over 3 columns
 * are 3 + 1, so they render 2 + 2. When NO count from `max` down to 2 avoids the
 * orphan (7 cards are 3 + 3 + 1, 2 + 2 + 2 + 1, ...) we keep `max`, because a
 * narrower grid would then buy taller rows and wider cards without fixing
 * anything. Counts that fit on a single row keep `max` too, so 2 cards stay the
 * size of a full row instead of blowing up to half the width each.
 */
function balancedColumns(count: number, max: number): number {
  for (let cols = max; cols >= 2; cols--) {
    if (count <= cols || count % cols !== 1) return cols;
  }
  return max;
}

// Written out in full (never assembled by string concatenation) so Tailwind's
// class scanner sees every variant this can emit.
//
// Each table holds exactly what its tier can return, which is NOT 2..max: at the
// 4-card tier, stepping to 2 would need a count that is both odd (4 rejected it,
// so count % 4 === 1) and even (2 accepted it, so count % 2 !== 1), so only 3 and
// 4 are reachable there. The class sweep in the tests pins that, and would catch
// an `undefined` slipping into the class string if the rule above ever changed.
const MD_COLS: Record<number, string> = {
  2: '@min-[720px]:grid-cols-2',
  3: '@min-[720px]:grid-cols-3',
};
const LG_COLS: Record<number, string> = {
  3: '@min-[960px]:grid-cols-3',
  4: '@min-[960px]:grid-cols-4',
};

/**
 * Responsive column classes for `count` highlight cards.
 *
 * The tiers hold 1 / 2 / 3 / 4 cards. Their thresholds are the width those cards
 * need at a ~230px minimum, gaps included: (480-16)/2 = 232, (720-32)/3 = 229,
 * (960-48)/4 = 228. Inside the 3-card and 4-card tiers `balancedColumns` then
 * removes an orphan last row. With the usual 4 cards that gives a row of 4 when it
 * fits, a 2 x 2 block as soon as it does not (instead of 3 + 1), and one card per
 * row on mobile.
 *
 * The 2-card tier is not balanced: the only step below it is a single column,
 * which would waste half the row to save one orphan, so 3 cards there stay 2 + 1.
 */
export function highlightGridColumns(count: number): string {
  return [
    'grid-cols-1',
    '@min-[480px]:grid-cols-2',
    MD_COLS[balancedColumns(count, 3)],
    LG_COLS[balancedColumns(count, 4)],
  ].join(' ');
}
