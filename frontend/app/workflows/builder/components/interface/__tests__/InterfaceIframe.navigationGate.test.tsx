/**
 * @vitest-environment jsdom
 *
 * Parent side of the external-link gate. The injected NAVIGATION_GATE_SCRIPT posts a
 * 'navigation-request' from inside the sandboxed iframe; InterfaceIframe turns that into a
 * confirmation modal and, on confirm, opens the URL in a new tab. The source check ensures a
 * foreign window cannot drive the modal.
 *
 * Note: the equality guard reads the SAME iframe.contentWindow that the test reads, so these
 * tests hold whether or not jsdom populates a contentWindow for a srcdoc iframe.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Disable run-mode file resolution (it would hit the file service).
vi.mock('../useInterfaceFileUrls', () => ({
  useInterfaceFileUrls: () => ({ resolveFileUrl: (u: string) => u }),
}));

vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { uploadFile: vi.fn() },
}));

import { InterfaceIframe } from '../InterfaceIframe';

function postNavigationRequest(iframe: HTMLIFrameElement, url: unknown) {
  act(() => {
    window.dispatchEvent(
      new MessageEvent('message', {
        data: { type: 'navigation-request', url },
        source: iframe.contentWindow,
      })
    );
  });
}

describe('InterfaceIframe external-link gate', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows the confirmation modal when the iframe posts a navigation-request', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hi</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;

    expect(screen.queryByText('https://example.com/page')).toBeNull();
    postNavigationRequest(iframe, 'https://example.com/page');

    expect(screen.getByText('title')).toBeTruthy();
    expect(screen.getByText('openAction')).toBeTruthy();
    expect(screen.getByText('https://example.com/page')).toBeTruthy();
  });

  it('opens the URL in a new tab with noopener on confirm, then closes', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const { container } = render(<InterfaceIframe htmlTemplate="<div/>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;

    postNavigationRequest(iframe, 'https://example.com/go');
    act(() => {
      fireEvent.click(screen.getByText('openAction'));
    });

    expect(openSpy).toHaveBeenCalledWith('https://example.com/go', '_blank', 'noopener,noreferrer');
    expect(screen.queryByText('https://example.com/go')).toBeNull();
  });

  it('does not open anything and closes on cancel', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const { container } = render(<InterfaceIframe htmlTemplate="<div/>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;

    postNavigationRequest(iframe, 'https://example.com/go');
    act(() => {
      fireEvent.click(screen.getByText('cancel'));
    });

    expect(openSpy).not.toHaveBeenCalled();
    expect(screen.queryByText('https://example.com/go')).toBeNull();
  });

  it('ignores navigation-request messages from a foreign window (source check)', () => {
    render(<InterfaceIframe htmlTemplate="<div/>" mode="edit" />);
    act(() => {
      window.dispatchEvent(
        new MessageEvent('message', {
          data: { type: 'navigation-request', url: 'https://evil.com' },
          source: window, // not this iframe's contentWindow
        })
      );
    });
    expect(screen.queryByText('https://evil.com')).toBeNull();
  });
});
