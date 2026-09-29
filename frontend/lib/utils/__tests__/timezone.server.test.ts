/**
 * @vitest-environment node
 *
 * The display zone on the SERVER.
 *
 * Module state in a Next.js server process is shared by every request. A zone applied there would
 * therefore leak one person's preference into everybody else's server-rendered HTML - the one
 * failure of this module that is not visible to the person who causes it. Nothing calls
 * `applyDisplayTimeZone` server-side today; this is what keeps that true.
 *
 * Deliberately `node`, with no `window`, which is also the environment the rest of the suite
 * defaults to.
 */
import { describe, it, expect } from 'vitest';
import {
  applyDisplayTimeZone,
  clearDisplayTimeZone,
  getBrowserTimeZone,
  getClientTimeZone,
  isValidTimeZone,
} from '../timezone';

describe('with no browser', () => {
  it('answers UTC', () => {
    expect(typeof window).toBe('undefined');
    expect(getClientTimeZone()).toBe('UTC');
  });

  it('refuses to apply a zone, so one request cannot set the zone for the next', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    expect(getClientTimeZone()).toBe('UTC');
  });

  it('clearing is inert rather than throwing on the missing cookie store', () => {
    expect(() => clearDisplayTimeZone()).not.toThrow();
    expect(getClientTimeZone()).toBe('UTC');
  });

  it('still validates a zone, which is pure and has no business with the DOM', () => {
    expect(isValidTimeZone('Europe/Paris')).toBe(true);
    expect(isValidTimeZone('Mars/Base')).toBe(false);
  });

  it('reads the runtime zone when asked for the DEVICE one', () => {
    // Node has a zone; this helper answers what the runtime says. It is the DISPLAY zone that
    // must not be inferred from it on the server.
    //
    // A usable zone id, not merely a truthy value: `toBeTruthy()` passed for any non-empty string,
    // including the ones this module elsewhere rejects.
    const device = getBrowserTimeZone();

    expect(device).toBeTypeOf('string');
    expect(isValidTimeZone(device as string)).toBe(true);
    // And it is what the runtime reports, not a constant baked in here.
    expect(device).toBe(new Intl.DateTimeFormat().resolvedOptions().timeZone);
  });
});
