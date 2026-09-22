/**
 * @vitest-environment jsdom
 *
 * That the agenda's toolbar is drawn with the app's Button, and not with a private copy
 * of one.
 *
 * This bar shipped once with a hand-written `TOOLBAR_BUTTON` string that re-declared the
 * border, radius, text size, hover and pressed ground itself. Nothing was broken by it and
 * nothing failed - it simply looked like a different product's toolbar, 28px tall and
 * `rounded-md` next to the app's `rounded-xl` controls, with no focus ring. That is the
 * failure mode worth a test: a second button dialect does not throw, it just drifts, and
 * it drifts back the moment someone adds a control here in a hurry.
 *
 * So these assertions are about provenance, not pixels. They check that each control
 * carries the signature the `Button` component stamps on everything it renders, that the
 * bar agrees with itself on one height, and that the pressed states are the component's
 * own variants rather than a colour picked here.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';
import { AgendaHeader } from '../AgendaHeader';
import { AGENDA_KIND_ORDER } from '../agendaLaunchKinds';
import { ALL_RESOURCE_TYPES, type AgendaPreferences } from '@/hooks/useAgendaPreferences';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

// The bar's clock formats through the APP locale, which is resolved from the URL and the
// NEXT_LOCALE cookie - neither of which exists in jsdom. Pinning it to `en` keeps these
// assertions about the clock rather than about the runner's environment.
vi.mock('@/lib/utils/locale', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/utils/locale')>()),
  getClientLocale: () => 'en',
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

// 09:30 UTC, i.e. 11:30 in Paris: the two zones the clock assertions below compare.
const NOW = new Date('2026-09-03T09:30:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

function preferences(overrides: Partial<AgendaPreferences> = {}): AgendaPreferences {
  return {
    view: 'week',
    timezone: 'UTC',
    weekStartsOn: 1,
    showWeekends: true,
    resourceTypes: [...ALL_RESOURCE_TYPES],
    showPast: true,
    showPaused: true,
    density: 'comfortable',
    dayStartHour: 0,
    dayEndHour: 24,
    ...overrides,
  };
}

function renderHeader(overrides: Partial<AgendaPreferences> = {}, onPickDate: (d: Date) => void = () => {}) {
  return render(
    <AgendaHeader
      title="September 2026"
      anchor={NOW}
      onPickDate={onPickDate}
      preferences={preferences(overrides)}
      timezoneOptions={['UTC', 'Europe/Paris']}
      search=""
      onSearchChange={() => {}}
      onPrevious={() => {}}
      onNext={() => {}}
      onToday={() => {}}
      onUpdate={() => {}}
      onToggleResourceType={() => {}}
      onResetPreferences={() => {}}
    />,
  );
}

/**
 * The classes `Button` puts on every instance regardless of variant or size. The old
 * hand-written string had neither: it was `rounded-md` and carried no focus ring at all,
 * so a control that fails this assertion was written by hand.
 */
const BUTTON_SIGNATURE = ['rounded-xl', 'focus-visible:ring-[var(--accent-primary)]'];

function barControls(): HTMLElement[] {
  // Everything on the bar except the search field, which is an Input and keeps its own
  // element - the point of the height assertion below is that it agrees with these.
  return Array.from(document.querySelectorAll('header button')) as HTMLElement[];
}

