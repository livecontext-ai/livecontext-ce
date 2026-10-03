'use client';

import clsx from 'clsx';
import { Edit2 } from 'lucide-react';
import * as React from 'react';
import { NodeProps } from 'reactflow';
import { useTranslations } from 'next-intl';

import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

import type { BuilderNodeData } from '../../types';
import { NodeActionButtons, useHoverVisibility } from './shared';
import { ResizableNodeWrapper } from './ResizableNodeWrapper';

export const NOTE_COLORS = [
  { name: 'Yellow', value: '#fef3c7', border: '#fbbf24', text: '#92400e' },
  { name: 'Blue', value: '#dbeafe', border: '#3b82f6', text: '#1e40af' },
  { name: 'Green', value: '#d1fae5', border: '#10b981', text: '#065f46' },
  { name: 'Pink', value: '#fce7f3', border: '#ec4899', text: '#831843' },
  { name: 'Purple', value: '#e9d5ff', border: '#a855f7', text: '#6b21a8' },
  { name: 'Orange', value: '#fed7aa', border: '#f97316', text: '#9a3412' },
] as const;

const MIN_WIDTH = 200;
const MIN_HEIGHT = 100;

export function NoteNode({ data, selected }: NodeProps<BuilderNodeData>) {
  const t = useTranslations('workflowBuilder.forms');
  // A run shows the workflow as it ran: its notes are read, not edited.
  const { isRunMode } = useWorkflowMode();
  const [isEditing, setIsEditing] = React.useState(false);
  const [text, setText] = React.useState(data.noteText || '');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();

  const noteColor = data.noteColor || NOTE_COLORS[0].value;
  const borderColor = data.noteBorderColor || NOTE_COLORS[0].border;

  React.useEffect(() => {
    setText(data.noteText || '');
  }, [data.noteText]);

  const handleTextChange = (newText: string) => {
    setText(newText);
  };

  const handleBlur = () => {
    setIsEditing(false);
    if (data.onNoteUpdate) {
      data.onNoteUpdate({ noteText: text });
    }
  };

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRunMode) return;
    setIsEditing(true);
  };

  const handleResizeEnd = React.useCallback((width: number, height: number) => {
    if (data.onNoteUpdate) {
      data.onNoteUpdate({ noteWidth: width, noteHeight: height });
    }
  }, [data]);

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-[28px] px-4 py-3 backdrop-blur',
        'cursor-pointer',
        'border-2 transition-colors',
      )}
      style={{
        // Selected, or its node is: the note is the explanation of what is focused.
        borderColor: selected || data.noteFocused ? borderColor : 'var(--border-color)',
        boxShadow: data.noteFocused && !selected ? `0 0 0 3px ${borderColor}55` : undefined,
        borderStyle: 'solid',
        backgroundColor: noteColor,
        width: '100%',
        minHeight: '100%',
        height: 'auto',
      }}
      onDoubleClick={handleDoubleClick}
    >
      <ResizableNodeWrapper
        enabled={!isRunMode}
        minWidth={MIN_WIDTH}
        minHeight={MIN_HEIGHT}
        onResizeEnd={handleResizeEnd}
        color={borderColor}
      />

      {isEditing && !isRunMode ? (
        <textarea
          value={text}
          onChange={(e) => handleTextChange(e.target.value)}
          onBlur={handleBlur}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && e.metaKey) {
              handleBlur();
            }
            if (e.key === 'Escape') {
              setText(data.noteText || '');
              setIsEditing(false);
            }
          }}
          autoFocus
          className="w-full bg-transparent border-none outline-none resize-none text-sm text-black"
          style={{ minHeight: '76px' }}
          rows={Math.max(3, text.split('\n').length)}
        />
      ) : (
        <div className="relative h-full">
          <p
            className="text-sm whitespace-pre-wrap break-words text-black"
          >
            {text || (isRunMode ? '' : t('note.emptyText'))}
          </p>
          {selected && !isRunMode && (
            <div className="absolute top-0 right-0 opacity-0 group-hover:opacity-100 transition-opacity">
              <Edit2 className="h-3 w-3 text-black" />
            </div>
          )}
        </div>
      )}

      <NodeActionButtons
        isVisible={showActions && !isRunMode}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined}
        onHover={show}
      />
    </div>
  );
}
