// @vitest-environment jsdom

// The replica reads the LOCAL calendar (which day is today, which fires already
// happened), so a suite that did not pin the zone would assert one thing on a
// UTC runner and another on a machine in Los Angeles. ESM hoists the imports
// above this line, so it is not "before the component is imported"; it is before
// any test RUNS, which is what matters, because the component builds no Date at
// import time. Restored in afterAll: the worker process is shared with other
// files, and a leaked TZ is an order-dependent flake for anything that formats a
// date.
const ORIGINAL_TZ = process.env.TZ;
process.env.TZ = 'UTC';

import '@testing-library/jest-dom/vitest';
import React from 'react';
import { renderToString } from 'react-dom/server';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RESOURCE_ACCENT, STATUS_ACCENT } from '@/components/agenda/agendaVisuals';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import { PERSONA_KEYS, type PersonaKey } from '@/components/landing/personas/personas';

import AgendaShowcase from '../AgendaShowcase';

/** Tuesday 15 September 2026, midday: a weekday, mid-month, after the morning
 *  fires (06:45, 08:00) and before the evening one (18:30). */
const NOW = '2026-09-15T12:00:00.000Z';

/** jsdom has no DataTransfer, and dragStart/drop read one off the event. */
function transfer() {
  return { setData: vi.fn(), getData: vi.fn(), effectAllowed: '', dropEffect: '' };
}

function cell(iso: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(`[data-day="${iso}"]`);
  if (!found) throw new Error(`no day cell for ${iso}`);
  return found;
}

function chipsTitled(pattern: RegExp): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('[title]')).filter((element) =>
    pattern.test(element.getAttribute('title') ?? ''),
  );
}

function showList() {
  fireEvent.click(screen.getByRole('button', { name: 'List' }));
}

function renderPersona(persona: PersonaKey, locale: 'en' | 'fr' = 'en') {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === 'fr' ? fr : en} onError={(error) => { throw error; }}>
      <AgendaShowcase nowIso={NOW} persona={persona} locale={locale} />
    </NextIntlClientProvider>,
  );
}


