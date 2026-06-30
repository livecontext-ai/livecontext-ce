// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react';

// next-intl → identity passthrough (labels render as their keys).
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

// Conversation API - the surface this component touches. Each fn is a spy so we
// can assert exactly WHEN a conversation is created vs merely found.
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    findWorkflowConversation: vi.fn(),
    createWorkflowConversation: vi.fn(),
    getRecentMessagesAsc: vi.fn(),
    addMessage: vi.fn(),
  },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn(), triggerSpecific: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { uploadFile: vi.fn() } }));

// MessageComposer → a tiny stand-in exposing the send action, so we can drive
// handleChatSubmit without pulling the composer's heavy internals.
vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: ({ onSendMessage }: { onSendMessage: (c?: string) => void }) => (
    <button type="button" data-testid="composer-send" onClick={() => onSendMessage('hello')}>send</button>
  ),
}));

import { conversationApi } from '@/lib/api/conversationApi';
import { TriggerPanel, type TriggerPanelConfig } from '../TriggerPanel';

const chatTrigger: TriggerPanelConfig = { triggerId: 'trigger:my_chat', triggerLabel: 'My Chat', type: 'chat' };

const find = () => vi.mocked(conversationApi.findWorkflowConversation);
const create = () => vi.mocked(conversationApi.createWorkflowConversation);
const getMsgs = () => vi.mocked(conversationApi.getRecentMessagesAsc);
const addMsg = () => vi.mocked(conversationApi.addMessage);

function renderPanel(extra?: Partial<React.ComponentProps<typeof TriggerPanel>>) {
  return render(
    <TriggerPanel
      isOpen
      onClose={() => {}}
      runId="run-1"
      workflowId="wf-1"
      triggerConfigs={[chatTrigger]}
      onExecuteTrigger={vi.fn(async () => [])}
      {...extra}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom has no scrollIntoView; the messages list auto-scrolls on mount.
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = vi.fn();
  getMsgs().mockResolvedValue([] as never);
  addMsg().mockResolvedValue(undefined as never);
  create().mockResolvedValue({ id: 'conv-new' } as never);
});
afterEach(() => cleanup());

describe('TriggerPanel - chat-trigger conversation lifecycle', () => {
  it('does NOT create a conversation just because the chat-trigger panel opened (find-only)', async () => {
    find().mockResolvedValue(null as never); // no existing conversation for this workflow
    renderPanel();

    await waitFor(() => expect(find()).toHaveBeenCalledWith('wf-1'));
    // Regression guard: pre-fix, this mount effect eagerly created an empty,
    // message-less workflow conversation (the reported bug). It must not.
    expect(create()).not.toHaveBeenCalled();
  });

  it('loads an existing conversation\'s messages without creating one', async () => {
    find().mockResolvedValue({ id: 'conv-existing' } as never);
    renderPanel();

    await waitFor(() => expect(getMsgs()).toHaveBeenCalledWith('conv-existing'));
    expect(create()).not.toHaveBeenCalled();
  });

  it('creates the conversation lazily only when the user actually sends a message, persisting the message before firing the trigger', async () => {
    find().mockResolvedValue(null as never);
    const onExecuteTrigger = vi.fn(async () => []);
    renderPanel({ onExecuteTrigger });

    await waitFor(() => expect(find()).toHaveBeenCalled());
    expect(create()).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('composer-send'));

    // First message → conversation is created NOW, the message is persisted,
    // and the trigger fires.
    await waitFor(() => expect(create()).toHaveBeenCalledWith('wf-1'));
    await waitFor(() =>
      expect(addMsg()).toHaveBeenCalledWith('conv-new', expect.objectContaining({ role: 'user', content: 'hello' })),
    );
    await waitFor(() => expect(onExecuteTrigger).toHaveBeenCalled());

    // Order matters: the user message must be saved to the conversation BEFORE
    // the trigger fires (so the response node can attach its reply to a thread
    // that already holds the prompt).
    expect(addMsg().mock.invocationCallOrder[0]).toBeLessThan(onExecuteTrigger.mock.invocationCallOrder[0]);
  });

  it('does not create a conversation when the find lookup fails (no eager fallback on error)', async () => {
    find().mockRejectedValue(new Error('network'));
    renderPanel();

    await waitFor(() => expect(find()).toHaveBeenCalledWith('wf-1'));
    // The catch branch must NOT fall back to creating a conversation, and the
    // panel must still render (composer available for a later send).
    expect(create()).not.toHaveBeenCalled();
    expect(screen.getByTestId('composer-send')).toBeTruthy();
  });
});
