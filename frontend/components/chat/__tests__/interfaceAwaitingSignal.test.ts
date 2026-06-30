import { describe, it, expect } from 'vitest';
import { computeIsAwaitingSignal, hasContinueAction, isCurrentInterfaceItemPending } from '../interfaceAwaitingSignal';

/**
 * Guards the Continue-button visibility invariant for interface nodes in the
 * application toolbar (ApplicationTabContent).
 *
 * Regression: for non-blocking interfaces (actionMapping without "__continue"),
 * the button flashed during the running→completed transient because
 * isAwaitingSignal was `awaitingSignalSteps || runningSteps`, unconditional.
 * The helper now requires actionMapping to contain "__continue" before the
 * signal-step checks ever matter.
 */

describe('hasContinueAction', () => {
  it('returns false when actionMapping is null', () => {
    expect(hasContinueAction(null)).toBe(false);
  });

  it('returns false when actionMapping is undefined', () => {
    expect(hasContinueAction(undefined)).toBe(false);
  });

  it('returns false when actionMapping is empty', () => {
    expect(hasContinueAction({})).toBe(false);
  });

  it('returns false when no value is "__continue"', () => {
    expect(hasContinueAction({ '#submit': 'trigger:handle_form', '#del': 'trigger:delete' })).toBe(false);
  });

  it('returns true when at least one value is "__continue"', () => {
    expect(hasContinueAction({ 'form': '__continue' })).toBe(true);
  });

  it('returns true when "__continue" is mixed with regular triggers', () => {
    expect(hasContinueAction({ 'form#next': '__continue', '#cancel': 'trigger:abort' })).toBe(true);
  });

  it('does not match a KEY named "__continue" (keys are CSS selectors, values are targets)', () => {
    // Backend invariant: __continue is always a VALUE in actionMapping.
    // A selector literally named "__continue" → not blocking.
    expect(hasContinueAction({ '__continue': 'trigger:custom' })).toBe(false);
  });
});

describe('computeIsAwaitingSignal', () => {
  const makeRunState = (opts?: { awaiting?: string[]; running?: string[] }) => ({
    awaitingSignalSteps: new Set(opts?.awaiting ?? []),
    runningSteps: new Set(opts?.running ?? []),
  });

  it('returns false when nodeId is missing', () => {
    const runState = makeRunState({ awaiting: ['interface:x'] });
    expect(computeIsAwaitingSignal(null, runState, { 'form': '__continue' })).toBe(false);
  });

  it('returns false when runState is null', () => {
    expect(computeIsAwaitingSignal('interface:x', null, { 'form': '__continue' })).toBe(false);
  });

  it('returns false for non-blocking interface even when node is in awaitingSignalSteps', () => {
    // Edge case: backend briefly reports status=awaiting for a node whose
    // actionMapping has no __continue. The helper must not trust the set -
    // the actionMapping is the authority.
    const runState = makeRunState({ awaiting: ['interface:x'] });
    expect(computeIsAwaitingSignal('interface:x', runState, { '#btn': 'trigger:go' })).toBe(false);
  });

  it('returns false for non-blocking interface even when node is in runningSteps', () => {
    // The exact transient that used to flash the Continue button.
    const runState = makeRunState({ running: ['interface:x'] });
    expect(computeIsAwaitingSignal('interface:x', runState, { '#btn': 'trigger:go' })).toBe(false);
  });

  it('returns false for non-blocking interface when both sets are empty', () => {
    const runState = makeRunState();
    expect(computeIsAwaitingSignal('interface:x', runState, {})).toBe(false);
  });

  it('returns true for blocking interface when node is in awaitingSignalSteps', () => {
    const runState = makeRunState({ awaiting: ['interface:wizard'] });
    expect(computeIsAwaitingSignal('interface:wizard', runState, { 'form': '__continue' })).toBe(true);
  });

  it('returns true for blocking interface when node is in runningSteps (transient window)', () => {
    // Catches the tiny running→awaiting transition window for blocking interfaces.
    const runState = makeRunState({ running: ['interface:wizard'] });
    expect(computeIsAwaitingSignal('interface:wizard', runState, { 'form': '__continue' })).toBe(true);
  });

  it('returns false for blocking interface when node is in neither set (already resolved)', () => {
    const runState = makeRunState();
    expect(computeIsAwaitingSignal('interface:wizard', runState, { 'form': '__continue' })).toBe(false);
  });

  it('returns true for blocking interface when both sets contain the node', () => {
    const runState = makeRunState({ awaiting: ['interface:wizard'], running: ['interface:wizard'] });
    expect(computeIsAwaitingSignal('interface:wizard', runState, { 'form': '__continue' })).toBe(true);
  });
});

describe('isCurrentInterfaceItemPending', () => {
  it('uses the coarse awaiting fallback when per-signal details have not loaded for a single page', () => {
    expect(isCurrentInterfaceItemPending([], 0, true)).toBe(true);
  });

  it('does not enable continue when there are no pending details and no coarse awaiting fallback', () => {
    expect(isCurrentInterfaceItemPending([], 0, false)).toBe(false);
  });

  it('treats a single signal without itemId as the current non-split item', () => {
    expect(isCurrentInterfaceItemPending([{ itemId: null }], 0, false)).toBe(true);
  });

  it('matches split signals by rendered item index', () => {
    expect(isCurrentInterfaceItemPending([{ itemId: '2' }], 2, false)).toBe(true);
    expect(isCurrentInterfaceItemPending([{ itemId: '2' }], 1, false)).toBe(false);
  });
});
