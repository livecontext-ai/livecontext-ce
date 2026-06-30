import { describe, it, expect } from 'vitest';
import { shouldAdoptEpochEvent, VIEWING_EPOCH_EVENT } from '../epochEventScope';

describe('shouldAdoptEpochEvent - runId scoping of the viewingEpochChanged event', () => {
  it('rejects an event addressed to a DIFFERENT run (the multi-app contamination case)', () => {
    // App A broadcasts its epoch; App B (a different run, kept mounted in the
    // side panel) must NOT adopt it. This is the bug the scoping fixes.
    expect(shouldAdoptEpochEvent('run-A', 'run-B')).toBe(false);
  });

  it('accepts an event for the SAME run (canvas RunInfo ↔ same-run app tab sync)', () => {
    expect(shouldAdoptEpochEvent('run-X', 'run-X')).toBe(true);
  });

  it('accepts a legacy event without a runId (backward-compatible global sync)', () => {
    expect(shouldAdoptEpochEvent(undefined, 'run-A')).toBe(true);
    expect(shouldAdoptEpochEvent(null, 'run-A')).toBe(true);
  });

  it('accepts when the listener has no runId yet (canvas before a run is attached)', () => {
    expect(shouldAdoptEpochEvent('run-A', null)).toBe(true);
    expect(shouldAdoptEpochEvent('run-A', undefined)).toBe(true);
  });

  it('accepts when both runIds are unknown', () => {
    expect(shouldAdoptEpochEvent(null, null)).toBe(true);
    expect(shouldAdoptEpochEvent(undefined, undefined)).toBe(true);
  });

  it('exposes the canonical window event name so dispatch/listen cannot drift', () => {
    expect(VIEWING_EPOCH_EVENT).toBe('viewingEpochChanged');
  });
});
