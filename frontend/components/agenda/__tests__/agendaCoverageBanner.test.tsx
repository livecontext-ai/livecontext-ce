/**
 * @vitest-environment jsdom
 *
 * What the calendar SAYS when its history is incomplete.
 *
 * The flag alone was not enough: a capped scan keeps the newest rows, which points at the
 * days being read only while the window ends at "now". Paged back to a past month, the
 * kept rows are its END and the earlier weeks are drawn empty, so the banner has to name
 * the point coverage starts at. These tests exist because that sentence is the ONLY
 * consumer of `pastCoveredFrom`, and the first version of it re-created the bug it was
 * written to fix, one day narrower, by printing an instant as a bare date.
 *
 * <p>The sentence ON the real page is covered by `agendaAgentRunOnThePage`, which renders
 * `AgendaView` itself. What is left here is the LABEL contract that page calls into,
 * where the formatting decision actually lives.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { coverageBoundaryLabel } from '../agendaCoverage';

/**
 * The banner, built from the SAME helper the page calls.
 *
 * <p>An earlier version of this file re-implemented the page's JSX expression, so it
 * agreed with itself: the page could go back to printing a bare date and every assertion
 * here would still pass. `coverageBoundaryLabel` exists to be the one place that decides,
 * and mounting `AgendaView` for a sentence would drag in the auth provider, the org
 * store, dnd-kit and the API client, none of which decide anything about it.
 */
function Banner({ coveredFrom, timezone = 'UTC' }: { coveredFrom?: string; timezone?: string }) {
  const t = (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key;
  return (
    <p>
      {coveredFrom
        ? t('truncated.pastFrom', { date: coverageBoundaryLabel(coveredFrom, timezone) })
        : t('truncated.past')}
    </p>
  );
}

describe('the truncated-history banner', () => {
  const BOUNDARY = '2026-09-14T18:32:11Z';

  it('names the TIME of day, not just the date', () => {
    // The boundary is the exact instant of the oldest row a capped scan reached. Printed
    // as "Monday, 14 September 2026" it tells the user that day is covered while every
    // run before 18:32 on it is missing from the grid they are reading - the same false
    // reading the field was added to remove, moved from a month to a day.
    render(<Banner coveredFrom={BOUNDARY} />);

    const text = screen.getByText(/truncated\.pastFrom/).textContent ?? '';
    expect(text).toContain('18:32');
  });

  it('names the instant in the DISPLAY timezone, like every other time on the page', () => {
    render(<Banner coveredFrom={BOUNDARY} timezone="Europe/Paris" />);

    // 18:32 UTC is 20:32 in Paris. A banner in UTC beside chips in Paris would send the
    // user looking for a gap two hours from where it is.
    expect(screen.getByText(/truncated\.pastFrom/).textContent).toContain('20:32');
  });

  it('falls back to the plain sentence when the server did not send a boundary', () => {
    // An older backend, or a truncation whose oldest row could not be read: say less
    // rather than say a wrong date.
    render(<Banner />);

    expect(screen.getByText('truncated.past')).toBeTruthy();
    expect(screen.queryByText(/truncated\.pastFrom/)).toBeNull();
  });
});

describe('the sentence itself', () => {
  it('claims completeness FROM the boundary, and does not claim everything older is hidden', async () => {
    // The two history sources have separate caps, so the common case is ONE of them
    // truncating: the workflow chips older than the boundary are still on screen. A
    // sentence saying "older ones are not shown" is then contradicted by the grid
    // underneath it. What is always true is the direction the value actually carries -
    // history is COMPLETE from here, and SOME earlier runs are missing.
    const en = (await import('@/messages/en.json')).default as {
      agenda: { truncated: { pastFrom: string } };
    };

    expect(en.agenda.truncated.pastFrom).toContain('{date}');
    expect(en.agenda.truncated.pastFrom).toMatch(/complete from/i);
    expect(en.agenda.truncated.pastFrom).toMatch(/some earlier/i);
  });
});
