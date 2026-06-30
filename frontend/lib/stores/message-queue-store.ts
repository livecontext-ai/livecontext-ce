import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type { PendingAttachment } from '@/lib/api/attachmentApi';

export const MAX_QUEUE_SIZE = 5;
export const MESSAGE_QUEUE_STORAGE_KEY = 'messageComposer:queue:v1';
export const QUEUE_MAX_AGE_MS = 30 * 60 * 1000;
const MAX_PERSISTED_ITEMS_PER_CONVERSATION = 20;

export interface QueuedMessage {
  id: string;
  content: string;
  attachments: PendingAttachment[];
  queuedAt: number;
  defaultSkillIds?: string[];
  /** True for a RESUME message enqueued after resolving ONE parallel card - keep the other cards. */
  keepPendingActions?: boolean;
}

interface EnqueueOptions {
  bypassLimit?: boolean;
  position?: 'front' | 'back';
}

interface ConversationQueue {
  items: QueuedMessage[];
}

interface MessageQueueState {
  queues: Record<string, ConversationQueue>;
}

interface MessageQueueActions {
  enqueue: (conversationId: string, message: Omit<QueuedMessage, 'id' | 'queuedAt'>, options?: EnqueueOptions) => boolean;
  dequeue: (conversationId: string) => QueuedMessage | null;
  remove: (conversationId: string, messageId: string) => void;
  updateContent: (conversationId: string, messageId: string, content: string) => void;
  extractForSendNow: (conversationId: string, messageId: string) => QueuedMessage | null;
  reorder: (conversationId: string, fromIndex: number, toIndex: number) => void;
  getQueue: (conversationId: string) => QueuedMessage[];
  isFull: (conversationId: string) => boolean;
  clearQueue: (conversationId: string) => void;
}

interface StoredQueuedMessage {
  id: string;
  content: string;
  queuedAt: number;
  defaultSkillIds?: string[];
  keepPendingActions?: boolean;
}

interface StoredQueuePayload {
  v: 1;
  queues: Record<string, { items: StoredQueuedMessage[] }>;
}

