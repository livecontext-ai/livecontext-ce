/**
 * @vitest-environment jsdom
 *
 * Editing a queued message now happens in a modal (not inline): clicking the
 * message opens the edit modal, Save commits the new content via onEditContent,
 * and Cancel closes without committing.
 */
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';

vi.mock('next-intl', () => ({
  // Passthrough: t('editTitle') -> 'editTitle', t('chat.queue.edit') -> 'chat.queue.edit'
  useTranslations: () => (key: string) => key,
}));

import { QueuedMessageBar } from '../QueuedMessageBar';
import type { QueuedMessage } from '@/lib/stores/message-queue-store';

function makeMessage(overrides: Partial<QueuedMessage> = {}): QueuedMessage {
  return {
    id: 'msg-1',
    content: 'hello world',
    attachments: [],
    queuedAt: Date.now(),
    ...overrides,
  };
}

function renderBar(onEditContent = vi.fn()) {
  render(
    <QueuedMessageBar
      messages={[makeMessage()]}
      onRemove={vi.fn()}
      onEditContent={onEditContent}
      onSendNow={vi.fn()}
      onReorder={vi.fn()}
    />,
  );
  return { onEditContent };
}

describe('QueuedMessageBar edit modal', () => {
  it('opens the edit modal when the queued message is clicked', () => {
    renderBar();

    // Modal not mounted initially
    expect(screen.queryByText('editTitle')).toBeNull();

    fireEvent.click(screen.getByText('hello world'));

    // Modal title + prefilled textarea now visible
    expect(screen.getByText('editTitle')).toBeTruthy();
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('hello world');
  });

  it('opens the edit modal from the explicit pencil button', () => {
    renderBar();

    expect(screen.queryByText('editTitle')).toBeNull();

    // Both the message text and the pencil button carry the edit title; pick the button.
    const pencilButton = screen
      .getAllByTitle('chat.queue.edit')
      .find((el) => el.tagName === 'BUTTON');
    expect(pencilButton).toBeTruthy();

    fireEvent.click(pencilButton!);

    expect(screen.getByText('editTitle')).toBeTruthy();
  });

  it('commits the edited (trimmed) content via onEditContent on Save', () => {
    const { onEditContent } = renderBar();

    fireEvent.click(screen.getByText('hello world'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '  updated text  ' } });
    fireEvent.click(screen.getByText('save'));

    expect(onEditContent).toHaveBeenCalledTimes(1);
    expect(onEditContent).toHaveBeenCalledWith('msg-1', 'updated text');
    // Modal closes after saving
    expect(screen.queryByText('editTitle')).toBeNull();
  });

  it('disables Save when the content is emptied', () => {
    renderBar();

    fireEvent.click(screen.getByText('hello world'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '   ' } });

    expect((screen.getByText('save').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('does not commit and closes the modal on Cancel', () => {
    const { onEditContent } = renderBar();

    fireEvent.click(screen.getByText('hello world'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'discard me' } });
    fireEvent.click(screen.getByText('cancel'));

    expect(onEditContent).not.toHaveBeenCalled();
    expect(screen.queryByText('editTitle')).toBeNull();
  });
});
