'use client';

import { useState, useMemo, useCallback, useEffect } from 'react';
import {
  Folder, FolderOpen, ChevronRight, Zap, Check, Minus, Pencil
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { SkillFolder, Skill } from '@/lib/api';

interface TreeNode {
  folder: SkillFolder;
  children: TreeNode[];
}

interface SkillFolderTreeSelectProps {
  allFolders: SkillFolder[];
  allSkills: Skill[];
  selectedSkillIds: Set<string>;
  onSelectionChange: (selectedIds: Set<string>) => void;
  onEditSkill?: (skill: Skill) => void;
  maxSkills?: number;
}

const TREE_ML = 'ml-[23px]';
const TREE_PL = 'pl-3';

export function SkillFolderTreeSelect({
  allFolders,
  allSkills,
  selectedSkillIds,
  onSelectionChange,
  onEditSkill,
  maxSkills,
}: SkillFolderTreeSelectProps) {
  const t = useTranslations('emptyState.skill');

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

  // Collect all skill IDs under a folder (recursively)
  const getSkillIdsInFolder = useCallback((node: TreeNode): string[] => {
    const ids: string[] = [];
    const directSkills = skillsByFolder.get(node.folder.id) || [];
    ids.push(...directSkills.map(s => s.id));
    for (const child of node.children) {
      ids.push(...getSkillIdsInFolder(child));
    }
    return ids;
  }, [skillsByFolder]);

  // Build a map from folderId -> TreeNode for quick lookup
  const nodeByFolderId = useMemo(() => {
    const map = new Map<string, TreeNode>();
    const walk = (nodes: TreeNode[]) => {
      for (const n of nodes) {
        map.set(n.folder.id, n);
        walk(n.children);
      }
    };
    walk(tree);
    return map;
  }, [tree]);

  // Folder selection state: 'all' | 'some' | 'none'
  const getFolderState = useCallback((node: TreeNode): 'all' | 'some' | 'none' => {
    const skillIds = getSkillIdsInFolder(node);
    if (skillIds.length === 0) return 'none';
    const selectedCount = skillIds.filter(id => selectedSkillIds.has(id)).length;
    if (selectedCount === 0) return 'none';
    if (selectedCount === skillIds.length) return 'all';
    return 'some';
  }, [getSkillIdsInFolder, selectedSkillIds]);

  const toggleSkill = useCallback((skillId: string) => {
    const next = new Set(selectedSkillIds);
    if (next.has(skillId)) {
      next.delete(skillId);
    } else {
      if (maxSkills && next.size >= maxSkills) return;
      next.add(skillId);
    }
    onSelectionChange(next);
  }, [selectedSkillIds, onSelectionChange, maxSkills]);

  const toggleFolder = useCallback((node: TreeNode) => {
    const skillIds = getSkillIdsInFolder(node);
    if (skillIds.length === 0) return;
    const state = getFolderState(node);
    const next = new Set(selectedSkillIds);
    if (state === 'all') {
      // Deselect all
      for (const id of skillIds) next.delete(id);
    } else {
      // Select all (respect max)
      for (const id of skillIds) {
        if (maxSkills && next.size >= maxSkills) break;
        next.add(id);
      }
    }
    onSelectionChange(next);
  }, [getSkillIdsInFolder, getFolderState, selectedSkillIds, onSelectionChange, maxSkills]);

  const toggleExpand = useCallback((id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  // Checkbox component
  const Checkbox = ({ state }: { state: 'checked' | 'indeterminate' | 'unchecked' }) => (
    <div className={`w-4 h-4 rounded border flex items-center justify-center flex-shrink-0 transition-colors ${
      state === 'checked'
        ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]'
        : state === 'indeterminate'
          ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)]'
          : 'border-theme'
    }`}>
      {state === 'checked' && <Check className="w-3 h-3 text-[var(--accent-foreground)]" />}
      {state === 'indeterminate' && <Minus className="w-3 h-3 text-[var(--accent-foreground)]" />}
    </div>
  );

  // Skill row
  const renderSkillRow = (skill: Skill) => {
    const isSelected = selectedSkillIds.has(skill.id);
    const isDisabled = !isSelected && !!maxSkills && selectedSkillIds.size >= maxSkills;
    return (
      <div
        key={skill.id}
        className={`group flex items-center h-8 text-sm rounded-lg transition-colors ${isDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer hover:bg-theme-secondary/50'}`}
        onClick={() => !isDisabled && toggleSkill(skill.id)}
      >
        <span className="w-[16px] flex-shrink-0" />
        <Checkbox state={isSelected ? 'checked' : 'unchecked'} />
        <Zap className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary mx-2" />
        <div className="flex-1 min-w-0 flex items-center gap-1.5">
          <span className="text-sm text-theme-primary truncate">{skill.name}</span>
        </div>
        {onEditSkill && (
          <button
            className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-theme-tertiary/50 transition-opacity flex-shrink-0 mr-1"
            onClick={(e) => { e.stopPropagation(); onEditSkill(skill); }}
            title={t('editSkill')}
          >
            <Pencil className="h-3 w-3 text-theme-secondary" />
          </button>
        )}
      </div>
    );
  };

  // Folder node
  const renderNode = (node: TreeNode) => {
    const isExpanded = expandedIds.has(node.folder.id);
    const folderSkills = skillsByFolder.get(node.folder.id) || [];
    const folderState = getFolderState(node);
    const checkState = folderState === 'all' ? 'checked' : folderState === 'some' ? 'indeterminate' : 'unchecked';
    const hasSkills = getSkillIdsInFolder(node).length > 0;

    return (
      <div key={node.folder.id}>
        <div
          className="flex items-center h-8 cursor-pointer text-sm text-theme-primary hover:bg-theme-secondary/50 rounded-lg transition-colors"
          onClick={(e) => toggleExpand(node.folder.id, e)}
        >
          <button
            onClick={(e) => toggleExpand(node.folder.id, e)}
            className="flex-shrink-0 p-0 mr-0.5"
          >
            <ChevronRight className={`h-3.5 w-3.5 text-theme-secondary transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`} />
          </button>

          {hasSkills && (
            <div className="flex-shrink-0 mr-1.5" onClick={(e) => { e.stopPropagation(); toggleFolder(node); }}>
              <Checkbox state={checkState} />
            </div>
          )}

          {isExpanded
            ? <FolderOpen className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
            : <Folder className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
          }

          <span className="truncate font-medium">{node.folder.name}</span>
        </div>

        {isExpanded && (
          <div className={`${TREE_ML} border-l border-theme ${TREE_PL}`}>
            {node.children.map(child => renderNode(child))}
            {folderSkills.map(skill => renderSkillRow(skill))}
          </div>
        )}
      </div>
    );
  };

  const rootSkills = skillsByFolder.get(null) || [];

  return (
    <div className="w-full">
      <nav>
        {/* Root row */}
        <div className="flex items-center h-9 cursor-default text-sm text-theme-primary">
          <span className="w-[16px] flex-shrink-0" />
          <Folder className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
          <span className="truncate flex-1 font-semibold">{t('rootFolder')}</span>
        </div>

        {/* Root children */}
        <div className={`${TREE_ML} border-l border-theme ${TREE_PL}`}>
          {tree.map(node => renderNode(node))}
          {rootSkills.map(skill => renderSkillRow(skill))}
        </div>
      </nav>
    </div>
  );
}
