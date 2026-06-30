'use client';

import { useState, useCallback, useEffect } from 'react';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { Skill, SkillFolder } from '@/lib/api';

interface UseSkillExplorerReturn {
  // State
  allFolders: SkillFolder[];
  allSkills: Skill[];
  loading: boolean;
  searchQuery: string;

  // Actions
  setSearchQuery: (query: string) => void;
  refresh: () => Promise<void>;

  // Folder operations
  createFolder: (name: string, parentId?: string | null) => Promise<SkillFolder>;
  renameFolder: (id: string, name: string) => Promise<void>;
  deleteFolder: (id: string) => Promise<void>;
  moveFolder: (id: string, parentId: string | null) => Promise<void>;

  // Skill operations
  moveSkill: (id: string, folderId: string | null) => Promise<void>;
}

export function useSkillExplorer(): UseSkillExplorerReturn {
  const [allFolders, setAllFolders] = useState<SkillFolder[]>([]);
  const [allSkills, setAllSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');

  const fetchAllFolders = useCallback(async () => {
    try {
      const data = await orchestratorApi.getAllSkillFolders();
      setAllFolders(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Error fetching skill folders:', err);
    }
  }, []);

  const fetchAllSkills = useCallback(async () => {
    try {
      const data = await orchestratorApi.getSkills();
      setAllSkills(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Error fetching all skills:', err);
    }
  }, []);

  // Initial load
  useEffect(() => {
    const load = async () => {
      setLoading(true);
      await Promise.all([fetchAllFolders(), fetchAllSkills()]);
      setLoading(false);
    };
    load();
  }, [fetchAllFolders, fetchAllSkills]);

  const refresh = useCallback(async () => {
    await Promise.all([fetchAllFolders(), fetchAllSkills()]);
  }, [fetchAllFolders, fetchAllSkills]);

  // Phase 6c (2026-05-19) - clear skills/folders on workspace switch.
  // The hook is consumed by the SidePanel skill tab and the workflow
  // builder skill picker (both keepMounted); without this reset the
  // previous workspace's skills survive across switches until the user
  // triggers a refresh manually.
  useOrgScopedReset(() => {
    setAllFolders([]);
    setAllSkills([]);
    setSearchQuery('');
    setLoading(true);
    void Promise.all([fetchAllFolders(), fetchAllSkills()]).finally(() => setLoading(false));
  });

  // Folder operations
  const createFolder = useCallback(async (name: string, parentId?: string | null): Promise<SkillFolder> => {
    const folder = await orchestratorApi.createSkillFolder(name, parentId ?? null);
    await refresh();
    return folder;
  }, [refresh]);

  const renameFolder = useCallback(async (id: string, name: string) => {
    await orchestratorApi.renameSkillFolder(id, name);
    await refresh();
  }, [refresh]);

  const deleteFolder = useCallback(async (id: string) => {
    await orchestratorApi.deleteSkillFolder(id);
    await refresh();
  }, [refresh]);

  const moveFolder = useCallback(async (id: string, parentId: string | null) => {
    await orchestratorApi.moveSkillFolder(id, parentId);
    await refresh();
  }, [refresh]);

  // Skill operations
  const moveSkill = useCallback(async (id: string, folderId: string | null) => {
    await orchestratorApi.moveSkill(id, folderId);
    await refresh();
  }, [refresh]);

  return {
    allFolders,
    allSkills,
    loading,
    searchQuery,
    setSearchQuery,
    refresh,
    createFolder,
    renameFolder,
    deleteFolder,
    moveFolder,
    moveSkill,
  };
}
