/**
 * @vitest-environment jsdom
 *
 * What a chat living IN the side panel builds opens as a tab beside the conversation, never in
 * front of it, and the tab shows the agent is working in it until the turn ends.
 *
 * Before: the AI Chat tab lives on pages that are not chat pages, where AppHeader dropped every
 * build marker, so a workflow built from the panel opened nothing at all; and a presentation
 * opened in front, taking the reader away from the conversation they were typing in.
 */
import * as React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/tables/5' }));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));

import { SidePanelProvider, useSidePanel, type SidePanelContextValue, type SidePanelTab } from '@/contexts/SidePanelContext';
import {
  autoOpenPlacement,
  isSidePanelConversation,
  markSidePanelConversation,
  opensInBackground,
  openTabInBackground,
  resetSidePanelConversationsForTest,
  tabOpener,
  useSettleAgentWorkingTabs,
  type AutoOpenPage,
} from '@/lib/sidePanel/sidePanelConversations';

const TABLE_PAGE: AutoOpenPage = { isChatPage: false, pageConversationId: null, workflowPageId: null, applicationPageId: null };
const CHAT_PAGE: AutoOpenPage = { isChatPage: true, pageConversationId: 'page-conv', workflowPageId: null, applicationPageId: null };

beforeEach(() => resetSidePanelConversationsForTest());
afterEach(cleanup);

describe('side-panel conversations', () => {
  it('knows only the conversations a side-panel chat marked', () => {
    markSidePanelConversation('panel-conv');
    markSidePanelConversation(null);

    expect(isSidePanelConversation('panel-conv')).toBe(true);
    expect(isSidePanelConversation('other')).toBe(false);
    expect(isSidePanelConversation(undefined)).toBe(false);
  });

  it('a side-panel conversation shown full page is a full-page chat again', () => {
    markSidePanelConversation('panel-conv');

    expect(opensInBackground({ conversationId: 'panel-conv' }, null)).toBe(true);
    expect(opensInBackground({ conversationId: 'panel-conv' }, 'panel-conv')).toBe(false);
  });

  it('a click is never placed in the background, whoever produced the row', () => {
    markSidePanelConversation('panel-conv');

    expect(opensInBackground({ conversationId: 'panel-conv', userInitiated: true }, null)).toBe(false);
  });
});

