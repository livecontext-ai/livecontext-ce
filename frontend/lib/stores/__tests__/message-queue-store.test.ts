// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PendingAttachment } from '@/lib/api/attachmentApi';

async function freshStore() {
  vi.resetModules();
  return import('../message-queue-store');
}

beforeEach(() => {
  sessionStorage.clear();
});

describe('message queue persistence', () => {
  it('rehydrates text-only queues after a refresh without mixing conversations', async () => {
    const first = await freshStore();
    first.useMessageQueueStore.getState().enqueue('conv-a', { content: 'A1', attachments: [] });
    first.useMessageQueueStore.getState().enqueue('conv-b', { content: 'B1', attachments: [], keepPendingActions: true });

    const second = await freshStore();
    expect(second.useMessageQueueStore.getState().getQueue('conv-a').map((m) => m.content)).toEqual(['A1']);
    expect(second.useMessageQueueStore.getState().getQueue('conv-b').map((m) => m.content)).toEqual(['B1']);
    expect(second.useMessageQueueStore.getState().getQueue('conv-b')[0]?.keepPendingActions).toBe(true);

    expect(second.useMessageQueueStore.getState().dequeue('conv-a')?.content).toBe('A1');
    expect(second.useMessageQueueStore.getState().getQueue('conv-a')).toEqual([]);
    expect(second.useMessageQueueStore.getState().getQueue('conv-b').map((m) => m.content)).toEqual(['B1']);
  });

  it('does not persist queued File attachments that cannot be restored safely', async () => {
    const { useMessageQueueStore } = await freshStore();
    const attachment: PendingAttachment = {
      id: 'att-1',
      file: new File(['x'], 'doc.pdf', { type: 'application/pdf' }),
      uploadStatus: 'pending',
      type: 'PDF',
      mimeType: 'application/pdf',
      sizeBytes: 1,
    };

    useMessageQueueStore.getState().enqueue('conv-a', { content: 'send with file', attachments: [attachment] });
    expect(useMessageQueueStore.getState().getQueue('conv-a')).toHaveLength(1);
    expect(sessionStorage.getItem('messageComposer:queue:v1')).toBeNull();

    const reloaded = await freshStore();
    expect(reloaded.useMessageQueueStore.getState().getQueue('conv-a')).toEqual([]);
  });

  it('purges stale persisted queues instead of auto-sending old messages later', async () => {
    const { MESSAGE_QUEUE_STORAGE_KEY, QUEUE_MAX_AGE_MS, readPersistedMessageQueues } = await freshStore();
    sessionStorage.setItem(MESSAGE_QUEUE_STORAGE_KEY, JSON.stringify({
      v: 1,
      queues: {
        'conv-a': {
          items: [{
            id: 'old-1',
            content: 'old queued text',
            queuedAt: 1_000,
          }],
        },
      },
    }));

    expect(readPersistedMessageQueues(1_000 + QUEUE_MAX_AGE_MS + 1)).toEqual({});
    expect(sessionStorage.getItem(MESSAGE_QUEUE_STORAGE_KEY)).toBeNull();
  });
});
