'use client';

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { createPortal } from 'react-dom';
import { Wrench, Plus, Loader2, CheckCircle, Search, Zap, SlidersHorizontal } from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { ServiceIcon } from '@/components/ui/service-icon';
import { orchestratorApi } from '@/lib/api/orchestrator';
import type { Credential, CredentialTemplate } from '@/lib/api/orchestrator';
import type { Skill, SkillFolder } from '@/lib/api/orchestrator/types';
import { CredentialWizard } from '@/components/credentials/CredentialWizard';
import { SkillFolderTreeSelect } from '@/components/skills/SkillFolderTreeSelect';
import { CreateSkillModal } from '@/components/chat/CreateSkillModal';
import { ChatConfigPanel } from '@/components/chat/ChatConfigPanel';

export type AttachmentView = 'tools' | 'skills' | 'options';

interface AttachmentHandlerProps {
  isOpen: boolean;
  /** Which panel to show directly (no root menu) */
  initialView: AttachmentView;
  onClose: () => void;
  onFileSelect: (e: React.ChangeEvent<HTMLInputElement>) => void;
  activeSkillIds?: Set<string>;
  onSkillSelectionChange?: (ids: Set<string>) => void;
  /** Called once with the full skill list to auto-activate defaults for new conversations */
  onInitializeDefaults?: (skills: Array<{ id: string; defaultKey?: string | null }>) => void;
  /** Ref to the anchor element (e.g. the "+" button container) used for portal positioning */
  anchorRef?: React.RefObject<HTMLElement | null>;
  /** Conversation identifier used by the Options tab and agent-scoped skill edits. */
  conversationId?: string | null;
  /**
   * Agent linked to the current conversation, if any.
   * When provided, the Skills tab toggles PUT the agent's skill assignments instead of
   * only updating local (per-conversation) selection. The Options tab also targets the agent.
   */
  agentId?: string | null;
  onPendingConfigurationSave?: (save: Promise<unknown>) => void;
  isScopeResolutionPending?: boolean;
  hasLocalAgentSkillSelection?: boolean;
}

const PAGE_SIZE = 20;

