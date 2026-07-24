import { describe, it, expect } from 'vitest';
import { resolveInsertedTargetHandle } from '../hoverConnectHandles';

describe('resolveInsertedTargetHandle', () => {
  it('normalizes a synthesized horizontal target id to null', () => {
    // A plain FlowNode target renders with no id; HoverEdgeManager synthesizes
    // `target-left` in horizontal. It is not a real handle, so it must become null.
    expect(resolveInsertedTargetHandle('target-left')).toBeNull();
  });

  it('normalizes a synthesized VERTICAL target id to null (the MED-1 regression)', () => {
    // In vertical the synthesized id is `target-top`. The old guard only stripped
    // `target-left`, so `target-top` was passed through as a targetHandle that no node
    // has -> ReactFlow could not resolve it and the inserted edge never rendered.
    expect(resolveInsertedTargetHandle('target-top')).toBeNull();
    expect(resolveInsertedTargetHandle('target-right')).toBeNull();
    expect(resolveInsertedTargetHandle('target-bottom')).toBeNull();
  });

  it('passes a real explicit handle id through unchanged', () => {
    // Merge/While inputs carry real ids; those DO resolve and must be kept.
    expect(resolveInsertedTargetHandle('merge-1-input-2')).toBe('merge-1-input-2');
    expect(resolveInsertedTargetHandle('while-abc-loop-back')).toBe('while-abc-loop-back');
  });
});
