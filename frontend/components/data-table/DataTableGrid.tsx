import type { ReactNode } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import React, { useEffect, useMemo } from 'react';
import { useTranslations } from 'next-intl';
import type { DataTableProps, DataSourceItemRow, ColumnDefinition } from '@/components/data-table/types';
import type { DataTableController } from '@/components/data-table/useDataTableController';
import { ImagePreview } from '@/components/data-table/ImagePreview';
import { EditableCell } from '@/components/data-table/EditableCell';
import { JsonPreviewPopover, PinnedJsonPopover, type PinnedPopoverData } from '@/components/data-table/JsonPreviewPopover';
import { COLUMN_TYPE_META, getDisplayOptions, getValueAtPath } from '@/components/data-table/visualHelpers';
import { renderVisualCellContent } from '@/components/data-table/cells';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { StatusBadge, mapBackendStatusToStatusType } from '@/components/ui/StatusBadge';
import { AddRowForm } from '@/components/data-table/AddRowForm';
import { calculateCheckboxColumnWidth, FIXED_COLUMN_WIDTH, MIN_ID_COLUMN_WIDTH, MAX_CHECKBOX_COLUMN_WIDTH } from '@/components/data-table/tableStyles';
import { FIXED_COLUMNS } from '@/components/data-table/hooks/useColumnOperations';
import { PreviewActionMenu } from '@/components/chat/PreviewActionMenu';
import { Button } from '@/components/ui/button';
import { getRenderer, NoData } from '@/components/data-table/columnRenderers';
import { LoadOlderSentinel } from '@/components/agent-fleet/LoadOlderSentinel';
import type { LucideIcon } from 'lucide-react';
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Check,
  CheckSquare,
  MoreVertical,
  Square,
  CircleDot,
  Code,
  Database,
  File,
  FileText,
  Gauge,
  GripVertical,
  Hash,
  Image as ImageIcon,
  Link2,
  Pencil,
  Plus,
  Star,
  Tag,
  ThumbsDown,
  ThumbsUp,
  X,
} from 'lucide-react';

// RenderTypes that should remain read-only in workflow mode
// These are computed visualizations, not raw editable data
const NON_EDITABLE_RENDER_TYPES = new Set([
  'STATUS_BADGE', 'BRANCH_BADGE', 'HTTP_STATUS_BADGE', 'HTTP_METHOD_BADGE',
  'BOOLEAN_BADGE', 'BADGE', 'DURATION', 'RELATIVE_TIME', 'PERCENTAGE',
  'PROGRESS_BAR', 'JSON_NAVIGABLE', 'JSON_PREVIEW', 'EVALUATIONS_TABLE',
  'CASES_TABLE', 'LOOP_PROGRESS', 'SPLIT_PROGRESS',
]);

interface DataTableGridProps {
  controller: DataTableController;
  workflowContext?: DataTableProps['workflowContext'];
  jsonPath?: string;
  dataSourceId: number;
  onNavigate?: DataTableProps['onNavigate'];
  navigateTo: (path: string) => void;
  dataSourceBasePath: string;
  /** Render a bottom "load more" sentinel inside the scroll container and disable the
   *  external prev/next paginator. Wired by DataTable when its `infiniteScroll` prop is set. */
  infiniteScroll?: boolean;
}