describe('AgendaShowcase', () => {
  beforeEach(() => {
    // Only Date: faking timers wholesale would take React's scheduler with it.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOW));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });
  afterAll(() => {
    if (ORIGINAL_TZ === undefined) delete process.env.TZ;
    else process.env.TZ = ORIGINAL_TZ;
  });

  /**
   * The calendar is CROPPED, not scrollable: a landing that carries a second scroll surface
   * traps the thumb that lands in it on a phone, and the list view is thousands of pixels
   * tall, so its height cannot be allowed to set the section's.
   */
  it('cuts the calendar off instead of scrolling it', () => {
    const { container } = renderPersona('support');
    const box = Array.from(container.querySelectorAll('div')).find((node) => node.className.includes('h-[35rem]'));
    expect(box, 'the fixed-height calendar box').toBeDefined();
    expect(box!.className).toContain('overflow-hidden');
    expect(box!.className).not.toContain('overflow-y-auto');
  });

  it('opens on the month the visitor is in, with today marked', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    expect(screen.getByText('September 2026')).toBeInTheDocument();
    // The date pill the app draws on today: accent ground, accent foreground.
    expect(screen.getByText('15').getAttribute('style')).toContain('var(--accent-primary)');
    // Six weeks, always, so paging months never makes the page jump. September
    // 2026 starts on a Tuesday, so the grid opens on Monday 31 August.
    expect(document.querySelectorAll('[data-day]')).toHaveLength(42);
    expect(cell('2026-08-31')).toBeInTheDocument();
    expect(cell('2026-10-11')).toBeInTheDocument();
  });

  it('projects each schedule from its recurrence rule, not from pinned dates', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    showList();

    // "Every Monday at 09:15" reaches every Monday still to come this month
    // (the 21st and the 28th); the ones already past show their outcome instead.
    expect(screen.getAllByText('Every Monday at 09:15')).toHaveLength(2);
    expect(screen.getAllByText('On the 1st and 15th at 18:30').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Completed').length).toBeGreaterThan(0);
  });

  it('paging months keeps drawing a full calendar, and Today comes back', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    fireEvent.click(screen.getByRole('button', { name: 'Next period' }));
    expect(screen.getByText('October 2026')).toBeInTheDocument();
    // A projection, not a fixed set of chips: the next month is populated too.
    expect(chipsTitled(/Inbox triage/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Previous period' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous period' }));
    expect(screen.getByText('August 2026')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Today' }));
    expect(screen.getByText('September 2026')).toBeInTheDocument();
  });

  it('marks today’s most recent fire as still running', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    showList();

    // 06:45 and 08:00 have fired by midday, 18:30 has not, so the 08:00 one is
    // the one still going. Counting Running rows would not see that: reverse the
    // search and the 06:45 fire is marked instead, still exactly one.
    const running = screen.getAllByText('Running');
    expect(running).toHaveLength(1);
    const row = running[0].closest('li')!;
    expect(within(row).getByText('Standup')).toBeInTheDocument();
    expect(within(row).getByText('08:00')).toBeInTheDocument();
  });

  it('draws a fire the spending cap will refuse, faded, instead of hiding it', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    const blocked = chipsTitled(/Market scan - Will not run/);
    expect(blocked.length).toBeGreaterThan(0);
    for (const chip of blocked) expect(chip.className).toContain('opacity-50');

    // The two Fridays still to come. A calendar that dropped them would read as
    // "the automation is gone", which is not what a reached cap means.
    showList();
    expect(screen.getAllByText('Will not run: spending cap reached')).toHaveLength(2);
  });

  it('filters by resource kind, and says so when every kind is off', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    expect(chipsTitled(/Client app/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Applications' }));
    expect(chipsTitled(/Client app/)).toHaveLength(0);
    expect(chipsTitled(/Inbox triage/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Workflows' }));
    fireEvent.click(screen.getByRole('button', { name: 'Agents' }));
    // The app's own words, so the calendar explains itself instead of looking
    // broken.
    expect(screen.getByText('No resource kind selected')).toBeInTheDocument();
    expect(document.querySelectorAll('[data-day]')).toHaveLength(0);
  });

  it('never offers the drag on the past', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    const past = chipsTitled(/- (Completed|Failed|Running)$/);
    expect(past.length).toBeGreaterThan(0);
    for (const chip of past) expect(chip).toHaveAttribute('draggable', 'false');
  });

  it('offers the drag the way the app does: any fire of a weekly schedule, the next one only of an interval schedule', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    // A weekly cron can be rewritten wholesale, so every planned fire moves.
    const weekly = chipsTitled(/Team digest - Every Monday/);
    expect(weekly.length).toBeGreaterThan(0);
    for (const chip of weekly) expect(chip).toHaveAttribute('draggable', 'true');

    // "Every 2 days" cannot: the app offers the gesture on the NEXT fire and
    // nowhere else, and leaving every chip draggable is a bug it already fixed.
    // From Tuesday the 15th at midday, that next fire is Wednesday the 16th.
    expect(within(cell('2026-09-16')).getByTitle(/New leads/)).toHaveAttribute('draggable', 'true');
    for (const iso of ['2026-09-18', '2026-09-20', '2026-09-22']) {
      expect(within(cell(iso)).getByTitle(/New leads/)).toHaveAttribute('draggable', 'false');
    }

    // A fire the cap will refuse is still a planned fire of a weekly schedule.
    const blocked = chipsTitled(/Market scan - Will not run/);
    expect(blocked.length).toBeGreaterThan(0);
    for (const chip of blocked) expect(chip).toHaveAttribute('draggable', 'true');
  });

  it('moves a planned run onto a later day and marks it moved', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    const chip = within(cell('2026-09-21')).getByTitle('09:15 Team digest - Every Monday at 09:15');

    const dataTransfer = transfer();
    fireEvent.dragStart(chip, { dataTransfer });
    fireEvent.dragOver(cell('2026-09-23'), { dataTransfer });
    fireEvent.drop(cell('2026-09-23'), { dataTransfer });

    // It left Monday, landed on Wednesday, and wears the badge the app puts on a
    // run that no longer sits where its schedule says.
    expect(within(cell('2026-09-21')).queryByTitle(/Team digest/)).not.toBeInTheDocument();
    expect(
      within(cell('2026-09-23')).getByTitle('09:15 Team digest - Moved off its schedule'),
    ).toBeInTheDocument();
  });

  it('refuses a drop on a day that has already happened', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    const chip = within(cell('2026-09-21')).getByTitle(/Team digest/);
    const dataTransfer = transfer();
    fireEvent.dragStart(chip, { dataTransfer });
    fireEvent.dragOver(cell('2026-09-07'), { dataTransfer });
    fireEvent.drop(cell('2026-09-07'), { dataTransfer });

    // Still where it was: the past is history, and the platform could not honour
    // the move anyway.
    expect(within(cell('2026-09-21')).getByTitle(/Team digest/)).toBeInTheDocument();
  });

  it('renders the server clock on the server, so hydration has nothing to fix up', () => {
    // The whole point of the useSyncExternalStore server snapshot: a client
    // render reads the visitor's clock, but the SERVER render must reproduce the
    // prop exactly, or the landing hydrates with a mismatch. A client-only test
    // cannot see this, because it never takes the server snapshot.
    const html = renderToString(<AgendaShowcase nowIso="2026-03-15T12:00:00.000Z" />);

    expect(html).toContain('March 2026');
    expect(html).not.toContain('September 2026');
  });

  it('opens a full day it is dropped on, so the moved run is not filed behind "+N more"', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    // Wednesday the 16th already holds four fires and shows "+1 more": a drop
    // there used to sort the moved run out of the visible three, which reads as
    // "the drag deleted it".
    expect(within(cell('2026-09-16')).getByText('+1 more')).toBeInTheDocument();

    const chip = within(cell('2026-09-21')).getByTitle(/Team digest/);
    const dataTransfer = transfer();
    fireEvent.dragStart(chip, { dataTransfer });
    fireEvent.dragOver(cell('2026-09-16'), { dataTransfer });
    fireEvent.drop(cell('2026-09-16'), { dataTransfer });

    expect(
      within(cell('2026-09-16')).getByTitle('09:15 Team digest - Moved off its schedule'),
    ).toBeInTheDocument();
  });

  it('lets a crowded day be opened and closed again', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    const crowded = cell('2026-09-16');
    expect(within(crowded).queryByTitle(/Client app/)).not.toBeInTheDocument();

    fireEvent.click(within(crowded).getByText('+1 more'));
    expect(within(crowded).getByTitle(/Client app/)).toBeInTheDocument();

    // The way back: without it the cell stays open for the rest of the visit.
    fireEvent.click(within(crowded).getByText('Show less'));
    expect(within(crowded).queryByTitle(/Client app/)).not.toBeInTheDocument();
  });

  it('colours a past fire by its outcome and a planned one by its resource', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    // On a past day the question is no longer "what kind of thing is this" but
    // "did it work", so the outcome takes the chip over. Both class strings come
    // from the app's own agendaVisuals, so this breaks if the replica stops
    // reading them.
    const past = within(cell('2026-09-07')).getByTitle(/Team digest/);
    expect(past.className).toContain(STATUS_ACCENT.COMPLETED!.chip);
    expect(past.className).not.toContain(RESOURCE_ACCENT.WORKFLOW.chip);

    const planned = within(cell('2026-09-21')).getByTitle(/Team digest/);
    expect(planned.className).toContain(RESOURCE_ACCENT.WORKFLOW.chip);

    const app = within(cell('2026-09-23')).getByTitle(/Client app/);
    expect(app.className).toContain(RESOURCE_ACCENT.APPLICATION.chip);
  });

  it('shows failed runs among the past ones', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    // The outcome is a stable hash of (schedule, day), so this is not luck: a
    // month of automation that never failed would be the unrealistic one.
    const failed = chipsTitled(/- Failed$/);
    expect(failed.length).toBeGreaterThan(0);
    for (const chip of failed) expect(chip.className).toContain(STATUS_ACCENT.FAILED!.chip);
  });

  it('goes back to the month grid from the list', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    showList();
    expect(document.querySelectorAll('[data-day]')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Month' }));
    expect(document.querySelectorAll('[data-day]')).toHaveLength(42);
  });

  it('names every chip for a screen reader, at every width', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    // The chip's name span is display:none below 6.5rem, so the label is the
    // only thing a reader on a phone gets. The app carries it on the element for
    // the same reason.
    const chip = within(cell('2026-09-21')).getByTitle(/Team digest/);
    expect(chip).toHaveAttribute('aria-label', '09:15 Team digest - Every Monday at 09:15');
  });

  it('a run moved out of the month on screen is drawn in the month it moved to, and nowhere else', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    fireEvent.click(screen.getByRole('button', { name: 'Next period' }));

    // October, Thursday the 1st: move Social posts to Tuesday the 27th.
    const chip = within(cell('2026-10-01')).getByTitle(/Social posts/);
    const dataTransfer = transfer();
    fireEvent.dragStart(chip, { dataTransfer });
    fireEvent.dragOver(cell('2026-10-27'), { dataTransfer });
    fireEvent.drop(cell('2026-10-27'), { dataTransfer });
    expect(within(cell('2026-10-27')).getByTitle(/Social posts/)).toBeInTheDocument();

    // September's six weeks run to 11 October, so they still carry the day it
    // LEFT but not the day it went to. It is deliberately drawn on neither:
    // putting it back on the 1st would say the run is on the 1st, which is the
    // one thing that is no longer true. Paging forward finds it.
    fireEvent.click(screen.getByRole('button', { name: 'Previous period' }));
    expect(screen.getByText('September 2026')).toBeInTheDocument();
    expect(cell('2026-10-01')).toBeInTheDocument();
    expect(within(cell('2026-10-01')).queryByTitle(/Social posts/)).not.toBeInTheDocument();
    expect(chipsTitled(/Social posts - Moved/)).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Next period' }));
    expect(
      within(cell('2026-10-27')).getByTitle('16:00 Social posts - Moved off its schedule'),
    ).toBeInTheDocument();
  });

  it('asks "next fire" of the schedule, not of the month on screen', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    // From today, the next "every 2 days" fire is Wednesday the 16th.
    expect(within(cell('2026-09-16')).getByTitle(/New leads/)).toHaveAttribute('draggable', 'true');

    fireEvent.click(screen.getByRole('button', { name: 'Next period' }));
    // October's first one is NOT a next fire just because it opens the grid:
    // the schedule's real next fire is still back in September, which is where
    // the app would offer the gesture.
    for (const chip of chipsTitled(/New leads/)) {
      expect(chip).toHaveAttribute('draggable', 'false');
    }
  });

  it('renders the shared app rail with Agenda active', () => {
    render(<AgendaShowcase nowIso={NOW} />);

    expect(screen.getByTitle('Agenda')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTitle('Marketplace')).not.toHaveAttribute('data-active');
    expect(screen.getByTitle('Account')).toBeInTheDocument();
  });

  it.each(PERSONA_KEYS)('projects all eight %s schedules instead of the homepage demo tasks', (persona) => {
    renderPersona(persona);
    showList();
    const names = en.PersonaLanding.personas[persona].agendaSchedules;
    for (const name of Object.values(names)) expect(screen.getAllByText(name).length).toBeGreaterThan(0);
    expect(screen.queryByText('Backups')).not.toBeInTheDocument();
    expect(screen.queryByText('Invoices')).not.toBeInTheDocument();
  });

  it.each(PERSONA_KEYS)('keeps Saturday and Sunday work in the %s calendar across months', (persona) => {
    renderPersona(persona);
    const names = en.PersonaLanding.personas[persona].agendaSchedules;
    expect(within(cell('2026-09-19')).getByTitle(new RegExp(names.seventh))).toBeInTheDocument();
    expect(within(cell('2026-09-20')).getByTitle(new RegExp(names.eighth))).toBeInTheDocument();
    expect(within(cell('2026-09-18')).queryByTitle(new RegExp(names.seventh))).not.toBeInTheDocument();
    expect(within(cell('2026-09-21')).queryByTitle(new RegExp(names.eighth))).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: en.agenda.nav.next }));
    expect(within(cell('2026-10-03')).getByTitle(new RegExp(names.seventh))).toBeInTheDocument();
    expect(within(cell('2026-10-04')).getByTitle(new RegExp(names.eighth))).toBeInTheDocument();
    showList();
    for (const row of screen.getAllByText(names.seventh)) expect(within(row.closest('li')!).getByText('09:00')).toBeInTheDocument();
    for (const row of screen.getAllByText(names.eighth)) expect(within(row.closest('li')!).getByText('17:00')).toBeInTheDocument();
  });

  it('localizes the creator agenda with the app locale and keeps the resource filters interactive', () => {
    renderPersona('creator', 'fr');
    const names = fr.PersonaLanding.personas.creator.agendaSchedules;
    expect(screen.getByText('septembre 2026')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: fr.agenda.view.list }));
    expect(screen.getAllByText(names.fifth).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: fr.agenda.resource.workflow }));
    expect(screen.queryByText(names.fifth)).not.toBeInTheDocument();
    expect(screen.getAllByText(names.first).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: fr.agenda.resource.agent }));
    fireEvent.click(screen.getByRole('button', { name: fr.agenda.resource.application }));
    expect(screen.getByText(fr.agenda.empty.allFilteredTitle)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: fr.agenda.resource.workflow }));
    expect(screen.getAllByText(names.fifth).length).toBeGreaterThan(0);
  });

  it('projects creator publishing into later months while preserving the selected recurrence', () => {
    renderPersona('creator');
    fireEvent.click(screen.getByRole('button', { name: en.agenda.nav.next }));
    showList();
    const rows = screen.getAllByText(en.PersonaLanding.personas.creator.agendaSchedules.fifth);
    // October 2026 has 22 weekdays. All planned posts retain their 18:00 slot.
    expect(rows).toHaveLength(22);
    for (const row of rows) expect(within(row.closest('li')!).getByText('18:00')).toBeInTheDocument();
    expect(screen.getByText('October 2026')).toBeInTheDocument();
  });

  it('moves a translated creator publication to a later day using the existing drag interaction', () => {
    renderPersona('creator', 'fr');
    const name = fr.PersonaLanding.personas.creator.agendaSchedules.fifth;
    const source = cell('2026-09-16');
    fireEvent.click(within(source).getByRole('button'));
    const chip = within(source).getByTitle(new RegExp(name));
    expect(chip).toHaveAttribute('draggable', 'true');
    const dataTransfer = transfer();
    fireEvent.dragStart(chip, { dataTransfer });
    fireEvent.dragOver(cell('2026-09-19'), { dataTransfer });
    fireEvent.drop(cell('2026-09-19'), { dataTransfer });
    expect(within(source).queryByTitle(new RegExp(name))).not.toBeInTheDocument();
    expect(within(cell('2026-09-19')).getByTitle(`18:00 ${name} - ${fr.agenda.movedBadge}`)).toBeInTheDocument();
  });

  it('preserves the homepage toolbar button classes on persona calendars', () => {
    render(<AgendaShowcase nowIso={NOW} />);
    const homepagePrevious = screen.getByRole('button', { name: 'Previous period' }).className;
    const homepageList = screen.getByRole('button', { name: 'List' }).className;
    cleanup();
    renderPersona('creator', 'fr');
    expect(screen.getByRole('button', { name: fr.agenda.nav.previous }).className).toBe(homepagePrevious);
    expect(screen.getByRole('button', { name: fr.agenda.view.list }).className).toBe(homepageList);
  });
  /**
   * What a narrow window gets.
   *
   * Seven columns inside this frame are 31px on a phone and 72px on a tablet, and a chip
   * needs 4.5rem before its dot appears and 6.5rem before its name does. Measured on
   * livecontext.ai at both widths: 0 of 107 names and 0 of 107 dots were drawn, so the
   * section was 42 boxes of clipped times. The replica now does what a phone calendar does,
   * and these pin the two halves of that: which view opens, and what the grid draws when
   * somebody asks for it anyway.
   *
   * jsdom has no matchMedia at all, which is why the component guards for it and why every
   * other test in this file still sees the desktop month grid.
   */
  describe('on a window too narrow for a month grid', () => {
    function pretendWidth(narrow: boolean) {
      vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
        matches: narrow && query.includes('900px'),
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })));
    }

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it('opens on the list, where a name and a time still fit', () => {
      pretendWidth(true);
      render(<AgendaShowcase nowIso={NOW} />);

      expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-pressed', 'false');
      // The point of the switch: the reader can actually read a run.
      expect(screen.getAllByText('Inbox triage').length).toBeGreaterThan(0);
      expect(screen.getAllByText('06:45').length).toBeGreaterThan(0);
    });

    it('draws the month as dots when it is asked for anyway, and keeps every sentence', () => {
      pretendWidth(true);
      render(<AgendaShowcase nowIso={NOW} />);
      fireEvent.click(screen.getByRole('button', { name: 'Month' }));

      // Still a whole month, still six weeks, so paging cannot make the page jump.
      expect(document.querySelectorAll('[data-day]')).toHaveLength(42);
      // A 31px cell cannot hold a time, so it no longer pretends to: the runs are dots.
      expect(within(cell('2026-09-15')).queryByText('06:45')).not.toBeInTheDocument();
      const dots = within(cell('2026-09-15')).getAllByTitle(/Inbox triage/);
      expect(dots.length).toBeGreaterThan(0);
      // Nothing is LOST with the text: the full sentence stays on each dot, which is what a
      // screen reader and a hover both read.
      expect(dots[0].getAttribute('title')).toMatch(/06:45 Inbox triage/);
      // One letter per weekday: "Wed" clipped to "We" would be worse than "W".
      expect(screen.getAllByText('W').length).toBeGreaterThan(0);
      expect(screen.queryByText('Wed')).not.toBeInTheDocument();
    });

    it('lets the visitor overrule the width, in both directions', () => {
      pretendWidth(true);
      const narrow = render(<AgendaShowcase nowIso={NOW} />);
      fireEvent.click(screen.getByRole('button', { name: 'Month' }));
      expect(screen.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-pressed', 'true');
      narrow.unmount();

      pretendWidth(false);
      render(<AgendaShowcase nowIso={NOW} />);
      expect(screen.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-pressed', 'true');
      fireEvent.click(screen.getByRole('button', { name: 'List' }));
      expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('leaves a wide window exactly as it was: chips, times and drag', () => {
      pretendWidth(false);
      render(<AgendaShowcase nowIso={NOW} />);

      expect(screen.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-pressed', 'true');
      const chip = within(cell('2026-09-15')).getByTitle(/Inbox triage/);
      expect(within(chip).getByText('06:45')).toBeInTheDocument();
      expect(screen.getAllByText('Wed').length).toBeGreaterThan(0);
    });
  });
});
