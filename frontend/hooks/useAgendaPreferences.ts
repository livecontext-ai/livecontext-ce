'use client';

import { useCallback, useEffect, useState } from 'react';
import type { ResourceType } from '@/lib/api/orchestrator/agenda.service';
import { browserTimezone } from '@/lib/utils/agendaTime';

/**
 * How the user wants their agenda to look. Persisted per browser, because it is a view
 * preference and not workspace data: two people sharing a workspace legitimately want
 * different week starts, different timezones and different densities.
 */
export interface AgendaPreferences {
  view: AgendaViewMode;
  /** Display timezone. Defaults to the viewer's own; a schedule still fires in its own. */
  timezone: string;
  weekStartsOn: 0 | 1;
  showWeekends: boolean;
  /** Which resource kinds are drawn. Empty means none, not all - see the filter UI. */
  resourceTypes: ResourceType[];
  /** Draw what already ran on past days. */
  showPast: boolean;
  /** Draw paused schedules, greyed, alongside the armed ones. */
  showPaused: boolean;
  density: 'comfortable' | 'compact';
  /** First and last hour drawn by the week and day grids. */
  dayStartHour: number;
  dayEndHour: number;
}

export type AgendaViewMode = 'month' | 'week' | 'day' | 'list';

const STORAGE_KEY = 'lc.agenda.preferences.v1';

export const ALL_RESOURCE_TYPES: ResourceType[] = ['WORKFLOW', 'APPLICATION', 'AGENT'];

function defaults(): AgendaPreferences {
  return {
    // Week, not month. A month cell can only ever show a few chips before it has to say
    // "+7 more", so the view that fits a whole month is the one that shows the least of
    // it; a week gives every occurrence a readable row and an hour to sit at.
    view: 'week',
    timezone: browserTimezone(),
    weekStartsOn: 1,
    showWeekends: true,
    resourceTypes: [...ALL_RESOURCE_TYPES],
    showPast: true,
    showPaused: true,
    density: 'comfortable',
    dayStartHour: 0,
    dayEndHour: 24,
  };
}

/**
 * Merge stored values over the defaults field by field.
 *
 * Never spread a parsed blob wholesale: a payload written by an older build (or edited
 * by hand) can carry a missing or wrong-typed field, and a `view` of `undefined` renders
 * nothing at all. Each field is validated against what the UI can actually draw, and
 * anything unrecognised falls back to its default instead of breaking the page.
 */
function hydrate(raw: unknown): AgendaPreferences {
  const base = defaults();
  if (!raw || typeof raw !== 'object') return base;
  const stored = raw as Partial<AgendaPreferences>;

  const views: AgendaViewMode[] = ['month', 'week', 'day', 'list'];
  const startHour = clampHour(stored.dayStartHour, base.dayStartHour);
  const endHour = clampHour(stored.dayEndHour, base.dayEndHour);

  return {
    view: views.includes(stored.view as AgendaViewMode) ? (stored.view as AgendaViewMode) : base.view,
    timezone: typeof stored.timezone === 'string' && isUsableTimezone(stored.timezone)
      ? stored.timezone
      : base.timezone,
    weekStartsOn: stored.weekStartsOn === 0 || stored.weekStartsOn === 1 ? stored.weekStartsOn : base.weekStartsOn,
    showWeekends: typeof stored.showWeekends === 'boolean' ? stored.showWeekends : base.showWeekends,
    resourceTypes: Array.isArray(stored.resourceTypes)
      ? stored.resourceTypes.filter((t): t is ResourceType => ALL_RESOURCE_TYPES.includes(t as ResourceType))
      : base.resourceTypes,
    showPast: typeof stored.showPast === 'boolean' ? stored.showPast : base.showPast,
    showPaused: typeof stored.showPaused === 'boolean' ? stored.showPaused : base.showPaused,
    density: stored.density === 'compact' ? 'compact' : base.density,
    // An inverted or zero-width range would render an empty grid with no way back.
    dayStartHour: startHour < endHour ? startHour : base.dayStartHour,
    dayEndHour: startHour < endHour ? endHour : base.dayEndHour,
  };
}

function clampHour(value: unknown, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.min(24, Math.max(0, Math.round(value)));
}

/**
 * The view a FIRST visit opens on, which is not the same answer on every screen.
 *
 * Week is right on a laptop for the reason `defaults()` gives. On a phone it is the worst
 * of the four: seven columns of a 390px screen are ~50px each, and a chip that narrow can
 * only show its time, so the view meant to show the most shows a wall of digits. The list
 * gives every run a full row at any width, which is what a phone calendar opens on.
 *
 * Consulted ONCE, when nothing has been stored yet. The moment somebody picks a view it is
 * written to storage and this is never asked again, on any screen.
 */
function firstView(): AgendaViewMode {
  if (typeof window === 'undefined' || !window.matchMedia) return defaults().view;
  return window.matchMedia('(max-width: 900px)').matches ? 'list' : defaults().view;
}

/** A zone the platform can actually format in; a stale or invented id must not throw. */
function isUsableTimezone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return true;
  } catch {
    return false;
  }
}

/**
 * Read/write the agenda's view preferences.
 *
 * Starts from the defaults on the first render and hydrates from storage in an effect,
 * never during render: reading `localStorage` while rendering makes the server and the
 * first client pass disagree, which React reports as a hydration mismatch.
 */
export function useAgendaPreferences() {
  const [preferences, setPreferences] = useState<AgendaPreferences>(defaults);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      // Nothing stored is the one moment the screen gets a say in the view: see firstView.
      if (raw) setPreferences(hydrate(JSON.parse(raw)));
      else setPreferences((current) => ({ ...current, view: firstView() }));
    } catch {
      // Private mode, blocked site data, corrupt JSON: the defaults are a fine agenda.
    }
    setHydrated(true);
  }, []);

  const update = useCallback((patch: Partial<AgendaPreferences>) => {
    setPreferences((current) => {
      const next = { ...current, ...patch };
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        // Persisting is a convenience; the session still gets the change.
      }
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    // Reset puts the agenda back to how it would arrive on THIS screen, not to how it would
    // arrive on a laptop: landing a phone back on a seven-column week is not a clean slate.
    const next = { ...defaults(), view: firstView() };
    try {
      window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* nothing to clean up */
    }
    setPreferences(next);
  }, []);

  const toggleResourceType = useCallback((type: ResourceType) => {
    setPreferences((current) => {
      const active = current.resourceTypes.includes(type);
      const next = {
        ...current,
        resourceTypes: active
          ? current.resourceTypes.filter((t) => t !== type)
          : [...current.resourceTypes, type],
      };
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        /* see update() */
      }
      return next;
    });
  }, []);

  return { preferences, update, reset, toggleResourceType, hydrated };
}