describe('AgendaHeader', () => {
  it('draws every control with the app Button, not a hand-written one', () => {
    renderHeader();

    const controls = barControls();
    // Two arrows, Today, the title (which is the date picker's trigger), three resource
    // kinds, trigger filter, four views, settings.
    expect(controls).toHaveLength(13);
    for (const control of controls) {
      for (const signature of BUTTON_SIGNATURE) {
        expect(control.className).toContain(signature);
      }
    }
  });

  it('agrees with the search field on one height', () => {
    // `Button`'s own `sm` is h-9 and the search Input is h-8, so an un-overridden bar puts
    // every control a pixel proud of the field beside it. This is the assertion that fails
    // if someone drops a plain <Button size="sm"> onto the bar.
    renderHeader();

    const searchField = document.querySelector('header input');
    expect(searchField?.className).toContain('h-8');
    for (const control of barControls()) {
      expect(control.className).toContain('h-8');
    }
  });

  it('marks the selected view with the component\'s filled variant', () => {
    renderHeader({ view: 'day' });

    const day = screen.getByRole('button', { name: 'view.day' });
    const month = screen.getByRole('button', { name: 'view.month' });

    // data-variant is stamped by Button itself, so this pins the pressed state to a
    // variant of the shared component rather than to a colour chosen on this bar.
    expect(day.getAttribute('data-variant')).toBe('default');
    expect(day.getAttribute('aria-pressed')).toBe('true');
    expect(month.getAttribute('data-variant')).toBe('outline');
    expect(month.getAttribute('aria-pressed')).toBe('false');
  });

  it('gives the accent fill to Today and not to the arrows', () => {
    // Today jumps the calendar somewhere; the arrows only step the cursor. One command,
    // one filled button - a bar where all three shout has no emphasis left to spend.
    renderHeader();

    expect(screen.getByRole('button', { name: 'nav.today' }).getAttribute('data-variant'))
      .toBeNull(); // no explicit variant = Button's default, the accent fill
    expect(screen.getByRole('button', { name: 'nav.previous' }).getAttribute('data-variant'))
      .toBe('outline');
    expect(screen.getByRole('button', { name: 'nav.next' }).getAttribute('data-variant'))
      .toBe('outline');
  });

  it('keeps ticking, so it is not the time the page was opened', () => {
    // The whole reason the chip is not `new Date()` read once during render. On a page that
    // is left open - which is what an agenda is for - a clock that renders once is a clock
    // that lies, and nothing about it looks wrong.
    renderHeader({ timezone: 'UTC' });
    const clock = () => screen.getByLabelText('clock.tooltip:{"zone":"UTC"}').textContent;

    expect(clock()).toContain('09:30');

    // Advancing the fake timers moves the clock with them, so this is one real minute
    // passing on a page nobody has touched.
    act(() => { vi.advanceTimersByTime(60_000); });

    expect(clock()).toContain('09:31');
  });

  it('prints the current time in the zone the calendar is drawn in', () => {
    // Not the viewer's own time: the agenda deliberately lets a workspace be read in
    // another zone, and a clock showing the browser's would quietly contradict every
    // occurrence on the page. 09:30 UTC is 11:30 in Paris, and that is what the bar says.
    const { unmount } = renderHeader({ timezone: 'UTC' });
    expect(screen.getByLabelText('clock.tooltip:{"zone":"UTC"}').textContent).toContain('09:30');
    unmount();

    renderHeader({ timezone: 'Europe/Paris' });
    const paris = screen.getByLabelText('clock.tooltip:{"zone":"Europe/Paris"}');
    expect(paris.textContent).toContain('11:30');
  });

  it('says which offset it is showing, so the zone is never left to memory', () => {
    // The picker is behind a popover; without the offset on the bar, a user reading a
    // workspace in a zone that is not theirs has no on-screen answer to "in which zone?".
    renderHeader({ timezone: 'Europe/Paris' });

    expect(screen.getByLabelText('clock.tooltip:{"zone":"Europe/Paris"}').textContent)
      .toContain('GMT+2');
  });

  it('dims a resource kind that is switched OFF, and leaves the included ones plain', () => {
    // All three kinds start included, so highlighting them would light up most of the bar
    // to say "nothing is filtered". The exception is what gets marked.
    const { unmount } = renderHeader();
    const allOn = screen.getByRole('button', { name: 'resource.agent' });
    expect(allOn.getAttribute('aria-pressed')).toBe('true');
    expect(allOn.className).not.toContain('opacity-40');
    unmount();

    renderHeader({ resourceTypes: ['WORKFLOW', 'APPLICATION'] });
    const off = screen.getByRole('button', { name: 'resource.agent' });
    expect(off.getAttribute('aria-pressed')).toBe('false');
    expect(off.className).toContain('opacity-40');
  });

  describe('the launch-kind filter', () => {
    function openFilter() {
      renderHeader();
      fireEvent.click(screen.getByRole('button', { name: 'filters.launchKinds' }));
    }

    it('offers every kind the calendar can report, agent launches included', () => {
      // The default selection is this whole list, so a kind missing from the popover is
      // a kind the user can never bring back once it is off.
      openFilter();

      for (const kind of AGENDA_KIND_ORDER) {
        expect(
          screen.getByRole('button', { name: new RegExp(`kind\.${kind.toLowerCase()}$`) }),
          kind,
        ).toBeTruthy();
      }
    });

    it('is headed by a name that is true of what it lists', () => {
      // It used to say "Trigger types" above Sub-agent, Task and Widget - three things
      // this feature's own documentation insists are deliberately not trigger types.
      openFilter();

      expect(screen.getAllByText('filters.launchKinds').length).toBeGreaterThan(0);
      expect(screen.queryByText('filters.triggerTypes')).toBeNull();
    });

    it('dims the kinds that are switched off and leaves the rest plain', () => {
      // The first version of this test rendered the DEFAULT selection, where nothing is
      // off, and then asserted one chip was not dimmed - it could not have failed. The
      // header takes the selection as a prop, so the off state has to be passed in.
      const selected = AGENDA_KIND_ORDER.filter((kind) => kind !== 'CHAT');
      render(
        <AgendaHeader
          title="September 2026"
          anchor={NOW}
          onPickDate={() => {}}
          preferences={preferences()}
          timezoneOptions={['UTC']}
          search=""
          triggerTypes={selected}
          onSearchChange={() => {}}
          onPrevious={() => {}}
          onNext={() => {}}
          onToday={() => {}}
          onUpdate={() => {}}
          onToggleResourceType={() => {}}
          onResetPreferences={() => {}}
        />,
      );
      fireEvent.click(screen.getByRole('button', { name: 'filters.launchKinds' }));

      const off = screen.getByRole('button', { name: /kind\.chat$/ });
      expect(off.getAttribute('aria-pressed')).toBe('false');
      expect(off.className).toContain('opacity-40');

      const on = screen.getByRole('button', { name: /kind\.sub_agent$/ });
      expect(on.getAttribute('aria-pressed')).toBe('true');
      expect(on.className).not.toContain('opacity-40');
    });
  });
});
