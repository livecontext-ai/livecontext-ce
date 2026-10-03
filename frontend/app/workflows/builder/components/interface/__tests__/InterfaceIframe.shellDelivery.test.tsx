/**
 * @vitest-environment jsdom
 *
 * LC-027 CASA E3. `InterfaceIframe` used to hand publisher/agent-authored HTML to the browser
 * via `srcDoc`. That inherits the embedder's CSP, which is why the app's CSP could never enforce
 * a strict script-src. Interfaces that keep scripts (`removeScripts` false, the default) now
 * navigate to the `/interface-frame` shell and hand it the rendered HTML over postMessage
 * instead - this suite pins that handshake. `removeScripts=true` surfaces are covered by
 * InterfaceIframe.navigationGate.test.tsx and friends, unchanged (still `srcDoc`).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
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

function ready(iframe: HTMLIFrameElement) {
  act(() => {
    window.dispatchEvent(
      new MessageEvent('message', { data: { type: 'interface-frame-ready' }, source: iframe.contentWindow }),
    );
  });
}

describe('InterfaceIframe shell delivery (removeScripts=false, the default)', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('points the iframe at the shell route instead of using srcDoc', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hi</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe.getAttribute('src')).toBe('/interface-frame');
    expect(iframe.hasAttribute('srcdoc')).toBe(false);
  });

  it('never grants allow-same-origin (opaque origin preserved, same invariant as srcDoc mode)', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div/>" mode="edit" sandbox="allow-scripts allow-forms" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe.getAttribute('sandbox')).not.toContain('allow-same-origin');
  });

  it('queues the rendered HTML and posts it only after the shell announces ready', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hello</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const postSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    expect(postSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html' }),
      '*',
    );

    ready(iframe);

    expect(postSpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html', html: expect.stringContaining('hello') }),
      '*',
    );
  });

  // Regression (CE e2e, CE-IFACE-DETAIL-CAROUSEL-UI-002): the shell's document.open() erases its
  // own message listener, so a second payload posted to the SAME document was silently dropped
  // and the interface stayed on its first render ("[title]" placeholders, never the row data that
  // arrived a moment later). jsdom does not model that erasure, which is why the previous version
  // of this test ("delivers a fresh payload without reloading the shell") passed on the bug.
  it('reloads the shell for a content change after a delivery, and posts only after the new ready', () => {
    const { container, rerender } = render(<InterfaceIframe htmlTemplate="<div>first</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    ready(iframe);
    const postSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');
    const setAttributeSpy = vi.spyOn(iframe, 'setAttribute');

    rerender(<InterfaceIframe htmlTemplate="<div>second</div>" mode="edit" />);

    expect(setAttributeSpy).toHaveBeenCalledWith('src', '/interface-frame');
    expect(postSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html' }),
      '*',
    );

    // jsdom hands the navigated iframe a new window object (a browser keeps the WindowProxy);
    // what matters is that the payload goes to the shell that just announced ready.
    const freshPostSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');
    ready(iframe);

    expect(postSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html' }),
      '*',
    );
    expect(freshPostSpy).toHaveBeenCalledTimes(1);
    expect(freshPostSpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html', html: expect.stringContaining('second') }),
      '*',
    );
  });

  it('a change that arrives BEFORE the first ready is simply queued: one shell load, latest content', () => {
    const { container, rerender } = render(<InterfaceIframe htmlTemplate="<div>placeholder</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const postSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');
    const setAttributeSpy = vi.spyOn(iframe, 'setAttribute');

    rerender(<InterfaceIframe htmlTemplate="<div>resolved</div>" mode="edit" />);
    ready(iframe);

    expect(setAttributeSpy).not.toHaveBeenCalledWith('src', expect.anything());
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(postSpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html', html: expect.stringContaining('resolved') }),
      '*',
    );
  });

  it('the reloaded shell load is not taken for the interface load until the new content is posted', () => {
    const onLoad = vi.fn();
    const { container, rerender } = render(<InterfaceIframe htmlTemplate="<div>a</div>" mode="edit" onLoad={onLoad} />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    ready(iframe);
    act(() => { iframe.dispatchEvent(new Event('load')); });
    expect(onLoad).toHaveBeenCalledTimes(1);

    rerender(<InterfaceIframe htmlTemplate="<div>b</div>" mode="edit" onLoad={onLoad} />);
    act(() => { iframe.dispatchEvent(new Event('load')); }); // the fresh, empty shell
    expect(onLoad).toHaveBeenCalledTimes(1);

    ready(iframe);
    act(() => { iframe.dispatchEvent(new Event('load')); }); // the written interface
    expect(onLoad).toHaveBeenCalledTimes(2);
  });

  it('ignores an interface-frame-ready message from a foreign window (source check)', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hello</div>" mode="edit" />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    const postSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    act(() => {
      window.dispatchEvent(
        new MessageEvent('message', { data: { type: 'interface-frame-ready' }, source: window }),
      );
    });

    expect(postSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ type: 'interface-frame-html' }),
      '*',
    );
  });

  it('the shell native load event is ignored for fade-in/onLoad until content has actually been delivered', () => {
    const onLoad = vi.fn();
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hi</div>" mode="edit" onLoad={onLoad} />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;

    // The shell's OWN (empty) document load - before any content has been posted.
    act(() => {
      iframe.dispatchEvent(new Event('load'));
    });
    expect(onLoad).not.toHaveBeenCalled();

    // Now the handshake completes and content is delivered.
    ready(iframe);
    act(() => {
      iframe.dispatchEvent(new Event('load'));
    });
    expect(onLoad).toHaveBeenCalledTimes(1);
  });
});

describe('InterfaceIframe removeScripts=true still uses srcDoc (nothing to exempt from a strict CSP)', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('keeps srcDoc and never points at the shell route', () => {
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hi</div>" mode="edit" removeScripts />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe.hasAttribute('srcdoc')).toBe(true);
    expect(iframe.getAttribute('src')).toBeNull();
  });

  it('does not gate onLoad on shell delivery (fires immediately on the srcDoc load, as before)', () => {
    const onLoad = vi.fn();
    const { container } = render(<InterfaceIframe htmlTemplate="<div>hi</div>" mode="edit" removeScripts onLoad={onLoad} />);
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    act(() => {
      iframe.dispatchEvent(new Event('load'));
    });
    expect(onLoad).toHaveBeenCalledTimes(1);
  });
});
