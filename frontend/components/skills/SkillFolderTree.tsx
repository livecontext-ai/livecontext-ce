'use client';

import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import {
  Folder, FolderOpen, ChevronRight, Zap,
  MoreVertical, Pencil, Trash2, FolderPlus, Plus, Globe, Clock,
  Check, Square
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover';
import { Switch } from '@/components/ui/switch';
import { PublicationStatusIcon } from '@/components/publications/PublicationStatusIcon';

import type { SkillFolder, Skill } from '@/lib/api';

interface TreeNode {
  folder: SkillFolder;
  children: TreeNode[];
}

interface DragItem {
  type: 'skill' | 'folder';
  id: string;
}

interface SkillFolderTreeProps {
  allFolders: SkillFolder[];
  allSkills: Skill[];
  onCreateFolder: (parentId: string | null) => void;
  onRenameFolder: (id: string, currentName: string) => void;
  onDeleteFolder: (id: string) => void;
  onCreateSkill: (folderId: string | null) => void;
  onEditSkill: (skill: Skill) => void;
  onDeleteSkill: (id: string) => void;
  onMoveSkillToFolder: (skillId: string, targetFolderId: string | null) => void;
  onMoveFolderToFolder: (folderId: string, targetParentId: string | null) => void;
  onShareSkill?: (skill: Skill) => void;
  onUnshareSkill?: (skillId: string) => void;
  publishedSkillIds?: Set<string>;
  pendingReviewSkillIds?: Set<string>;
  rejectedSkillReasons?: Map<string, string | null>;
  /** True when the viewing user has the ADMIN role. Controls global-skill management UI. */
  isAdmin?: boolean;
  // V275/V276 (2026-05-21) - per-user effective-active resolution.
  /** Map of {skillId: active} for the calling user. Absence = use skill.isDefaultActive. */
  userOverrides?: Record<string, boolean>;
  /** Toggle the user's per-skill override (effective active state for this user only). */
  onToggleSkillActive?: (skillId: string, nextActive: boolean) => void;
  /** Toggle the org/global default-active flag (owner / admin-for-globals). */
  onToggleSkillIsDefaultActive?: (skillId: string, isDefaultActive: boolean) => void;
  /** Admin-only - flip the skill's is_global visibility. */
  onToggleSkillGlobal?: (skillId: string, isGlobal: boolean) => void;
  /** Admin-only - flip the folder's is_global visibility. */
  onToggleFolderGlobal?: (folderId: string, isGlobal: boolean) => void;
}

const TREE_ML = 'ml-[23px]';
const TREE_PL = 'pl-3';
const DROP_HIGHLIGHT = 'bg-blue-100/50 dark:bg-blue-900/30 ring-1 ring-blue-400/50';
const AUTO_EXPAND_DELAY = 500;

export function SkillFolderTree({
  allFolders,
  allSkills,
  onCreateFolder,
  onRenameFolder,
  onDeleteFolder,
  onCreateSkill,
  onEditSkill,
  onDeleteSkill,
  onMoveSkillToFolder,
  onMoveFolderToFolder,
  onShareSkill,
  onUnshareSkill,
  publishedSkillIds,
  pendingReviewSkillIds,
  rejectedSkillReasons,
  isAdmin = false,
  userOverrides,
  onToggleSkillActive,
  onToggleSkillIsDefaultActive,
  onToggleSkillGlobal,
  onToggleFolderGlobal,
}: SkillFolderTreeProps) {
  const t = useTranslations('emptyState.skill');
  const tCommon = useTranslations('common');
  const tMarketplace = useTranslations('marketplace');
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [dragItem, setDragItem] = useState<DragItem | null>(null);
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const autoExpandTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const allFolderIds = useMemo(() => new Set(allFolders.map(f => f.id)), [allFolders]);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(allFolderIds);

  useEffect(() => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      for (const id of allFolderIds) next.add(id);
      return next;
    });
  }, [allFolderIds]);

  const tree = useMemo(() => {
    const childrenMap = new Map<string | null, SkillFolder[]>();
    for (const f of allFolders) {
      const key = f.parentId || null;
      if (!childrenMap.has(key)) childrenMap.set(key, []);
      childrenMap.get(key)!.push(f);
    }
    const build = (parentId: string | null): TreeNode[] =>
      (childrenMap.get(parentId) || [])
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(folder => ({ folder, children: build(folder.id) }));
    return build(null);
  }, [allFolders]);

  const skillsByFolder = useMemo(() => {
    const map = new Map<string | null, Skill[]>();
    for (const s of allSkills) {
      const key = s.folderId || null;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(s);
    }
    return map;
  }, [allSkills]);

  // Build a set of descendant folder IDs for a given folder (including itself)
  const getDescendantIds = useCallback((folderId: string): Set<string> => {
    const descendants = new Set<string>([folderId]);
    for (const f of allFolders) {
      if (f.parentId && descendants.has(f.parentId)) {
        descendants.add(f.id);
      }
    }
    // Multiple passes to handle deeper nesting
    let changed = true;
    while (changed) {
      changed = false;
      for (const f of allFolders) {
        if (f.parentId && descendants.has(f.parentId) && !descendants.has(f.id)) {
          descendants.add(f.id);
          changed = true;
        }
      }
    }
    return descendants;
  }, [allFolders]);

  const folderById = useMemo(() => {
    const map = new Map<string, SkillFolder>();
    for (const f of allFolders) map.set(f.id, f);
    return map;
  }, [allFolders]);

  const skillById = useMemo(() => {
    const map = new Map<string, Skill>();
    for (const s of allSkills) map.set(s.id, s);
    return map;
  }, [allSkills]);

  // Check if a drop is valid
  const isValidDrop = useCallback((item: DragItem, targetFolderId: string | null): boolean => {
    if (item.type === 'skill') {
      const skill = skillById.get(item.id);
      // Skip if already in target folder
      return (skill?.folderId || null) !== targetFolderId;
    }
    // Folder drag
    const folder = folderById.get(item.id);
    if (!folder) return false;
    // Can't drop on itself
    if (targetFolderId === item.id) return false;
    // Can't drop on a descendant
    if (targetFolderId && getDescendantIds(item.id).has(targetFolderId)) return false;
    // Skip if already in target folder
    return (folder.parentId || null) !== targetFolderId;
  }, [skillById, folderById, getDescendantIds]);

  const clearAutoExpandTimer = useCallback(() => {
    if (autoExpandTimerRef.current) {
      clearTimeout(autoExpandTimerRef.current);
      autoExpandTimerRef.current = null;
    }
  }, []);

  // DnD handlers
  const handleDragStart = useCallback((item: DragItem, e: React.DragEvent) => {
    setDragItem(item);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', JSON.stringify(item));
  }, []);

  const handleDragEnd = useCallback(() => {
    setDragItem(null);
    setDropTargetId(null);
    clearAutoExpandTimer();
  }, [clearAutoExpandTimer]);

  const handleDragOver = useCallback((targetId: string | null, e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!dragItem) return;
    if (!isValidDrop(dragItem, targetId)) {
      e.dataTransfer.dropEffect = 'none';
      return;
    }
    e.dataTransfer.dropEffect = 'move';
    const key = targetId ?? '__root__';
    setDropTargetId(key);
  }, [dragItem, isValidDrop]);

  const handleDragEnter = useCallback((targetId: string | null, e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!dragItem) return;
    if (!isValidDrop(dragItem, targetId)) return;
    const key = targetId ?? '__root__';
    setDropTargetId(key);
    // Auto-expand collapsed folders after delay
    if (targetId && !expandedIds.has(targetId)) {
      clearAutoExpandTimer();
      autoExpandTimerRef.current = setTimeout(() => {
        setExpandedIds(prev => {
          const next = new Set(prev);
          next.add(targetId);
          return next;
        });
      }, AUTO_EXPAND_DELAY);
    }
  }, [dragItem, isValidDrop, expandedIds, clearAutoExpandTimer]);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.stopPropagation();
    // Only clear if leaving the element (not entering a child)
    const relatedTarget = e.relatedTarget as HTMLElement | null;
    if (relatedTarget && (e.currentTarget as HTMLElement).contains(relatedTarget)) return;
    setDropTargetId(null);
    clearAutoExpandTimer();
  }, [clearAutoExpandTimer]);

  const handleDrop = useCallback((targetFolderId: string | null, e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDropTargetId(null);
    clearAutoExpandTimer();
    if (!dragItem) return;
    if (!isValidDrop(dragItem, targetFolderId)) return;

    if (dragItem.type === 'skill') {
      onMoveSkillToFolder(dragItem.id, targetFolderId);
    } else {
      onMoveFolderToFolder(dragItem.id, targetFolderId);
    }
    setDragItem(null);
  }, [dragItem, isValidDrop, onMoveSkillToFolder, onMoveFolderToFolder, clearAutoExpandTimer]);

  const toggleExpand = useCallback((id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  // Shared menu item style (matches ConversationSidebar)
  const menuItemClass = "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800";
  const menuItemDangerClass = "w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30";

  // "+" button with popover menu
  const renderAddButton = (folderId: string | null) => {
    const addKey = `add:${folderId ?? '__root__'}`;

    return (
      <div className="flex items-center h-8 -ml-[15px]">
        <Popover open={openMenuId === addKey} onOpenChange={(open) => setOpenMenuId(open ? addKey : null)}>
          <PopoverTrigger asChild>
            <Button
              variant="ghostGray"
              aria-label={t('addMenu')}
              onClick={(e) => e.stopPropagation()}
              className="w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center"
              style={{ marginLeft: '4px' }}
            >
              <Plus className="w-4 h-4 flex-shrink-0" />
            </Button>
          </PopoverTrigger>
          <PopoverContent
            align="start"
            sideOffset={5}
            className="w-auto min-w-[160px] p-2 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
          >
            <div className="space-y-1">
              <button
                onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onCreateSkill(folderId); }}
                className={menuItemClass}
              >
                <Zap className="h-4 w-4" />
                <span className="text-sm">{t('createButton')}</span>
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onCreateFolder(folderId); }}
                className={menuItemClass}
              >
                <FolderPlus className="h-4 w-4" />
                <span className="text-sm">{t('newFolder')}</span>
              </button>
            </div>
          </PopoverContent>
        </Popover>
      </div>
    );
  };

  // Skill row
  const renderSkillRow = (skill: Skill) => {
    const menuKey = `skill:${skill.id}`;
    const isOpen = openMenuId === menuKey;
    const isDragging = dragItem?.type === 'skill' && dragItem.id === skill.id;
    const isPublished = publishedSkillIds?.has(skill.id) ?? false;
    const isPendingReview = pendingReviewSkillIds?.has(skill.id) ?? false;
    const isRejected = rejectedSkillReasons?.has(skill.id) ?? false;
    const rejectionReason = rejectedSkillReasons?.get(skill.id) ?? null;
    const isGlobalSkill = !!skill.isGlobal;
    // Globals live outside any tenant's folder hierarchy - don't let non-admins
    // drag them around (the move endpoint also rejects this server-side).
    const canDrag = !isGlobalSkill || isAdmin;
    const canDelete = !skill.defaultKey && (!isGlobalSkill || isAdmin);
    // Publishing a global to the marketplace is incoherent (already visible to all);
    // also block non-admins from attempting to share a skill they don't own.
    const canShare = !isGlobalSkill;
    // V275 - the owner of a personal skill sets is_default_active; for a global
    // skill that capability follows the admin gate (backend enforces).
    const canEditDefaultActive = !!onToggleSkillIsDefaultActive && (!isGlobalSkill || isAdmin);
    const canEditGlobal = !!onToggleSkillGlobal && isAdmin;

    // V276 - effective active state for THIS user (override wins, else fall back
    // to the skill-level default). Used to position the toggle next to the row.
    const isDefaultActive = !!skill.isDefaultActive;
    const userOverride = userOverrides ? userOverrides[skill.id] : undefined;
    const effectiveActive = userOverride !== undefined ? userOverride : isDefaultActive;

    // 3-dots popover content gate: only render the trigger when at least one
    // menu item would actually appear. Previously a global skill viewed by a
    // non-admin opened an empty popover (no share, no unshare, no delete) -
    // V275 (2026-05-21) hides the trigger in that case.
    const hasMenuItems =
      (!!onShareSkill && canShare && !isPublished && !isPendingReview) ||
      (!!onUnshareSkill && isPublished) ||
      isPendingReview ||
      canEditDefaultActive ||
      canEditGlobal ||
      canDelete;

    return (
      <div
        key={skill.id}
        draggable={canDrag}
        onDragStart={(e) => handleDragStart({ type: 'skill', id: skill.id }, e)}
        onDragEnd={handleDragEnd}
        className={`group flex items-center h-8 ${canDrag ? 'cursor-grab' : 'cursor-pointer'} text-sm text-theme-primary hover:bg-theme-secondary/50 rounded-lg transition-colors ${isDragging ? 'opacity-50' : ''}`}
        onClick={() => onEditSkill(skill)}
      >
        <span className="w-[16px] flex-shrink-0" />
        <Zap className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary mr-2" />
        <span className="truncate">{skill.name}</span>
        {isGlobalSkill && (
          <span className="ml-1.5 flex-shrink-0" title={tCommon('global')}>
            <Globe className="h-3 w-3 text-[var(--accent-primary)]" />
          </span>
        )}
        <span className="ml-1.5 flex-shrink-0">
          <PublicationStatusIcon
            isShared={isPublished}
            isPending={!isPublished && !isRejected && isPendingReview}
            isRejected={!isPublished && isRejected}
            rejectionReason={rejectionReason}
          />
        </span>
        <div className="flex-1" />
        {onToggleSkillActive && (
          // V276 toggle - per-user effective-active. Click stops propagation so
          // it doesn't open the edit modal. `scale-75` shrinks the shared Switch
          // (smallest size is h-6) to read as inline metadata on an h-8 row.
          <span
            className="flex-shrink-0 ml-1 origin-right scale-75"
            onClick={(e) => e.stopPropagation()}
          >
            <Switch
              checked={effectiveActive}
              onCheckedChange={(next) => onToggleSkillActive(skill.id, next)}
              aria-label={skill.name}
            />
          </span>
        )}
        {hasMenuItems && (
          <Popover open={isOpen} onOpenChange={(open) => setOpenMenuId(open ? menuKey : null)}>
            <PopoverTrigger asChild>
              <Button
                variant="ghostGray"
                aria-label={t('skillActions', { name: skill.name })}
                onClick={(e) => e.stopPropagation()}
                className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center flex-shrink-0 ml-1 ${isOpen ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
              >
                <MoreVertical className="w-3 h-3 flex-shrink-0" />
              </Button>
            </PopoverTrigger>
            <PopoverContent
              align="end"
              sideOffset={5}
              className="w-auto min-w-[200px] p-2 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
            >
              <div className="space-y-1">
                {canEditDefaultActive && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOpenMenuId(null);
                      onToggleSkillIsDefaultActive!(skill.id, !isDefaultActive);
                    }}
                    className={menuItemClass}
                  >
                    {isDefaultActive ? <Check className="h-4 w-4" /> : <Square className="h-4 w-4" />}
                    <span className="text-sm">{tCommon('defaultActiveForNewChats')}</span>
                  </button>
                )}
                {canEditGlobal && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOpenMenuId(null);
                      onToggleSkillGlobal!(skill.id, !isGlobalSkill);
                    }}
                    className={menuItemClass}
                  >
                    <Globe className="h-4 w-4" />
                    <span className="text-sm">
                      {isGlobalSkill ? tCommon('makePersonal') : tCommon('makeGlobal')}
                    </span>
                  </button>
                )}
                {onShareSkill && canShare && !isPublished && !isPendingReview && (
                  <button
                    onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onShareSkill(skill); }}
                    className={menuItemClass}
                  >
                    <Globe className="h-4 w-4" />
                    <span className="text-sm">{tCommon('share')}</span>
                  </button>
                )}
                {onUnshareSkill && isPublished && (
                  <button
                    onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onUnshareSkill(skill.id); }}
                    className={menuItemDangerClass}
                  >
                    <Globe className="h-4 w-4" />
                    <span className="text-sm">{tCommon('unshare')}</span>
                  </button>
                )}
                {isPendingReview && (
                  <div className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-amber-600 dark:text-amber-400">
                    <Clock className="h-4 w-4" />
                    <span className="text-sm">{tMarketplace('pendingReview')}</span>
                  </div>
                )}
                {canDelete && (
                  <button
                    onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onDeleteSkill(skill.id); }}
                    className={menuItemDangerClass}
                  >
                    <Trash2 className="h-4 w-4" />
                    <span className="text-sm">{t('deleteTitle')}</span>
                  </button>
                )}
              </div>
            </PopoverContent>
          </Popover>
        )}
      </div>
    );
  };

  // Folder node
  const renderNode = (node: TreeNode) => {
    const isExpanded = expandedIds.has(node.folder.id);
    const folderSkills = skillsByFolder.get(node.folder.id) || [];
    const menuKey = `folder:${node.folder.id}`;
    const isOpen = openMenuId === menuKey;
    const isDragging = dragItem?.type === 'folder' && dragItem.id === node.folder.id;
    const isDropTarget = dropTargetId === node.folder.id && dragItem !== null
      && isValidDrop(dragItem, node.folder.id);

    const isGlobalFolder = !!node.folder.isGlobal;
    // V275 - non-admins can't rename / delete a global folder (the backend
    // rejects too); the move endpoint already gates server-side.
    const canRename = !isGlobalFolder || isAdmin;
    const canDeleteFolder = !isGlobalFolder || isAdmin;
    const canEditFolderGlobal = !!onToggleFolderGlobal && isAdmin;
    const canDragFolder = !isGlobalFolder || isAdmin;
    // Hide the 3-dots trigger entirely when no menu item would render -
    // mirrors the skill row fix above.
    const folderHasMenuItems = canRename || canDeleteFolder || canEditFolderGlobal;

    return (
      <div key={node.folder.id}>
        <div
          draggable={canDragFolder}
          onDragStart={(e) => handleDragStart({ type: 'folder', id: node.folder.id }, e)}
          onDragEnd={handleDragEnd}
          onDragOver={(e) => handleDragOver(node.folder.id, e)}
          onDragEnter={(e) => handleDragEnter(node.folder.id, e)}
          onDragLeave={handleDragLeave}
          onDrop={(e) => handleDrop(node.folder.id, e)}
          className={`group flex items-center h-8 ${canDragFolder ? 'cursor-grab' : 'cursor-pointer'} text-sm text-theme-primary hover:bg-theme-secondary/50 rounded-lg transition-colors ${isDragging ? 'opacity-50' : ''} ${isDropTarget ? DROP_HIGHLIGHT : ''}`}
          onClick={(e) => toggleExpand(node.folder.id, e)}
        >
          <button
            onClick={(e) => toggleExpand(node.folder.id, e)}
            className="flex-shrink-0 p-0 mr-0.5"
          >
            <ChevronRight className={`h-3.5 w-3.5 text-theme-secondary transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`} />
          </button>

          {isExpanded
            ? <FolderOpen className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
            : <Folder className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
          }

          <span className="truncate font-medium">{node.folder.name}</span>
          {isGlobalFolder && (
            <span className="ml-1.5 flex-shrink-0" title={tCommon('global')}>
              <Globe className="h-3 w-3 text-[var(--accent-primary)]" />
            </span>
          )}

          {folderHasMenuItems && (
            <Popover open={isOpen} onOpenChange={(open) => setOpenMenuId(open ? menuKey : null)}>
              <PopoverTrigger asChild>
                <Button
                  variant="ghostGray"
                  aria-label={t('folderActions', { name: node.folder.name })}
                  onClick={(e) => e.stopPropagation()}
                  className={`w-5 h-5 min-w-[20px] min-h-[20px] p-0 rounded-full text-theme-muted hover:text-[var(--bg-primary)] transition-colors font-normal flex items-center justify-center flex-shrink-0 ml-1 ${isOpen ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
                >
                  <MoreVertical className="w-3 h-3 flex-shrink-0" />
                </Button>
              </PopoverTrigger>
              <PopoverContent
                align="end"
                sideOffset={5}
                className="w-auto min-w-[200px] p-2 bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
              >
                <div className="space-y-1">
                  {canRename && (
                    <button
                      onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onRenameFolder(node.folder.id, node.folder.name); }}
                      className={menuItemClass}
                    >
                      <Pencil className="h-4 w-4" />
                      <span className="text-sm">{t('renameFolder')}</span>
                    </button>
                  )}
                  {canEditFolderGlobal && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setOpenMenuId(null);
                        onToggleFolderGlobal!(node.folder.id, !isGlobalFolder);
                      }}
                      className={menuItemClass}
                    >
                      <Globe className="h-4 w-4" />
                      <span className="text-sm">
                        {isGlobalFolder ? tCommon('makePersonal') : tCommon('makeGlobal')}
                      </span>
                    </button>
                  )}
                  {canDeleteFolder && (
                    <button
                      onClick={(e) => { e.stopPropagation(); setOpenMenuId(null); onDeleteFolder(node.folder.id); }}
                      className={menuItemDangerClass}
                    >
                      <Trash2 className="h-4 w-4" />
                      <span className="text-sm">{t('deleteFolder')}</span>
                    </button>
                  )}
                </div>
              </PopoverContent>
            </Popover>
          )}
        </div>

        {isExpanded && (
          <div className={`${TREE_ML} border-l border-theme ${TREE_PL}`}>
            {node.children.map(child => renderNode(child))}
            {folderSkills.map(skill => renderSkillRow(skill))}
            {renderAddButton(node.folder.id)}
          </div>
        )}
      </div>
    );
  };

  const rootSkills = skillsByFolder.get(null) || [];
  const isRootDropTarget = dropTargetId === '__root__' && dragItem !== null
    && isValidDrop(dragItem, null);

  return (
    <div className="w-full" onDragEnd={handleDragEnd}>
      <nav>
        {/* Root row - drop target for moving items to root */}
        <div
          className={`group flex items-center h-9 cursor-default text-sm text-theme-primary rounded-lg transition-colors ${isRootDropTarget ? DROP_HIGHLIGHT : ''}`}
          onDragOver={(e) => handleDragOver(null, e)}
          onDragEnter={(e) => handleDragEnter(null, e)}
          onDragLeave={handleDragLeave}
          onDrop={(e) => handleDrop(null, e)}
        >
          <span className="w-[16px] flex-shrink-0" />
          <Folder className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
          <span className="truncate flex-1 font-semibold">{t('rootFolder')}</span>
        </div>

        {/* Root children */}
        <div className={`${TREE_ML} border-l border-theme ${TREE_PL}`}>
          {tree.map(node => renderNode(node))}
          {rootSkills.map(skill => renderSkillRow(skill))}
          {renderAddButton(null)}
        </div>
      </nav>
    </div>
  );
}
