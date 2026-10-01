/**
 * @vitest-environment jsdom
 *
 * The AI Chat tab is registered closed on every page, except the page a reload left while the
 * chat was on screen: there it comes back open, so the conversation that was waiting (for an
 * account connected through an OAuth round trip, typically) is on screen and can carry on.
 */
import * as React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/app/ChatPanelContent', () => ({ ChatPanelContent: () => null }));

import { AI_CHAT_TAB_ID, registerAiChatTab } from '@/lib/sidePanel/openAiChatTab';
import type { SidePanelTab } from '@/contexts/SidePanelContext';

const ON_SCREEN_KEY = 'lc.sidePanel.onScreen:/app/tables/5';

function registrar() {
  return { addTab: vi.fn<(tab: SidePanelTab) => void>(), openTab: vi.fn<(tab: SidePanelTab) => void>() };
}

beforeEach(() => sessionStorage.clear());

describe('registerAiChatTab after a reload', () => {
  it('registers the tab closed on an ordinary load', () => {
    const panel = registrar();

    registerAiChatTab(panel, '/app/tables/5');

    expect(panel.addTab).toHaveBeenCalledOnce();
    expect(panel.openTab).not.toHaveBeenCalled();
  });

  it('regression: reopens the chat on the page a reload left while it was on screen', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, AI_CHAT_TAB_ID);
    const panel = registrar();

    registerAiChatTab(panel, '/fr/app/tables/5');

    expect(panel.openTab).toHaveBeenCalledOnce();
    expect(panel.openTab.mock.calls[0][0].id).toBe(AI_CHAT_TAB_ID);
    expect(panel.addTab).not.toHaveBeenCalled();
  });

  it('another page, or another chat marked on this one, keeps it closed', () => {
    sessionStorage.setItem('lc.sidePanel.onScreen:/app/tables/6', AI_CHAT_TAB_ID);
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');
    const panel = registrar();

    registerAiChatTab(panel, '/app/tables/5');

    expect(panel.addTab).toHaveBeenCalledOnce();
    expect(panel.openTab).not.toHaveBeenCalled();
  });

  it('spends the mark: a later registration on that page, without a reload, keeps it closed', () => {
    // A mark left by a round trip that never came back must not reopen the chat on every visit.
    sessionStorage.setItem(ON_SCREEN_KEY, AI_CHAT_TAB_ID);
    registerAiChatTab(registrar(), '/app/tables/5');
    const later = registrar();

    registerAiChatTab(later, '/app/tables/5');

    expect(later.openTab).not.toHaveBeenCalled();
    expect(later.addTab).toHaveBeenCalledOnce();
  });

  it('a registrar with no openTab still registers the tab', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, AI_CHAT_TAB_ID);
    const addTab = vi.fn();

    registerAiChatTab({ addTab }, '/app/tables/5');

    expect(addTab).toHaveBeenCalledOnce();
  });
});
