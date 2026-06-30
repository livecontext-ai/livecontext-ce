'use client';

import React, { useState, useMemo, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import {
  Workflow as WorkflowIcon,
  Monitor,
  Bot,
  Table,
  AppWindow,
  FileText,
  Plus,
  Pencil,
  Clock,
  Globe,
  Search,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { AuthenticatedView } from '@/components/views/AuthenticatedView';
import { useProject, useProjectResources, useProjectPermissions } from '@/hooks/useProjects';
import { ProjectMultiStepModal, getProjectIcon } from '@/components/project/ProjectMultiStepModal';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { AvatarDisplay } from '@/components/agents';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { useSidePanel } from '@/contexts/SidePanelContext';
import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';
import { AgentPanelContent } from '@/components/app/AgentPanelContent';
import { InterfacePanelContent } from '@/components/app/InterfacePanelContent';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import { openFilesPanel } from '@/lib/sidePanel/openFilesPanel';
import { DataSourceCard } from '@/components/data-table/DataSourceCard';
import { FilesExplorerBody } from '@/components/files/FilesExplorerBody';
import { ApplicationCard } from '@/components/applications/ApplicationCard';
import type { DataSource } from '@/lib/api';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { fileService } from '@/lib/api/orchestrator/file.service';


type TabKey = 'workflows' | 'agents' | 'interfaces' | 'tables' | 'applications' | 'files';

const TABS: { key: TabKey; icon: React.ElementType; labelKey: string }[] = [
  { key: 'agents', icon: Bot, labelKey: 'tabs.agents' },
  { key: 'applications', icon: AppWindow, labelKey: 'tabs.applications' },
  { key: 'workflows', icon: WorkflowIcon, labelKey: 'tabs.workflows' },
  { key: 'interfaces', icon: Monitor, labelKey: 'tabs.interfaces' },
  { key: 'tables', icon: Table, labelKey: 'tabs.tables' },
  { key: 'files', icon: FileText, labelKey: 'tabs.files' },
];

interface ProjectDetailViewProps {
  projectId: string;
}

export function ProjectDetailView({ projectId }: ProjectDetailViewProps) {
  const t = useTranslations('project');
  const sidePanel = useSidePanel();

  const { project, loading: projectLoading } = useProject(projectId);
  const { resources, loading: resourcesLoading, refetch: refetchResources } = useProjectResources(projectId);
  const permissions = useProjectPermissions(project);

  const [activeTab, setActiveTab] = useState<TabKey>('agents');
  const [showModal, setShowModal] = useState(false);
  const [modalStep, setModalStep] = useState(1);

  const tabCounts = useMemo(() => {
    if (!resources) return { workflows: 0, agents: 0, interfaces: 0, tables: 0, applications: 0, files: 0 };
    return {
      workflows: resources.workflows?.length || 0,
      agents: resources.agents?.length || 0,
      interfaces: (resources.interfaces || []).filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search').length,
      tables: resources.datasources?.length || 0,
      applications: resources.applications?.length || 0,
      files: resources.files?.length || 0,
    };
  }, [resources]);

  const handleOpenResource = useCallback((type: string, id: string, meta?: { name?: string; avatarUrl?: string; mimeType?: string; sizeBytes?: number; createdAt?: string }) => {
    if (!sidePanel) return;
    const label = meta?.name || id;

    switch (type) {
      case 'workflow':
        sidePanel.openTab({
          id: `workflow-${id}`,
          label,
          icon: <WorkflowIcon className="w-4 h-4" />,
          content: <WorkflowBuilderPanelContent workflowId={id} />,
          preferredWidth: 0.35,
        });
        break;
      case 'agent':
        sidePanel.openTab({
          id: `agent-${id}`,
          label,
          icon: meta?.avatarUrl
            ? <AvatarDisplay avatarUrl={meta.avatarUrl} name={label} size="sm" className="!w-4 !h-4" />
            : <Bot className="w-4 h-4" />,
          content: <AgentPanelContent agentId={id} />,
          preferredWidth: 0.35,
        });
        break;
      case 'interface':
        sidePanel.openTab({
          id: `interface-${id}`,
          label,
          icon: <Monitor className="w-4 h-4" />,
          content: <InterfacePanelContent interfaceId={id} />,
        });
        break;
      case 'datasource':
        sidePanel.openTab({
          id: `datasource-${id}`,
          label,
          icon: <Table className="w-4 h-4" />,
          content: <DataSourcePanelContent dataSourceId={id} />,
          preferredWidth: 0.35,
        });
        break;
      case 'application':
        sidePanel.openTab({
          id: `application-${id}`,
          label,
          icon: <AppWindow className="w-4 h-4" />,
          content: <ApplicationPanelContent publicationId={id} />,
          preferredWidth: 0.35,
        });
        break;
      case 'file':
        // Canonical Files side panel: FileDetailView whose back chevron returns to the
        // files LIST (never closes the panel), identical to every other file surface -
        // instead of a bespoke `file-<id>` tab whose onBack removed it. Metadata
        // (mimeType/size/created) is threaded so the inline preview + metadata grid reach
        // parity with the Files browser (a bare name with no MIME shows the placeholder).
        openFilesPanel(sidePanel, {
          id,
          name: meta?.name,
          mimeType: meta?.mimeType,
          size: meta?.sizeBytes,
          createdAt: meta?.createdAt,
        });
        break;
    }
  }, [sidePanel]);

  if (projectLoading || !project) {
    return (
      <AuthenticatedView>
        <div className="flex items-center justify-center py-24">
          <LoadingSpinner size="md" />
        </div>
      </AuthenticatedView>
    );
  }

  return (
    <AuthenticatedView overflow>
      {/* Header: matches workflow/tables/agent page pattern */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div className="flex items-center gap-3">
          {(() => {
            const IconComp = getProjectIcon(project.icon);
            const bgColor = project.color || '#3b82f6';
            return (
              <div
                className="w-10 h-10 rounded-full flex items-center justify-center"
                style={{ backgroundColor: bgColor }}
              >
                <IconComp className="w-5 h-5 text-white" />
              </div>
            );
          })()}
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">{project.name}</h2>
            {project.description && (
              <p className="text-sm text-theme-secondary">{project.description}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {permissions.canEdit && (
            <Button variant="outline" size="sm" onClick={() => { setModalStep(1); setShowModal(true); }}>
              <Pencil className="h-3.5 w-3.5 mr-1.5" />
              {t('editProject')}
            </Button>
          )}
          {permissions.canAssignResources && (
            <Button size="sm" onClick={() => { setModalStep(2); setShowModal(true); }}>
              <Plus className="h-3.5 w-3.5 mr-1.5" />
              {t('addResources')}
            </Button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-theme mb-6">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          const count = tabCounts[tab.key];
          const isActive = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                isActive
                  ? 'border-[var(--accent-primary)] text-[var(--accent-primary)]'
                  : 'border-transparent text-theme-secondary hover:text-theme-primary'
              }`}
            >
              <Icon className="h-4 w-4" />
              {t(tab.labelKey)}
              {count > 0 && (
                <span className={`text-xs px-1.5 py-0.5 rounded-full ${
                  isActive
                    ? 'bg-[var(--accent-primary)]/10 text-[var(--accent-primary)]'
                    : 'bg-theme-tertiary text-theme-muted'
                }`}>
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* Tab content */}
      {resourcesLoading ? (
        <div className="flex items-center justify-center py-16">
          <LoadingSpinner size="md" />
        </div>
      ) : (
        <div>
          {activeTab === 'workflows' && (
            <WorkflowsGrid
              workflows={resources?.workflows || []}
              onCardClick={(id, name) => handleOpenResource('workflow', id, { name })}
              t={t}
            />
          )}
          {activeTab === 'agents' && (
            <AgentsGrid
              agents={resources?.agents || []}
              onCardClick={(id, name, avatarUrl) => handleOpenResource('agent', id, { name, avatarUrl })}
              t={t}
            />
          )}
          {activeTab === 'interfaces' && (
            <InterfacesGrid
              interfaces={(resources?.interfaces || []).filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search')}
              onCardClick={(id, name) => handleOpenResource('interface', id, { name })}
              t={t}
            />
          )}
          {activeTab === 'tables' && (
            <TablesGrid
              datasources={resources?.datasources || []}
              rowCounts={resources?.datasourceRowCounts || {}}
              sampleRows={resources?.datasourceSampleRows || {}}
              onCardClick={(id, name) => handleOpenResource('datasource', id, { name })}
              t={t}
            />
          )}
          {activeTab === 'applications' && (
            <ApplicationsGrid
              applications={resources?.applications || []}
              onCardClick={(id, name) => handleOpenResource('application', id, { name })}
              t={t}
            />
          )}
          {activeTab === 'files' && (
            <FilesGrid
              files={resources?.files || []}
              onCardClick={(entry) => handleOpenResource('file', entry.id, {
                name: entry.fileName ?? undefined,
                mimeType: entry.mimeType ?? undefined,
                sizeBytes: entry.sizeBytes ?? undefined,
                createdAt: entry.createdAt,
              })}
              t={t}
            />
          )}
        </div>
      )}

      {/* Modal */}
      {showModal && (
        <ProjectMultiStepModal
          project={project}
          initialStep={modalStep}
          onClose={() => setShowModal(false)}
          onSuccess={() => {
            setShowModal(false);
            refetchResources();
          }}
        />
      )}
    </AuthenticatedView>
  );
}

// ─── Workflow cards (matches WorkflowTable pattern) ──────────────

function WorkflowsGrid({
  workflows,
  onCardClick,
  t,
}: {
  workflows: any[];
  onCardClick: (id: string, name?: string) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  if (workflows.length === 0) {
    return <EmptyTab type={t('tabs.workflows')} t={t} />;
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {workflows.map((w: any) => {
        let nodeCount = 0;
        if (w.plan) {
          nodeCount =
            (w.plan.triggers?.length || 0) +
            (w.plan.mcps?.length || 0) +
            (w.plan.cores?.length || 0) +
            (w.plan.agents?.length || 0) +
            (w.plan.interfaces?.length || 0);
        }

        return (
          <div
            key={w.id}
            className="group rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow cursor-pointer"
            onClick={() => onCardClick(w.id, w.name)}
          >
            {/* Dot pattern background */}
            <div className="relative h-[120px] flex items-center justify-center overflow-hidden bg-slate-50 dark:bg-slate-900">
              <div
                className="absolute inset-0 dark:hidden"
                style={{
                  backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)',
                  backgroundSize: '16px 16px',
                }}
              />
              <div
                className="hidden dark:block absolute inset-0"
                style={{
                  backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)',
                  backgroundSize: '16px 16px',
                }}
              />
              <div className="relative z-10">
                {w.nodeIcons && w.nodeIcons.length > 0 ? (
                  <WorkflowNodeIcons nodeIcons={w.nodeIcons} totalNodeCount={nodeCount} />
                ) : (
                  <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center">
                    <WorkflowIcon className="w-6 h-6 text-theme-primary" />
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-theme-primary truncate">{w.name}</span>
                  {nodeCount > 0 && (
                    <span className="text-xs text-theme-muted shrink-0">
                      {nodeCount} node{nodeCount !== 1 ? 's' : ''}
                    </span>
                  )}
                </div>
                {w.description && (
                  <p className="text-xs text-theme-muted truncate mt-0.5">{w.description}</p>
                )}
                <div className="flex items-center gap-1 mt-1 text-xs text-theme-muted">
                  <Clock className="h-3 w-3" />
                  <span>{formatRelativeDate(w.updatedAt)}</span>
                  {w.isPublished && (
                    <>
                      <span className="text-slate-300 dark:text-slate-600">·</span>
                      <Globe className="h-3 w-3" />
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ─── Agent cards (matches AgentTable pattern) ────────────────────

function AgentsGrid({
  agents,
  onCardClick,
  t,
}: {
  agents: any[];
  onCardClick: (id: string, name?: string, avatarUrl?: string) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  if (agents.length === 0) {
    return <EmptyTab type={t('tabs.agents')} t={t} />;
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {agents.map((agent: any) => (
        <div
          key={agent.id}
          className="group cursor-pointer rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow"
          onClick={() => onCardClick(agent.id, agent.name, agent.avatarUrl)}
        >
          {/* Avatar area */}
          <div className="relative h-[120px] flex items-center justify-center overflow-hidden bg-white dark:bg-slate-900">
            <div className="relative z-10">
              <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="xl" />
            </div>
          </div>

          {/* Footer */}
          <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
            <div className="flex-1 min-w-0">
              <span className="text-sm font-medium text-theme-primary truncate block">{agent.name}</span>
              {agent.description && (
                <p className="text-xs text-theme-muted truncate mt-0.5">{agent.description}</p>
              )}
              {agent.modelProvider && agent.modelName && (
                <p className="text-xs text-theme-muted mt-0.5">{agent.modelProvider}/{agent.modelName}</p>
              )}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Interface cards (matches InterfaceTable pattern) ─────────────

function InterfacesGrid({
  interfaces,
  onCardClick,
  t,
}: {
  interfaces: any[];
  onCardClick: (id: string, name?: string) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  if (interfaces.length === 0) {
    return <EmptyTab type={t('tabs.interfaces')} t={t} />;
  }

  return (
    <div className="columns-1 md:columns-2 lg:columns-3 gap-4">
      {interfaces.map((intf: any) => {
        const isWebSearch = intf.interfaceType === 'web_search';
        const hasTemplate = !!intf.htmlTemplate;

        // Parse web_search data
        let webSearchPreview: { query: string; searchItems: { title: string; url: string }[]; fetchedCount: number } | null = null;
        if (isWebSearch && intf.data) {
          const results = (intf.data.results as any[]) || [];
          const firstResult = results[0];
          const query = (firstResult?.query as string) || '';
          const searchItems: { title: string; url: string }[] = [];
          for (const r of results) {
            const items = r.results as any[] | undefined;
            if (items) {
              for (const item of items) {
                if (item.url && item.title) searchItems.push({ title: item.title, url: item.url });
              }
            }
          }
          const fetchedCount = results.reduce((count: number, r: any) => {
            if (r.pages) return count + (r.pages as any[]).length;
            if (r.action === 'fetch' && r.url) return count + 1;
            return count;
          }, 0);
          webSearchPreview = { query, searchItems, fetchedCount };
        }

        return (
          <div
            key={intf.id}
            className="group space-y-3 mb-4 break-inside-avoid relative cursor-pointer"
            onClick={() => onCardClick(intf.id, intf.name)}
          >
            {/* Preview area */}
            {isWebSearch && webSearchPreview ? (
              <div className="rounded-xl border border-theme overflow-hidden">
                {/* Browser chrome header */}
                <div className="flex items-center gap-2 px-2.5 py-1.5 bg-theme-secondary border-b border-theme">
                  <div className="flex items-center gap-1">
                    <span className="w-2 h-2 rounded-full bg-red-400/70" />
                    <span className="w-2 h-2 rounded-full bg-yellow-400/70" />
                    <span className="w-2 h-2 rounded-full bg-green-400/70" />
                  </div>
                  <div className="flex-1 flex items-center gap-1 px-2 py-0.5 bg-theme-primary rounded border border-theme text-xs">
                    <Search className="w-3 h-3 text-theme-muted shrink-0" />
                    <span className="truncate text-theme-secondary">{webSearchPreview.query || intf.name}</span>
                  </div>
                </div>
                {/* Results list */}
                <div className="bg-theme-primary px-3 py-2 space-y-1">
                  {webSearchPreview.searchItems.length > 0 ? (
                    <>
                      {webSearchPreview.searchItems.slice(0, 4).map((item, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <img width={12} height={12} alt="" className="rounded-full shrink-0"
                            src={`https://s2.googleusercontent.com/s2/favicons?domain=${item.url}&sz=64`} />
                          <span className="text-xs text-theme-secondary truncate">{item.title}</span>
                        </div>
                      ))}
                      {webSearchPreview.searchItems.length > 4 && (
                        <span className="text-xs text-theme-muted">+{webSearchPreview.searchItems.length - 4} more</span>
                      )}
                    </>
                  ) : (
                    <div className="flex items-center gap-2">
                      <Globe className="w-3.5 h-3.5 text-theme-muted shrink-0" />
                      <span className="text-xs text-theme-secondary">{intf.name}</span>
                    </div>
                  )}
                </div>
                {/* Footer counts */}
                <div className="bg-theme-primary border-t border-theme px-3 py-1.5">
                  <div className="flex items-center gap-2">
                    {webSearchPreview.searchItems.length > 0 && (
                      <span className="text-xs text-theme-muted flex items-center gap-1">
                        <Search className="h-2.5 w-2.5" />
                        {webSearchPreview.searchItems.length}
                      </span>
                    )}
                    {webSearchPreview.fetchedCount > 0 && (
                      <span className="text-xs text-theme-muted flex items-center gap-1">
                        <Globe className="h-2.5 w-2.5" />
                        {webSearchPreview.fetchedCount}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            ) : hasTemplate ? (
              <div className="pointer-events-none">
                <InterfaceThumbnail
                  htmlTemplate={intf.htmlTemplate}
                  mode="edit"
                  customCss={intf.cssTemplate || undefined}
                  jsTemplate={intf.jsTemplate || undefined}
                  maxHeight={400}
                />
              </div>
            ) : (
              <div className="overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900">
                <div className="relative h-[120px] flex items-center justify-center overflow-hidden">
                  <div
                    className="absolute inset-0 dark:hidden"
                    style={{
                      backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)',
                      backgroundSize: '16px 16px',
                    }}
                  />
                  <div
                    className="hidden dark:block absolute inset-0"
                    style={{
                      backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)',
                      backgroundSize: '16px 16px',
                    }}
                  />
                  <div className="relative z-10">
                    <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center">
                      <Monitor className="w-6 h-6 text-theme-tertiary" />
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Card content */}
            <div className="px-1 py-1 space-y-1">
              <div className="flex items-center gap-1.5">
                <span className="text-sm font-medium text-theme-primary truncate flex-1">{intf.name}</span>
              </div>
              {intf.description && (
                <p className="text-xs text-theme-muted truncate" title={intf.description}>{intf.description}</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ─── Tables/DataSource rows (matches DataSourceTable pattern) ─────

function TablesGrid({
  datasources,
  rowCounts,
  sampleRows,
  onCardClick,
  t,
}: {
  datasources: any[];
  rowCounts: Record<string, number>;
  sampleRows: Record<string, Array<Record<string, unknown>>>;
  onCardClick: (id: string, name?: string) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  if (datasources.length === 0) {
    return <EmptyTab type={t('tabs.tables')} t={t} />;
  }

  // Same mini-table card grid as /app/tables (DataSourceTable). Read-only here:
  // no selection checkbox, no publication-status badge.
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {datasources.map((ds: any) => (
        <DataSourceCard
          key={ds.id}
          ds={ds as DataSource}
          rowCount={rowCounts[String(ds.id)] ?? 0}
          sampleRows={sampleRows[String(ds.id)] ?? []}
          onClick={() => onCardClick(String(ds.id), ds.name)}
        />
      ))}
    </div>
  );
}

// ─── Application cards (same ApplicationCard as /app/applications) ───────

function ApplicationsGrid({
  applications,
  onCardClick,
  t,
}: {
  applications: any[];
  onCardClick: (id: string, name?: string) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  if (applications.length === 0) {
    return <EmptyTab type={t('tabs.applications')} t={t} />;
  }

  // Same card as /app/applications (ApplicationCard) - full parity incl. the real
  // publisher avatar (the backend now returns publisherId), node icons + version
  // footer. Read-only project tab: source='published', no selection / bulk bar.
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
      {applications.map((pub: any) => {
        // The project resources map carries category as a slug string; ApplicationCard
        // reads category.name (an object), so drop a bare slug rather than mis-render it.
        const publication = {
          ...pub,
          category: pub.category && typeof pub.category === 'object' ? pub.category : undefined,
        } as WorkflowPublication;
        return (
          <ApplicationCard
            key={pub.id}
            publication={publication}
            source="published"
            isSelected={false}
            onToggleSelect={() => {}}
            onCardClick={() => onCardClick(pub.id, pub.title)}
          />
        );
      })}
    </div>
  );
}

// ─── File tiles (same FileCard grid as /app/files) ───────────────

/** Map a project file resource (ProjectService.toFileMap shape) to the
 *  StorageExplorerEntry that FileCard renders. FileCard only reads id, fileName,
 *  mimeType/contentType, formattedSize and createdAt; the rest are filled with
 *  safe nulls. The thumbnail loads via /files/by-id/{id}/raw (org-scoped by id). */
function toStorageEntry(file: any): StorageExplorerEntry {
  return {
    id: String(file.id),
    storageType: 'S3_FILE',
    sourceType: 'S3_FILE',
    fileName: file.fileName ?? file.name ?? null,
    mimeType: file.mimeType ?? null,
    sizeBytes: typeof file.sizeBytes === 'number' ? file.sizeBytes : null,
    formattedSize: formatFileSize(file.sizeBytes),
    createdAt: file.createdAt,
    workflowId: null,
    workflowName: null,
    projectId: file.projectId ?? null,
    runId: null,
    stepKey: null,
    epoch: null,
    s3Key: null,
    contentType: file.contentType ?? null,
    isFolder: false,
  };
}

function FilesGrid({
  files,
  onCardClick,
  t,
}: {
  files: any[];
  onCardClick: (entry: StorageExplorerEntry) => void;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  const tFiles = useTranslations('files');
  const tExp = useTranslations('storageExplorer');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const toggleSelect = useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const handleDownload = useCallback(async (entry: StorageExplorerEntry) => {
    try {
      await fileService.downloadAndSave(
        { id: entry.id, path: entry.s3Key ?? undefined, name: entry.fileName ?? undefined },
        entry.fileName ?? undefined,
      );
    } catch (err) {
      console.error('Download failed:', err);
    }
  }, []);

  const entries = useMemo(() => files.map(toStorageEntry), [files]);

  if (files.length === 0) {
    return <EmptyTab type={t('tabs.files')} t={t} />;
  }

  // The SAME shared body the /app/files page and the side-panel explorer use (grid
  // density): files grouped by day, newest first. The project scope has no folders
  // (flat listing). Click opens the file in the side panel; download saves it.
  return (
    <FilesExplorerBody
      variant="grid"
      entries={entries}
      enableFolders={false}
      tFiles={tFiles}
      onOpenFolder={() => {}}
      onOpenFile={onCardClick}
      onDownloadFile={handleDownload}
      downloadLabel={tExp('download')}
      selectable
      selectedIds={selected}
      onToggleSelect={toggleSelect}
    />
  );
}

function formatFileSize(bytes: number | null | undefined): string {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return unit === 0 ? `${bytes} B` : `${size.toFixed(1)} ${units[unit]}`;
}

// ─── Empty state ──────────────────────────────────────────────────

function EmptyTab({
  type,
  t,
}: {
  type: string;
  t: (key: string, values?: Record<string, string>) => string;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <p className="text-sm text-theme-secondary">
        {t('noResourcesInTab', { type: type.toLowerCase() })}
      </p>
    </div>
  );
}
