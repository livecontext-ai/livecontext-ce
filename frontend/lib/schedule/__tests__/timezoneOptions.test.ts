import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { TIMEZONE_PRESETS, localTimezone, timezoneOptionsFor } from '../timezoneOptions';

/**
 * A schedule control must offer the zone it is showing.
 *
 * <p>A Radix `Select` whose value matches none of its items renders an EMPTY trigger, so the
 * control read as "no timezone" over a schedule that was correctly stored in one, and
 * picking any listed zone to fill the blank silently moved the fire time. A zone reaches
 * these controls from outside the seven offered ones often enough to matter: a workflow
 * trigger created from the agenda carries the calendar's display zone, which is any zone
 * one of the workspace's schedules uses.
 *
 * <p>The list is checked here rather than through the rendered control because Radix only
 * mounts its items while the popover is open, which jsdom cannot reliably do. The two call
 * sites are checked by reading the source: without that, reverting either one to a hardcoded
 * list leaves every other test in the repo green, since a saved payload comes from state and
 * never from the options.
 */
describe('timezoneOptionsFor', () => {
  it('offers the presets untouched when the current zone is already one of them', () => {
    const options = timezoneOptionsFor('UTC');
    for (const preset of TIMEZONE_PRESETS) expect(options).toContain(preset);
    expect(options.filter((z) => z === 'UTC')).toHaveLength(1);
  });

  it('adds a zone the presets never had, so the control is not left blank over a real value', () => {
    // A zone nobody runs a CI machine in, and asserted by membership rather than position:
    // the list also carries the MACHINE's zone, so a fixed index passes or fails depending
    // on where the test runs.
    const options = timezoneOptionsFor('Antarctica/Troll');
    expect(options).toContain('Antarctica/Troll');
    expect(options).toContain('UTC');
  });

  it('never lists the same zone twice, whichever one is current', () => {
    // Duplicated items give a Select two rows with the same value; Radix keys on the value,
    // so the second is a silent no-op row.
    for (const zone of ['UTC', localTimezone(), 'Antarctica/Troll', 'Europe/Paris']) {
      const options = timezoneOptionsFor(zone);
      expect(new Set(options).size).toBe(options.length);
    }
  });

  it('always offers the viewer their own zone, even when the control has no value', () => {
    expect(timezoneOptionsFor(undefined)).toContain(localTimezone());
    expect(timezoneOptionsFor(null)).toContain(localTimezone());
  });

  it('adds nothing for a blank current value, which would be a row meaning nothing', () => {
    // A control with no value renders its placeholder, which is the correct answer; an empty
    // `SelectItem` is also rejected outright by Radix.
    expect(timezoneOptionsFor('')).toEqual(timezoneOptionsFor(undefined));
    expect(timezoneOptionsFor('   ')).toEqual(timezoneOptionsFor(undefined));
    expect(timezoneOptionsFor('  UTC  ')).toContain('UTC');
  });
});

describe('the schedule controls that must use it', () => {
  const read = (...parts: string[]) => readFileSync(join(__dirname, '..', '..', '..', ...parts), 'utf8');

  it('is what the agent form maps, not a helper nothing calls', () => {
    const source = read('components', 'chat', 'CreateAgentModal.tsx');
    expect(source).toContain('timezoneOptionsFor(scheduleTimezone).map');
  });

  it('is what the workflow schedule trigger maps too', () => {
    // The same bug by the other door: the agenda's empty slot creates a workflow in the
    // calendar's display zone and opens THIS inspector on it. Fixing one control and leaving
    // the other is the same defect, reachable by the path the same feature creates.
    const source = read(
      'app', 'workflows', 'builder', 'components', 'inspector', 'forms',
      'ScheduleTriggerParametersForm.tsx',
    );
    expect(source).toContain('timezoneOptionsFor(scheduleData.timezone).map');
    // The seven zones must not be re-listed by hand next to the shared list. Matched on the
    // zone inside a SelectItem rather than on one exact spelling of the tag, so neither a
    // reformat nor `value={'Europe/Paris'}` slips a second list back in.
    expect(source).not.toMatch(/<SelectItem[^>]*Europe\/Paris/);
  });
});
