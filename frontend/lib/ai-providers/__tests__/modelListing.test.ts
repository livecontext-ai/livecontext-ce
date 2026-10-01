import { describe, expect, it } from 'vitest';
import {
  listingStatePatch,
  listingTransitionPatch,
  modelListingState,
  nextStateFromEye,
  nextStateFromSwitch,
} from '@/lib/ai-providers/modelListing';

/**
 * The three states of a model (V554) and the transitions the admin row offers. The backend lets
 * `enabled = false` win over `unlisted`, so the one thing these pin above all is that a flag the
 * row cannot show never survives a transition.
 */
describe('modelListingState', () => {
  it('reads off before anything else: an unlisted flag on a disabled row means nothing', () => {
    expect(modelListingState({ enabled: false, unlisted: true })).toBe('off');
    expect(modelListingState({ enabled: false })).toBe('off');
  });

  it('reads an enabled unlisted row as unlisted, and a missing enabled as enabled', () => {
    expect(modelListingState({ enabled: true, unlisted: true })).toBe('unlisted');
    expect(modelListingState({ unlisted: true })).toBe('unlisted');
  });

  it('reads everything else as listed', () => {
    expect(modelListingState({ enabled: true, unlisted: false })).toBe('listed');
    expect(modelListingState({})).toBe('listed');
  });
});

describe('listingStatePatch', () => {
  it('always writes both flags, and clears unlisted when switching off', () => {
    expect(listingStatePatch('listed')).toEqual({ enabled: true, unlisted: false });
    expect(listingStatePatch('unlisted')).toEqual({ enabled: true, unlisted: true });
    expect(listingStatePatch('off')).toEqual({ enabled: false, unlisted: false });
  });

  it('round-trips: the patch for a state reads back as that state', () => {
    for (const state of ['listed', 'unlisted', 'off'] as const) {
      expect(modelListingState(listingStatePatch(state))).toBe(state);
    }
  });
});

describe('row transitions', () => {
  it('the eye unlists a listed model, lists an unlisted one, and brings an off one back UNLISTED', () => {
    expect(nextStateFromEye('listed')).toBe('unlisted');
    expect(nextStateFromEye('unlisted')).toBe('listed');
    // One save from off to unlisted: never exposed in the pickers on the way.
    expect(nextStateFromEye('off')).toBe('unlisted');
  });

  it('the switch turns any enabled state off, and off back to LISTED', () => {
    expect(nextStateFromSwitch('listed')).toBe('off');
    expect(nextStateFromSwitch('unlisted')).toBe('off');
    expect(nextStateFromSwitch('off')).toBe('listed');
  });
});

describe('listingTransitionPatch', () => {
  const listed = { enabled: true, unlisted: false };
  const unlisted = { enabled: true, unlisted: true };
  const off = { enabled: false, unlisted: false };

  it('between listed and unlisted, writes only the flag: the model stays enabled (no price check)', () => {
    expect(listingTransitionPatch(listed, 'unlisted')).toEqual({ unlisted: true });
    expect(listingTransitionPatch(unlisted, 'listed')).toEqual({ unlisted: false });
  });

  it('crossing off writes enabled, and unlisted only when it changes (no claim on a flag it did not move)', () => {
    expect(listingTransitionPatch(off, 'unlisted')).toEqual({ enabled: true, unlisted: true });
    expect(listingTransitionPatch(off, 'listed')).toEqual({ enabled: true });
    expect(listingTransitionPatch(unlisted, 'off')).toEqual({ enabled: false, unlisted: false });
    expect(listingTransitionPatch(listed, 'off')).toEqual({ enabled: false });
  });

  it('writes nothing when the model is already there, and reads a missing flag the way the backend does', () => {
    expect(listingTransitionPatch(listed, 'listed')).toEqual({});
    expect(listingTransitionPatch({}, 'listed')).toEqual({});
    // A stray flag left on an OFF row is cleared on the way back.
    expect(listingTransitionPatch({ enabled: false, unlisted: true }, 'listed')).toEqual({ enabled: true, unlisted: false });
  });

  it('every transition lands in the state it names', () => {
    const states = ['listed', 'unlisted', 'off'] as const;
    for (const from of states) {
      for (const to of states) {
        const before = listingStatePatch(from);
        expect(modelListingState({ ...before, ...listingTransitionPatch(before, to) }), `${from} -> ${to}`).toBe(to);
      }
    }
  });
});
