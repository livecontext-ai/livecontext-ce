/**
 * The three chart axes that plot DAY buckets, and must not translate them into a zone.
 *
 * <p>A daily bucket from an analytics query is a day, not a moment: the backend groups rows by
 * calendar date and hands back `2026-01-15`, which becomes UTC midnight only because a `Date` has
 * nowhere else to put it. Rendered in the reader's zone, every bucket west of Greenwich slides to
 * the previous day, so a chart of "yesterday's usage" is silently off by one for everyone in the
 * Americas, with a plausible-looking curve and nothing on screen to suggest it.
 *
 * <p>Asserted at the SOURCE. Rendering three recharts dashboards would need their data, their
 * container measurements and their tooltips, and would still prove only that the label matched on
 * the one machine zone the runner happens to have. What has to hold is WHICH helper each axis
 * calls, which is a fact about the files - and which no other test in the change can see: switch
 * any of these back to `formatUtcDate` and every suite stays green while the charts move a day.
 *
 * <p>Each formatter is named INDIVIDUALLY below rather than found by a pattern. A regex over
 * `tickFormatter|labelFormatter` lines looked thorough and inspected two of the six real sites:
 * two of them pass a named helper (`tickFormatter={formatDate}`), so the interesting code is
 * elsewhere in the file, and two open a multi-line arrow whose body is on the following lines. A
 * scan that cannot see the call it is judging is worse than no scan.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (...segments: string[]) => readFileSync(join(process.cwd(), ...segments), 'utf8');

const QUOTA = ['app', '[locale]', 'app', 'settings', 'quota', 'components', 'UsageAnalyticsPanel.tsx'];
const STORAGE = ['app', '[locale]', 'app', 'settings', 'storage', 'components', 'StorageBreakdownChart.tsx'];
const FLEET = ['components', 'agent-fleet', 'AgentMetricsDashboard.tsx'];

/**
 * Every place a day bucket becomes a label, by the FUNCTION that produces it.
 *
 * <p>`body` is the declaration or the inline arrow, taken as a window of lines from its opening, so
 * a multi-line formatter is judged on what it actually calls.
 */
const AXIS_FORMATTERS: ReadonlyArray<{ what: string; path: string[]; opensWith: string; lines: number }> = [
  // Both the tick and the tooltip label of the quota chart go through this one helper.
  { what: 'quota chart: the shared formatDate helper', path: QUOTA, opensWith: 'const formatDate = (dateStr: string) => {', lines: 6 },
  { what: 'storage chart: the x-axis tick', path: STORAGE, opensWith: 'tickFormatter={(v: string) => {', lines: 8 },
  { what: 'storage chart: the tooltip label', path: STORAGE, opensWith: 'labelFormatter={(label: string) =>', lines: 2 },
  { what: 'fleet dashboard: the x-axis tick', path: FLEET, opensWith: 'tickFormatter={(v: string) => {', lines: 8 },
  { what: 'fleet dashboard: the tooltip label', path: FLEET, opensWith: 'labelFormatter={(v: string) =>', lines: 2 },
];

/** The `lines` lines starting at the one that opens `opensWith`. */
function windowAfter(source: string, opensWith: string, lines: number): string {
  const all = source.split('\n');
  const at = all.findIndex((line) => line.includes(opensWith));
  if (at < 0) throw new Error(`could not find a formatter opening with: ${opensWith}`);
  return all.slice(at, at + lines).join('\n');
}

describe('day buckets are labelled as days, not as instants', () => {
  for (const axis of AXIS_FORMATTERS) {
    it(`${axis.what} keeps a day a day`, () => {
      const body = windowAfter(read(...axis.path), axis.opensWith, axis.lines);

      // The PROPERTY, not one spelling of it. Two of these axes call `formatCalendarDate`; the other
      // two build "D/M" from UTC getters on a UTC-parsed bucket, which keeps the day just as well.
      // Asserting the helper by name failed those two while nothing was wrong with them, which is
      // how a guard trains people to loosen it.
      expect(body, `${axis.what}: must label a day without translating it`)
        .toMatch(/formatCalendarDate\(|getUTC/);

      // What must never happen: a zone-aware formatter on a value that names a day. Those render in
      // the reader's zone, and a day rendered in a zone west of Greenwich is the day before.
      expect(body, `${axis.what}: must not translate a day into a zone`)
        .not.toMatch(/formatUtcDate\(|formatUtcDateTime\(|formatUtcTime\(/);
    });
  }

  it('and the two indirect axes really do route through that helper', () => {
    // `tickFormatter={formatDate}` proves nothing on its own: the helper it names has to be the one
    // asserted above, and it has to still be wired to both the tick and the tooltip.
    const quota = read(...QUOTA);

    expect(quota).toContain('tickFormatter={formatDate}');
    expect(quota).toContain('labelFormatter={formatDate}');
  });
});