describe('autoOpenPlacement', () => {
  beforeEach(() => markSidePanelConversation('panel-conv'));

  it('regression: a workflow built from the AI Chat tab on a table page opens beside the chat', () => {
    // The whole bug: off a chat page, this marker used to be dropped.
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf1', conversationId: 'panel-conv' }, TABLE_PAGE))
      .toBe('background');
  });

  it('opens every build type beside the chat, the interface included', () => {
    for (const type of ['workflow', 'table', 'datasource', 'interface', 'application', 'agent', 'agent_browse']) {
      expect(autoOpenPlacement({ type, id: 'x', conversationId: 'panel-conv' }, TABLE_PAGE), type).toBe('background');
    }
  });

  it('beside a side-panel chat on a CHAT page too, without stealing the tab the reader is on', () => {
    expect(autoOpenPlacement({ type: 'table', id: 't1', conversationId: 'panel-conv' }, CHAT_PAGE)).toBe('background');
  });

  it('leaves the full-page chat as it was: in front, and only on chat pages', () => {
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf1', conversationId: 'page-conv' }, CHAT_PAGE)).toBe('front');
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf1', conversationId: 'elsewhere' }, TABLE_PAGE)).toBeNull();
    // A replay after a reconnect carries no conversation.
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf1' }, TABLE_PAGE)).toBeNull();
  });

  it('a full-page chat keeps its interface as a card: no tab unless clicked', () => {
    expect(autoOpenPlacement({ type: 'interface', id: 'i1', conversationId: 'page-conv' }, CHAT_PAGE)).toBeNull();
    expect(autoOpenPlacement({ type: 'interface', id: 'i1', userInitiated: true }, CHAT_PAGE)).toBe('front');
  });

  it('a click on a tool row opens in front on every page, which used to do nothing off chat pages', () => {
    expect(autoOpenPlacement({ type: 'table', id: 't1', userInitiated: true }, TABLE_PAGE)).toBe('front');
    expect(autoOpenPlacement({ type: 'table', id: 't1', userInitiated: true, conversationId: 'panel-conv' }, TABLE_PAGE))
      .toBe('front');
  });

  it('the workflow page never opens a second view of its own workflow or its runs', () => {
    const workflowPage: AutoOpenPage = { isChatPage: false, pageConversationId: null, workflowPageId: 'wf-page', applicationPageId: null };

    expect(autoOpenPlacement({ type: 'workflow', id: 'wf-page', conversationId: 'panel-conv' }, workflowPage)).toBeNull();
    expect(autoOpenPlacement({ type: 'workflow_run', id: 'wf-page', runId: 'r1', conversationId: 'panel-conv' }, workflowPage))
      .toBeNull();
    // Another workflow the same agent builds does open, beside the chat.
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf-other', conversationId: 'panel-conv' }, workflowPage))
      .toBe('background');
  });

  it('the chat of a workflow panel never opens a second view of the workflow its host shows', () => {
    // The application page's panel hosts the canvas of its workflow: building on it from that
    // chat must not stack a builder tab of the same workflow beside it.
    markSidePanelConversation('app-panel-conv', 'wf-host');
    const applicationPage: AutoOpenPage = { isChatPage: false, pageConversationId: null, workflowPageId: null, applicationPageId: 'pub-1' };

    expect(autoOpenPlacement({ type: 'workflow', id: 'wf-host', conversationId: 'app-panel-conv' }, applicationPage)).toBeNull();
    expect(autoOpenPlacement({ type: 'workflow_run', id: 'wf-host', runId: 'r1', conversationId: 'app-panel-conv' }, applicationPage))
      .toBeNull();
    expect(autoOpenPlacement({ type: 'workflow', id: 'wf-other', conversationId: 'app-panel-conv' }, applicationPage))
      .toBe('background');
  });

  it('the application page never opens a second view of its own application', () => {
    const applicationPage: AutoOpenPage = { isChatPage: false, pageConversationId: null, workflowPageId: null, applicationPageId: 'pub-1' };

    expect(autoOpenPlacement({ type: 'application', id: 'pub-1', conversationId: 'panel-conv' }, applicationPage)).toBeNull();
    expect(autoOpenPlacement({ type: 'application', id: 'pub-2', conversationId: 'panel-conv' }, applicationPage)).toBe('background');
  });

  it('a later mark without a host keeps the host it was recorded with', () => {
    markSidePanelConversation('app-panel-conv', 'wf-host');
    markSidePanelConversation('app-panel-conv');

    expect(autoOpenPlacement({ type: 'workflow', id: 'wf-host', conversationId: 'app-panel-conv' }, TABLE_PAGE)).toBeNull();
  });

  it('presentations are left to openPresentedView, except the run views of a full-page chat', () => {
    expect(autoOpenPlacement({ type: 'present_run', id: 'wf1', runId: 'r1', conversationId: 'panel-conv' }, CHAT_PAGE))
      .toBeNull();
    expect(autoOpenPlacement({ type: 'present_run', id: 'wf1', runId: 'r1', userInitiated: true }, TABLE_PAGE)).toBeNull();
    expect(autoOpenPlacement({ type: 'present_run', id: 'wf1', runId: 'r1', conversationId: 'page-conv' }, CHAT_PAGE))
      .toBe('front');
  });

  it('a marker without an id opens nothing', () => {
    expect(autoOpenPlacement({ type: 'workflow', id: '', conversationId: 'panel-conv' }, TABLE_PAGE)).toBeNull();
  });
});

// ── The panel side: a background tab is added, not brought forward, and settles ──

const panelRef: { current: SidePanelContextValue | null } = { current: null };
const streamingRef = { current: new Set<string>() };

function Harness({ streamingVersion }: { streamingVersion: number }) {
  const panel = useSidePanel();
  panelRef.current = panel;
  // A new function when streaming changes, as StreamingContext's isStreamingConversation is.
  const isStreaming = React.useCallback(
    (conversationId: string) => streamingRef.current.has(conversationId),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [streamingVersion],
  );
  useSettleAgentWorkingTabs(isStreaming, panel.updateTab);
  return null;
}

