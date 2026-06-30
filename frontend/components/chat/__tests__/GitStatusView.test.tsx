/**
 * @vitest-environment jsdom
 *
 * GitStatusView - clean branch + status-badge rendering of a repo(git_status) result.
 * Covers branch/ahead/behind display, porcelain-code classification into badges, and
 * the clean-tree empty state.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params && 'count' in params ? `${params.count} ${key}` : key,
}));
vi.mock('lucide-react', () => ({
  GitBranch: () => null, ArrowUp: () => null, ArrowDown: () => null,
}));

import GitStatusView from '../GitStatusView';

describe('GitStatusView', () => {
  it('shows the branch, change count, and each changed file', () => {
    const { container } = render(
      <GitStatusView status={{ branch: 'dev', ahead: 1, behind: 2, files: [
        { path: 'src/a.ts', status: 'M' },
        { path: 'b.txt', status: '??' },
      ] }} />,
    );
    expect(container.textContent).toContain('dev');
    expect(container.textContent).toContain('src/a.ts');
    expect(container.textContent).toContain('b.txt');
    expect(container.textContent).toContain('2 changes');
  });

  it('maps porcelain codes to the right badge label', () => {
    const { container } = render(
      <GitStatusView status={{ branch: 'main', files: [
        { path: 'added.ts', status: 'A' },
        { path: 'gone.ts', status: 'D' },
        { path: 'untracked.ts', status: '??' },
      ] }} />,
    );
    // The badge label sits in a small fixed-width span next to each path.
    const badges = Array.from(container.querySelectorAll('span[title]')).map((e) => e.textContent);
    expect(badges).toContain('A');
    expect(badges).toContain('D');
    expect(badges).toContain('U'); // untracked → U
  });

  it('shows the clean state when there are no changed files', () => {
    const { container } = render(<GitStatusView status={{ branch: 'dev', files: [] }} />);
    expect(container.textContent).toContain('clean');
  });

  it('classifies conflict, renamed and copied codes', () => {
    const { container } = render(
      <GitStatusView status={{ branch: 'dev', files: [
        { path: 'conflict.ts', status: 'UU' },
        { path: 'renamed.ts', status: 'R' },
        { path: 'copied.ts', status: 'C' },
      ] }} />,
    );
    const badges = Array.from(container.querySelectorAll('span[title]')).map((e) => e.textContent);
    expect(badges).toContain('!'); // conflict
    expect(badges).toContain('R');
    expect(badges).toContain('C');
  });

  it('renders ahead/behind counts', () => {
    const { container } = render(
      <GitStatusView status={{ branch: 'dev', ahead: 4, behind: 7, files: [{ path: 'a.ts', status: 'M' }] }} />,
    );
    expect(container.textContent).toContain('4');
    expect(container.textContent).toContain('7');
  });
});