export function DataTableGrid({ controller, workflowContext, jsonPath, dataSourceId, onNavigate, navigateTo, dataSourceBasePath, infiniteScroll = false }: DataTableGridProps) {
  const t = useTranslations('dataTable');
  const tRunSteps = useTranslations('workflow.runSteps');
  const locale = getClientLocale();
  const progressSaveRef = React.useRef<{ cellKey: string; timestamp: number } | null>(null);
  const closeHoverTimeoutRef = React.useRef<NodeJS.Timeout | null>(null);
  const isHoveringPopoverRef = React.useRef(false);
  const [pinnedPopovers, setPinnedPopovers] = React.useState<PinnedPopoverData[]>([]);
  // Scroll container is the IntersectionObserver root for the infinite-scroll sentinel -
  // without it, the observer would compare against the viewport and fire prematurely
  // (the sentinel is inside an overflow:auto element, not the page).
  const scrollContainerRef = React.useRef<HTMLDivElement>(null);
  const [scrollRoot, setScrollRoot] = React.useState<Element | null>(null);
  React.useEffect(() => {
    setScrollRoot(scrollContainerRef.current);
  }, []);

  // Fonctions pour gérer les popovers épinglées
  // Fonctions pour gérer les popovers épinglées
  const handlePinPopover = (data: { jsonData: any; headerName?: string; rowId?: string | number; position: { x: number; y: number } }) => {
    const newPinned: PinnedPopoverData = {
      id: `pinned-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
      jsonData: data.jsonData,
      headerName: data.headerName,
      rowId: data.rowId,
      comparisonItems: [
        {
          id: `item-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
          jsonData: data.jsonData,
          headerName: data.headerName,
          rowId: data.rowId
        }
      ],
      position: { x: data.position.x + 20, y: data.position.y + 20 },
    };
    setPinnedPopovers(prev => [...prev, newPinned]);
  };

  const handleMergePopovers = (sourceId: string, targetId: string) => {
    setPinnedPopovers(prev => {
      const source = prev.find(p => p.id === sourceId);
      const target = prev.find(p => p.id === targetId);

      if (!source || !target) return prev;

      // Check limit (max 3 items)
      const targetItems = target.comparisonItems || [{ id: 'target-legacy', jsonData: target.jsonData, headerName: target.headerName, rowId: target.rowId }];
      const sourceItems = source.comparisonItems || [{ id: 'source-legacy', jsonData: source.jsonData, headerName: source.headerName, rowId: source.rowId }];

      if (targetItems.length + sourceItems.length > 3) {
        return prev;
      }

      // Create merged items
      const newItems = [
        ...targetItems,
        ...sourceItems
      ];

      // Update target with merged items
      const updatedTarget: PinnedPopoverData = {
        ...target,
        comparisonItems: newItems,
      };

      // Filter out source and replace target
      return prev.map(p => {
        if (p.id === targetId) return updatedTarget;
        return p;
      }).filter(p => p.id !== sourceId);
    });
  };

  const handleClosePinnedPopover = (id: string) => {
    setPinnedPopovers(prev => prev.filter(p => p.id !== id));
  };

  const {
    rows,
    displayRows,
    getUniqueColumns,
    getDynamicColumns,
    dragOverColumn,
    draggedColumn,
    dragPosition,
    selectedColumns,
    handleSort,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    selectedRows,
    selectAllRows,
    toggleRowSelection,
    getRowUniqueKey,
    setSelectedRows,
    tableLoading,
    toggleColumnSelection,
    hoveredCell,
    setHoveredCell,
    makeCellKey,
    editingCellKey,
    setEditingCellKey,
    handleSaveEdit,
    progressTempValues,
    setProgressTempValues,
    getFieldPath,
    clearSelection,
    sortConfig,
    // AddRowForm props
    columns,
    viewConfig,
    isAddingRowInline,
    isAddingRow,
    newRowPriority,
    newRowData,
    startAddingRowInline,
    cancelAddingRowInline,
    addNewRow,
    setNewRowPriority,
    handleRowDataChange,
    // Add Column button props
    loadingColumns,
    setShowAddColumnModal,
    // Edit Column (per-column pencil-icon trigger from <th>)
    openEditColumn,
    // Readonly
    readOnly,
  } = controller;

  // Calculate checkbox column width based on ID length
  const checkboxColumnWidth = useMemo(() => {
    return calculateCheckboxColumnWidth(rows, displayRows as Array<{ type?: string; parentId?: number }>);
  }, [rows, displayRows]);

  // Calculate workflow ID column width (dynamic sizing with higher minimum than checkbox)
  const idColumnWidth = useMemo(() => {
    if (!workflowContext) return '100px';
    const raw = calculateCheckboxColumnWidth(rows, displayRows as Array<{ type?: string; parentId?: number }>);
    const px = parseInt(raw, 10) || MIN_ID_COLUMN_WIDTH;
    const clamped = Math.max(MIN_ID_COLUMN_WIDTH, Math.min(MAX_CHECKBOX_COLUMN_WIDTH, px));
    return `${clamped}px`;
  }, [rows, displayRows, workflowContext]);

  const renderVisualCell = (
    row: DataSourceItemRow,
    col: ColumnDefinition,
    value: any,
    rowKey: string,
    className: string,
    onMouseEnter: () => void
  ) => {
    const cellKey = makeCellKey(rowKey, col.field);
    const isEditing = editingCellKey === cellKey;

    const startEditing = (event?: React.MouseEvent) => {
      if (readOnly) return;
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      setEditingCellKey(cellKey);
    };

    const exitEditing = () => setEditingCellKey(null);
    const saveAndExit = (editedValue: any) => {
      if (readOnly) return;
      handleSaveEdit(row.id, col.field, String(editedValue), row.data?.array_index);
      exitEditing();
    };

    const handleProgressSave = (newValue: number) => {
      const now = Date.now();
      if (progressSaveRef.current?.cellKey === cellKey && now - progressSaveRef.current.timestamp < 100) {
        return;
      }
      progressSaveRef.current = { cellKey, timestamp: now };
      saveAndExit(newValue);
      setProgressTempValues(prev => {
        const newMap = new Map(prev);
        newMap.delete(cellKey);
        return newMap;
      });
    };

    const result = renderVisualCellContent({
      value,
      rowKey,
      field: col.field,
      type: col.type,
      displayConfig: col.displayConfig,
      isEditing,
      onSaveAndExit: saveAndExit,
      onStartEditing: startEditing,
      onExitEditing: exitEditing,
      readOnly,
      cellKey,
      progressTempValue: progressTempValues.get(cellKey),
      onProgressTempChange: (key, val) => {
        setProgressTempValues(prev => {
          const newMap = new Map(prev);
          newMap.set(key, val);
          return newMap;
        });
      },
      onProgressSave: handleProgressSave,
    });

    if (!result) return null;

    const { content, editable } = result;
    return (
      <td
        key={`${rowKey}-${col.field}`}
        className={`relative group/cell ${className} text-center ${editable && !isEditing ? 'cursor-pointer' : ''}`}
        onMouseEnter={onMouseEnter}
        onClick={editable && !isEditing ? startEditing : undefined}
      >
        <div className="flex w-full flex-col items-center justify-center gap-2 py-2 max-h-32 overflow-y-auto">{content}</div>
      </td>
    );
  };

  return (
    <>
      {/* 🇫🇷 Table de donnees - EXACTEMENT comme dans la demo */}
      <div className="w-full min-h-0 flex flex-col overflow-hidden" style={{ flex: '0 1 auto' }}>
        <div ref={scrollContainerRef} className="overflow-x-auto overflow-y-auto border border-theme rounded-xl" style={{ flex: '0 1 auto' }}>
          <table className="w-full text-sm border-separate" style={{ tableLayout: 'auto', borderSpacing: 0 }}>
            <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-40">
              <tr>
                {getUniqueColumns().map(col => {
                  const isFixed = ['checkbox', 'id'].includes(col.field);
                  const totalColumns = getUniqueColumns().length;
                  const isFewColumns = totalColumns <= 4;

                  // Style dynamique pour la colonne checkbox basé sur la taille des IDs
                  const hasCheckbox = getUniqueColumns().some(c => c.field === 'checkbox');
                  const fixedColumnStyle = col.field === 'checkbox' ? {
                    width: checkboxColumnWidth,
                    minWidth: checkboxColumnWidth,
                    maxWidth: checkboxColumnWidth
                  } : col.field === 'id' ? {
                    width: idColumnWidth,
                    minWidth: idColumnWidth,
                    maxWidth: idColumnWidth,
                  } : col.field === 'priority' ? {
                    minWidth: '96px',
                    maxWidth: '128px'
                  } : col.field === 'created_at' || col.field === 'array_index' || col.field === 'value' ? {
                    minWidth: '128px',
                    maxWidth: '192px'
                  } : isFewColumns ? {
                    width: '200px',
                    maxWidth: '200px'
                  } : {
                    minWidth: '120px',
                    maxWidth: '200px'
                  };

                  // Calculer la position sticky pour la colonne id
                  const idLeftOffset = hasCheckbox ? checkboxColumnWidth : 0;

                  return (
                    <th
                      key={col.field}
                      className={`${col.field === 'checkbox' ? 'px-1' : col.field === 'id' ? 'px-2' : 'px-3'} py-3 text-center font-medium text-theme-primary select-none group ${dragOverColumn === col.field ? 'bg-blue-200 dark:bg-blue-800/40' : ''
                        } ${draggedColumn === col.field ? 'opacity-40 scale-[0.98]' : ''} ${!isFixed ? 'hover:bg-theme-tertiary' : ''
                        } ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''} ${col.field === 'checkbox' ? 'sticky left-0 top-0 z-40 bg-theme-secondary' : col.field === 'id' ? 'sticky left-0 top-0 bg-theme-secondary' : 'sticky top-0 bg-theme-secondary'
                        }`}
                      style={{
                        ...fixedColumnStyle,
                        position: 'sticky',
                        top: 0,
                        ...(col.field === 'checkbox' ? { left: 0, zIndex: 40 } : {}),
                        ...(col.field === 'id' ? { left: idLeftOffset, zIndex: 35 } : {}),
                        cursor: col.field !== 'checkbox' && col.sortable !== false ? 'pointer' : (isFixed ? 'default' : 'pointer'),
                        minHeight: '60px',
                        ...(draggedColumn ? {
                          willChange: 'transform, opacity',
                          transform: draggedColumn === col.field ? 'scale(0.98)' : 'scale(1)',
                          transition: 'transform 0.3s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                        } : {}),
                      }}
                      onClick={col.field !== 'checkbox' && col.sortable !== false ? () => handleSort(col.field) : undefined}
                      onDragOver={!isFixed && !workflowContext ? (e) => handleDragOver(e, col.field) : undefined}
                      onDragLeave={!isFixed && !workflowContext ? handleDragLeave : undefined}
                      onDrop={!isFixed && !workflowContext ? (e) => handleDrop(e, col.field) : undefined}
                    >
                      {/* Indicateur de position de drop */}
                      {dragOverColumn === col.field && dragPosition && (
                        <div
                          className={`absolute top-0 bottom-0 w-1.5 bg-black dark:bg-white z-50 shadow-lg ${dragPosition === 'before' ? 'left-0' : 'right-0'
                            }`}
                          style={{
                            boxShadow: '0 0 12px rgba(0, 0, 0, 0.6)',
                            animation: 'pulse 1.5s ease-in-out infinite'
                          }}
                        />
                      )}
                      <div className="flex items-center justify-center gap-2 w-full">
                        {!isFixed && !workflowContext && (
                          <div
                            className="grip-handle opacity-0 group-hover:opacity-100 hover:bg-blue-100 dark:hover:bg-blue-900/30 rounded p-1.5 transition-all duration-200 active:scale-95"
                            draggable={true}
                            onDragStart={(e) => handleDragStart(e, col.field)}
                            title={t('dragToReorganize')}
                            style={{
                              cursor: 'grab',
                              touchAction: 'none',
                              userSelect: 'none'
                            }}
                            onMouseDown={(e) => {
                              (e.currentTarget as HTMLElement).style.cursor = 'grabbing';
                            }}
                            onMouseUp={(e) => {
                              (e.currentTarget as HTMLElement).style.cursor = 'grab';
                            }}
                          >
                            <GripVertical className="w-4 h-4 text-slate-500 dark:text-slate-400" />
                          </div>
                        )}
                        {col.field === 'checkbox' ? (
                          workflowContext || readOnly ? null : (
                            <div className="w-full flex items-center justify-center">
                              <input
                                type="checkbox"
                                checked={rows.length > 0 && rows.every(row => selectedRows.has(getRowUniqueKey(row)))}
                                onChange={rows.every(row => selectedRows.has(getRowUniqueKey(row))) ? clearSelection : selectAllRows}
                                className="rounded border-theme"
                              />
                            </div>
                          )
                        ) : (col.field === 'id' && !workflowContext && viewConfig.showCheckbox) ? null : (
                          <div className="flex items-center justify-center gap-2 flex-1">
                            {/* Icon based on column type */}
                            {col.type && COLUMN_TYPE_META[col.type] && (
                              <span className="text-theme-secondary flex-shrink-0">
                                {(() => {
                                  const Icon = COLUMN_TYPE_META[col.type].icon;
                                  return <Icon className="h-3.5 w-3.5" />;
                                })()}
                              </span>
                            )}
                            <span className="truncate" title={col.header_name}>
                              {col.header_name}
                            </span>
                            {/* Per-column kebab menu - replaces the inline select-checkbox + edit-pencil to
                              * avoid being clipped by sticky right-aligned columns. The menu uses a portal
                              * (PreviewActionMenu), so it always renders above everything else. The trigger
                              * button stops propagation to the th's onClick (sort).
                              * Stays visible when the column is selected (gives the user a clear signal). */}
                            {!workflowContext && !readOnly && !FIXED_COLUMNS.includes(col.field) && (
                              <div
                                className={`${selectedColumns.has(col.field) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'} transition-opacity flex items-center flex-shrink-0`}
                                onClick={(e) => e.stopPropagation()}
                              >
                                <PreviewActionMenu
                                  placement="below"
                                  triggerIcon={<MoreVertical className="h-4 w-4" />}
                                  triggerTitle={t('columnActions')}
                                  triggerClassName="!w-7 !h-7"
                                  items={[
                                    {
                                      id: 'select',
                                      label: selectedColumns.has(col.field)
                                        ? t('deselectColumn')
                                        : t('selectColumn'),
                                      icon: selectedColumns.has(col.field)
                                        ? <CheckSquare className="h-4 w-4" />
                                        : <Square className="h-4 w-4" />,
                                      onClick: () => toggleColumnSelection(col.field),
                                    },
                                    ...(openEditColumn ? [{
                                      id: 'edit',
                                      label: t('editColumn'),
                                      icon: <Pencil className="h-4 w-4" />,
                                      onClick: () => openEditColumn(col),
                                    }] : []),
                                  ]}
                                />
                              </div>
                            )}
                            {/* Sort indicator */}
                            {col.field !== 'checkbox' && col.sortable !== false && sortConfig?.key === col.field && (
                              <div className="flex items-center flex-shrink-0">
                                {sortConfig.direction === 'asc' ? (
                                  <ArrowUp className="w-3 h-3 text-blue-600" />
                                ) : (
                                  <ArrowDown className="w-3 h-3 text-blue-600" />
                                )}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    </th>
                  );
                })}
                {/* Bouton "+" pour ajouter des colonnes - seulement dans la vue data principale */}
                {!loadingColumns && !jsonPath && !workflowContext && !readOnly && (
                  <th
                    className="px-1 py-3 text-center font-medium text-theme-primary sticky top-0 right-0 bg-theme-secondary"
                    style={{
                      zIndex: 40,
                      minHeight: '60px',
                      width: FIXED_COLUMN_WIDTH,
                      minWidth: FIXED_COLUMN_WIDTH,
                      maxWidth: FIXED_COLUMN_WIDTH
                    }}
                  >
                    <Button
                      variant="default"
                      onClick={() => setShowAddColumnModal(true)}
                      className="w-5 h-5 p-0 rounded-full"
                      title={t('addColumn')}
                    >
                      <Plus className="w-4 h-4" />
                    </Button>
                  </th>
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {/* In `infiniteScroll` mode, only show skeletons on the initial load
                  (no rows yet). A subsequent `loadMore` fetch keeps the existing rows
                  mounted so the user's scroll position is preserved - the
                  LoadOlderSentinel below the tbody shows its own spinner during the
                  append. In non-infinite (prev/next) mode, keep the original behavior:
                  skeletons signal page-change activity. */}
              {tableLoading && (!infiniteScroll || rows.length === 0) ? (
                // Skeleton loading rows
                Array.from({ length: 5 }).map((_, skeletonIndex) => (
                  <tr key={`skeleton-${skeletonIndex}`} className="border border-transparent">
                    {getUniqueColumns().map((col, colIndex) => {
                      const isFixed = ['checkbox', 'id'].includes(col.field);
                      const hasCheckbox = getUniqueColumns().some(c => c.field === 'checkbox');
                      const idLeftOffset = hasCheckbox ? checkboxColumnWidth : '0px';

                      // Style dynamique pour la colonne checkbox
                      const cellStyle = col.field === 'checkbox' ? {
                        width: checkboxColumnWidth,
                        minWidth: checkboxColumnWidth,
                        maxWidth: checkboxColumnWidth
                      } : col.field === 'id' ? {
                        left: idLeftOffset,
                        width: idColumnWidth,
                        minWidth: idColumnWidth,
                        maxWidth: idColumnWidth,
                      } : {};

                      return (
                        <td
                          key={`skeleton-${skeletonIndex}-${col.field}`}
                          className={`px-3 py-3 text-center ${col.field === 'checkbox' ? 'sticky left-0 z-30 bg-theme-secondary' : col.field === 'id' ? 'sticky left-0 z-20 bg-theme-secondary' : ''}`}
                          style={cellStyle}
                        >
                          <div className="flex items-center justify-center">
                            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full max-w-[80%]" style={{
                              animationDelay: `${colIndex * 50}ms`
                            }}></div>
                          </div>
                        </td>
                      );
                    })}
                    {/* Cellule correspondante à la colonne "Add Column" dans skeleton */}
                    {!jsonPath && !workflowContext && !readOnly && (
                      <td
                        className="px-1 py-3 text-center sticky right-0 z-30"
                        style={{
                          width: FIXED_COLUMN_WIDTH,
                          minWidth: FIXED_COLUMN_WIDTH,
                          maxWidth: FIXED_COLUMN_WIDTH,
                          backgroundColor: 'transparent'
                        }}
                      >
                      </td>
                    )}
                  </tr>
                ))
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={getUniqueColumns().length + (!jsonPath && !workflowContext && !readOnly ? 1 : 0)} className="px-3 py-8 text-center text-theme-secondary">
                    No data found
                  </td>
                </tr>
              ) : (
                displayRows.map((rowOrGroup, index) => {
                  // 🇫🇷 Vérifier si c'est une ligne parente (pour les tableaux groupés par ID)
                  if ('type' in rowOrGroup && rowOrGroup.type === 'parent') {
                    const parent = rowOrGroup as unknown as { type: 'parent'; parentId: number; subRows: DataSourceItemRow[] };
                    const firstRow = parent.subRows[0];

                    return (
                      <tr
                        key={`parent-${parent.parentId}`}
                        className="bg-theme-secondary border-b border-theme"
                      >
                        {getUniqueColumns().map(col => {
                          const hasCheckbox = getUniqueColumns().some(c => c.field === 'checkbox');
                          const idLeftOffset = hasCheckbox ? checkboxColumnWidth : '0px';

                          // Style dynamique pour les cellules
                          const cellStyle = col.field === 'checkbox' ? {
                            width: checkboxColumnWidth,
                            minWidth: checkboxColumnWidth,
                            maxWidth: checkboxColumnWidth
                          } : col.field === 'id' ? {
                            left: idLeftOffset,
                            width: idColumnWidth,
                            minWidth: idColumnWidth,
                            maxWidth: idColumnWidth,
                          } : {};

                          // Classes de largeur pour les autres colonnes
                          const cellWidth = col.field === 'priority' ? 'w-24 max-w-32' :
                            col.field === 'created_at' || col.field === 'array_index' || col.field === 'value' ? 'w-32 max-w-48' : 'max-w-xs';

                          if (col.field === 'checkbox') {
                            // En mode workflow, ne pas afficher les checkboxes de lignes parentes
                            if (workflowContext) {
                              return null;
                            }
                            return (
                              <td key={`parent-${parent.parentId}-${col.field}`} className={`px-3 py-2 ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''} sticky left-0 z-10 bg-theme-primary`} style={cellStyle}>
                                <div className="w-full h-full flex items-center justify-center bg-theme-primary rounded relative group">
                                  {/* ID par défaut */}
                                  <span className={`text-sm font-mono text-theme-primary transition-opacity ${parent.subRows.every(subRow => selectedRows.has(getRowUniqueKey(subRow))) ? 'opacity-0' : 'opacity-100 group-hover:opacity-0'}`}>
                                    {parent.parentId}
                                  </span>
                                  {/* Checkbox au survol ou si sélectionnée */}
                                  {!readOnly && (
                                  <input
                                    type="checkbox"
                                    checked={parent.subRows.every(subRow => selectedRows.has(getRowUniqueKey(subRow)))}
                                    onChange={() => {
                                      const allSelected = parent.subRows.every(subRow => selectedRows.has(getRowUniqueKey(subRow)));
                                      if (allSelected) {
                                        // Désélectionner toutes les sous-lignes
                                        parent.subRows.forEach(subRow => {
                                          const uniqueKey = getRowUniqueKey(subRow);
                                          setSelectedRows(prev => {
                                            const newSet = new Set(prev);
                                            newSet.delete(uniqueKey);
                                            return newSet;
                                          });
                                        });
                                      } else {
                                        // Sélectionner toutes les sous-lignes
                                        parent.subRows.forEach(subRow => {
                                          const uniqueKey = getRowUniqueKey(subRow);
                                          setSelectedRows(prev => {
                                            const newSet = new Set(prev);
                                            newSet.add(uniqueKey);
                                            return newSet;
                                          });
                                        });
                                      }
                                    }}
                                    className={`rounded border-theme transition-opacity cursor-pointer absolute ${parent.subRows.every(subRow => selectedRows.has(getRowUniqueKey(subRow))) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                                    onClick={(e) => e.stopPropagation()}
                                  />
                                  )}
                                </div>
                              </td>
                            );
                          }

                          // Only skip the fixed ID column (row.id displayed in checkbox column)
                          // For data columns named 'id', we should display them normally
                          if (col.field === 'id' && !workflowContext && viewConfig.showCheckbox) {
                            // Fixed ID column is already displayed in the checkbox column
                            return null;
                          }

                          // Sticky ID column for workflow parent rows
                          if (col.field === 'id' && workflowContext) {
                            return (
                              <td key={`parent-${parent.parentId}-${col.field}`} className="px-2 py-2 text-center sticky left-0 z-20 bg-theme-secondary" style={cellStyle}>
                                <span className="text-sm font-mono text-theme-secondary">{parent.parentId}</span>
                              </td>
                            );
                          }

                          // Pour les autres colonnes, afficher les valeurs de la première ligne
                          if (col.field === 'priority') {
                            return (
                              <td key={`parent-${parent.parentId}-${col.field}`} className={`px-3 py-2 text-center ${cellWidth}`}>
                                <span className="text-sm text-theme-primary">{firstRow.priority}</span>
                              </td>
                            );
                          }

                          if (col.field === 'created_at') {
                            return (
                              <td key={`parent-${parent.parentId}-${col.field}`} className={`px-3 py-2 text-center ${cellWidth}`}>
                                <span className="text-sm text-theme-primary truncate block">
                                  {formatUtcDateTime(firstRow.created_at, { locale })}
                                </span>
                              </td>
                            );
                          }

                          // Pour les colonnes dynamiques, afficher un indicateur
                          return (
                            <td key={`parent-${parent.parentId}-${col.field}`} className={`px-3 py-2 text-center ${cellWidth}`}>
                              <span className="text-xs text-theme-secondary italic">
                                ({parent.subRows.length} items)
                              </span>
                            </td>
                          );
                        })}
                        {/* Cellule correspondante à la colonne "Add Column" dans les lignes parentes */}
                        {!jsonPath && !workflowContext && !readOnly && (
                          <td
                            className="px-1 py-2 text-center sticky right-0 z-30"
                            style={{
                              width: FIXED_COLUMN_WIDTH,
                              minWidth: FIXED_COLUMN_WIDTH,
                              maxWidth: FIXED_COLUMN_WIDTH,
                              backgroundColor: 'transparent'
                            }}
                          >
                          </td>
                        )}
                      </tr>
                    );
                  }

                  // 🇫🇷 C'est une ligne normale ou une sous-ligne
                  const row = rowOrGroup as DataSourceItemRow;

                  // Détecter si c'est une sous-ligne (dans un tableau groupé par ID)
                  const isSubRow = jsonPath && row.data?.array_index !== undefined;
                  const prevRow = displayRows[index - 1];
                  const isParentRow = prevRow && 'type' in prevRow && (prevRow as any).type === 'parent';

                  // Pour les tableaux, utiliser une clé unique combinant l'ID et l'index du tableau
                  // car plusieurs éléments d'un tableau peuvent avoir le même row.id (ID de la ligne parente)
                  // Inclure l'index de la liste pour garantir l'unicité
                  const rowKey = row.data?.array_index !== undefined
                    ? `${row.id}-${row.data.array_index}-${index}`
                    : `${row.id}-${index}`;

                  // 🇫🇷 Détecter si c'est le premier élément d'un nouveau groupe (nouveau parent)
                  // Un groupe commence quand l'ID parent change
                  const isArrayElement = jsonPath && row.data?.array_index !== undefined;
                  const isFirstInGroup = index === 0 ||
                    (isArrayElement && displayRows[index - 1] && 'id' in displayRows[index - 1] && (displayRows[index - 1] as DataSourceItemRow).id !== row.id);

                  // 🇫🇷 Détecter si c'est le dernier élément d'un groupe
                  const isLastInGroup = index === displayRows.length - 1 ||
                    (isArrayElement && displayRows[index + 1] && 'id' in displayRows[index + 1] && (displayRows[index + 1] as DataSourceItemRow).id !== row.id);

                  // 🇫🇷 Calculer le numéro de groupe pour alterner les couleurs de fond
                  let groupNumber = 0;
                  if (isArrayElement) {
                    let currentGroupId = displayRows[0] && 'id' in displayRows[0] ? (displayRows[0] as DataSourceItemRow).id : null;
                    for (let i = 1; i <= index; i++) {
                      if (displayRows[i] && 'id' in displayRows[i]) {
                        const currentRow = displayRows[i] as DataSourceItemRow;
                        if (currentRow.id !== currentGroupId) {
                          groupNumber++;
                          currentGroupId = currentRow.id;
                        }
                      }
                    }
                  }

                  // 🇫🇷 Classes CSS pour le regroupement visuel
                  const groupClasses = isArrayElement
                    ? (isFirstInGroup ? 'border-t-2 border-theme-secondary' : 'border-t border-theme-tertiary/30')
                    : '';

                  // 🇫🇷 Background alterné pour les groupes (très subtil pour ne pas gêner)
                  // Ne pas appliquer aux sous-lignes, elles ont leur propre background
                  const groupBg = isArrayElement && groupNumber % 2 === 1 && !isSubRow
                    ? 'bg-theme-secondary/20'
                    : '';

                  // Classes pour les sous-lignes (indentation visuelle)
                  // Le même border que le header
                  const subRowClasses = isSubRow ? 'border-b border-theme' : '';

                  return (
                    <tr key={rowKey} className={`border border-transparent hover-row-item ${selectedRows.has(getRowUniqueKey(row)) ? 'focus-selected' : ''} ${groupClasses} ${subRowClasses} ${groupBg} ${workflowContext ? 'h-12' : ''}`}>
                      {getUniqueColumns().map(col => {
                        const hasCheckbox = getUniqueColumns().some(c => c.field === 'checkbox');
                        const idLeftOffset = hasCheckbox ? checkboxColumnWidth : '0px';

                        // Style dynamique pour les cellules
                        const cellStyle = col.field === 'checkbox' ? {
                          width: checkboxColumnWidth,
                          minWidth: checkboxColumnWidth,
                          maxWidth: checkboxColumnWidth
                        } : col.field === 'id' ? {
                          left: idLeftOffset,
                          width: idColumnWidth,
                          minWidth: idColumnWidth,
                          maxWidth: idColumnWidth,
                        } : {};

                        // Classes de largeur pour les autres colonnes
                        const cellWidth = col.field === 'priority' ? 'w-24 max-w-32' :
                          col.field === 'created_at' || col.field === 'array_index' || col.field === 'value' ? 'w-32 max-w-48' : 'max-w-xs';

                        // Rendu des cellules fixes
                        // Handler pour cacher la prévisualisation quand on survole une cellule non-JSON
                        const handleNonJsonCellMouseEnter = () => {
                          // Cacher immédiatement la fenêtre contextuelle
                          if (hoveredCell) {
                            setHoveredCell(null);
                          }
                        };

                        if (col.field === 'checkbox') {
                          // En mode workflow, ne pas afficher les checkboxes de lignes
                          if (workflowContext) {
                            return null;
                          }
                          return (
                            <td
                              key={`${rowKey}-${col.field}`}
                              className={`px-3 py-2 ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''} sticky left-0 z-30 bg-theme-primary`}
                              style={cellStyle}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                            >
                              <div className="w-full h-full flex items-center justify-center bg-theme-primary rounded relative group">
                                {/* ID par défaut */}
                                <span className={`text-sm font-mono text-theme-primary transition-opacity ${selectedRows.has(getRowUniqueKey(row)) ? 'opacity-0' : 'opacity-100 group-hover:opacity-0'}`}>
                                  {row.row_index ?? row.id}
                                </span>
                                {/* Checkbox au survol ou si sélectionnée */}
                                {!readOnly && (
                                  <input
                                    type="checkbox"
                                    checked={selectedRows.has(getRowUniqueKey(row))}
                                    onChange={() => toggleRowSelection(row)}
                                    className={`rounded border-theme transition-opacity cursor-pointer absolute ${selectedRows.has(getRowUniqueKey(row)) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                                  />
                                )}
                              </div>
                            </td>
                          );
                        }

                        // Only skip the fixed ID column (row.id displayed in checkbox column)
                        // For data columns named 'id' (from row.data.id), we should display them normally
                        if (col.field === 'id' && !workflowContext && viewConfig.showCheckbox) {
                          // Fixed ID column is already displayed in the checkbox column
                          return null;
                        }

                        // Sticky ID column for workflow context (leftmost fixed column)
                        if (col.field === 'id' && workflowContext) {
                          return (
                            <td
                              key={`${rowKey}-${col.field}`}
                              className="px-2 py-2 text-center sticky left-0 z-20 bg-theme-primary"
                              style={cellStyle}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                            >
                              <span className="text-sm font-mono text-theme-secondary">{getValueAtPath(row.data, 'id') ?? row.id}</span>
                            </td>
                          );
                        }

                        if (col.field === 'priority') {
                          // Pour les sous-lignes de tableaux seulement, ne rien afficher
                          // En mode JSON (objet nested), on garde la priority pour toutes les lignes
                          const isArraySubRow = isSubRow && row.data?.array_index !== undefined;
                          if (isArraySubRow) {
                            return (
                              <td
                                key={`${rowKey}-${col.field}`}
                                className={`px-3 py-2 text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                onMouseEnter={handleNonJsonCellMouseEnter}
                              >
                                {/* Vide pour les sous-lignes de tableaux */}
                              </td>
                            );
                          }

                          // Pour les lignes normales et mode JSON, la priority est éditable (sauf en mode workflow)
                          if (workflowContext) {
                            return (
                              <td
                                key={`${rowKey}-${col.field}`}
                                className={`px-3 py-2 text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                onMouseEnter={handleNonJsonCellMouseEnter}
                              >
                                <span className="text-sm text-theme-primary">{row.priority}</span>
                              </td>
                            );
                          }
                          return (
                            <EditableCell
                              key={`${rowKey}-${col.field}`}
                              value={row.priority}
                              onSave={(newValue) => handleSaveEdit(row.id, 'priority', newValue)}
                              className={`text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                              columnType="number"
                              readOnly={readOnly}
                            />
                          );
                        }

                        if (col.field === 'created_at') {
                          // Pour les sous-lignes de tableaux seulement, ne rien afficher
                          // En mode JSON (objet nested), on garde created_at pour toutes les lignes
                          const isArraySubRow = isSubRow && row.data?.array_index !== undefined;
                          if (isArraySubRow) {
                            return (
                              <td
                                key={`${rowKey}-${col.field}`}
                                className={`px-3 py-2 text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                onMouseEnter={handleNonJsonCellMouseEnter}
                              >
                                {/* Vide pour les sous-lignes de tableaux */}
                              </td>
                            );
                          }

                          // Pour les lignes normales et mode JSON, afficher la date
                          return (
                            <td
                              key={`${rowKey}-${col.field}`}
                              className={`px-3 py-2 text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                            >
                              <span className="text-sm text-theme-primary truncate block" title={formatUtcDateTime(row.created_at, { locale })}>
                                {formatUtcDateTime(row.created_at, { locale })}
                              </span>
                            </td>
                          );
                        }

                        // Rendu des colonnes dynamiques (data.*) - EXACTEMENT comme dans la demo
                        // Pour les données imbriquées, le champ est directement la clé JSON
                        // Pour les données normales, le champ a le préfixe "data."
                        const fieldPath = getFieldPath(col.field);
                        const value = getValueAtPath(row.data, fieldPath);
                        const totalColumns = getUniqueColumns().length;
                        const isFewColumns = totalColumns <= 4;
                        const dynamicCellWidth = isFewColumns ? 'w-[200px] max-w-[200px]' : 'w-auto min-w-[120px] max-w-xs';
                        const sharedCellClass = `px-3 py-2 ${dynamicCellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`;
                        // Skip visual cell rendering for workflow steps - use backend renderType instead
                        if (!row._isWorkflowStep) {
                          const specialCell = renderVisualCell(row, col, value, String(rowKey), sharedCellClass, handleNonJsonCellMouseEnter);
                          if (specialCell) {
                            return specialCell;
                          }
                        }

                        // Pour array_index, rendre en lecture seule (même style que created_at)
                        if (col.field === 'array_index') {
                          return (
                            <td
                              key={`${rowKey}-${col.field}`}
                              className={`px-3 py-2 text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                            >
                              <span className="text-sm text-theme-primary truncate block" title="Array index (read-only)">
                                {value ?? ''}
                              </span>
                            </td>
                          );
                        }

                        // Pour value (tableaux de primitifs), rendre avec le même style que created_at (moins visible)
                        if (col.field === 'value') {
                          return (
                            <EditableCell
                              key={`${rowKey}-${col.field}`}
                              value={value}
                              onSave={(newValue) => handleSaveEdit(row.id, col.field, newValue, row.data?.array_index)}
                              className={`text-center ${cellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                              onMouseEnter={handleNonJsonCellMouseEnter}
                              columnType={col.type}
                              readOnly={readOnly}
                            />
                          );
                        }

                        // 🇫🇷 Détecter si c'est un JSON navigable
                        // Détection améliorée : vérifier d'abord le contenu réel, puis le type de colonne
                        const isNavigableJson = (() => {
                          // Si la valeur est null/undefined, pas navigable
                          if (value === null || value === undefined) {
                            return false;
                          }

                          // Vérifier si c'est une string JSON
                          let parsedValue = value;
                          if (typeof value === 'string') {
                            try {
                              parsedValue = JSON.parse(value);
                            } catch (e) {
                              // Pas un JSON valide
                              return false;
                            }
                          }

                          // Vérifier si la valeur est un objet JSON navigable
                          // Un JSON navigable est :
                          // - Un objet (pas null, pas undefined, pas array, pas primitif)
                          // - Non vide (au moins une clé)
                          if (typeof parsedValue === 'object' &&
                            parsedValue !== null &&
                            !Array.isArray(parsedValue)) {
                            const keys = Object.keys(parsedValue);

                            // Si l'objet a au moins une clé, vérifier si c'est un objet plain
                            if (keys.length > 0) {
                              // Vérifier que c'est un objet plain (pas Date, RegExp, Error, etc.)
                              // Un objet plain a constructor === Object ou est un objet littéral
                              const isPlainObject = parsedValue.constructor === Object ||
                                parsedValue.constructor === undefined ||
                                (typeof parsedValue.constructor === 'function' &&
                                  parsedValue.constructor.name === 'Object');

                              if (isPlainObject) {
                                // console.log(`[Navigation] Detected navigable JSON in column ${col.field}:`, keys.length, 'keys');
                                return true;
                              }
                            } else {
                              // Objet vide - ne pas le marquer comme navigable, même si la colonne est marquée comme navigable
                              // console.log(`[Navigation] Empty JSON object in column ${col.field} - not navigable`);
                              return false;
                            }
                          }

                          // Si la colonne est explicitement marquée comme navigable mais le contenu n'est pas un objet JSON valide
                          // Ne pas le marquer comme navigable
                          return false;
                        })();

                        // 🇫🇷 Détecter si c'est un tableau navigable
                        const isNavigableArray = (() => {
                          if (value == null) return false;

                          // Si c'est déjà un tableau
                          if (Array.isArray(value)) {
                            return value.length > 0; // Tableau non vide
                          }

                          // Si c'est une string, essayer de parser en JSON
                          if (typeof value === 'string') {
                            try {
                              const parsedValue = JSON.parse(value);
                              if (Array.isArray(parsedValue)) {
                                return parsedValue.length > 0;
                              }
                            } catch (e) {
                              // Pas du JSON valide
                            }
                          }

                          return false;
                        })();

                        // 🇫🇷 Si navigable (objet JSON ou tableau), afficher avec navigation au clic (priorité sur édition)
                        if (isNavigableJson || isNavigableArray) {
                          const jsonKey = getFieldPath(col.field);
                          // Parser la valeur si c'est une string
                          let displayValue = value;
                          if (typeof value === 'string') {
                            try {
                              displayValue = JSON.parse(value);
                            } catch (e) {
                              // Si parsing échoue, utiliser la valeur originale
                              displayValue = value;
                            }
                          }

                          const handleNavigate = (e: React.MouseEvent) => {
                            e.preventDefault();
                            e.stopPropagation();

                            // Fermer immédiatement le popover
                            if (closeHoverTimeoutRef.current) {
                              clearTimeout(closeHoverTimeoutRef.current);
                              closeHoverTimeoutRef.current = null;
                            }
                            setHoveredCell(null);

                            console.log(`[Navigation] Clicking on ${isNavigableArray ? 'array' : 'JSON'} cell: ${col.field}, rowId: ${row.id}`);

                            // Construire le nouveau chemin en ajoutant le champ au chemin courant
                            const currentPath = jsonPath || '';

                            // Check if this row came from an array expansion (has array_index)
                            // If so, we need to include the index in the path
                            const arrayIndex = row.data?.array_index;
                            let newPath: string;
                            if (arrayIndex !== undefined && currentPath) {
                              // We're navigating from within an array item, include the index
                              newPath = `${currentPath}.${arrayIndex}.${jsonKey}`;
                            } else {
                              newPath = currentPath ? `${currentPath}.${jsonKey}` : jsonKey;
                            }

                            const pathSegments = newPath.split('.');
                            if (onNavigate) {
                              // Use callback for modal navigation
                              onNavigate(newPath);
                            } else if (workflowContext) {
                              console.log(`[Navigation] Navigating to: /app/workflow/${workflowContext.workflowId}/run/${workflowContext.runId}/step/${workflowContext.stepId}/${pathSegments.join('/')}`);
                              navigateTo(`/app/workflow/${workflowContext.workflowId}/run/${workflowContext.runId}/step/${workflowContext.stepId}/${pathSegments.join('/')}`);
                            } else {
                              console.log(`[Navigation] Navigating to: ${dataSourceBasePath}/${dataSourceId}/${pathSegments.join('/')}`);
                              navigateTo(`${dataSourceBasePath}/${dataSourceId}/${pathSegments.join('/')}`);
                            }
                          };

                          const formattedJson = JSON.stringify(displayValue, null, 2);

                          const handleMouseEnter = (e: React.MouseEvent<HTMLSpanElement>) => {
                            // Annuler tout timeout de fermeture en attente
                            if (closeHoverTimeoutRef.current) {
                              clearTimeout(closeHoverTimeoutRef.current);
                              closeHoverTimeoutRef.current = null;
                            }
                            // Mettre à jour directement sans fermer
                            setHoveredCell({
                              rowId: row.id,
                              colField: col.field,
                              anchorElement: e.currentTarget,
                              jsonData: displayValue,
                              headerName: col.header_name
                            });
                          };

                          const handleMouseLeave = () => {
                            // Délai avant de fermer pour permettre de bouger vers la popover
                            if (closeHoverTimeoutRef.current) {
                              clearTimeout(closeHoverTimeoutRef.current);
                            }
                            closeHoverTimeoutRef.current = setTimeout(() => {
                              // Ne pas fermer si la souris est sur la popover
                              if (!isHoveringPopoverRef.current) {
                                setHoveredCell(null);
                              }
                            }, 150);
                          };

                          const isHovered = hoveredCell?.rowId === row.id && hoveredCell?.colField === col.field;

                          // Affichage différent pour tableaux et objets JSON
                          const displayText = isNavigableArray ? `[...]` : `{...}`;

                          return (
                            <td
                              key={`${rowKey}-${col.field}`}
                              className={`px-3 py-2 text-center ${dynamicCellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''} cursor-pointer group relative`}
                              onClick={handleNavigate}
                              style={{ position: 'relative', zIndex: 10 }}
                            >
                              <div className="relative flex items-center justify-center z-10">
                                <span
                                  className="text-theme-muted font-mono text-sm hover:text-theme-primary transition-colors cursor-help"
                                  onMouseEnter={handleMouseEnter}
                                  onMouseLeave={handleMouseLeave}
                                >
                                  {displayText}
                                </span>
                                <ArrowRight className="absolute left-full ml-1 w-4 h-4 text-theme-primary opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
                              </div>
                            </td>
                          );
                        }

                        // Sinon, cellule éditable normale (sauf en mode workflow)
                        if (workflowContext && row._isWorkflowStep) {
                          // En mode workflow avec stepData, use renderType from backend if available
                          const renderType = col.renderType;
                          const colWidth = col.width ? `w-[${col.width}px] min-w-[${col.width}px]` : dynamicCellWidth;

                          // Handle output column with storage-backed navigation
                          if (col.field === 'output' && col.renderType === 'JSON_NAVIGABLE' && row._outputStorageId) {
                            if (value) {
                              const handleNavigate = (e: React.MouseEvent) => {
                                e.preventDefault();
                                e.stopPropagation();
                                const currentPath = jsonPath || '';
                                const newPath = currentPath ? `${currentPath}.output` : 'output';
                                if (onNavigate) {
                                  onNavigate(newPath);
                                } else if (workflowContext) {
                                  navigateTo(`/app/workflow/${workflowContext.workflowId}/run/${workflowContext.runId}/step/${workflowContext.stepId}/${newPath}`);
                                }
                              };

                              return (
                                <td
                                  key={`${rowKey}-${col.field}`}
                                  className={`px-3 py-2 text-center align-middle ${colWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''} cursor-pointer group relative`}
                                  onClick={handleNavigate}
                                  onMouseEnter={() => {
                                    if (hoveredCell) {
                                      setHoveredCell(null);
                                    }
                                  }}
                                >
                                  <div className="flex items-center justify-center gap-2 relative z-20 h-full">
                                    <FileText className="w-4 h-4 text-theme-muted" />
                                    <ArrowRight className="w-4 h-4 text-theme-primary flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
                                  </div>
                                </td>
                              );
                            } else {
                              return (
                                <td
                                  key={`${rowKey}-${col.field}`}
                                  className={`px-3 py-2 text-center align-middle ${colWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                  onMouseEnter={() => {
                                    if (hoveredCell) {
                                      setHoveredCell(null);
                                    }
                                  }}
                                >
                                  <NoData />
                                </td>
                              );
                            }
                          }

                          // For non-editable render types (badges, progress, etc.), use read-only Renderer
                          if (NON_EDITABLE_RENDER_TYPES.has(renderType || '')) {
                            const Renderer = getRenderer(renderType || 'TEXT');
                            const handleCellNavigate = (path: string) => {
                              if (onNavigate) {
                                onNavigate(path);
                              } else if (workflowContext) {
                                navigateTo(`/app/workflow/${workflowContext.workflowId}/run/${workflowContext.runId}/step/${workflowContext.stepId}/${path}`);
                              }
                            };

                            return (
                              <td
                                key={`${rowKey}-${col.field}`}
                                className={`px-3 py-2 text-center align-middle ${colWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                onMouseEnter={() => {
                                  if (hoveredCell) {
                                    setHoveredCell(null);
                                  }
                                }}
                              >
                                <div className="flex items-center justify-center h-full">
                                  <Renderer value={value} field={col.field} width={col.width} onNavigate={handleCellNavigate} />
                                </div>
                              </td>
                            );
                          }

                          // For editable render types (TEXT, CODE, etc.), use EditableCell
                          return (
                            <EditableCell
                              key={`${rowKey}-${col.field}`}
                              value={value}
                              onSave={(newValue) => handleSaveEdit(row.id, col.field, newValue, row.data?.array_index)}
                              className={`text-center ${colWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                              onMouseEnter={() => {
                                if (hoveredCell) {
                                  setHoveredCell(null);
                                }
                              }}
                              columnType={col.type}
                              readOnly={readOnly}
                            />
                          );
                        }

                        if (workflowContext) {
                          // For non-editable render types, keep read-only Renderer
                          if (NON_EDITABLE_RENDER_TYPES.has(col.renderType || '')) {
                            const Renderer = getRenderer(col.renderType || 'TEXT');
                            const handleCellNavigate = (path: string) => {
                              if (onNavigate) {
                                onNavigate(path);
                              } else if (workflowContext) {
                                navigateTo(`/app/workflow/${workflowContext.workflowId}/run/${workflowContext.runId}/step/${workflowContext.stepId}/${path}`);
                              }
                            };

                            return (
                              <td
                                key={`${rowKey}-${col.field}`}
                                className={`px-3 py-2 text-center align-middle ${dynamicCellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                                onMouseEnter={() => {
                                  if (hoveredCell) {
                                    setHoveredCell(null);
                                  }
                                }}
                              >
                                <div className="flex items-center justify-center h-full">
                                  <Renderer value={value} field={col.field} width={col.width} onNavigate={handleCellNavigate} />
                                </div>
                              </td>
                            );
                          }
                          // For editable render types, fall through to EditableCell below
                        }
                        return (
                          <EditableCell
                            key={`${rowKey}-${col.field}`}
                            value={value}
                            onSave={(newValue) => handleSaveEdit(row.id, col.field, newValue, row.data?.array_index)}
                            className={`text-center ${dynamicCellWidth} ${selectedColumns.has(col.field) ? 'bg-theme-tertiary hover:bg-theme-tertiary' : ''}`}
                            onMouseEnter={() => {
                              // Cacher la prévisualisation si on survole une cellule non-JSON
                              if (hoveredCell) {
                                setHoveredCell(null);
                              }
                            }}
                            columnType={col.type}
                            readOnly={readOnly}
                          />
                        );
                      })}
                      {/* Cellule correspondante à la colonne "Add Column" dans les lignes normales */}
                      {!jsonPath && !workflowContext && !readOnly && (
                        <td
                          className="px-1 py-2 text-center sticky right-0 z-30"
                          style={{
                            width: FIXED_COLUMN_WIDTH,
                            minWidth: FIXED_COLUMN_WIDTH,
                            maxWidth: FIXED_COLUMN_WIDTH,
                            backgroundColor: 'transparent'
                          }}
                        >
                        </td>
                      )}
                    </tr>
                  );
                })
              )}
              {/* Ligne sticky en bas pour ajouter des lignes - seulement dans la vue data principale */}
              {!jsonPath && !workflowContext && !readOnly && (
                <AddRowForm
                  columns={columns}
                  getUniqueColumns={getUniqueColumns}
                  getDynamicColumns={getDynamicColumns}
                  getFieldPath={getFieldPath}
                  viewConfig={viewConfig}
                  checkboxColumnWidth={calculateCheckboxColumnWidth(rows)}
                  isAddingRowInline={isAddingRowInline}
                  isAddingRow={isAddingRow}
                  newRowPriority={newRowPriority}
                  newRowData={newRowData}
                  onStartAddingRow={startAddingRowInline}
                  onCancelAddingRow={cancelAddingRowInline}
                  onAddRow={addNewRow}
                  onPriorityChange={setNewRowPriority}
                  onRowDataChange={handleRowDataChange}
                />
              )}
            </tbody>
          </table>
          {/* Infinite-scroll trigger - appears below the table inside the same scroll
              container, so it intersects only when the user has scrolled past the last
              row. Hidden once `pagination.hasMore` becomes false. */}
          {infiniteScroll && (
            <LoadOlderSentinel
              placement="bottom"
              scrollRoot={scrollRoot}
              hasMore={controller.pagination.hasMore}
              loading={controller.tableLoading}
              onLoadOlder={controller.loadMore}
              loadingLabel={tRunSteps('loadingMoreRows')}
              idleLabel={tRunSteps('scrollForMoreRows')}
            />
          )}
        </div>
      </div>

      {/* Prévisualisation du JSON au hover avec positionnement intelligent */}
      {hoveredCell && (
        <JsonPreviewPopover
          anchorElement={hoveredCell.anchorElement}
          jsonData={hoveredCell.jsonData}
          headerName={hoveredCell.headerName}
          rowId={hoveredCell.rowId}
          onClose={() => setHoveredCell(null)}
          isHoveringPopoverRef={isHoveringPopoverRef}
          onPin={handlePinPopover}
        />
      )}

      {/* Popovers épinglées (indépendantes, draggables) */}
      {pinnedPopovers.map(pinned => (
        <PinnedJsonPopover
          key={pinned.id}
          {...pinned}
          onClose={handleClosePinnedPopover}
          onMerge={handleMergePopovers}
        />
      ))}
    </>
  );
}
