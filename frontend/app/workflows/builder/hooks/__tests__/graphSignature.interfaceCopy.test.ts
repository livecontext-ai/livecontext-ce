/**
 * An interface node's copy of its page (HTML/CSS/JS, plus the signature of the stored page
 * it last followed) belongs to the INTERFACE and is never exported with the workflow. When
 * the node refreshed that copy from the stored interface, the workflow looked edited (Save
 * armed, a navigate-away warning) and gained an undo step whose undo put back a copy the node
 * no longer showed. The copy is not part of the workflow's edit signature; what IS exported
 * (mappings, the interface binding, the node box) still is.
 */
import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import { computeGraphSignature } from '../graphSignature';

const interfaceNode = (interfaceData: Record<string, unknown>): Node =>
  ({ id: 'i1', type: 'interfaceNode', position: { x: 0, y: 0 }, data: { label: 'Page', interfaceData } }) as Node;

const base = { interfaceId: 'if-1', variableMapping: { title: '{{x}}' }, editorExpression: '<p>v1</p>', cssTemplate: 'a{}' };

describe('computeGraphSignature and the interface page copy', () => {
  it('regression: refreshing the page copy from the stored interface is not a workflow edit', () => {
    const before = computeGraphSignature([interfaceNode(base)], []);
    const after = computeGraphSignature([interfaceNode({
      ...base, editorExpression: '<p>v2</p>', cssTemplate: 'b{}', jsTemplate: 'x()',
      storedSignature: 'sig', storedSignatureFor: 'if-1',
    })], []);

    expect(after).toBe(before);
  });

  it('a change the workflow does save (the variable mapping) is still an edit', () => {
    const before = computeGraphSignature([interfaceNode(base)], []);
    const after = computeGraphSignature([interfaceNode({ ...base, variableMapping: { title: '{{y}}' } })], []);

    expect(after).not.toBe(before);
  });

  it('an unsaved inspector draft still counts, through its flag on the node', () => {
    const clean = interfaceNode(base);
    const drafting = { ...clean, data: { ...clean.data, hasUnsavedInterfaceChanges: true } } as Node;

    expect(computeGraphSignature([drafting], [])).not.toBe(computeGraphSignature([clean], []));
  });
});
