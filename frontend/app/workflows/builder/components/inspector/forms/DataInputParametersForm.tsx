'use client';

import * as React from 'react';
import { useCallback, useRef, useState } from 'react';
import { X, Upload, Plus, Trash2, Info, GripVertical } from 'lucide-react';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import type { BuilderNodeData } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { fileService, type FileRef } from '@/lib/api/orchestrator/file.service';
import { useTranslations } from 'next-intl';
import { StorageExplorerTab } from '../StorageExplorerTab';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { FilePreview } from '../outputs/FilePreview';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

interface DataInputParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  connections: Connection[];
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

const MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
const MAX_ITEMS = 10;

interface DataInputItem {
  id: string;
  label: string;
  type: 'text' | 'file';
  text?: string;
  file?: FileRef | null;
}

function generateItemId(): string {
  return `item_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
}

/**
 * Form component for Data Input node parameters (multi-item).
 * First item = main preview on canvas. Drag to reorder.
 */
export function DataInputParametersForm({
  node,
  data,
  connections,
  isRunMode = false,
  onUpdate,
  findUnknownVariables,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: DataInputParametersFormProps) {
  const t = useTranslations('workflowBuilder.dataInput');
  const { workflowId: contextWorkflowId } = useWorkflowMode();

  const defaultItems = React.useMemo<DataInputItem[]>(() => [
    { id: 'default_item', label: 'text', type: 'text' as const, text: '' },
  ], []);
  const items: DataInputItem[] = (data as any).dataInputItems ?? defaultItems;

  const canDelete = items.length > 1;

  const updateItems = useCallback((newItems: DataInputItem[]) => {
    onUpdate({
      ...data,
      dataInputItems: newItems,
    } as BuilderNodeData);
  }, [data, onUpdate]);

  const handleAddItem = useCallback(() => {
    if (isRunMode || items.length >= MAX_ITEMS) return;
    const newItem: DataInputItem = {
      id: generateItemId(),
      label: `input_${items.length + 1}`,
      type: 'text',
      text: '',
    };
    updateItems([...items, newItem]);
  }, [isRunMode, items, updateItems]);

  const handleRemoveItem = useCallback((itemId: string) => {
    if (isRunMode || items.length <= 1) return;
    updateItems(items.filter((i) => i.id !== itemId));
  }, [isRunMode, items, updateItems]);

  const handleItemUpdate = useCallback((itemId: string, updates: Partial<DataInputItem>) => {
    if (isRunMode) return;
    const newItems = items.map((i) =>
      i.id === itemId ? { ...i, ...updates } : i
    );
    updateItems(newItems);
  }, [isRunMode, items, updateItems]);

  // Drag-to-reorder state
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropIndex, setDropIndex] = useState<number | null>(null);

  const handleReorderDragStart = useCallback((e: React.DragEvent, index: number) => {
    if (isRunMode) return;
    setDragIndex(index);
    e.dataTransfer.effectAllowed = 'move';
  }, [isRunMode]);

  const handleReorderDragEnd = useCallback(() => {
    if (dragIndex !== null && dropIndex !== null && dragIndex !== dropIndex) {
      const newItems = [...items];
      const [dragged] = newItems.splice(dragIndex, 1);
      newItems.splice(dropIndex, 0, dragged);
      updateItems(newItems);
    }
    setDragIndex(null);
    setDropIndex(null);
  }, [dragIndex, dropIndex, items, updateItems]);

  // Check for duplicate labels
  const duplicateLabels = React.useMemo(() => {
    const labelCounts = new Map<string, number>();
    items.forEach((item) => {
      const key = item.label.toLowerCase().trim();
      labelCounts.set(key, (labelCounts.get(key) ?? 0) + 1);
    });
    const dups = new Set<string>();
    labelCounts.forEach((count, key) => {
      if (count > 1) dups.add(key);
    });
    return dups;
  }, [items]);

  return (
    <div className="space-y-4 pt-2">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('items')}
          </span>
          <Popover>
            <PopoverTrigger asChild>
              <button
                type="button"
                className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
              >
                <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p className="font-semibold text-slate-900 dark:text-slate-100">{t('title')}</p>
                <p>{t('description')}</p>
                <ul className="list-disc list-inside space-y-1 text-xs">
                  <li>{t('helpItemCreatesOutput')}</li>
                  <li>{t('helpLabelsAvailable')}</li>
                  <li>{t('helpFirstItemPreview')}</li>
                </ul>
              </div>
            </PopoverContent>
          </Popover>
        </div>
        {!isRunMode && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
            onClick={(e) => {
              e.stopPropagation();
              handleAddItem();
            }}
            disabled={items.length >= MAX_ITEMS}
            title={items.length >= MAX_ITEMS ? t('maxItemsReached') : t('addItem')}
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}
      </div>

      {/* Items list - onDragOver/onDragEnd at list level to avoid stealing focus from child inputs */}
      <div
        className="space-y-3"
        onDragOver={(e) => {
          if (dragIndex === null) return;
          e.preventDefault();
          // Detect which item we're hovering based on mouse position
          const children = (e.currentTarget as HTMLElement).children;
          for (let i = 0; i < children.length; i++) {
            const rect = children[i].getBoundingClientRect();
            if (e.clientY >= rect.top && e.clientY <= rect.bottom) {
              if (i !== dragIndex) setDropIndex(i);
              break;
            }
          }
        }}
        onDragEnd={handleReorderDragEnd}
      >
        {items.map((item, index) => (
          <DataInputItemEditor
            key={item.id}
            item={item}
            index={index}
            canDelete={canDelete}
            isDuplicateLabel={duplicateLabels.has(item.label.toLowerCase().trim())}
            isRunMode={isRunMode}
            isDragging={dragIndex === index}
            isDropTarget={dropIndex === index}
            onReorderDragStart={handleReorderDragStart}
            nodeId={node.id}
            connections={connections}
            findUnknownVariables={findUnknownVariables}
            onUpdate={(updates) => handleItemUpdate(item.id, updates)}
            onRemove={() => handleRemoveItem(item.id)}
            draggingFromHandle={draggingFromHandle}
            hoveredTargetHandle={hoveredTargetHandle}
            handleHandleClick={handleHandleClick}
            handleHandleMouseDown={handleHandleMouseDown}
            handleHandleMouseUp={handleHandleMouseUp}
            handleSetHandleRef={handleSetHandleRef}
          />
        ))}
      </div>
    </div>
  );
}

// ============================================================================
// Single item editor
// ============================================================================

interface DataInputItemEditorProps {
  item: DataInputItem;
  index: number;
  canDelete: boolean;
  isDuplicateLabel: boolean;
  isRunMode: boolean;
  isDragging: boolean;
  isDropTarget: boolean;
  onReorderDragStart: (e: React.DragEvent, index: number) => void;
  nodeId: string;
  connections: Connection[];
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  onUpdate: (updates: Partial<DataInputItem>) => void;
  onRemove: () => void;
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
}

function DataInputItemEditor({
  item,
  index,
  canDelete,
  isDuplicateLabel,
  isRunMode,
  isDragging,
  isDropTarget,
  onReorderDragStart,
  nodeId,
  connections,
  findUnknownVariables,
  onUpdate,
  onRemove,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
}: DataInputItemEditorProps) {
  const t = useTranslations('workflowBuilder.dataInput');
  const { workflowId: contextWorkflowId } = useWorkflowMode();
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [isFileDragOver, setIsFileDragOver] = useState(false);
  const [fileSource, setFileSource] = useState<'upload' | 'storage'>('upload');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleUpload = useCallback(async (fileList: FileList) => {
    if (isRunMode || fileList.length === 0) return;
    setUploadError(null);
    setUploading(true);

    try {
      const file = fileList[0];
      if (file.size > MAX_FILE_SIZE) {
        setUploadError(t('maxFileSize'));
        return;
      }

      const fileRef = await fileService.uploadFile(file, {
        workflowId: contextWorkflowId || 'draft',
        runId: 'assets',
        stepAlias: nodeId,
      });
      onUpdate({ file: fileRef });
    } catch (err: any) {
      setUploadError(err.message || t('uploadError'));
    } finally {
      setUploading(false);
    }
  }, [isRunMode, contextWorkflowId, nodeId, onUpdate, t]);

  const handleStorageSelect = useCallback((entry: StorageExplorerEntry) => {
    if (isRunMode) return;
    const fileRef: FileRef = {
      _type: 'file',
      path: entry.s3Key!,
      name: entry.fileName || 'unknown',
      mimeType: entry.mimeType || 'application/octet-stream',
      size: entry.sizeBytes || 0,
    };
    onUpdate({ file: fileRef });
  }, [isRunMode, onUpdate]);

  const handleFileDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!isRunMode) setIsFileDragOver(true);
  }, [isRunMode]);

  const handleFileDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsFileDragOver(false);
  }, []);

  const handleFileDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsFileDragOver(false);
    if (isRunMode) return;
    if (e.dataTransfer.files.length > 0) {
      handleUpload(e.dataTransfer.files);
    }
  }, [isRunMode, handleUpload]);

  const handleFileInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      handleUpload(e.target.files);
      e.target.value = '';
    }
  }, [handleUpload]);

  return (
    <div
      className={`flex flex-col gap-2 relative transition-all ${
        isDragging ? 'opacity-50' :
        isDropTarget ? 'border-l-2 border-blue-500 pl-1' : ''
      }`}
    >
      {/* Row 1: grip + label input + delete */}
      <div className="flex items-center justify-between gap-1">
        <div
          draggable={!isRunMode}
          onDragStart={(e) => onReorderDragStart(e, index)}
          className="flex-shrink-0"
        >
          <GripVertical className={`h-3.5 w-3.5 text-slate-400 ${isRunMode ? 'cursor-default' : 'cursor-grab active:cursor-grabbing'}`} />
        </div>
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <Input
            value={item.label}
            onChange={(e) => onUpdate({ label: e.target.value })}
            placeholder={t('itemLabelPlaceholder')}
            className={`text-sm font-semibold text-slate-500 dark:text-slate-400 h-6 px-2 py-1 flex-1 min-w-0 border-0 focus-visible:ring-0 focus-visible:ring-offset-0 shadow-none ${isDuplicateLabel ? 'text-red-500 dark:text-red-400' : ''}`}
            readOnly={isRunMode}
          />
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 disabled:opacity-30 disabled:cursor-not-allowed"
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          disabled={!canDelete || isRunMode}
          title={canDelete ? t('removeItem') : t('items')}
        >
          <Trash2 className="h-3 w-3" />
        </Button>
      </div>

      {/* Duplicate label warning */}
      {isDuplicateLabel && (
        <p className="text-xs text-red-500 dark:text-red-400 px-2 -mt-1">{t('duplicateLabel')}</p>
      )}

      {/* Row 2: type selector */}
      <Select
        value={item.type}
        onValueChange={(v) => onUpdate({ type: v as 'text' | 'file' })}
        disabled={isRunMode}
      >
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="text">{t('modeText')}</SelectItem>
          <SelectItem value="file">{t('modeFile')}</SelectItem>
        </SelectContent>
      </Select>

      {/* Content editor based on type */}
      {item.type === 'text' && (
        <ExpressionEditor
          value={item.text ?? ''}
          onChange={(value) => onUpdate({ text: value })}
          placeholder={t('textPlaceholder')}
          className="w-full"
          fullHeight
          unknownVariables={findUnknownVariables({ [`text_${item.id}`]: item.text ?? '' })}
          handleId={`data-input-text-${nodeId}-${item.id}`}
          connections={connections}
          onHandleClick={handleHandleClick}
          draggingFromHandle={draggingFromHandle}
          onHandleMouseDown={handleHandleMouseDown}
          onHandleMouseUp={handleHandleMouseUp}
          hoveredTargetHandle={hoveredTargetHandle}
          onSetHandleRef={handleSetHandleRef}
          isRequired={true}
          readOnly={isRunMode}
        />
      )}

      {item.type === 'file' && (
        <div className="space-y-2">
          {/* File source sub-selector */}
          {!isRunMode && !item.file && (
            <Select value={fileSource} onValueChange={(v) => setFileSource(v as 'upload' | 'storage')}>
              <SelectTrigger className="w-full h-7 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="upload">{t('sourceUpload')}</SelectItem>
                <SelectItem value="storage">{t('sourceStorage')}</SelectItem>
              </SelectContent>
            </Select>
          )}

          {/* File preview */}
          {item.file && (
            <div className="relative">
              <FilePreview fileRef={item.file} />
              {!isRunMode && (
                <button
                  type="button"
                  onClick={() => onUpdate({ file: null })}
                  className="absolute top-2 right-2 p-0.5 rounded hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                  title={t('removeFile')}
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
          )}

          {/* Upload drop zone */}
          {!isRunMode && !item.file && fileSource === 'upload' && (
            <div
              onDragOver={handleFileDragOver}
              onDragLeave={handleFileDragLeave}
              onDrop={handleFileDrop}
              onClick={() => fileInputRef.current?.click()}
              className={`
                flex flex-col items-center justify-center gap-1 rounded-lg border-2 border-dashed
                cursor-pointer transition-colors py-3 px-3
                ${isFileDragOver
                  ? 'border-amber-400 bg-amber-50 dark:border-amber-500 dark:bg-amber-900/20'
                  : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800/50'
                }
                ${uploading ? 'opacity-50 pointer-events-none' : ''}
              `}
            >
              <Upload className="h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
              <span className="text-xs text-slate-500 dark:text-slate-400">
                {uploading ? t('uploading') : t('dropZone')}
              </span>
              <input
                ref={fileInputRef}
                type="file"
                onChange={handleFileInputChange}
                className="hidden"
              />
            </div>
          )}

          {/* Inline storage explorer */}
          {!isRunMode && !item.file && fileSource === 'storage' && (
            <div className="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden min-h-[200px] max-h-[60vh] flex flex-col">
              <StorageExplorerTab onSelect={handleStorageSelect} />
            </div>
          )}

          {/* Upload error */}
          {uploadError && (
            <p className="text-xs text-red-500 dark:text-red-400">{uploadError}</p>
          )}
        </div>
      )}
    </div>
  );
}

