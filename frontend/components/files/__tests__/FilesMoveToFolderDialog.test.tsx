// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, screen } from '@testing-library/react';
import { FilesMoveToFolderDialog } from '../FilesMoveToFolderDialog';
import type { MoveFolderRow } from '@/lib/files/moveFolderTree';

// next-intl: return readable labels; resolve the {count} param for the title.
vi.mock('next-intl', () => ({
  useTranslations: (ns: string) => (key: string, vars?: Record<string, unknown>) => {
    if (ns === 'common' && key === 'cancel') return 'Cancel';
    const labels: Record<string, string> = {
      moveToFolderTitle: `Move ${vars?.count ?? 0} items`,
      moveToRoot: 'Top level (root)',
      moveHere: 'Move here',
      noFoldersYet: 'No folders yet',
    };
    return labels[key] ?? key;
  },
}));

afterEach(() => cleanup());

const FOLDERS: MoveFolderRow[] = [
  { id: 'root-a', name: 'Root A', parentFolderId: null },
  { id: 'a-child', name: 'A child', parentFolderId: 'root-a' },
  { id: 'root-b', name: 'Root B', parentFolderId: null },
];

function renderDialog(props: Partial<React.ComponentProps<typeof FilesMoveToFolderDialog>> = {}) {
  const onMove = props.onMove ?? vi.fn().mockResolvedValue(undefined);
  const onClose = props.onClose ?? vi.fn();
  const utils = render(
    <FilesMoveToFolderDialog
      isOpen
      allFolders={FOLDERS}
      excludeFolderIds={new Set()}
      itemCount={3}
      onClose={onClose}
      onMove={onMove}
      {...props}
    />,
  );
  return { ...utils, onMove, onClose };
}

describe('FilesMoveToFolderDialog', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <FilesMoveToFolderDialog
        isOpen={false}
        allFolders={FOLDERS}
        excludeFolderIds={new Set()}
        itemCount={1}
        onClose={vi.fn()}
        onMove={vi.fn()}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('shows the root option and the top-level folders (collapsed by default)', () => {
    renderDialog();
    expect(screen.getByText('Top level (root)')).toBeTruthy();
    expect(screen.getByText('Root A')).toBeTruthy();
    expect(screen.getByText('Root B')).toBeTruthy();
    // A child is under Root A, which starts collapsed → not yet visible.
    expect(screen.queryByText('A child')).toBeNull();
  });

  it('renders the pluralized title from itemCount', () => {
    renderDialog({ itemCount: 3 });
    expect(screen.getByText('Move 3 items')).toBeTruthy();
  });

  it('Move button is disabled until a destination is chosen, then calls onMove with that folder id', () => {
    const { onMove } = renderDialog();
    const moveBtn = screen.getByRole('button', { name: 'Move here' });
    expect((moveBtn as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByText('Root B'));
    expect((moveBtn as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(moveBtn);
    expect(onMove).toHaveBeenCalledWith('root-b');
  });

  it('selecting the root option calls onMove with null', () => {
    const { onMove } = renderDialog();
    fireEvent.click(screen.getByText('Top level (root)'));
    fireEvent.click(screen.getByRole('button', { name: 'Move here' }));
    expect(onMove).toHaveBeenCalledWith(null);
  });

  it('expanding a parent reveals its child folder', () => {
    const { container } = renderDialog();
    // The first chevron button (inside the Root A row) toggles expansion.
    const chevrons = container.querySelectorAll('button button'); // nested expand buttons
    expect(chevrons.length).toBeGreaterThan(0);
    fireEvent.click(chevrons[0]);
    expect(screen.getByText('A child')).toBeTruthy();
  });

  it('excludes a folder being moved AND its subtree from the destinations', () => {
    // Moving Root A: neither Root A nor its child may be offered (only Root B + root remain).
    renderDialog({ excludeFolderIds: new Set(['root-a']) });
    expect(screen.queryByText('Root A')).toBeNull();
    expect(screen.queryByText('A child')).toBeNull();
    expect(screen.getByText('Root B')).toBeTruthy();
    expect(screen.getByText('Top level (root)')).toBeTruthy();
  });

  it('shows the empty hint when there are no selectable folders', () => {
    renderDialog({ allFolders: [] });
    expect(screen.getByText('No folders yet')).toBeTruthy();
  });

  it('shows a spinner instead of the tree while folders load', () => {
    const { container } = renderDialog({ loading: true });
    // The tree (root option) is not rendered while loading; a spinner is.
    expect(screen.queryByText('Top level (root)')).toBeNull();
    expect(container.querySelector('svg')).toBeTruthy();
  });
});
