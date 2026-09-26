/**
 * @vitest-environment jsdom
 *
 * Bug: the chat's interface preview showed a scrollbar nobody could use. A
 * transparent click-catching layer covered the whole iframe (so a click opened
 * the side panel), and it swallowed every wheel event and scrollbar drag. The
 * iframe must be reachable, and opening the panel goes through a button instead.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const openTab = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getInterface: vi.fn(async () => ({ id: 'iface-1', name: 'Leads', htmlTemplate: '<div>tall page</div>' })),
    renderInterfaceWithDatasource: vi.fn(),
    deleteInterface: vi.fn(),
  },
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ isForward: false, activeTabId: null, openTab, removeTab: vi.fn(), close: vi.fn() }),
}));
vi.mock('@/components/InterfacePreview', () => ({
  InterfacePreview: () => <iframe data-testid="preview-iframe" title="preview" />,
}));
vi.mock('@/components/app/InterfacePanelContent', () => ({ InterfacePanelContent: () => null }));
vi.mock('@/components/chat/PreviewActionMenu', () => ({ PreviewActionMenu: () => null, ActionIcons: {} }));

import { InterfacePreviewBlock } from '@/components/chat/InterfacePreviewBlock';

describe('InterfacePreviewBlock - the preview scrolls', () => {
  afterEach(() => {
    cleanup();
    openTab.mockReset();
  });

  it('leaves nothing stacked over the iframe that would catch the wheel', async () => {
    render(<InterfacePreviewBlock interfaceId="iface-1" />);
    const iframe = await screen.findByTestId('preview-iframe');

    // The preview box's only full-size overlay candidates are its direct children:
    // none may be an empty absolute inset-0 layer.
    const box = iframe.parentElement!;
    const blockers = Array.from(box.children).filter(
      (el) => el !== iframe && el.className.includes('inset-0') && el.childElementCount === 0,
    );
    expect(blockers).toHaveLength(0);
  });

  it('keeps the hidden Open button out of the way of taps on the interface', async () => {
    render(<InterfacePreviewBlock interfaceId="iface-1" />);
    await screen.findByTestId('preview-iframe');

    expect(screen.getByRole('button', { name: 'open' }).className).toContain('pointer-events-none');
  });

  it('still opens the side panel from the Open button', async () => {
    const { container } = render(<InterfacePreviewBlock interfaceId="iface-1" />);
    await screen.findByTestId('preview-iframe');
    fireEvent.mouseEnter(container.firstElementChild!);
    expect(screen.getByRole('button', { name: 'open' }).className).not.toContain('pointer-events-none');

    fireEvent.click(screen.getByRole('button', { name: 'open' }));

    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab.mock.calls[0][0]).toMatchObject({ id: 'interface-iface-1', label: 'Leads' });
  });
});
