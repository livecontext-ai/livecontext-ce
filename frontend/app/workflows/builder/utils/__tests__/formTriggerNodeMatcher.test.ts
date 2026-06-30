import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { findLiveFormTriggerNode } from '../formTriggerNodeMatcher';

// Build a form-trigger node the way TriggerNodeCreator does: data.id carries a
// "form-trigger-<timestamp>-<rand>" suffix with NO label, and data.label holds
// the human label. The form definition lives in data.formTriggerData.
function formNode(suffix: string, label: string, fieldName: string): Node<BuilderNodeData> {
  return {
    id: `node-${suffix}`,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: `form-trigger-${suffix}`,
      label,
      kind: 'entry',
      formTriggerData: {
        title: label,
        fields: [{ id: `field-${fieldName}`, name: fieldName, label: fieldName, type: 'text' }],
      },
    } as unknown as BuilderNodeData,
  };
}

describe('findLiveFormTriggerNode', () => {
  // ── Regression: two form triggers rendered the SAME form in the run panel ──
  // Pre-fix the lookup matched `data.id.startsWith('form-trigger-')`, so .find()
  // returned the FIRST form node for EVERY waiting trigger and both tabs showed
  // the first form's fields. Each trigger must resolve to its OWN form node.
  it('binds each waiting form trigger to its own node by normalized label', () => {
    const nodes = [
      formNode('1700000000000-aaaaa', 'mcall setup', 'meeting_url'),
      formNode('1700000000001-bbbbb', 'transcript request', 'transcript_id'),
    ];

    const first = findLiveFormTriggerNode(nodes, 'trigger:mcall_setup', 'mcall setup');
    const second = findLiveFormTriggerNode(nodes, 'trigger:transcript_request', 'transcript request');

    expect(first?.id).toBe('node-1700000000000-aaaaa');
    expect(second?.id).toBe('node-1700000000001-bbbbb');
    // The crux of the bug: the two triggers must NOT collapse to the same node.
    expect(first?.id).not.toBe(second?.id);
    expect((second!.data as any).formTriggerData.fields[0].name).toBe('transcript_id');
  });

  it('matches via the triggerId key alone, isolated from the label argument', () => {
    // Node label normalizes to my_form, so its triggerKey is trigger:my_form and
    // matches triggerId. The passed label is deliberately a STALE/unrelated string
    // that normalizes differently - only the triggerId-key path (a) can match here.
    const nodes = [formNode('s1', 'My Form', 'a')];
    expect(findLiveFormTriggerNode(nodes, 'trigger:my_form', 'a stale label')?.id).toBe('node-s1');
  });

  it('falls back to normalized-label matching when triggerId is an id-based key', () => {
    // triggerId 'trigger:42' is an id fallback (not a label key), so path (a) cannot
    // match; only the normalized-label path (b) can. Messy node casing/spacing must
    // still normalize to the same slug as the trigger label.
    const nodes = [formNode('s1', 'Contact  US', 'email')];
    expect(findLiveFormTriggerNode(nodes, 'trigger:42', 'contact us')?.id).toBe('node-s1');
  });

  it('returns undefined when no node carries a form definition for the trigger', () => {
    const nodes = [formNode('s1', 'mcall setup', 'a')];
    expect(findLiveFormTriggerNode(nodes, 'trigger:transcript_request', 'transcript request')).toBeUndefined();
  });

  it('ignores nodes without formTriggerData (e.g. webhook/chat triggers)', () => {
    const webhook: Node<BuilderNodeData> = {
      id: 'node-wh',
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: { id: 'webhook-trigger-x', label: 'mcall setup', kind: 'entry' } as unknown as BuilderNodeData,
    };
    expect(findLiveFormTriggerNode([webhook], 'trigger:mcall_setup', 'mcall setup')).toBeUndefined();
  });

  it('does not false-match two blank-labeled nodes to each other', () => {
    // Both normalize to null; must not collapse via null === null.
    const nodes = [formNode('s1', '', 'a'), formNode('s2', '', 'b')];
    expect(findLiveFormTriggerNode(nodes, 'trigger:42', '')).toBeUndefined();
  });
});
