// @vitest-environment jsdom
/**
 * The in-chat live card and the post-run visualize card must open the SAME
 * side-panel tab (one surface: live during the run, final page after), not two
 * different ones. This pins the unification: the tab id is keyed on the SESSION
 * id (agent-browse-{sessionId}), matching AgentBrowseVisualizeCard + the
 * AppHeader auto-open.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

const openTab = vi.fn();
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    isOpen: false,
    activeTabId: null,
    openTab,
    removeTab: vi.fn(),
    close: vi.fn(),
  }),
}));
// Stub the panel content so we don't pull its CDP/API transitive deps.
vi.mock('@/components/app/AgentBrowsePanelContent', () => ({
  AgentBrowsePanelContent: () => null,
}));

import { AgentBrowseLivePreview } from '../AgentBrowseLivePreview';

const messages = { agentBrowse: { cardDefaultTitle: 'Browser agent session', viewLiveBrowser: 'View live browser' } };

const session = {
  sessionId: 'ses_abc',
  cdpToken: 'tok',
  cdpWsUrl: 'ws://internal/cdp/ses_abc',
  currentUrl: 'https://example.com',
  runId: 'run_1',
  nodeId: 'node_1',
};

describe('AgentBrowseLivePreview - tab unification', () => {
  beforeEach(() => openTab.mockReset());

  it('opens the side-panel tab keyed on the SESSION id (not the tool id)', () => {
    render(
      <NextIntlClientProvider locale="en" messages={messages as any} onError={() => {}}>
        <AgentBrowseLivePreview toolId="tool_xyz" session={session} />
      </NextIntlClientProvider>,
    );
    fireEvent.click(screen.getByText('https://example.com'));
    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab.mock.calls[0][0].id).toBe('agent-browse-ses_abc');
  });
});