function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `queued-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function getStorage(): Storage | null {
  try {
    if (typeof window === 'undefined') return null;
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function isFresh(queuedAt: number, now: number): boolean {
  return Number.isFinite(queuedAt) && now - queuedAt < QUEUE_MAX_AGE_MS;
}

function toStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const strings = value.filter((entry): entry is string => typeof entry === 'string' && entry.length > 0);
  return strings.length > 0 ? strings : undefined;
}

function toStoredQueues(queues: Record<string, ConversationQueue>, now = Date.now()): StoredQueuePayload['queues'] {
  const stored: StoredQueuePayload['queues'] = {};

  for (const [conversationId, queue] of Object.entries(queues)) {
    if (!conversationId || !Array.isArray(queue?.items)) continue;

    const items = queue.items
      .filter((message) => {
        // Browser File objects cannot be safely restored from sessionStorage. Persist
        // text-only queued messages (notably approval/authorization resumes) and keep
        // attachment queues in memory for the current page lifetime only.
        return message.attachments.length === 0
          && message.content.trim().length > 0
          && isFresh(message.queuedAt, now);
      })
      .slice(0, MAX_PERSISTED_ITEMS_PER_CONVERSATION)
      .map((message) => ({
        id: message.id,
        content: message.content,
        queuedAt: message.queuedAt,
        defaultSkillIds: message.defaultSkillIds,
        keepPendingActions: message.keepPendingActions,
      }));

    if (items.length > 0) {
      stored[conversationId] = { items };
    }
  }

  return stored;
}

export function clearPersistedMessageQueues(): void {
  const storage = getStorage();
  if (!storage) return;
  try {
    storage.removeItem(MESSAGE_QUEUE_STORAGE_KEY);
  } catch {
    /* silent */
  }
}

export function readPersistedMessageQueues(now = Date.now()): Record<string, ConversationQueue> {
  const storage = getStorage();
  if (!storage) return {};

  let raw: string | null;
  try {
    raw = storage.getItem(MESSAGE_QUEUE_STORAGE_KEY);
  } catch {
    return {};
  }
  if (!raw) return {};

  try {
    const parsed = JSON.parse(raw) as Partial<StoredQueuePayload> | null;
    if (!parsed || parsed.v !== 1 || !parsed.queues || typeof parsed.queues !== 'object') {
      clearPersistedMessageQueues();
      return {};
    }

    const queues: Record<string, ConversationQueue> = {};
    for (const [conversationId, queue] of Object.entries(parsed.queues)) {
      if (!conversationId || !queue || !Array.isArray(queue.items)) continue;

      const items: QueuedMessage[] = queue.items
        .filter((message) =>
          message
          && typeof message.id === 'string'
          && typeof message.content === 'string'
          && message.content.trim().length > 0
          && typeof message.queuedAt === 'number'
          && isFresh(message.queuedAt, now)
        )
        .slice(0, MAX_PERSISTED_ITEMS_PER_CONVERSATION)
        .map((message) => ({
          id: message.id,
          content: message.content,
          attachments: [],
          queuedAt: message.queuedAt,
          defaultSkillIds: toStringArray(message.defaultSkillIds),
          keepPendingActions: message.keepPendingActions === true,
        }));

      if (items.length > 0) {
        queues[conversationId] = { items };
      }
    }

    persistQueues(queues, now);
    return queues;
  } catch {
    clearPersistedMessageQueues();
    return {};
  }
}

function persistQueues(queues: Record<string, ConversationQueue>, now = Date.now()): void {
  const storage = getStorage();
  if (!storage) return;

  const storedQueues = toStoredQueues(queues, now);
  try {
    if (Object.keys(storedQueues).length === 0) {
      storage.removeItem(MESSAGE_QUEUE_STORAGE_KEY);
      return;
    }
    storage.setItem(MESSAGE_QUEUE_STORAGE_KEY, JSON.stringify({ v: 1, queues: storedQueues } satisfies StoredQueuePayload));
  } catch {
    /* quota/security: keep the in-memory queue */
  }
}

export const useMessageQueueStore = create<MessageQueueState & MessageQueueActions>()(
  immer((set, get) => ({
    queues: readPersistedMessageQueues(),

    enqueue: (conversationId, message, options) => {
      const state = get();
      const queue = state.queues[conversationId];
      if (!options?.bypassLimit && queue && queue.items.length >= MAX_QUEUE_SIZE) {
        return false;
      }
      const newMessage: QueuedMessage = {
        ...message,
        id: randomId(),
        queuedAt: Date.now(),
      };
      set((draft) => {
        if (!draft.queues[conversationId]) {
          draft.queues[conversationId] = { items: [] };
        }
        if (options?.position === 'front') {
          draft.queues[conversationId].items.unshift(newMessage);
        } else {
          draft.queues[conversationId].items.push(newMessage);
        }
      });
      persistQueues(get().queues);
      return true;
    },

    dequeue: (conversationId) => {
      const state = get();
      const queue = state.queues[conversationId];
      if (!queue || queue.items.length === 0) return null;
      const first = queue.items[0];
      set((draft) => {
        const q = draft.queues[conversationId];
        q.items.shift();
        if (q.items.length === 0) delete draft.queues[conversationId];
      });
      persistQueues(get().queues);
      return first;
    },

    remove: (conversationId, messageId) => {
      set((draft) => {
        const queue = draft.queues[conversationId];
        if (!queue) return;
        queue.items = queue.items.filter((m) => m.id !== messageId);
        if (queue.items.length === 0) delete draft.queues[conversationId];
      });
      persistQueues(get().queues);
    },

    updateContent: (conversationId, messageId, content) => {
      set((draft) => {
        const queue = draft.queues[conversationId];
        if (!queue) return;
        const msg = queue.items.find((m) => m.id === messageId);
        if (msg) msg.content = content;
      });
      persistQueues(get().queues);
    },

    extractForSendNow: (conversationId, messageId) => {
      const state = get();
      const queue = state.queues[conversationId];
      if (!queue) return null;
      const msg = queue.items.find((m) => m.id === messageId);
      if (!msg) return null;
      set((draft) => {
        const q = draft.queues[conversationId];
        if (q) {
          q.items = q.items.filter((m) => m.id !== messageId);
          if (q.items.length === 0) delete draft.queues[conversationId];
        }
      });
      persistQueues(get().queues);
      return msg;
    },

    reorder: (conversationId, fromIndex, toIndex) => {
      set((draft) => {
        const queue = draft.queues[conversationId];
        if (!queue) return;
        const items = queue.items;
        if (fromIndex < 0 || fromIndex >= items.length || toIndex < 0 || toIndex >= items.length) return;
        const [moved] = items.splice(fromIndex, 1);
        items.splice(toIndex, 0, moved);
      });
      persistQueues(get().queues);
    },

    getQueue: (conversationId) => {
      return get().queues[conversationId]?.items ?? [];
    },

    isFull: (conversationId) => {
      const queue = get().queues[conversationId];
      return !!queue && queue.items.length >= MAX_QUEUE_SIZE;
    },

    clearQueue: (conversationId) => {
      set((draft) => {
        delete draft.queues[conversationId];
      });
      persistQueues(get().queues);
    },
  }))
);
