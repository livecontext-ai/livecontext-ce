/**
 * @vitest-environment jsdom
 *
 * DiffView - the red/green unified-diff card rendered for repo edit/write/diff and
 * interface patch tool results. Covers the unified-diff parser (header stripping,
 * +/- classification) and the rendered output (added lines green, removed lines red,
 * +N/-N header counts, multi-file listing).
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params && 'count' in params ? `${params.count} ${key}` : key,
}));
vi.mock('lucide-react', () => ({
  ChevronDown: () => null, ChevronRight: () => null, FileText: () => null,
}));

import DiffView, { parseUnifiedDiff } from '../DiffView';

const UNIFIED = [
  'Index: a/app.js',
  '===================================================================',
  '--- a/app.js',
  '+++ b/app.js',
  '@@ -1,2 +1,2 @@',
  ' const a = 1;',
  '-const greeting = "hello world";',
  '+const greeting = "hello repo";',
].join('\n');

describe('parseUnifiedDiff', () => {
  it('strips file-meta headers and classifies add/del/context/hunk rows', () => {
    const rows = parseUnifiedDiff(UNIFIED);
    expect(rows.find((r) => r.type === 'hunk')?.text).toMatch(/^@@/);
    expect(rows.filter((r) => r.type === 'add').map((r) => r.text)).toEqual(['const greeting = "hello repo";']);
    expect(rows.filter((r) => r.type === 'del').map((r) => r.text)).toEqual(['const greeting = "hello world";']);
    expect(rows.filter((r) => r.type === 'ctx').map((r) => r.text)).toEqual(['const a = 1;']);
    // No '---'/'+++'/'Index:'/'===' lines survive as content.
    expect(rows.some((r) => r.text.startsWith('---') || r.text.startsWith('+++') || r.text.startsWith('==='))).toBe(false);
  });

  it('treats only +++/--- as headers, not +/- content lines', () => {
    const rows = parseUnifiedDiff('@@ -1 +1 @@\n-old\n+new');
    expect(rows.filter((r) => r.type === 'add')).toHaveLength(1);
    expect(rows.filter((r) => r.type === 'del')).toHaveLength(1);
  });
});

describe('DiffView render', () => {
  const diff = {
    files: [{ path: 'app.js', status: 'modified' as const, language: 'javascript', additions: 1, deletions: 1, unifiedDiff: UNIFIED }],
  };

  it('shows the file path and +N / −N counts', () => {
    const { container } = render(<DiffView diff={diff} />);
    expect(container.textContent).toContain('app.js');
    expect(container.textContent).toContain('+1');
    expect(container.textContent).toContain('−1'); // U+2212 minus
  });

  it('renders added lines green and removed lines red', () => {
    const { container } = render(<DiffView diff={diff} />);
    const green = Array.from(container.querySelectorAll('[class*="green"]')).map((e) => e.textContent).join(' ');
    const red = Array.from(container.querySelectorAll('[class*="red"]')).map((e) => e.textContent).join(' ');
    expect(green).toContain('hello repo');
    expect(red).toContain('hello world');
  });

  it('renders nothing when there are no files', () => {
    const { container } = render(<DiffView diff={{ files: [] }} />);
    expect(container.textContent).toBe('');
  });

  it('lists a count header for multi-file diffs', () => {
    const { container } = render(<DiffView diff={{ files: [diff.files[0], { ...diff.files[0], path: 'b.js' }] }} />);
    expect(container.textContent).toContain('2 files');
  });

  it('collapses a large diff by default (content hidden, counts still shown)', () => {
    const body = Array.from({ length: 70 }, (_, i) => `+added line ${i}`).join('\n');
    const big = `@@ -1,1 +1,70 @@\n${body}\n`;
    const { container } = render(
      <DiffView diff={{ files: [{ path: 'big.ts', status: 'modified', additions: 70, deletions: 0, unifiedDiff: big }] }} />,
    );
    expect(container.textContent).toContain('big.ts');
    expect(container.textContent).toContain('+70');
    expect(container.textContent).not.toContain('added line 5'); // collapsed → rows not rendered
  });

  it('renders the truncated note when a file diff was capped', () => {
    const { container } = render(
      <DiffView diff={{ files: [{ path: 'x.ts', status: 'modified', additions: 1, deletions: 0, unifiedDiff: '@@ -0,0 +1 @@\n+x', truncated: true }] }} />,
    );
    expect(container.textContent).toContain('truncated');
  });

  it('shows "old → new" in the header for a renamed file', () => {
    const { container } = render(
      <DiffView diff={{ files: [{ path: 'new.ts', oldPath: 'old.ts', status: 'renamed', additions: 0, deletions: 0, unifiedDiff: '' }] }} />,
    );
    expect(container.textContent).toContain('old.ts → new.ts');
  });
});