export const AttachmentHandler: React.FC<AttachmentHandlerProps> = ({
  isOpen,
  initialView,
  onClose,
  onFileSelect,
  activeSkillIds,
  onSkillSelectionChange,
  onInitializeDefaults,
  anchorRef,
  conversationId = null,
  agentId = null,
  onPendingConfigurationSave,
  isScopeResolutionPending = false,
  hasLocalAgentSkillSelection = false,
}) => {
  const t = useTranslations('credentials');
  const queryClient = useQueryClient();
  const [view, setView] = useState<AttachmentView>(initialView);
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [isWizardOpen, setIsWizardOpen] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<CredentialTemplate | null>(null);
  const [extraTemplates, setExtraTemplates] = useState<CredentialTemplate[]>([]);
  const [currentPage, setCurrentPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [skillSearch, setSkillSearch] = useState('');
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [portalPos, setPortalPos] = useState<
    | { left: number; placement: 'above'; bottom: number; maxHeight: number }
    | { left: number; placement: 'below'; top: number; maxHeight: number }
    | null
  >(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const skillSearchRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Compute fixed position from anchor
  useEffect(() => {
    if (!isOpen || !anchorRef?.current) {
      setPortalPos(null);
      return;
    }
    const update = () => {
      const rect = anchorRef.current?.getBoundingClientRect();
      if (!rect) return;
      const GAP = 8;
      const MARGIN = 16;
      const DESIRED = 440;
      const spaceAbove = rect.top - MARGIN - GAP;
      const spaceBelow = window.innerHeight - rect.bottom - MARGIN - GAP;
      // Prefer opening above (historic behaviour) unless there is clearly more
      // room below - avoids the menu getting cropped when the composer sits
      // high on the page (e.g. chat welcome view).
      if (spaceAbove >= Math.min(DESIRED, 240) || spaceAbove >= spaceBelow) {
        setPortalPos({
          left: rect.left,
          placement: 'above',
          bottom: window.innerHeight - rect.top + GAP,
          maxHeight: Math.max(200, Math.min(DESIRED, spaceAbove)),
        });
      } else {
        setPortalPos({
          left: rect.left,
          placement: 'below',
          top: rect.bottom + GAP,
          maxHeight: Math.max(200, Math.min(DESIRED, spaceBelow)),
        });
      }
    };
    update();
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    return () => {
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [isOpen, anchorRef]);

  // Reset state when panel closes, sync view when initialView changes
  useEffect(() => {
    if (!isOpen) {
      setSearchTerm('');
      setDebouncedSearch('');
      setSkillSearch('');
      setExtraTemplates([]);
      setCurrentPage(1);
      setHasMore(true);
    }
  }, [isOpen]);

  // Sync view when parent changes which panel to show
  useEffect(() => {
    if (isOpen) {
      setView(initialView);
    }
  }, [initialView, isOpen]);

  // Auto-focus search when entering tools or skills view
  useEffect(() => {
    if (view === 'tools' && searchRef.current) {
      searchRef.current.focus();
    }
    if (view === 'skills' && skillSearchRef.current) {
      skillSearchRef.current.focus();
    }
  }, [view]);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(searchTerm);
      setExtraTemplates([]);
      setCurrentPage(1);
      setHasMore(true);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchTerm]);

  // Fetch user credentials
  const { data: userCredentials = [], isLoading: isLoadingCredentials } = useQuery({
    queryKey: ['user-credentials'],
    queryFn: () => orchestratorApi.getAllCredentials(),
    staleTime: 30_000,
    enabled: isOpen,
  });

  // Fetch user-created skills
  const { data: rawUserSkills = [], isLoading: isLoadingSkills } = useQuery({
    queryKey: ['skills'],
    queryFn: () => orchestratorApi.getSkills(),
    staleTime: 30_000,
    enabled: isOpen && view === 'skills',
  });

  // Fetch skill folders
  const { data: rawSkillFolders = [], isLoading: isLoadingFolders } = useQuery({
    queryKey: ['skillFolders'],
    queryFn: () => orchestratorApi.getAllSkillFolders(),
    staleTime: 30_000,
    enabled: isOpen && view === 'skills',
  });

  // When an agent is linked to this conversation, load the agent's assigned skills so
  // the Skills tab reflects the authoritative agent state instead of local prefs.
  const { data: agentAssignedSkills } = useQuery({
    queryKey: ['agent-skills', agentId],
    queryFn: () => (agentId ? orchestratorApi.getAgentSkills(agentId) : Promise.resolve([])),
    enabled: isOpen && view === 'skills' && !!agentId,
    staleTime: 30_000,
  });

  // Sync agent-assigned skills into the selection set on first load so the Skills tab
  // reflects what's actually persisted on the agent entity.
  const agentSkillsSyncedRef = useRef<string | null>(null);
  useEffect(() => {
    if (!agentId) return;
    if (hasLocalAgentSkillSelection) return;
    if (!agentAssignedSkills) return;
    if (agentSkillsSyncedRef.current === agentId) return;
    agentSkillsSyncedRef.current = agentId;
    const ids = new Set<string>(agentAssignedSkills.map(a => a.skillId));
    onSkillSelectionChange?.(ids);
  }, [agentId, agentAssignedSkills, hasLocalAgentSkillSelection, onSkillSelectionChange]);

  // Fetch first page of templates
  const { data: firstPageTemplates = [], isLoading: isLoadingFirstPage } = useQuery({
    queryKey: ['credential-templates-page', debouncedSearch, 1],
    queryFn: async () => {
      const res = await orchestratorApi.getCredentialTemplates({
        page: 1,
        pageSize: PAGE_SIZE,
        search: debouncedSearch || undefined,
      });
      const items = res.credentials || [];
      setHasMore(res.hasNext ?? items.length === PAGE_SIZE);
      setCurrentPage(1);
      setExtraTemplates([]);
      return items;
    },
    staleTime: 5 * 60_000,
    enabled: isOpen && view === 'tools',
  });

  // Combine first page (from cache) + extra pages (from state)
  const loadedTemplates = React.useMemo(
    () => [...firstPageTemplates, ...extraTemplates],
    [firstPageTemplates, extraTemplates]
  );

  // Load more pages
  const loadMore = useCallback(async () => {
    if (isLoadingMore || !hasMore) return;
    setIsLoadingMore(true);
    try {
      const nextPage = currentPage + 1;
      const res = await orchestratorApi.getCredentialTemplates({
        page: nextPage,
        pageSize: PAGE_SIZE,
        search: debouncedSearch || undefined,
      });
      const items = res.credentials || [];
      setExtraTemplates(prev => [...prev, ...items]);
      setCurrentPage(nextPage);
      setHasMore(res.hasNext ?? items.length === PAGE_SIZE);
    } finally {
      setIsLoadingMore(false);
    }
  }, [currentPage, debouncedSearch, hasMore, isLoadingMore]);

  // Infinite scroll
  const handleScroll = useCallback(() => {
    const el = listRef.current;
    if (!el || !hasMore || isLoadingMore) return;
    const { scrollTop, scrollHeight, clientHeight } = el;
    if (scrollHeight - scrollTop - clientHeight < 80) {
      loadMore();
    }
  }, [hasMore, isLoadingMore, loadMore]);

  // Configured integrations set
  const configuredIntegrations = React.useMemo(() => {
    return new Set(
      userCredentials.map(c => c.integration?.toLowerCase()).filter(Boolean)
    );
  }, [userCredentials]);

  // Group credentials by integration
  const groupedCredentials = React.useMemo(() => {
    const map = new Map<string, Credential[]>();
    for (const cred of userCredentials) {
      const key = cred.integration?.toLowerCase() || cred.name;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(cred);
    }
    return map;
  }, [userCredentials]);

  // Filter out already configured templates
  const availableTemplates = React.useMemo(() => {
    return loadedTemplates.filter(tmpl => {
      const slug = tmpl.icon_slug?.toLowerCase() || '';
      return !slug || !configuredIntegrations.has(slug);
    });
  }, [loadedTemplates, configuredIntegrations]);

  // Auto-activate defaults for new conversations when skills are loaded
  useEffect(() => {
    if (rawUserSkills && rawUserSkills.length > 0 && onInitializeDefaults) {
      onInitializeDefaults(rawUserSkills as Array<{ id: string; defaultKey?: string | null }>);
    }
  }, [rawUserSkills, onInitializeDefaults]);

  // Skills come directly from DB (defaults are seeded automatically)
  const allSkills = useMemo(() => {
    const skills = (rawUserSkills || []) as Skill[];
    if (!skillSearch) return skills;
    const lower = skillSearch.toLowerCase();
    return skills.filter(
      s => s.name.toLowerCase().includes(lower) || (s.description && s.description.toLowerCase().includes(lower))
    );
  }, [rawUserSkills, skillSearch]);

  const allFolders = useMemo(() => (rawSkillFolders || []) as SkillFolder[], [rawSkillFolders]);

  const handleConfigureNew = (template: CredentialTemplate) => {
    setSelectedTemplate(template);
    setIsWizardOpen(true);
    onClose();
  };

  const handleCredentialAdded = () => {
    queryClient.invalidateQueries({ queryKey: ['user-credentials'] });
    queryClient.invalidateQueries({ queryKey: ['user-credentials-all'] });
  };

  const handleWizardClose = (open: boolean) => {
    setIsWizardOpen(open);
    if (!open) setSelectedTemplate(null);
  };

  const handleSkillSelectionChange = useCallback((ids: Set<string>) => {
    onSkillSelectionChange?.(ids);
    // When a concrete agent is linked, mirror the selection to the agent entity so that
    // the change persists beyond the conversation's local prefs. Failures are logged but
    // do not disrupt the UI - the local selection remains as visible feedback.
    if (agentId) {
      const assignments = Array.from(ids).map(skillId => ({ skillId }));
      const save = orchestratorApi
        .setAgentSkills(agentId, assignments)
        .then(() => {
          return queryClient.invalidateQueries({ queryKey: ['agent-skills', agentId] });
        });
      onPendingConfigurationSave?.(save);
      void save.catch(err => {
        console.error('[AttachmentHandler] failed to persist agent skills', err);
      });
    }
  }, [onSkillSelectionChange, agentId, queryClient, onPendingConfigurationSave]);

  if (!isOpen && !isWizardOpen && !editingSkill) return null;

  const isLoadingList = isLoadingFirstPage || isLoadingCredentials;
  const isLoadingSkillsView = isLoadingSkills || isLoadingFolders;

  // Use portal when anchorRef is available, otherwise fall back to inline absolute
  const usePortal = !!anchorRef?.current && !!portalPos;

  const menuContent = isOpen ? (
    <div
      ref={menuRef}
      data-attachment-menu
      className={`${usePortal ? 'fixed' : 'absolute bottom-full left-0 mb-2'} bg-theme-primary rounded-2xl border border-gray-300/70 dark:border-gray-600/70 z-[99999] overflow-hidden flex flex-col shadow-xl`}
      style={{
        width: 'min(320px, calc(100vw - 16px))',
        maxHeight: usePortal ? `${portalPos!.maxHeight}px` : 'min(440px, 70vh)',
        ...(usePortal
          ? portalPos!.placement === 'above'
            ? { left: portalPos!.left, bottom: portalPos!.bottom }
            : { left: portalPos!.left, top: portalPos!.top }
          : {}),
      }}
    >
      {/* Shared tab switcher */}
      <div className="flex items-center gap-1 p-2 pb-0">
        <button
          type="button"
          onClick={() => setView('tools')}
          className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            view === 'tools'
              ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary'
              : 'text-[var(--text-secondary)] hover:bg-gray-50 dark:hover:bg-gray-800/50'
          }`}
        >
          <Wrench className="h-3.5 w-3.5" />
          {t('tools')}
        </button>
        <button
          type="button"
          onClick={() => setView('skills')}
          className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            view === 'skills'
              ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary'
              : 'text-[var(--text-secondary)] hover:bg-gray-50 dark:hover:bg-gray-800/50'
          }`}
        >
          <Zap className="h-3.5 w-3.5" />
          {t('skills')}
        </button>
        <button
          type="button"
          onClick={() => setView('options')}
          className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            view === 'options'
              ? 'bg-gray-100 dark:bg-gray-800 text-theme-primary'
              : 'text-[var(--text-secondary)] hover:bg-gray-50 dark:hover:bg-gray-800/50'
          }`}
        >
          <SlidersHorizontal className="h-3.5 w-3.5" />
          {t('options')}
        </button>
      </div>

      {view === 'options' ? (
        /* ====== OPTIONS VIEW ====== */
        <div className="flex-1 overflow-y-auto">
          {isScopeResolutionPending ? (
            <div className="flex items-center justify-center p-8">
              <Loader2 className="h-4 w-4 animate-spin text-theme-secondary" />
            </div>
          ) : (
            <ChatConfigPanel
              agentId={agentId}
              conversationId={conversationId}
              compact
              onPendingConfigurationSave={onPendingConfigurationSave}
            />
          )}
        </div>
      ) : view === 'tools' ? (
        /* ====== TOOLS VIEW ====== */
        <>
          {/* Header: search */}
          <div className="p-2 border-b border-gray-200/70 dark:border-gray-700/70 space-y-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-[var(--text-tertiary)]" />
              <input
                ref={searchRef}
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder={t('searchTools')}
                className="w-full pl-9 pr-3 py-2 text-sm bg-[var(--bg-secondary)] rounded-lg border-none outline-none text-theme-primary placeholder:text-[var(--text-tertiary)]"
              />
            </div>
          </div>

          {/* List */}
          <div
            ref={listRef}
            onScroll={handleScroll}
            className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5"
          >
            {isLoadingList ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-4 w-4 animate-spin text-[var(--text-tertiary)]" />
              </div>
            ) : (
              <>
                {/* Configured credentials (when no search) */}
                {!searchTerm && groupedCredentials.size > 0 && (
                  <>
                    <div className="px-3 pt-1 pb-1.5">
                      <span className="text-[10px] font-semibold uppercase tracking-wider text-[var(--text-tertiary)]">
                        {t('configured')}
                      </span>
                    </div>
                    {Array.from(groupedCredentials.entries()).map(([integration, creds]) => {
                      const firstCred = creds[0];
                      return (
                        <div
                          key={integration}
                          className="flex items-center gap-3 px-3 py-2 rounded-xl text-sm text-theme-primary"
                        >
                          <ServiceIcon iconSlug={firstCred.integration?.toLowerCase()} size="sm" />
                          <span className="flex-1 truncate">{firstCred.name}</span>
                          <CheckCircle className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
                        </div>
                      );
                    })}
                    <div className="my-1.5 border-t border-gray-200/70 dark:border-gray-700/70" />
                  </>
                )}

                {/* Available section label */}
                {!searchTerm && (
                  <div className="px-3 pt-1 pb-1.5">
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-[var(--text-tertiary)]">
                      {t('availableLabel')}
                    </span>
                  </div>
                )}

                {/* Templates */}
                {availableTemplates.length === 0 && !isLoadingMore ? (
                  <div className="text-center py-6 text-xs text-[var(--text-tertiary)]">
                    {t('noResults')}
                  </div>
                ) : (
                  availableTemplates.map((tmpl, idx) => (
                      <button
                        key={`${tmpl.id}-${tmpl.credential_name}-${idx}`}
                        onClick={() => handleConfigureNew(tmpl)}
                        className="w-full flex items-center gap-3 px-3 py-2 rounded-xl text-sm text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
                      >
                        <ServiceIcon iconSlug={tmpl.icon_slug} size="sm" />
                        <span className="flex-1 truncate text-left">{tmpl.display_name}</span>
                        <Plus className="h-3.5 w-3.5 text-[var(--text-tertiary)] shrink-0" />
                      </button>
                  ))
                )}

                {/* Load more spinner */}
                {isLoadingMore && (
                  <div className="flex items-center justify-center py-3">
                    <Loader2 className="h-4 w-4 animate-spin text-[var(--text-tertiary)]" />
                  </div>
                )}
              </>
            )}
          </div>
        </>
      ) : (
        /* ====== SKILLS VIEW ====== */
        <>
          {/* Header: search */}
          <div className="p-2 border-b border-gray-200/70 dark:border-gray-700/70 space-y-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-[var(--text-tertiary)]" />
              <input
                ref={skillSearchRef}
                type="text"
                value={skillSearch}
                onChange={(e) => setSkillSearch(e.target.value)}
                placeholder={t('searchSkills')}
                className="w-full pl-9 pr-3 py-2 text-sm bg-[var(--bg-secondary)] rounded-lg border-none outline-none text-theme-primary placeholder:text-[var(--text-tertiary)]"
              />
            </div>
          </div>

          {/* Skills tree */}
          <div className="flex-1 overflow-y-auto p-2">
            {isLoadingSkillsView ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-4 w-4 animate-spin text-[var(--text-tertiary)]" />
              </div>
            ) : allSkills.length === 0 ? (
              <div className="text-center py-6 text-xs text-[var(--text-tertiary)]">
                {t('noResults')}
              </div>
            ) : (
              <SkillFolderTreeSelect
                allFolders={allFolders}
                allSkills={allSkills}
                selectedSkillIds={activeSkillIds ?? new Set()}
                onSelectionChange={handleSkillSelectionChange}
                onEditSkill={(skill) => { setEditingSkill(skill); onClose(); }}
              />
            )}
          </div>
        </>
      )}
    </div>
  ) : null;

  return (
    <>
      {usePortal
        ? createPortal(menuContent, document.body)
        : menuContent
      }

      {/* Credential Wizard */}
      <CredentialWizard
        template={selectedTemplate}
        open={isWizardOpen}
        onOpenChange={handleWizardClose}
        onCredentialAdded={handleCredentialAdded}
      />

      {/* Skill Edit Modal */}
      {editingSkill && (
        <CreateSkillModal
          onClose={() => setEditingSkill(null)}
          onSkillCreated={() => {
            setEditingSkill(null);
            queryClient.invalidateQueries({ queryKey: ['skills'] });
          }}
          skill={editingSkill}
        />
      )}
    </>
  );
};
