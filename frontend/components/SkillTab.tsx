'use client';

import { useState, useCallback, useMemo, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Zap, Plus, FolderPlus, Search, Globe } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { CreateSkillModal } from '@/components/chat/CreateSkillModal';
import { orchestratorApi } from '@/lib/api';
import type { Skill } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { buildPublicationStatusSets } from '@/lib/utils/publicationStatusSets';

import { useTranslations } from 'next-intl';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { EmptyState } from '@/components/ui/EmptyState';
import { useSkillExplorer } from '@/hooks/useSkillExplorer';
import { SkillFolderTree } from '@/components/skills/SkillFolderTree';
import { CreateFolderDialog } from '@/components/skills/CreateFolderDialog';
import { useToast } from '@/components/Toast';
import Toast from '@/components/Toast';
import PublishResourceModal from '@/components/marketplace/PublishResourceModal';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';

interface SkillTabProps {
  className?: string;
}

export function SkillTab({ className = '' }: SkillTabProps) {
  const t = useTranslations();
  const { toasts, addToast, removeToast } = useToast();
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: hide the
  // empty-state create CTAs (the tree gates its own row/folder actions).
  const canMutate = useCanMutateInCurrentOrg();
  const {
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
  } = useSkillExplorer();

  // V275/V276 (2026-05-21) - per-user override map. Effective active state
  // for a skill = `userOverrides[skill.id] ?? skill.isDefaultActive`.
  const [userOverrides, setUserOverrides] = useState<Record<string, boolean>>({});
  const refreshOverrides = useCallback(async () => {
    try {
      const data = await orchestratorApi.getMySkillOverrides();
      setUserOverrides(data || {});
    } catch (err) {
      console.error('Error fetching skill overrides:', err);
    }
  }, []);
  useEffect(() => { refreshOverrides(); }, [refreshOverrides]);

  const handleToggleSkillActive = useCallback(async (skillId: string, nextActive: boolean) => {
    const skill = allSkills.find(s => s.id === skillId);
    const ownerDefault = !!skill?.isDefaultActive;
    // Optimistic update - flip immediately so the toggle feels instant.
    setUserOverrides(prev => ({ ...prev, [skillId]: nextActive }));
    try {
      if (nextActive === ownerDefault) {
        // Match the owner's default → forget the override row.
        await orchestratorApi.clearUserSkillActive(skillId);
        setUserOverrides(prev => {
          const next = { ...prev };
          delete next[skillId];
          return next;
        });
      } else {
        await orchestratorApi.setUserSkillActive(skillId, nextActive);
      }
    } catch (err) {
      console.error('Error toggling skill override:', err);
      // Roll back on failure.
      void refreshOverrides();
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    }
  }, [allSkills, refreshOverrides, addToast, t]);

  const handleToggleSkillIsDefaultActive = useCallback(async (skillId: string, isDefaultActive: boolean) => {
    try {
      await orchestratorApi.updateSkill(skillId, { isDefaultActive });
      await refresh();
    } catch (err) {
      console.error('Error toggling skill is_default_active:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    }
  }, [refresh, addToast, t]);

  const handleToggleSkillGlobal = useCallback(async (skillId: string, isGlobal: boolean) => {
    try {
      await orchestratorApi.updateSkill(skillId, { isGlobal });
      await refresh();
    } catch (err) {
      console.error('Error toggling skill is_global:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    }
  }, [refresh, addToast, t]);

  const handleToggleFolderGlobal = useCallback(async (folderId: string, isGlobal: boolean) => {
    try {
      await orchestratorApi.setSkillFolderGlobal(folderId, isGlobal);
      await refresh();
    } catch (err) {
      console.error('Error toggling folder is_global:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    }
  }, [refresh, addToast, t]);

  // Dialogs state
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [createSkillFolderId, setCreateSkillFolderId] = useState<string | null>(null);
  const [showCreateFolderDialog, setShowCreateFolderDialog] = useState(false);
  const [createFolderParentId, setCreateFolderParentId] = useState<string | null>(null);
  const [renamingFolder, setRenamingFolder] = useState<{ id: string; name: string } | null>(null);
  const [showDeleteFolderConfirm, setShowDeleteFolderConfirm] = useState<string | null>(null);
  const [showDeleteSkillConfirm, setShowDeleteSkillConfirm] = useState<string | null>(null);
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [publishingSkill, setPublishingSkill] = useState<Skill | null>(null);
  const [publishedSkillIds, setPublishedSkillIds] = useState<Set<string>>(new Set());
  const [pendingReviewSkillIds, setPendingReviewSkillIds] = useState<Set<string>>(new Set());
  const [rejectedSkillReasons, setRejectedSkillReasons] = useState<Map<string, string | null>>(new Map());
  const [unsharing, setUnsharing] = useState(false);
  const [unshareConfirmSkill, setUnshareConfirmSkill] = useState<string | null>(null);

  // Resolve published / pending / rejected from a SINGLE /publications/my sweep (keyed by
  // resourceId) instead of one getResourcePublicationStatus request per skill - the per-item version
  // fired one HTTP call per skill (an N+1) and saturated the connection pool on large skill sets.
  // Same batched pattern AgentTable / InterfaceTable already use.
  const checkPublishedStatus = useCallback(async () => {
    try {
      const pubs = await publicationService.getAllMyPublications();
      const { publishedIds, pendingIds, rejectedReasons } = buildPublicationStatusSets(pubs, 'SKILL');
      setPublishedSkillIds(publishedIds);
      setPendingReviewSkillIds(pendingIds);
      setRejectedSkillReasons(rejectedReasons);
    } catch {
      // ignore - markers stay absent, items read as private
    }
  }, []);

  useEffect(() => {
    if (allSkills.length > 0) checkPublishedStatus();
  }, [allSkills, checkPublishedStatus]);

  const handleUnshareClick = useCallback((skillId: string) => {
    setUnshareConfirmSkill(skillId);
  }, []);

  const confirmUnshare = useCallback(async () => {
    if (!unshareConfirmSkill) return;
    setUnsharing(true);
    try {
      await publicationService.unpublishResource('SKILL', unshareConfirmSkill);
      setPublishedSkillIds(prev => {
        const next = new Set(prev);
        next.delete(unshareConfirmSkill);
        return next;
      });
      addToast({ type: 'success', title: t('marketplace.agents.unpublishSuccess', { type: 'SKILL' }), message: t('marketplace.agents.unpublishSuccess', { type: 'SKILL' }) });
    } catch (err) {
      console.error('Error unsharing skill:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    } finally {
      setUnsharing(false);
      setUnshareConfirmSkill(null);
    }
  }, [unshareConfirmSkill, addToast, t]);

  const filteredSkills = useMemo(() => {
    const term = searchQuery.trim().toLowerCase();
    if (!term) return allSkills;
    return allSkills.filter(s =>
      [s.name, s.description || ''].join(' ').toLowerCase().includes(term)
    );
  }, [searchQuery, allSkills]);

  // When searching, also show folders that contain matching skills
  const filteredFolders = useMemo(() => {
    const term = searchQuery.trim().toLowerCase();
    if (!term) return allFolders;
    // Collect folder IDs that contain matching skills + their ancestors
    const matchingFolderIds = new Set<string>();
    for (const skill of filteredSkills) {
      if (skill.folderId) {
        // Walk up the tree
        let current: string | null = skill.folderId;
        while (current) {
          matchingFolderIds.add(current);
          const folder = allFolders.find(f => f.id === current);
          current = folder?.parentId || null;
        }
      }
    }
    // Also include folders whose name matches
    for (const f of allFolders) {
      if (f.name.toLowerCase().includes(term)) {
        matchingFolderIds.add(f.id);
        // Walk up
        let current: string | null = f.parentId;
        while (current) {
          matchingFolderIds.add(current);
          const folder = allFolders.find(ff => ff.id === current);
          current = folder?.parentId || null;
        }
      }
    }
    return allFolders.filter(f => matchingFolderIds.has(f.id));
  }, [searchQuery, allFolders, filteredSkills]);

  // Handlers - Folder
  const handleCreateFolderInParent = useCallback(async (name: string) => {
    await createFolder(name, createFolderParentId);
    addToast({ title: t('emptyState.skill.folderCreated'), message: name, type: 'success' });
  }, [createFolder, createFolderParentId, addToast, t]);

  const handleRenameFolder = useCallback((id: string, currentName: string) => {
    setRenamingFolder({ id, name: currentName });
  }, []);

  const handleRenameFolderSubmit = useCallback(async (newName: string) => {
    if (!renamingFolder) return;
    await renameFolder(renamingFolder.id, newName);
    addToast({ title: t('emptyState.skill.folderRenamed'), message: newName, type: 'success' });
  }, [renamingFolder, renameFolder, addToast, t]);

  const handleDeleteFolder = useCallback(async () => {
    if (!showDeleteFolderConfirm) return;
    await deleteFolder(showDeleteFolderConfirm);
    setShowDeleteFolderConfirm(null);
    addToast({ title: t('emptyState.skill.folderDeleted'), message: '', type: 'success' });
  }, [deleteFolder, showDeleteFolderConfirm, addToast, t]);

  // Handlers - Skill
  const handleDeleteSkill = useCallback(async () => {
    if (!showDeleteSkillConfirm) return;
    await orchestratorApi.deleteSkill(showDeleteSkillConfirm);
    setShowDeleteSkillConfirm(null);
    await refresh();
  }, [showDeleteSkillConfirm, refresh]);

  const handleSkillCreated = useCallback(() => {
    refresh();
    setShowCreateModal(false);
    setEditingSkill(null);
  }, [refresh]);

  // Handlers - Drag & Drop move
  const handleMoveSkillToFolder = useCallback(async (skillId: string, targetFolderId: string | null) => {
    await moveSkill(skillId, targetFolderId);
    addToast({ title: t('emptyState.skill.skillMoved'), message: '', type: 'success' });
  }, [moveSkill, addToast, t]);

  const handleMoveFolderToFolder = useCallback(async (folderId: string, targetParentId: string | null) => {
    await moveFolder(folderId, targetParentId);
    addToast({ title: t('emptyState.skill.folderMoved'), message: '', type: 'success' });
  }, [moveFolder, addToast, t]);

  const isEmpty = allFolders.length === 0 && allSkills.length === 0;

  return (
    <div className={`w-full ${className}`}>
      {/* Toolbar - compact top bar */}
      <div className="flex items-center gap-2 mb-4">
        <div className="relative flex-1 overflow-visible">
          <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('emptyState.skill.searchPlaceholder')}
            className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 disabled:cursor-not-allowed disabled:opacity-50 pl-11"
          />
        </div>
        <div className="flex-1" />
        <span className="text-sm text-theme-secondary">{allSkills.length} skill{allSkills.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Tree - full width, centered */}
      {loading ? (
        <div className="space-y-2 py-4">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-8 bg-theme-tertiary rounded animate-pulse" style={{ marginLeft: `${i % 3 * 20}px` }} />
          ))}
        </div>
      ) : isEmpty ? (
        <EmptyState
          icon={<Zap className="h-8 w-8 text-theme-tertiary" />}
          title={t('emptyState.skill.noSkillsFound')}
          subtitle={t('emptyState.skill.createFirstSkill')}
          actions={canMutate ? (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => { setCreateFolderParentId(null); setShowCreateFolderDialog(true); }}>
                <FolderPlus className="w-4 h-4 mr-1.5" />
                {t('emptyState.skill.newFolder')}
              </Button>
              <Button variant="default" onClick={() => { setCreateSkillFolderId(null); setShowCreateModal(true); }}>
                <Plus className="w-4 h-4 mr-1.5" />
                {t('emptyState.skill.createButton')}
              </Button>
            </div>
          ) : undefined}
        />
      ) : (
        <div className="w-full">
          <SkillFolderTree
            allFolders={filteredFolders}
            allSkills={filteredSkills}
            onCreateFolder={(parentId) => { setCreateFolderParentId(parentId); setShowCreateFolderDialog(true); }}
            onRenameFolder={handleRenameFolder}
            onDeleteFolder={(id) => setShowDeleteFolderConfirm(id)}
            onCreateSkill={(folderId) => { setCreateSkillFolderId(folderId); setShowCreateModal(true); }}
            onEditSkill={setEditingSkill}
            onDeleteSkill={(id) => setShowDeleteSkillConfirm(id)}
            onMoveSkillToFolder={handleMoveSkillToFolder}
            onMoveFolderToFolder={handleMoveFolderToFolder}
            onShareSkill={setPublishingSkill}
            onUnshareSkill={handleUnshareClick}
            publishedSkillIds={publishedSkillIds}
            pendingReviewSkillIds={pendingReviewSkillIds}
            rejectedSkillReasons={rejectedSkillReasons}
            isAdmin={isAdmin}
            userOverrides={userOverrides}
            onToggleSkillActive={handleToggleSkillActive}
            onToggleSkillIsDefaultActive={handleToggleSkillIsDefaultActive}
            onToggleSkillGlobal={handleToggleSkillGlobal}
            onToggleFolderGlobal={handleToggleFolderGlobal}
          />
        </div>
      )}

      {/* Modals */}
      {(showCreateModal || editingSkill) && (
        <CreateSkillModal
          onClose={() => { setShowCreateModal(false); setEditingSkill(null); }}
          onSkillCreated={handleSkillCreated}
          skill={editingSkill || undefined}
          folderId={editingSkill ? editingSkill.folderId : createSkillFolderId}
        />
      )}

      <CreateFolderDialog
        isOpen={showCreateFolderDialog}
        onClose={() => setShowCreateFolderDialog(false)}
        onCreate={handleCreateFolderInParent}
      />

      <CreateFolderDialog
        isOpen={renamingFolder !== null}
        onClose={() => setRenamingFolder(null)}
        onCreate={handleRenameFolderSubmit}
        initialName={renamingFolder?.name}
      />

      <BulkDeleteModal
        isOpen={showDeleteFolderConfirm !== null}
        title={t('emptyState.skill.deleteFolder')}
        message={t('emptyState.skill.deleteFolderConfirmation')}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteFolderConfirm(null)}
        onConfirm={handleDeleteFolder}
      />

      <BulkDeleteModal
        isOpen={showDeleteSkillConfirm !== null}
        title={t('emptyState.skill.deleteTitle')}
        message={t('emptyState.skill.deleteConfirmation', { count: 1 })}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteSkillConfirm(null)}
        onConfirm={handleDeleteSkill}
      />

      {publishingSkill && (
        <PublishResourceModal
          isOpen={!!publishingSkill}
          onClose={() => setPublishingSkill(null)}
          resourceType="SKILL"
          resourceId={publishingSkill.id}
          resourceName={publishingSkill.name}
          resourceDescription={publishingSkill.description}
          onSuccess={() => {
            setPendingReviewSkillIds(prev => new Set(prev).add(publishingSkill.id));
            addToast({
              type: 'success',
              title: t('marketplace.agents.publishSuccess', { type: 'SKILL' }),
              message: t('marketplace.agents.publishSuccess', { type: 'SKILL' }),
            });
          }}
        />
      )}

      {unshareConfirmSkill && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setUnshareConfirmSkill(null)}
        >
          <div
            className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-4">
                <Globe className="w-8 h-8 text-red-500" />
              </div>
              <h3 className="text-2xl font-semibold text-theme-primary">
                {t('marketplace.agents.unpublishConfirmTitle')}
              </h3>
            </div>
            <div className="space-y-3 mb-8">
              <p className="text-theme-secondary text-center">
                {t('marketplace.agents.unpublishConfirmMessage')}
              </p>
              <ul className="text-sm text-theme-secondary space-y-1 list-disc list-inside">
                <li>{t('marketplace.agents.unpublishLostVisibility')}</li>
                <li>{t('marketplace.agents.unpublishLostAcquisitions')}</li>
              </ul>
            </div>
            <div className="flex gap-3">
              <Button variant="outline" onClick={() => setUnshareConfirmSkill(null)} disabled={unsharing} className="flex-1">
                {t('common.cancel')}
              </Button>
              <Button variant="destructive" onClick={confirmUnshare} disabled={unsharing} className="flex-1">
                {unsharing && <LoadingSpinner size="xs" className="mr-1.5" />}
                {t('marketplace.agents.unpublishConfirmButton')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Toasts */}
      <div className="fixed top-4 right-4 z-[10000] space-y-2">
        {toasts.map((toast) => (
          <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} duration={toast.duration} onClose={removeToast} />
        ))}
      </div>
    </div>
  );
}
