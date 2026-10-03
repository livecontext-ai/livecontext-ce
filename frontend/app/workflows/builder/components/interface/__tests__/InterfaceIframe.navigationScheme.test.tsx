/**
 * @vitest-environment jsdom
 *
 * LC-076 regression: the parent frame called window.open() on whatever URL the sandboxed
 * iframe posted, with no scheme check. classifyAnchorNavigation blocks javascript: hrefs but
 * it runs INSIDE the iframe, so a hostile interface skips it by posting a navigation-request
 * of its own. The parent is not sandboxed, so window.open('javascript:...') would execute on
 * the app origin with the viewer's session.
 *
 * Pre-fix these tests fail: the modal appeared for every scheme, and clicking "open" called
 * window.open with the hostile URL.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('../useInterfaceFileUrls', () => ({
  useInterfaceFileUrls: () => ({ resolveFileUrl: (u: string) => u }),
}));

vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { uploadFile: vi.fn() },
}));

import { InterfaceIframe } from '../InterfaceIframe';
import { isOpenableNavigationUrl } from '../../../utils/interfaceHtmlUtils';

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

function renderIframe() {
  const { container } = render(<InterfaceIframe htmlTemplate="<div/>" mode="edit" />);
  return container.querySelector('iframe') as HTMLIFrameElement;
}

describe('InterfaceIframe navigation-request scheme allow-list', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  const hostile: Array<[string, string]> = [
    ['javascript:', 'javascript:alert(document.cookie)'],
    ['data:text/html', 'data:text/html,<script>alert(1)</script>'],
    ['blob:', 'blob:https://app.example.com/6f1a-4b2c-uuid'],
    ['vbscript:', 'vbscript:msgbox(1)'],
  ];

  it.each(hostile)('never opens a %s URL posted by the iframe', (_label, url) => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    postNavigationRequest(iframe, url);

    // The confirmation modal must not even be offered for a rejected scheme.
    expect(screen.queryByText(url)).toBeNull();
    expect(screen.queryByText('openAction')).toBeNull();
    expect(openSpy).not.toHaveBeenCalled();
  });

  it('never opens a hostile URL even if the confirm button is driven directly', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    // Queue a legitimate URL so the modal is mounted, then have the iframe try to swap in a
    // javascript: URL behind it. The swap must be rejected, and confirming must open the
    // legitimate URL only.
    postNavigationRequest(iframe, 'https://example.com/ok');
    postNavigationRequest(iframe, 'javascript:alert(1)');
    act(() => {
      fireEvent.click(screen.getByText('openAction'));
    });

    expect(openSpy).toHaveBeenCalledTimes(1);
    expect(openSpy).toHaveBeenCalledWith('https://example.com/ok', '_blank', 'noopener,noreferrer');
  });

  it('still opens an https URL (the guard is not a blanket block)', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    postNavigationRequest(iframe, 'https://example.com/page');
    act(() => {
      fireEvent.click(screen.getByText('openAction'));
    });

    expect(openSpy).toHaveBeenCalledWith('https://example.com/page', '_blank', 'noopener,noreferrer');
  });

  it('still opens a tel: URL (the in-frame classifier gates tel: links too)', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    postNavigationRequest(iframe, 'tel:+15551234567');
    act(() => {
      fireEvent.click(screen.getByText('openAction'));
    });

    expect(openSpy).toHaveBeenCalledWith('tel:+15551234567', '_blank', 'noopener,noreferrer');
  });

  it('still opens a mailto: URL', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    postNavigationRequest(iframe, 'mailto:someone@example.com');
    act(() => {
      fireEvent.click(screen.getByText('openAction'));
    });

    expect(openSpy).toHaveBeenCalledWith('mailto:someone@example.com', '_blank', 'noopener,noreferrer');
  });

  it('ignores a relative URL (unopenable from the parent, and never intended)', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    const iframe = renderIframe();

    postNavigationRequest(iframe, '/settings/api-keys');

    expect(screen.queryByText('openAction')).toBeNull();
    expect(openSpy).not.toHaveBeenCalled();
  });
});

describe('isOpenableNavigationUrl', () => {
  it.each(['https://example.com', 'http://example.com/a?b=1', 'mailto:a@example.com', 'tel:+15551234567', '  HTTPS://EXAMPLE.COM  '])(
    'accepts %s',
    (url) => {
      expect(isOpenableNavigationUrl(url)).toBe(true);
    },
  );

  it.each([
    'javascript:alert(1)',
    ' JaVaScRiPt:alert(1)',
    'data:text/html,x',
    'blob:https://a/b',
    '/relative',
    '//example.com',
    '',
    'not a url',
  ])('rejects %s', (url) => {
    expect(isOpenableNavigationUrl(url)).toBe(false);
  });

  it('rejects non-strings', () => {
    expect(isOpenableNavigationUrl(undefined)).toBe(false);
    expect(isOpenableNavigationUrl(null)).toBe(false);
    expect(isOpenableNavigationUrl({ toString: () => 'https://x' })).toBe(false);
  });
});
