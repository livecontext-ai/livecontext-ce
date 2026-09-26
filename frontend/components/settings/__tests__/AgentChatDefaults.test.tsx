// @vitest-environment jsdom
/**
 * AgentChatDefaults is the editor behind the Agents page "Settings" tab
 * (/app/agent?view=settings). It must edit the per-(user, workspace) chat defaults -
 * i.e. mount ChatConfigPanel with `userDefault`, NOT a conversation- or agent-scoped
 * panel - which is what makes the general chat configurable from there.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

const panelProps: Record<string, unknown>[] = [];
vi.mock('@/components/chat/ChatConfigPanel', () => ({
  ChatConfigPanel: (props: Record<string, unknown>) => {
    panelProps.push(props);
    return <div data-testid="chat-config-panel" />;
  },
}));
// Returns the FULLY qualified key so the assertions below pin the namespace too: a
// header wired to the wrong namespace still renders, just with English fallbacks.
vi.mock('next-intl', () => ({
  useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

import { AgentChatDefaults } from '../AgentChatDefaults';

afterEach(() => {
  panelProps.length = 0;
  cleanup();
});

describe('AgentChatDefaults', () => {
  it('edits the per-(user, workspace) defaults (userDefault target)', () => {
    render(<AgentChatDefaults />);
    expect(screen.getByTestId('chat-config-panel')).toBeTruthy();
    expect(panelProps).toHaveLength(1);
    expect(panelProps[0].userDefault).toBe(true);
    // A conversation or agent id would silently scope the edits to one chat instead.
    expect(panelProps[0].agentId).toBeUndefined();
    expect(panelProps[0].conversationId).toBeUndefined();
  });

  it('titles itself from settings.agentDefaults', () => {
    render(<AgentChatDefaults />);
    expect(screen.getByText('settings.agentDefaults.title')).toBeTruthy();
    expect(screen.getByText('settings.agentDefaults.subtitle')).toBeTruthy();
  });

  // The sibling tabs of the Agents page render an h2 at most, so an h1 here would make
  // the page heading level come and go with the tab.
  it('titles itself with an h2, never an h1', () => {
    const { container } = render(<AgentChatDefaults />);
    expect(container.querySelector('h1')).toBeNull();
    expect(container.querySelector('h2')?.textContent).toBe('settings.agentDefaults.title');
  });

  // The Agents page column is max-w-6xl; uncapped, the sliders and the 2-column number
  // grids of the panel stretch across the whole width and become hard to read.
  it('caps its width so the sliders and number grids stay readable', () => {
    const { container } = render(<AgentChatDefaults />);
    expect(container.querySelector('.max-w-4xl')).toBeTruthy();
  });
});
