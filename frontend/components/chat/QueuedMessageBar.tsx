'use client';

import React, { useState } from 'react';
import { CornerDownRight, GripVertical, Paperclip, Pencil, Trash2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { QueuedMessage } from '@/lib/stores/message-queue-store';
import { EditQueuedMessageModal } from './EditQueuedMessageModal';

interface QueuedMessageBarProps {
  messages: QueuedMessage[];
  onRemove: (messageId: string) => void;
  onEditContent: (messageId: string, content: string) => void;
  onSendNow: (messageId: string) => void;
  onReorder: (fromIndex: number, toIndex: number) => void;
}

function QueuedMessageRow({
  message,
  index,
  onRemove,
  onEditContent,
  onSendNow,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  isDragging,
  isDragOver,
}: {
  message: QueuedMessage;
  index: number;
  onRemove: (messageId: string) => void;
  onEditContent: (messageId: string, content: string) => void;
  onSendNow: (messageId: string) => void;
  onDragStart: (index: number) => void;
  onDragOver: (e: React.DragEvent, index: number) => void;
  onDrop: (index: number) => void;
  onDragEnd: () => void;
  isDragging: boolean;
  isDragOver: boolean;
}) {
  const t = useTranslations();
  const [editOpen, setEditOpen] = useState(false);

  const attachmentCount = message.attachments.length;

  return (
    <div
      className={`flex items-center gap-2 px-3 py-1.5 transition-colors ${
        isDragging ? 'opacity-40' : ''
      } ${isDragOver ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]'}`}
      draggable
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = 'move';
        onDragStart(index);
      }}
      onDragOver={(e) => onDragOver(e, index)}
      onDrop={() => onDrop(index)}
      onDragEnd={onDragEnd}
    >
      <GripVertical className="w-3.5 h-3.5 text-theme-muted shrink-0 cursor-grab active:cursor-grabbing" />

      <button
        onClick={() => onSendNow(message.id)}
        className="flex items-center text-xs text-theme-secondary hover:text-theme-primary transition-colors shrink-0"
        title={t('chat.queue.sendNow')}
      >
        <CornerDownRight className="w-3.5 h-3.5" />
      </button>

      <span
        className="truncate text-sm text-theme-primary min-w-0 flex-1 cursor-pointer"
        onClick={() => setEditOpen(true)}
        title={t('chat.queue.edit')}
      >
        {message.content || t('chat.queue.queued')}
      </span>

      {attachmentCount > 0 && (
        <span className="flex items-center gap-0.5 text-xs text-theme-muted shrink-0">
          <Paperclip className="w-3 h-3" />
          {attachmentCount}
        </span>
      )}

      <button
        onClick={() => setEditOpen(true)}
        className="p-0.5 text-theme-muted hover:text-theme-primary transition-colors shrink-0"
        title={t('chat.queue.edit')}
      >
        <Pencil className="w-3.5 h-3.5" />
      </button>

      <button
        onClick={() => onRemove(message.id)}
        className="p-0.5 text-theme-muted hover:text-red-500 transition-colors shrink-0"
        title={t('chat.queue.remove')}
      >
        <Trash2 className="w-3.5 h-3.5" />
      </button>

      {editOpen && (
        <EditQueuedMessageModal
          initialContent={message.content}
          onClose={() => setEditOpen(false)}
          onSave={(content) => onEditContent(message.id, content)}
        />
      )}
    </div>
  );
}

export function QueuedMessageBar({ messages, onRemove, onEditContent, onSendNow, onReorder }: QueuedMessageBarProps) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  if (messages.length === 0) return null;

  const handleDragStart = (index: number) => setDragIndex(index);

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDragOverIndex(index);
  };

  const handleDrop = (toIndex: number) => {
    if (dragIndex !== null && dragIndex !== toIndex) {
      onReorder(dragIndex, toIndex);
    }
    setDragIndex(null);
    setDragOverIndex(null);
  };

  const handleDragEnd = () => {
    setDragIndex(null);
    setDragOverIndex(null);
  };

  return (
    <div className="flex flex-col py-0.5">
      {messages.map((msg, idx) => (
        <QueuedMessageRow
          key={msg.id}
          message={msg}
          index={idx}
          onRemove={onRemove}
          onEditContent={onEditContent}
          onSendNow={onSendNow}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
          onDragEnd={handleDragEnd}
          isDragging={dragIndex === idx}
          isDragOver={dragOverIndex === idx && dragIndex !== idx}
        />
      ))}
    </div>
  );
}
