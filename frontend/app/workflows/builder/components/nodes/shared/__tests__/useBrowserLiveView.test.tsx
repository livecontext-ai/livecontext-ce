// @vitest-environment jsdom
/**
 * Tests for {@link useBrowserLiveView} - the shared live-view side-panel
 * wiring used by BOTH the dedicated agent:browser_agent node and the
 * generic agent node (workflow parity for agent_browse tool calls).
 *
 * Pins:
 *  - `hasLiveSession` requires the FULL coordinate set, not just a session
 *    id (an unconfigured CDP issuer must not produce a dead eye button),
 *  - the panel receives the CONTROL node id (`lastBrowserNodeId`, the
 *    tool-call id for a generic agent node) with the builder node id as
 *    fallback - REST calls (takeover-resume/refresh/final-screenshot) are
 *    keyed by the control address,
 *  - the tab opens at the unified 0.5 width.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

const openTab = vi.fn();
const updateTab = vi.fn();
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab, updateTab, tabs: [], isOpen: false, activeTabId: null }),
}));

const panelProps: unknown[] = [];
vi.mock('@/components/app/AgentBrowsePanelContent', () => ({
  AgentBrowsePanelContent: (props: unknown) => {
    panelProps.push(props);
    return null;
  },
}));

import { useBrowserLiveView } from '../useBrowserLiveView';
import type { BuilderNodeData } from '../../../../types';

const messages = {
  workflowBuilder: { nodes: { browserAgent: { tabLabel: 'Browser Agent' } } },
};

function probe(id: string, data: Partial<BuilderNodeData>) {
  const result: { hasLiveSession?: boolean; openLiveView?: () => void } = {};
  function Probe() {
    const hook = useBrowserLiveView(id, data as BuilderNodeData);
    result.hasLiveSession = hook.hasLiveSession;
    result.openLiveView = hook.openLiveView;
    return null;
  }
  render(
    <NextIntlClientProvider locale="en" messages={messages as never} onError={() => {}}>
      <Probe />
    </NextIntlClientProvider>,
  );
  return result;
}

const fullCoords = {
  lastBrowserSessionId: 'ses_1',
  lastBrowserCdpToken: 'tok',
  lastBrowserCdpWsUrl: 'wss://x/cdp/ses_1',
  lastBrowserRunId: 'run_1',
  lastBrowserCurrentUrl: 'https://example.com',
};

describe('useBrowserLiveView', () => {
  beforeEach(() => {
    openTab.mockClear();
    updateTab.mockClear();
    panelProps.length = 0;
  });

  it('hasLiveSession is false with a session id but NO token/wsUrl (dead-button guard)', () => {
    const r = probe('agent_1', { lastBrowserSessionId: 'ses_1' });
    expect(r.hasLiveSession).toBe(false);
  });

  it('hasLiveSession is true with the full coordinate set', () => {
    const r = probe('agent_1', fullCoords);
    expect(r.hasLiveSession).toBe(true);
  });

  it('opens the tab at the unified 0.5 width, keyed on the builder node id', () => {
    const r = probe('agent_1', fullCoords);
    r.openLiveView?.();
    expect(openTab).toHaveBeenCalledTimes(1);
    const tab = openTab.mock.calls[0][0];
    expect(tab.id).toBe('agent-browse-agent_1');
    expect(tab.preferredWidth).toBe(0.5);
    expect(tab.keepMounted).toBe(true);
  });

  it('hands the panel the CONTROL node id when the event carried one (generic agent node)', () => {
    const r = probe('agent_1', { ...fullCoords, lastBrowserNodeId: 'call_abc123' });
    r.openLiveView?.();
    const content = openTab.mock.calls[0][0].content;
    render(content);
    const coords = (panelProps[0] as { liveCoords?: { nodeId?: string } }).liveCoords;
    expect(coords?.nodeId).toBe('call_abc123');
  });

  it('falls back to the builder node id as control address (dedicated browser_agent node)', () => {
    const r = probe('browser_1', fullCoords);
    r.openLiveView?.();
    const content = openTab.mock.calls[0][0].content;
    render(content);
    const coords = (panelProps[0] as { liveCoords?: { nodeId?: string } }).liveCoords;
    expect(coords?.nodeId).toBe('browser_1');
  });
});
