import { describe, it, expect } from 'vitest';

import * as ownKeyHistory from '../ownKeyHistory';
import { isOwnKeyEntry, OWN_KEY_ROUTE } from '../ownKeyHistory';

describe('own-key rows in the credit history', () => {
  it('flags only rows billed under OWN_KEY - a platform or unpinned row shows no badge', () => {
    expect(isOwnKeyEntry({ keyRoute: OWN_KEY_ROUTE })).toBe(true);
    expect(isOwnKeyEntry({ keyRoute: 'PLATFORM' })).toBe(false);
    expect(isOwnKeyEntry({ keyRoute: null })).toBe(false);
    expect(isOwnKeyEntry({})).toBe(false);
  });

  it('reads the route and nothing else - the provider-side dollar estimate is no longer part of a row', () => {
    // This module used to export `ownKeyEstimate` / `formatEstimatedUsd`, and the row drew their
    // output on a second line under the credits. Pinned as an export SURFACE rather than as a
    // rendering, because a helper left behind is how a removed figure comes back: the next reader
    // finds it, assumes there is a caller, and restores the line.
    expect(Object.keys(ownKeyHistory).sort()).toEqual(['OWN_KEY_ROUTE', 'isOwnKeyEntry']);
  });
});
