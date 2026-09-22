/**
 * @vitest-environment jsdom
 *
 * That a narrow column shows less, rather than showing it broken.
 *
 * Seven days in a phone's 390px is a 40px column, and everything in it used to be
 * `shrink-0`: the chip clipped its own time mid-digit ("08:1") and showed no name at all,
 * and the day header wrapped "Thu, Sep 3" onto three lines, pushing the grid two rows down
 * the screen. Neither of those is dense, they are broken.
 *
 * <p>The rules are CONTAINER queries, not viewport ones, and that is the point: a month cell
 * is ~42px wide on a phone whatever the viewport says, while the same phone in day view
 * gives a column ten times that. Only the cell knows. jsdom does not evaluate container
 * queries, so these assert the declarations - which is where the fix lives - and the real
 * widths were checked in a browser at 390, 768, 1024 and 1500.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { DndContext } from '@dnd-kit/core';
import { OccurrenceChip } from '../OccurrenceChip';
import { TimeGridView } from '../TimeGridView';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

const THURSDAY = new Date('2026-09-03T00:00:00Z');

function occurrence(): AgendaOccurrence {
  return {
    id: 'occ-1', kind: 'PLANNED', startAt: '2026-09-03T09:15:00Z',
    resourceType: 'WORKFLOW', resourceId: 'wf-1', name: 'Weekly digest',
    scheduleId: 'sched-1', cronExpression: '0 9 * * *', timezone: 'UTC',
    armed: true, isNextFire: false, overridden: false, moveAllSupported: true,
    status: 'PLANNED',
  } as AgendaOccurrence;
}

describe('a chip in a narrow cell', () => {
  function renderChip() {
    render(
      <DndContext>
        <OccurrenceChip
          occurrence={occurrence()}
          timezone="UTC"
          compact={false}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );
    return screen.getByRole('button');
  }

  it('keeps the time unconditionally, because it is the fact being scanned for', () => {
    // The time carries no container query at all: whatever the width, it is what survives.
    // (This one also passed before the change - it is a standing contract, not a regression
    // test, and it is here so a later tier does not quietly gate the last readable fact.)
    const chip = renderChip();
    const time = [...chip.querySelectorAll('span')].find((el) => el.textContent === '09:15');

    expect(time).toBeDefined();
    expect(time!.className).not.toContain('hidden');
  });

  it('width-gates everything that can push the time out of a narrow cell', () => {
    // The moved-off-schedule badge was left unguarded and `shrink-0`: 16px taken out of a
    // 39px month cell, which clipped the time mid-digit on precisely the chip whose time
    // matters most. Anything `shrink-0` in here has to be gated or the tiers are a
    // half-measure.
    render(
      <DndContext>
        <OccurrenceChip
          occurrence={{ ...occurrence(), overridden: true }}
          timezone="UTC"
          compact={false}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );
    const chip = screen.getByRole('button');
    const gated = [...chip.querySelectorAll('svg')].every((el) => {
      for (let node: Element | null = el; node && node !== chip; node = node.parentElement) {
        if ((node.getAttribute('class') ?? '').split(/\s+/).includes('hidden')) return true;
      }
      return false;
    });

    expect(chip.querySelectorAll('svg').length).toBeGreaterThan(0);
    expect(gated).toBe(true);
  });

  it('spends its width on the name before the kind icon', () => {
    // The icon repeats what the chip's accent colour already says; the name is the thing
    // the user came for, so it appears at a narrower width than the icon does.
    const chip = renderChip();
    const name = [...chip.querySelectorAll('span')].find((el) => el.textContent === 'Weekly digest')!;
    const iconCarrier = [...chip.querySelectorAll('span')].find((el) =>
      el.className.includes('@[8rem]:block'),
    )!;

    expect(name.className).toContain('@[6.5rem]:block');
    expect(iconCarrier.getAttribute('class')).toContain('@[8rem]:block');
  });

  it('says the whole thing to a screen reader and a tooltip whatever it draws', () => {
    // Everything hidden by width is hidden VISUALLY. Nothing is lost: this is the contract
    // that makes dropping the name at 40px acceptable rather than destructive. (Also true
    // before the change - kept because the tiers are only defensible while it holds.)
    const chip = renderChip();

    expect(chip.getAttribute('aria-label')).toBe('09:15 Weekly digest');
    expect(chip.getAttribute('title')).toBe('09:15 Weekly digest');
  });
});

describe('the day headers', () => {
  it('offer three widths of the same two facts', () => {
    // Which weekday, which date - as "T 3", "3 Thu" or "Thu, Sep 3" depending on the room
    // the column actually has.
    const { container } = render(
      <DndContext>
        <TimeGridView
          days={[THURSDAY]}
          timezone="UTC"
          startHour={9}
          endHour={10}
          compact={false}
          occurrencesByDay={new Map()}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );
    const header = [...container.querySelectorAll('div')].find((el) =>
      el.className.includes('@container') && el.textContent?.includes('Thu'),
    )!;
    const tiers = [...header.querySelectorAll('span')].map((el) => el.className);

    expect(tiers).toHaveLength(3);
    expect(tiers[0]).toContain('@[4.5rem]:hidden');
    expect(tiers[1]).toContain('@[4.5rem]:block');
    expect(tiers[2]).toContain('@[6.5rem]:block');
  });

  it('gives EVERY chip a container, the out-of-range strip included', () => {
    // The one this test file exists for. A container query with no container evaluates
    // false, so a chip rendered outside an `@container` is pinned to its narrowest tier -
    // time only, no name - at 390px and at 2560px alike. That is what happened to the
    // strip above the grid, which is a different column from the hour cells and had to be
    // told separately: hours cropped to 09:00-18:00 and a 07:00 run, and the strip lost the
    // name it had always shown. jsdom cannot evaluate the queries, but it can walk the
    // ancestors, which is exactly where this broke.
    const outOfRange = { ...occurrence(), id: 'occ-early', startAt: '2026-09-03T07:00:00Z' };
    const { container } = render(
      <DndContext>
        <TimeGridView
          days={[THURSDAY]}
          timezone="UTC"
          startHour={9}
          endHour={18}
          compact={false}
          occurrencesByDay={new Map([['2026-09-03', [outOfRange]]])}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );

    const chips = [...container.querySelectorAll('button')].filter((el) =>
      el.getAttribute('aria-label')?.includes('Weekly digest'),
    );
    expect(chips).toHaveLength(1);

    const hasContainerAncestor = (el: HTMLElement): boolean => {
      for (let node: HTMLElement | null = el; node; node = node.parentElement) {
        if (node.className && node.className.split(/\s+/).includes('@container')) return true;
      }
      return false;
    };
    expect(hasContainerAncestor(chips[0] as HTMLElement)).toBe(true);
  });

  it('makes every cell a container, or the queries inside answer about the wrong box', () => {
    render(
      <DndContext>
        <TimeGridView
          days={[THURSDAY]}
          timezone="UTC"
          startHour={9}
          endHour={10}
          compact={false}
          occurrencesByDay={new Map([['2026-09-03', [occurrence()]]])}
          canMutate
          onSelect={() => {}}
        />
      </DndContext>,
    );
    const chip = screen.getByRole('button', { name: /Weekly digest/ });

    // `@container` is not a valid CSS selector token to query with, so the class list is
    // read directly - the assertion is that the CELL declares itself the container.
    const cell = chip.parentElement!;
    expect(cell.className.split(/\s+/)).toContain('@container');
  });
});
