// @vitest-environment jsdom
/**
 * A note in a run is read, not edited: no resize handle, no double-click editor, no
 * delete/duplicate actions. A note whose node is selected is highlighted.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';
import type { NodeProps } from 'reactflow';
import type { BuilderNodeData } from '../../../types';

const mode = { isRunMode: false };
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mode }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../ResizableNodeWrapper', () => ({
  ResizableNodeWrapper: ({ enabled }: { enabled: boolean }) => <div data-testid="resizer" data-enabled={String(enabled)} />,
}));
vi.mock('../shared', () => ({
  useHoverVisibility: () => ({ targetRef: { current: null }, isVisible: true, show: () => {} }),
  NodeActionButtons: ({ isVisible }: { isVisible: boolean }) => (isVisible ? <div data-testid="actions" /> : null),
}));

import { NoteNode } from '../NoteNode';

function renderNote(data: Partial<BuilderNodeData>, selected = false) {
  const props = {
    id: 'n',
    data: { id: 'n', label: 'Why', kind: 'action', noteText: 'Because.', ...data } as BuilderNodeData,
    selected,
  } as unknown as NodeProps<BuilderNodeData>;
  return render(<NoteNode {...props} />);
}

afterEach(() => {
  cleanup();
  mode.isRunMode = false;
});

describe('NoteNode', () => {
  it('can be resized, edited and deleted in the editor', () => {
    const { getByTestId, getByText, container } = renderNote({});
    expect(getByTestId('resizer').dataset.enabled).toBe('true');
    expect(getByTestId('actions')).toBeTruthy();
    fireEvent.doubleClick(getByText('Because.'));
    expect(container.querySelector('textarea')).not.toBeNull();
  });

  it('is read-only in a run: no resize, no editor, no actions', () => {
    mode.isRunMode = true;
    const { getByTestId, queryByTestId, getByText, container } = renderNote({});
    expect(getByTestId('resizer').dataset.enabled).toBe('false');
    expect(queryByTestId('actions')).toBeNull();
    fireEvent.doubleClick(getByText('Because.'));
    expect(container.querySelector('textarea')).toBeNull();
  });

  it('shows the translated hint on an empty note in the editor, and nothing in a run', () => {
    expect(renderNote({ noteText: '' }).getByText('note.emptyText')).toBeTruthy();
    cleanup();
    mode.isRunMode = true;
    expect(renderNote({ noteText: '' }).queryByText('note.emptyText')).toBeNull();
  });

  it('is highlighted when the node it explains is selected', () => {
    const plain = renderNote({}).container.firstElementChild as HTMLElement;
    expect(plain.style.boxShadow).toBe('');
    cleanup();
    const focused = renderNote({ noteFocused: true }).container.firstElementChild as HTMLElement;
    expect(focused.style.boxShadow).not.toBe('');
  });
});
