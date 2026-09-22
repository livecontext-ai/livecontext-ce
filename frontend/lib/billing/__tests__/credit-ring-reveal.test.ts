/**
 * The credit ring's once-per-visit memory.
 *
 * Tiny, and worth its own file for one reason: its whole value is that it lives
 * OUTSIDE React. That is not an implementation detail to be tidied away later -
 * `AppSidebar` renders the ring from both arms of its collapsed/expanded
 * ternary, so React reconciles them as different components and destroys any
 * state the ring holds on every toggle. A version of this that "simplifies" into
 * component state compiles, passes every rendering test, and replays the whole
 * 1.3-second reveal each time a reader collapses the sidebar.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  creditRingRevealPlayed,
  markCreditRingRevealPlayed,
  resetCreditRingRevealForTests,
} from '../credit-ring-reveal';

beforeEach(resetCreditRingRevealForTests);

describe('credit ring reveal memory', () => {
  it('has not played before anything marks it', () => {
    expect(creditRingRevealPlayed()).toBe(false);
  });

  it('remembers once marked', () => {
    markCreditRingRevealPlayed();
    expect(creditRingRevealPlayed()).toBe(true);
  });

  it('survives being read many times, which is what a remount does', () => {
    // The ring reads it on every mount. A flag that cleared itself on read
    // would let the second mount - the other arm of the sidebar's ternary -
    // animate again, which is the exact bug this module exists to stop.
    markCreditRingRevealPlayed();
    expect(creditRingRevealPlayed()).toBe(true);
    expect(creditRingRevealPlayed()).toBe(true);
    expect(creditRingRevealPlayed()).toBe(true);
  });

  it('is idempotent, so a re-render marking it again changes nothing', () => {
    markCreditRingRevealPlayed();
    markCreditRingRevealPlayed();
    expect(creditRingRevealPlayed()).toBe(true);
  });

  it('is reset by the test seam, which every ring suite depends on', () => {
    // Without a working reset the second test in any ring suite silently gets
    // the already-played path and asserts nothing about the animation.
    markCreditRingRevealPlayed();
    resetCreditRingRevealForTests();
    expect(creditRingRevealPlayed()).toBe(false);
  });
});
