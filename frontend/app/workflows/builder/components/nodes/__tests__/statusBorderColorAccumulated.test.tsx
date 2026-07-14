// @vitest-environment jsdom
/**
 * getStatusBorderColor: a re-runnable node (reset to ready/pending by "rerun from
 * this step") keeps its ACCUMULATED terminal border, derived from the preserved
 * statusCounts, so a rerun never blanks the node's colour. A fresh ready/pending
 * node with no counts stays neutral (its runnable cue is the blue-shimmer button,
 * not a green ring). Genuine terminal statuses and running/awaiting are unaffected.
 */
import { describe, it, expect } from 'vitest';
import { getStatusBorderColor } from '../shared';

const EMERALD = '#10b981';
const RED = '#ef4444';
const AMBER = '#f59e0b';
const SLATE = '#94a3b8';
const NEUTRAL = 'var(--border-color)';

describe('getStatusBorderColor - accumulated border for re-runnable nodes', () => {
  it('keeps the emerald border for a ready node that already completed', () => {
    expect(getStatusBorderColor('ready', false, true, { COMPLETED: 3 })).toBe(EMERALD);
  });

  it('keeps the red border for a ready node that failed', () => {
    expect(getStatusBorderColor('ready', false, true, { FAILED: 2 })).toBe(RED);
  });

  it('derives the accumulated border for a PENDING node that has historical counts', () => {
    expect(getStatusBorderColor('pending', false, true, { COMPLETED: 2 })).toBe(EMERALD);
  });

  it('shows amber for a partial (completed + failed) re-runnable node', () => {
    expect(getStatusBorderColor('ready', false, true, { COMPLETED: 2, FAILED: 1 })).toBe(AMBER);
  });

  it('shows slate for an all-skipped re-runnable node', () => {
    expect(getStatusBorderColor('ready', false, true, { SKIPPED: 3 })).toBe(SLATE);
  });

  it('stays neutral for a FRESH ready node with no counts (no green ring)', () => {
    expect(getStatusBorderColor('ready', false, true)).toBe(NEUTRAL);
    expect(getStatusBorderColor('ready', false, true, {})).toBe(NEUTRAL);
    expect(getStatusBorderColor('ready', false, true, { RUNNING: 0, COMPLETED: 0 })).toBe(NEUTRAL);
  });

  it('does not override a genuine terminal status', () => {
    expect(getStatusBorderColor('completed', false, true, { FAILED: 5 })).toBe(EMERALD);
    expect(getStatusBorderColor('failed')).toBe(RED);
  });

  it('does not let counts override running / awaiting', () => {
    expect(getStatusBorderColor('running', false, true, { COMPLETED: 3 })).toBe('#3b82f6');
    expect(getStatusBorderColor('awaiting_signal', false, true, { COMPLETED: 3 })).toBe(AMBER);
  });
});
