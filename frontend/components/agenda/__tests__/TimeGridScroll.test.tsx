/**
 * @vitest-environment jsdom
 *
 * That the hour grid still scrolls.
 *
 * A cell holding 84 fires needs a cap and a scrollbar, and the way that shipped made every
 * one of the 168 cells an `overflow-y-auto` container with `overscroll-contain`. Both
 * halves of that are wrong for the same reason: the wheel belongs to whatever scroll
 * container the pointer is over, nearly the whole surface of the week is cells, and
 * `overscroll-contain` is precisely the instruction "do not pass this gesture on". The
 * grid behind them stopped moving, and nothing looked broken - the calendar simply did not
 * respond to the wheel.
 *
 * jsdom lays nothing out, so these assertions are about which cells are declared scroll
 * containers and whether they chain, not about pixels. That is where the defect lived: in
 * a class applied unconditionally.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { TimeGridView } from '../TimeGridView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

const DAY = new Date('2026-09-03T00:00:00Z');

function occurrence(index: number): AgendaOccurrence {
  return {
    id: `wf-1:sched-1@${index}`,
    kind: 'PLANNED',
    // All in the 09:00 hour, so they land in one cell.
    startAt: `2026-09-03T09:${String(index % 60).padStart(2, '0')}:00Z`,
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: `Run ${index}`,
    scheduleId: 'sched-1',
    cronExpression: '* * * * *',
    timezone: 'UTC',
    armed: true,
    isNextFire: index === 0,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
  };
}

function renderGrid(count: number) {
  const occurrences = Array.from({ length: count }, (_, i) => occurrence(i));
  return render(
    <DndContext>
      <TimeGridView
        days={[DAY]}
        timezone="UTC"
        startHour={9}
        endHour={11}
        compact={false}
        occurrencesByDay={new Map([['2026-09-03', occurrences]])}
        canMutate
        onSelect={() => {}}
      />
    </DndContext>,
  );
}

const cells = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('div')).filter((el) =>
    el.className.includes('min-h-[2.25rem]'),
  );

describe('TimeGridView scrolling', () => {
  it('sizes its card to the hours it draws, leaving no blank space under the last row', () => {
    // Regression: the card was `flex-1`, so a short hour range (08:00-18:00) left an empty
    // band inside the bordered card, below the last row. It now takes its content height
    // and only shrinks (then scrolls) when the page is shorter than the grid.
    const { container } = renderGrid(1);

    const card = container.querySelector('.rounded-xl.border') as HTMLElement;
    expect(card).not.toBeNull();
    expect(card.classList.contains('flex-1')).toBe(false);
    expect(card.classList.contains('flex-initial')).toBe(true);
    expect(card.classList.contains('min-h-0')).toBe(true);
  });

  it('never tells a cell to keep a scroll to itself', () => {
    // The regression in one line. `overscroll-contain` anywhere in this grid means the
    // hours behind it cannot be reached with the wheel.
    const { container } = renderGrid(40);

    for (const cell of cells(container)) {
      expect(cell.className).not.toContain('overscroll-contain');
    }
  });

  it('leaves an ordinary cell out of the scroll chain entirely', () => {
    // Two chips fit with room to spare, so the cell has no reason to be a scroll
    // container - and a container is exactly what would capture the wheel over it.
    const { container } = renderGrid(2);

    for (const cell of cells(container)) {
      expect(cell.className).not.toContain('overflow-y-auto');
      expect(cell.className).not.toContain('max-h-40');
    }
  });

  it('caps and scrolls the cell that would otherwise stretch the row', () => {
    // 84 fires in one hour is the case this cap exists for: without it the row grows to
    // thousands of pixels and every other day of the week shares that height.
    const { container } = renderGrid(84);
    const crowded = cells(container).filter((c) => c.className.includes('overflow-y-auto'));

    expect(crowded).toHaveLength(1);
    expect(crowded[0].className).toContain('max-h-40');
    // Chains on purpose: scroll it to the end and the rest of the gesture moves the hours.
    expect(crowded[0].className).toContain('overscroll-y-auto');
  });

  it('keeps the hour grid itself scrollable, which is what the wheel should reach', () => {
    const { container } = renderGrid(2);
    const scroller = container.querySelector('.min-h-0.flex-1.overflow-y-auto');

    expect(scroller).toBeTruthy();
  });
});
