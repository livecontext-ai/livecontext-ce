// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';
import { CodePreview } from '../CodePreview';

// i18n: return `namespace.key` so we can assert the copy button is wired to the
// common.copy/common.copied keys (not a hardcoded English string).
vi.mock('next-intl', () => ({
  useTranslations: (namespace?: string) => (key: string) => `${namespace}.${key}`,
}));

// Theme hook drives the Prism light/dark style; pin it so the render is deterministic.
vi.mock('@/hooks/useThemeSafely', () => ({
  useThemeSafely: () => ({ theme: 'light' as const }),
}));

afterEach(() => cleanup());

describe('CodePreview', () => {
  it('renders the code content syntax-highlighted (tokens concatenate back to the source)', () => {
    const code = 'const answer = 42;';
    const { container } = render(<CodePreview code={code} language="typescript" showLineNumbers />);
    // Prism splits the source across token <span>s, so assert on the flattened
    // textContent rather than a single text node.
    expect(container.textContent).toContain('answer');
    expect(container.textContent).toContain('42');
    // It actually tokenised (more than one span) rather than dumping a raw <pre>.
    expect(container.querySelectorAll('span').length).toBeGreaterThan(1);
  });

  it('labels the copy button via the shared common.copy i18n key (no hardcoded string)', () => {
    const { getByTitle } = render(<CodePreview code={'{"a":1}'} language="json" wrap />);
    expect(getByTitle('common.copy')).toBeTruthy();
  });

  it('copies the source to the clipboard and flips to the copied label on click', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const code = 'print("hi")';
    const { getByTitle, findByTitle } = render(<CodePreview code={code} language="python" />);
    fireEvent.click(getByTitle('common.copy'));
    expect(writeText).toHaveBeenCalledWith(code);
    // State flips to the "copied" key once the async write resolves.
    expect(await findByTitle('common.copied')).toBeTruthy();
  });

  it('does not throw on an invalid language id (falls back to a safe grammar)', () => {
    const { container } = render(<CodePreview code={'plain body'} language={'no spaces!'} />);
    expect(container.textContent).toContain('plain body');
  });
});
