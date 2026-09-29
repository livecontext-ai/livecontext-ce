/**
 * The three schedule surfaces default to the ACCOUNT's display zone, not to this browser.
 *
 * <p>The distinction is the whole point of the setting: a schedule is created once and fires for
 * years, so seeding it from whichever machine happened to be open is how a workflow ends up firing
 * at 09:00 in a zone nobody chose. Someone who picked Tokyo in Settings while sitting in Paris
 * must get Tokyo here.
 *
 * <p>Asserted at the source rather than by rendering three heavy components: what matters is
 * WHICH resolver each default reads, and a render would prove it only for the one machine zone the
 * test happens to run on. The audit that asked for this coverage found all three untested.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (...segments: string[]) => readFileSync(join(process.cwd(), ...segments), 'utf8');

describe('a new schedule starts in the account display zone', () => {
  it('the workflow schedule trigger', () => {
    const source = read(
      'app', 'workflows', 'builder', 'components', 'inspector', 'forms',
      'ScheduleTriggerParametersForm.tsx'
    );

    expect(source).toContain('timezone: existing?.timezone || getClientTimeZone()');
    // The browser reader must not be what seeds it any more.
    expect(source).not.toContain('resolvedOptions().timeZone');
  });

  it('the agent recurrence form', () => {
    const source = read('components', 'chat', 'CreateAgentModal.tsx');

    expect(source).toContain('seededSchedule?.timezone || getClientTimeZone()');
    expect(source).not.toContain('resolvedOptions().timeZone');
  });

  it('the agenda view', () => {
    const source = read('hooks', 'useAgendaPreferences.ts');

    expect(source).toContain('timezone: getClientTimeZone()');
    expect(source).not.toContain('browserTimezone()');
  });
});

describe('an EXISTING schedule keeps the zone it was saved with', () => {
  it('the trigger form reads its own value first, so a live fire time never moves', () => {
    const source = read(
      'app', 'workflows', 'builder', 'components', 'inspector', 'forms',
      'ScheduleTriggerParametersForm.tsx'
    );

    // `existing?.timezone ||` before the resolver: changing a stored zone would silently move
    // when a workflow runs, for every schedule already armed.
    const line = source.split('\n').find((l) => l.includes('timezone: existing?.timezone'));
    expect(line).toBeDefined();
    expect(line!.indexOf('existing?.timezone')).toBeLessThan(line!.indexOf('getClientTimeZone'));
  });

  it('the agent form does the same with its seed', () => {
    const source = read('components', 'chat', 'CreateAgentModal.tsx');
    const line = source.split('\n').find((l) => l.includes('seededSchedule?.timezone'));

    expect(line).toBeDefined();
    expect(line!.indexOf('seededSchedule?.timezone')).toBeLessThan(line!.indexOf('getClientTimeZone'));
  });
});

describe('the next-run time is shown in the TRIGGER\'s zone, not the reader\'s', () => {
  /**
   * Every surface that prints a schedule's zone AND one of its fire times.
   *
   * <p>Enumerated, because covering only the first one is how two of the three shipped wrong: a
   * card that reads "0 9 * * * (Asia/Tokyo)" above "Next run: 01:00" puts two zones in one row,
   * and the reader checks 01:00 against "0 9" and concludes the schedule is broken. Note that the
   * fire times no longer carry an offset, so this mismatch is now SILENT where it used to be
   * visible: a surface of this shape has to draw the fire time in the schedule's own zone, not
   * merely name the zone somewhere. Any new surface of this shape belongs in this list.
   */
  const SURFACES: ReadonlyArray<{
    what: string;
    path: string[];
    zoneExpression: string;
    /** Present where the stored zone can be blank and must not fall through to the reader. */
    blankZoneFallback?: string;
  }> = [
    {
      what: 'the builder inspector panel',
      path: ['app', 'workflows', 'builder', 'components', 'inspector', 'forms',
        'ScheduleTriggerParametersForm.tsx'],
      zoneExpression: 'scheduleData.timezone',
    },
    {
      what: 'the agent schedule card',
      path: ['components', 'chat', 'CreateAgentModal.tsx'],
      zoneExpression: 'scheduleData.timezone',
    },
    {
      what: 'the public-access schedule list',
      path: ['app', '[locale]', 'app', 'settings', 'public-access', 'components',
        'ScheduleTabContent.tsx'],
      zoneExpression: 'schedule.timezone',
      blankZoneFallback: 'zoneOf(',
    },
  ];

  for (const surface of SURFACES) {
    it(`${surface.what} draws every fire time in the schedule zone`, () => {
      const source = read(...surface.path);

      // Two separate things, because either alone lets the bug through.
      //
      // First: no formatUtcDateTime call may be zone-LESS. A one-argument call is a timestamp drawn
      // in the reader's zone, which is the defect. The test asks only for "a zone is passed" rather
      // than for one spelling of it, because two of these files route it through a local helper and
      // pinning the spelling would just be a second copy of the code.
      const lines = source.split('\n');
      const zoneless = lines
        .map((line, index) => ({ line: line.trim(), at: index + 1 }))
        .filter(({ line }) => /formatUtcDateTime\(/.test(line))
        .filter(({ line }) => !line.startsWith('//') && !line.startsWith('*'))
        // A call split across lines carries its zone on the next one; judge the pair.
        .filter(({ line, at }) => !(line + (lines[at] ?? '')).includes('timeZone'));

      expect(zoneless, `${surface.what}: fire times drawn in the reader zone`).toEqual([]);

      // Second: the zone reaching those calls must be the SCHEDULE's. A helper defaulting to the
      // reader's zone would satisfy the check above while rendering exactly the wrong thing.
      expect(source, `${surface.what}: must read its own schedule zone`)
        .toContain(surface.zoneExpression);

      // Third, where the stored zone can be BLANK: it must resolve to something, not fall through
      // to the reader's zone. A fall-through used to be visible (every instant carried its zone
      // label) and is now silent, under a detail line that reads "0 9 * * * ()".
      if (surface.blankZoneFallback) {
        expect(source, `${surface.what}: a blank stored zone must not fall through`)
          .toContain(surface.blankZoneFallback);
      }
    });
  }

  it('and the builder panel spells it the documented way', () => {
    const source = read(
      'app', 'workflows', 'builder', 'components', 'inspector', 'forms',
      'ScheduleTriggerParametersForm.tsx'
    );

    expect(source).toContain('formatUtcDateTime(isoDate, { timeZone: scheduleData.timezone })');
  });
});
