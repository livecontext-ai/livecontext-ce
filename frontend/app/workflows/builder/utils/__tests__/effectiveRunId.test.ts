import { describe, it, expect } from 'vitest';
import { resolveEffectiveRunId } from '../effectiveRunId';

describe('resolveEffectiveRunId', () => {
  it('lets the context run id win over the static prop', () => {
    // contextRunId truthy implies run mode; the prop is never consulted.
    expect(resolveEffectiveRunId('run_ctx', 'run_prop', true)).toBe('run_ctx');
  });

  it('falls back to the static prop while in run mode (side-panel binding, no context run id yet)', () => {
    expect(resolveEffectiveRunId(null, 'run_prop', true)).toBe('run_prop');
    expect(resolveEffectiveRunId(undefined, 'run_prop', true)).toBe('run_prop');
  });

  // ── Regression: agent-opened `workflow_run` panel lost node status counts on edit→run ──
  it('does NOT pin to the static prop in edit mode - returns undefined so edit→run repaints', () => {
    // Pre-fix this returned 'run_prop' (contextRunId || runId), pinning effectiveRunId.
    // The run→edit reset blanks node statusCounts and sets contextRunId=null; if
    // effectiveRunId stayed at the prop, toggling back to the SAME latest run never
    // changes it, so useRunStateProcessing never re-paints the blanked nodes.
    expect(resolveEffectiveRunId(null, 'run_prop', false)).toBeUndefined();
    expect(resolveEffectiveRunId(undefined, 'run_prop', false)).toBeUndefined();
  });

  it('matches the prop-less + menu path: undefined in both edit and run mode when no run is bound', () => {
    expect(resolveEffectiveRunId(null, undefined, false)).toBeUndefined();
    expect(resolveEffectiveRunId(null, undefined, true)).toBeUndefined();
  });

  it('binds to a run fired from edit mode once the context promotes to run mode', () => {
    // Firing a manual trigger from edit mode sets contextRunId AND mode='run'.
    expect(resolveEffectiveRunId('run_fired', undefined, true)).toBe('run_fired');
  });

  it('normalizes an empty/null prop fallback to undefined (never returns null/"")', () => {
    expect(resolveEffectiveRunId(null, null, true)).toBeUndefined();
    expect(resolveEffectiveRunId('', '', true)).toBeUndefined();
  });
});
