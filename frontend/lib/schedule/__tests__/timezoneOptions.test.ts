/**
 * @vitest-environment jsdom
 *
 * The suite's default is `node`, where `applyDisplayTimeZone` returns early (no `window`) and the
 * display zone can only ever be UTC. Under that environment a test that pins a display zone pins
 * nothing, and an assertion about it passes only when the zone chosen happens to be one of the
 * seven presets - which is how the first version of the two tests at the bottom of this file
 * looked like they discriminated when one of them did not.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { TIMEZONE_PRESETS, allTimezoneOptions, localTimezone, timezoneOptionsFor } from '../timezoneOptions';
import * as agendaTime from '@/lib/utils/agendaTime';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

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
afterEach(() => {
  vi.restoreAllMocks();
  clearDisplayTimeZone();
});

describe('timezoneOptionsFor', () => {
  it('offers the presets untouched when the current zone is already one of them', () => {
    const options = timezoneOptionsFor('UTC');
    for (const preset of TIMEZONE_PRESETS) expect(options).toContain(preset);
    expect(options.filter((z) => z === 'UTC')).toHaveLength(1);
  });

  it('adds a zone the presets never had, so the control is not left blank over a real value', () => {
    // A zone nobody runs a CI machine in, and asserted by membership rather than position: the
    // list also carries the machine's zone and the display zone, so a fixed index passes or
    // fails depending on where the test runs.
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

  it('offers THIS DEVICE zone even when it is not a preset and the account pinned another', () => {
    // The assertion above passes on a machine whose zone happens to be one of the seven presets,
    // which every CI box and most laptops in this project are - `Europe/Paris` is on the list. So
    // it could not see the regression it is named for. Stubbing a zone that is neither a preset
    // nor the display zone is what makes it real.
    //
    // (`TZ=...` cannot be used for this: on Windows it does not change Node's resolved zone, so a
    // run under another TZ silently tests the same zone as before.)
    vi.spyOn(agendaTime, 'browserTimezone').mockReturnValue('Asia/Kolkata');
    applyDisplayTimeZone('America/Denver');

    const options = timezoneOptionsFor(undefined);

    // Both: how they READ times, and where they ARE. A schedule needs to express "9am where I
    // am", which the display zone cannot answer for someone who pinned a different one.
    expect(options).toContain('Asia/Kolkata');
    expect(options).toContain('America/Denver');
  });

  it('still lists each zone once when the device zone IS the display zone', () => {
    // The common case, and the one that makes a naive "add both" emit a duplicate row: Radix
    // keys a Select on the value, so the second row is a silently dead item.
    vi.spyOn(agendaTime, 'browserTimezone').mockReturnValue('Asia/Kolkata');
    applyDisplayTimeZone('Asia/Kolkata');

    const options = timezoneOptionsFor(undefined);

    expect(options.filter((z) => z === 'Asia/Kolkata')).toHaveLength(1);
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

describe('allTimezoneOptions - the account-wide setting, not the seven presets', () => {
  it('offers every zone the runtime knows, sorted', () => {
    const all = allTimezoneOptions();

    expect(all.length).toBeGreaterThan(100);
    expect(all).toContain('Europe/Paris');
    expect(all).toContain('Pacific/Auckland');
    expect([...all].sort((a, b) => a.localeCompare(b))).toEqual(all);
  });

  it('keeps a stored zone this runtime does not list, so the select is never blank', () => {
    // A value set on another device, or a legacy alias: a Select whose value matches no item
    // renders an EMPTY trigger over a setting that has one.
    const all = allTimezoneOptions('Antarctica/South_Pole');

    expect(all[0]).toBe('Antarctica/South_Pole');
  });

  it('does not duplicate a current zone the runtime already lists', () => {
    const all = allTimezoneOptions('Europe/Paris');

    expect(all.filter((z) => z === 'Europe/Paris')).toHaveLength(1);
  });

  it('falls back to the seven presets where Intl.supportedValuesOf is missing', () => {
    // Not a theoretical branch: older Safari has no supportedValuesOf. An empty list would leave
    // the person unable to keep, let alone change, their zone.
    const original = (Intl as { supportedValuesOf?: unknown }).supportedValuesOf;
    delete (Intl as { supportedValuesOf?: unknown }).supportedValuesOf;
    try {
      expect(allTimezoneOptions('Asia/Tokyo')).toEqual(timezoneOptionsFor('Asia/Tokyo'));
    } finally {
      (Intl as { supportedValuesOf?: unknown }).supportedValuesOf = original;
    }
  });

  it('falls back when Intl.supportedValuesOf throws rather than letting the page die', () => {
    const original = (Intl as { supportedValuesOf?: unknown }).supportedValuesOf;
    (Intl as { supportedValuesOf?: unknown }).supportedValuesOf = () => {
      throw new Error('unsupported key');
    };
    try {
      expect(allTimezoneOptions()).toEqual(timezoneOptionsFor());
    } finally {
      (Intl as { supportedValuesOf?: unknown }).supportedValuesOf = original;
    }
  });
});