const tab = (id: string): SidePanelTab => ({ id, label: id, icon: <span />, content: <div>{id}</div> });
const tabById = (id: string) => panelRef.current?.tabs.find(t => t.id === id);

describe('openTabInBackground + useSettleAgentWorkingTabs', () => {
  function renderPanel(streamingVersion = 0) {
    const view = render(<SidePanelProvider><Harness streamingVersion={streamingVersion} /></SidePanelProvider>);
    act(() => { panelRef.current!.openTab(tab('ai-chat')); });
    return view;
  }

  it('adds the tab beside the chat: the chat stays the tab on screen', () => {
    streamingRef.current = new Set(['panel-conv']);
    renderPanel();

    act(() => { openTabInBackground(panelRef.current!, tab('workflow-wf1'), 'panel-conv'); });

    expect(panelRef.current?.activeTabId).toBe('ai-chat');
    expect(panelRef.current?.isOpen).toBe(true);
    expect(tabById('workflow-wf1')?.shimmer, 'shows the agent works there').toBe(true);
  });

  it('stops the shimmer once the conversation working in the tab stops streaming', () => {
    streamingRef.current = new Set(['panel-conv']);
    const view = renderPanel(1);
    act(() => { openTabInBackground(panelRef.current!, tab('datasource-t1'), 'panel-conv'); });
    expect(tabById('datasource-t1')?.shimmer).toBe(true);

    streamingRef.current = new Set();
    view.rerender(<SidePanelProvider><Harness streamingVersion={2} /></SidePanelProvider>);

    expect(tabById('datasource-t1')?.shimmer).toBe(false);
  });

  it('a marker that lands after the turn ended does not shimmer for good', () => {
    // The auto-open is debounced, so the last marker of a turn can flush after its last chunk.
    streamingRef.current = new Set();
    renderPanel();

    act(() => { openTabInBackground(panelRef.current!, tab('agent-a1'), 'panel-conv'); });

    expect(tabById('agent-a1')?.shimmer).toBe(false);
  });

  it('keeps shimmering a tab whose conversation still streams while another one ends', () => {
    streamingRef.current = new Set(['panel-conv', 'other-conv']);
    const view = renderPanel(1);
    act(() => {
      openTabInBackground(panelRef.current!, tab('workflow-a'), 'panel-conv');
      openTabInBackground(panelRef.current!, tab('workflow-b'), 'other-conv');
    });

    streamingRef.current = new Set(['panel-conv']);
    view.rerender(<SidePanelProvider><Harness streamingVersion={2} /></SidePanelProvider>);

    expect(tabById('workflow-a')?.shimmer).toBe(true);
    expect(tabById('workflow-b')?.shimmer).toBe(false);
  });
});

describe('tabOpener', () => {
  const panelSpy = () => ({ addTab: vi.fn(), openTab: vi.fn(), openTabDeferred: vi.fn() });

  it('beside a side-panel chat: added with the shimmer, never opened in front', () => {
    const panel = panelSpy();

    tabOpener(panel, { background: true, conversationId: 'panel-conv', isMobile: false })(tab('workflow-wf1'));

    expect(panel.addTab).toHaveBeenCalledWith(expect.objectContaining({ id: 'workflow-wf1', shimmer: true }));
    expect(panel.openTab).not.toHaveBeenCalled();
    expect(panel.openTabDeferred).not.toHaveBeenCalled();
  });

  it('in front on desktop, as a peek on mobile, exactly as before', () => {
    const desktop = panelSpy();
    const mobile = panelSpy();

    tabOpener(desktop, { background: false, conversationId: 'page-conv', isMobile: false })(tab('t1'));
    tabOpener(mobile, { background: false, conversationId: 'page-conv', isMobile: true })(tab('t1'));

    expect(desktop.openTab).toHaveBeenCalledOnce();
    expect(mobile.openTabDeferred).toHaveBeenCalledOnce();
    expect(desktop.addTab).not.toHaveBeenCalled();
  });

  it('a background placement without a conversation falls back to the front', () => {
    const panel = panelSpy();

    tabOpener(panel, { background: true, conversationId: undefined, isMobile: false })(tab('t1'));

    expect(panel.openTab).toHaveBeenCalledOnce();
  });
});
