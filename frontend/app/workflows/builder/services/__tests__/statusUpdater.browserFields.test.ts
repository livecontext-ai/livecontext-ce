// @vitest-environment jsdom
/**
 * Tests for the browser-agent live-view field mapping in
 * {@link updateNodesFromBatchSteps}: the wire's snake_case coordinates
 * become camelCase `lastBrowser*` node data, including the NEW
 * `control_node_id` → `lastBrowserNodeId` (the tool-call id the REST
 * control endpoints are keyed by when a GENERIC agent node hosts the
 * browser session - the event's own nodeId addresses the HOST node).
 */
import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';

import { updateNodesFromBatchSteps, type BatchStepData } from '../statusUpdater';
import type { BuilderNodeData } from '../../types';

function agentNode(id: string): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'ai-agent',
      label: 'My Agent',
      kind: 'reasoning',
    } as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

describe('statusUpdater - browser-agent live-view fields', () => {
  it('maps the wire coordinates onto the matched node, including control_node_id', () => {
    const nodes = [agentNode('agent_1')];
    const step: BatchStepData = {
      id: 'agent_1',
      status: 'running',
      session_id: 'ses_9',
      cdp_token: 'tok9',
      cdp_ws_url: 'wss://x/cdp/ses_9',
      run_id: 'stream-run-9',
      control_node_id: 'call_xyz',
      step_index: 0,
      current_url: 'https://example.com',
    };

    const updated = updateNodesFromBatchSteps(nodes, [step]);
    const data = updated[0].data;

    expect(data.lastBrowserSessionId).toBe('ses_9');
    expect(data.lastBrowserCdpToken).toBe('tok9');
    expect(data.lastBrowserCdpWsUrl).toBe('wss://x/cdp/ses_9');
    // Control address: run id from the wire, node id from control_node_id.
    expect(data.lastBrowserRunId).toBe('stream-run-9');
    expect(data.lastBrowserNodeId).toBe('call_xyz');
    expect(data.lastBrowserCurrentUrl).toBe('https://example.com');
  });

  it('leaves lastBrowserNodeId unset when the wire has no control_node_id (dedicated node)', () => {
    const nodes = [agentNode('browser_1')];
    const step: BatchStepData = {
      id: 'browser_1',
      status: 'running',
      session_id: 'ses_1',
      cdp_token: 'tok',
      cdp_ws_url: 'wss://x/cdp/ses_1',
      run_id: 'run_1',
    };

    const updated = updateNodesFromBatchSteps(nodes, [step]);
    expect(updated[0].data.lastBrowserNodeId).toBeUndefined();
    expect(updated[0].data.lastBrowserSessionId).toBe('ses_1');
  });
});
