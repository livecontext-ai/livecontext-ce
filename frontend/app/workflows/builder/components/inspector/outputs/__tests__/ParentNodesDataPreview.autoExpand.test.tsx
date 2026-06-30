// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import React from 'react';
import type { Node, Edge } from 'reactflow';

// Stub the heavy run-data preview (it pulls in useRunData + orchestratorApi):
// each instance just marks its own presence by step alias, so we can assert
// WHICH ancestor groups are mounted (= expanded).
vi.mock('../RunDataPreview', () => ({
  RunDataPreview: ({ stepAlias }: { stepAlias?: string }) => (
    <div data-testid={`rdp-${stepAlias}`} />
  ),
}));
vi.mock('../../../nodes/shared', () => ({
  NodeIcon: () => null,
  getIconSlug: () => '',
}));

import { ParentNodesDataPreview } from '../ParentNodesDataPreview';

function node(id: string, label: string): Node<any> {
  return { id, type: 'flowNode', position: { x: 0, y: 0 }, data: { id, label, kind: 'action' } };
}

// child = direct parent (distance 1), grand = grandparent (distance 2, collapsible).
const child = node('rf-child', 'Child');
const grand = node('rf-grand', 'Grand');
const allNodes = [child, grand];
const edges: Edge[] = [{ id: 'e1', source: 'rf-grand', target: 'rf-child' }];

describe('ParentNodesDataPreview - grandparent auto-expand during approval review', () => {
  it('keeps grandparents collapsed by default (no review target)', () => {
    render(
      <ParentNodesDataPreview
        parentNodes={[child]}
        allNodes={allNodes}
        edges={edges}
        workflowId="wf"
        runId="run"
      />,
    );
    // Direct parent always rendered; grandparent group collapsed → not mounted.
    expect(screen.getByTestId('rdp-Child')).toBeTruthy();
    expect(screen.queryByTestId('rdp-Grand')).toBeNull();
  });

  it('auto-expands the grandparent group when an approval review is active', () => {
    render(
      <ParentNodesDataPreview
        parentNodes={[child]}
        allNodes={allNodes}
        edges={edges}
        workflowId="wf"
        runId="run"
        autoExpandAncestors
        reviewRequestId={1}
      />,
    );
    expect(screen.getByTestId('rdp-Child')).toBeTruthy();
    expect(screen.getByTestId('rdp-Grand')).toBeTruthy();
  });
});
