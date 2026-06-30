'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ArrowRight, ArrowLeft, Database, Table, Zap, Workflow, Info } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import { ApiListSkeleton, ToolListSkeleton } from '../../SkeletonLoaders';
import { TRIGGER_TYPES } from '../nodeTypes';
import type { DataSource, DataSourceTable } from '../../../hooks/useDataSourceData';
import type { WorkflowItem } from '../../../hooks/useWorkflowsData';
import { EmptyState } from '../../shared/EmptyState';

interface InspectorTriggerNodeProps {
  // Navigation state
  triggerNavigationLevel: 'triggers' | 'types' | 'datasources' | 'tables' | 'workflows';
  triggerSearchQuery: string;
  setTriggerSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  triggerSelectedType: string | null;
  selectedDataSourceId: number | null;

  // Node detection
  isTriggerGenericNode: boolean;
  isGenericEntryTrigger: boolean;
  isWebhookTrigger: boolean;
  isScheduleTrigger: boolean;
  isManualTrigger: boolean;
  isTablesTrigger: boolean;
  isWorkflowsTrigger: boolean;
  isChatTrigger: boolean;
  isFormTrigger: boolean;
  isDataSourceSelected: boolean;

  // Data
  dataSources: DataSource[];
  isLoadingDataSources: boolean;
  dataSourceTables: DataSourceTable[];
  isLoadingTables: boolean;
  workflows: WorkflowItem[];
  isLoadingWorkflows: boolean;

  // Handlers
  handleTriggerTypeClick: (triggerType: typeof TRIGGER_TYPES[0]) => void;
  handleTriggerSelect: (triggerType: typeof TRIGGER_TYPES[0]) => void;
  handleDataSourceSelect: (dataSource: any) => void;
  handleTableSelect: (table: any, dataSource: any) => void;
  handleWorkflowSelect: (workflow: WorkflowItem) => void;
  handleTriggerBackFromTables?: () => void;
  handleTriggerBackFromDataSources?: () => void;
  handleTriggerBackFromWorkflows?: () => void;
  onSelectNode?: (nodeId: string | any) => void;
  onUpdate: (data: any) => void;

  // Node
  node: any;

  // Current workflow ID to filter from selection
  currentWorkflowId?: string;
}

export function InspectorTriggerNode({
  triggerNavigationLevel,
  triggerSearchQuery,
  setTriggerSearchQuery,
  triggerSelectedType,
  selectedDataSourceId,
  isTriggerGenericNode,
  isGenericEntryTrigger,
  isWebhookTrigger,
  isScheduleTrigger,
  isManualTrigger,
  isTablesTrigger,
  isWorkflowsTrigger,
  isChatTrigger,
  isFormTrigger,
  isDataSourceSelected,
  dataSources,
  isLoadingDataSources,
  dataSourceTables,
  isLoadingTables,
  workflows,
  isLoadingWorkflows,
  handleTriggerTypeClick,
  handleTriggerSelect,
  handleDataSourceSelect,
  handleTableSelect,
  handleWorkflowSelect,
  handleTriggerBackFromTables,
  handleTriggerBackFromDataSources,
  handleTriggerBackFromWorkflows,
  onSelectNode,
  onUpdate,
  node,
  currentWorkflowId,
}: InspectorTriggerNodeProps) {
  const t = useTranslations('workflowBuilder.inspector');

  // Don't show navigation if datasource is already selected OR if table is selected OR if workflow is selected
  const isTableSelected = isTablesTrigger && (node?.data as any)?.dataSourceData?.tableName;
  const isWorkflowSelected = isWorkflowsTrigger && (node?.data as any)?.workflowData?.workflowId;
  if (isDataSourceSelected || isTableSelected || isWorkflowSelected) {
    return null; // Don't show navigation if datasource, table, or workflow is already selected
  }

  // Check if current workflow is being filtered out
  const isCurrentWorkflowFiltered = currentWorkflowId && workflows.some(wf => wf.id === currentWorkflowId);

  return (
    <div className="space-y-4 pt-2">
      {/* Search - Show for triggers list */}
      {triggerNavigationLevel === 'triggers' && (isTriggerGenericNode || isGenericEntryTrigger) && (
        <div className="relative">
          <Input
            type="text"
            placeholder="Search for a trigger type..."
            value={triggerSearchQuery}
            onChange={(e) => setTriggerSearchQuery(e.target.value)}
            className="w-full"
          />
        </div>
      )}
      
      {/* Search - Show for datasources */}
      {triggerNavigationLevel === 'datasources' && (
        <div className="relative">
          <Input
            type="text"
            placeholder="Search datasources..."
            value={triggerSearchQuery}
            onChange={(e) => setTriggerSearchQuery(e.target.value)}
            className="w-full"
          />
        </div>
      )}

      {/* Search - Show for workflows */}
      {triggerNavigationLevel === 'workflows' && (
        <div className="relative">
          <Input
            type="text"
            placeholder="Search workflows..."
            value={triggerSearchQuery}
            onChange={(e) => setTriggerSearchQuery(e.target.value)}
            className="w-full"
          />
        </div>
      )}

      {/* Triggers List - Show for generic trigger node */}
      {triggerNavigationLevel === 'triggers' && (isTriggerGenericNode || isGenericEntryTrigger) && (
        <div className="space-y-2 overflow-x-hidden">
          {TRIGGER_TYPES
            .filter(trigger => 
              !triggerSearchQuery.trim() ||
              trigger.name.toLowerCase().includes(triggerSearchQuery.toLowerCase()) ||
              trigger.description?.toLowerCase().includes(triggerSearchQuery.toLowerCase())
            )
            .map((trigger) => {
              const TriggerIcon = trigger.icon;
              return (
                <div
                  key={trigger.id}
                  className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer"
                  onClick={() => handleTriggerTypeClick(trigger)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-yellow-100 dark:bg-yellow-900/30">
                    <TriggerIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {trigger.name}
                      </div>
                      {trigger.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {trigger.description}
                        </div>
                      )}
                    </div>
                    <ArrowRight className="w-4 h-4 text-slate-400 dark:text-slate-500 flex-shrink-0" />
                  </div>
                </div>
              );
            })}
        </div>
      )}

      {/* DataSources List - Show when Tables trigger is clicked */}
      {triggerNavigationLevel === 'datasources' && (
        <div className="space-y-2 overflow-x-hidden">
          {isLoadingDataSources ? (
            <ApiListSkeleton count={5} />
          ) : (
            <>
              {dataSources
                .filter(ds => 
                  !triggerSearchQuery.trim() ||
                  ds.name.toLowerCase().includes(triggerSearchQuery.toLowerCase()) ||
                  ds.description?.toLowerCase().includes(triggerSearchQuery.toLowerCase())
                )
                .map((dataSource) => {
                  const dataSourceId = `tables-trigger-${dataSource.id}`;
                  const isSelected = node?.data?.id === dataSourceId || ((node?.data as any)?.dataSourceData?.dataSourceId === dataSource.id && !(node?.data as any)?.dataSourceData?.tableName);
                  
                              const handleDataSourceClick = () => {
                                handleDataSourceSelect(dataSource);
                              };
                  
                  return (
                    <div
                      key={dataSource.id}
                      className={clsx(
                        "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                        isSelected 
                          ? "bg-yellow-100 dark:bg-yellow-900/30 border-2 border-yellow-300 dark:border-yellow-700"
                          : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                      )}
                      onClick={handleDataSourceClick}
                    >
                      <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-yellow-100 dark:bg-yellow-900/30">
                        <Database className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                      </div>
                      <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                        <div className="flex-1 min-w-0 flex flex-col justify-center">
                          <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                            {dataSource.name}
                          </div>
                          {dataSource.description && (
                            <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                              {dataSource.description}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              {dataSources.length === 0 && !isLoadingDataSources && (
                <EmptyState message="No data source found" className="text-center py-8" />
              )}
            </>
          )}
        </div>
      )}

      {/* Workflows List - Show when Workflows trigger is clicked */}
      {triggerNavigationLevel === 'workflows' && (
        <div className="space-y-2 overflow-x-hidden">
          {isLoadingWorkflows ? (
            <ApiListSkeleton count={5} />
          ) : (
            <>
              {workflows
                .filter(wf =>
                  // Exclude the current workflow to prevent self-triggering
                  wf.id !== currentWorkflowId &&
                  (!triggerSearchQuery.trim() ||
                  wf.name.toLowerCase().includes(triggerSearchQuery.toLowerCase()) ||
                  wf.description?.toLowerCase().includes(triggerSearchQuery.toLowerCase()))
                )
                .map((workflow) => {
                  const workflowTriggerId = `workflows-trigger-${workflow.id}`;
                  const isSelected = node?.data?.id === workflowTriggerId || ((node?.data as any)?.workflowData?.workflowId === workflow.id);

                  const handleWorkflowClick = () => {
                    handleWorkflowSelect(workflow);
                  };

                  return (
                    <div
                      key={workflow.id}
                      className={clsx(
                        "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                        isSelected
                          ? "bg-yellow-100 dark:bg-yellow-900/30 border-2 border-yellow-300 dark:border-yellow-700"
                          : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                      )}
                      onClick={handleWorkflowClick}
                    >
                      <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-yellow-100 dark:bg-yellow-900/30">
                        <Workflow className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                      </div>
                      <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                        <div className="flex-1 min-w-0 flex flex-col justify-center">
                          <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                            {workflow.name}
                          </div>
                          {workflow.description && (
                            <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                              {workflow.description}
                            </div>
                          )}
                        </div>
                        {isSelected && (
                          <div className="text-xs text-yellow-600 dark:text-yellow-400 font-semibold">
                            Selected
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              {/* Info message when current workflow is filtered out */}
              {isCurrentWorkflowFiltered && (
                <div className="flex items-start gap-2 px-3 py-2 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800 mt-2">
                  <Info className="w-4 h-4 text-blue-500 dark:text-blue-400 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-700 dark:text-blue-300">
                    {t('workflowsTriggerCurrentExcluded')}
                  </p>
                </div>
              )}
              {workflows.length === 0 && !isLoadingWorkflows && (
                <div className="text-center py-8 text-slate-500 dark:text-slate-400 text-sm">
                  No workflows found
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* DataSource Tables List - Show when a datasource is selected */}
      {triggerNavigationLevel === 'tables' && selectedDataSourceId && (
        <div className="space-y-2 overflow-x-hidden">
          {/* Back button to return to datasources list */}
          {handleTriggerBackFromTables && (
            <button
              onClick={handleTriggerBackFromTables}
              className="flex items-center gap-2 px-3 py-2 rounded-2xl hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer text-left w-full"
            >
              <ArrowLeft className="w-4 h-4 text-slate-400 dark:text-slate-500" />
              <span className="text-sm font-medium text-slate-900 dark:text-slate-100">
                Back to {dataSources.find(ds => ds.id === selectedDataSourceId)?.name || 'Data Sources'}
              </span>
            </button>
          )}
          {isLoadingTables ? (
            <ToolListSkeleton count={5} />
          ) : (
            <>
              {dataSourceTables
                .filter(table => 
                  !triggerSearchQuery.trim() ||
                  table.name.toLowerCase().includes(triggerSearchQuery.toLowerCase())
                )
                .map((table) => {
                  const dataSource = dataSources.find(ds => ds.id === selectedDataSourceId);
                  const tableId = `tables-trigger-${selectedDataSourceId}-${table.name}`;
                  const isSelected = node?.data?.id === tableId || (node?.data as any)?.dataSourceData?.tableName === table.name;
                  return (
                    <div
                      key={`${table.name}-${table.schema || ''}`}
                      className={clsx(
                        "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                        isSelected 
                          ? "bg-yellow-100 dark:bg-yellow-900/30 border-2 border-yellow-300 dark:border-yellow-700"
                          : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                      )}
                      onClick={() => handleTableSelect(table, dataSource)}
                    >
                      <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-yellow-100 dark:bg-yellow-900/30">
                        <Table className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                      </div>
                      <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                        <div className="flex-1 min-w-0 flex flex-col justify-center">
                          <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                            {table.name}
                          </div>
                          {table.schema && (
                            <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                              {table.schema}
                            </div>
                          )}
                        </div>
                        {isSelected && (
                          <div className="text-xs text-yellow-600 dark:text-yellow-400 font-semibold">
                            Selected
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              {dataSourceTables.length === 0 && !isLoadingTables && (
                <div className="text-center py-8 text-slate-500 dark:text-slate-400 text-sm">
                  No tables found for this data source
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Trigger Type Details - Show when a specific trigger type is selected (but not tables, workflows, or chat) */}
      {(triggerNavigationLevel === 'types' || isWebhookTrigger || isScheduleTrigger || isManualTrigger || isChatTrigger || isFormTrigger) && !isTriggerGenericNode && !isTablesTrigger && !isWorkflowsTrigger && (
        <div className="space-y-2 overflow-x-hidden">
          {TRIGGER_TYPES
            .filter(trigger =>
              trigger.id === triggerSelectedType ||
              trigger.id === (isWebhookTrigger ? 'webhook-trigger' :
                            isScheduleTrigger ? 'schedule-trigger' :
                            isManualTrigger ? 'manual-trigger' :
                            isChatTrigger ? 'chat-trigger' :
                            isFormTrigger ? 'form-trigger' :
                            isTablesTrigger ? 'tables-trigger' :
                            isWorkflowsTrigger ? 'workflows-trigger' : null)
            )
            .map((trigger) => {
              const isSelected = trigger.id === (node?.data?.id || triggerSelectedType);
              const TriggerIcon = trigger.icon;
              return (
                <div
                  key={trigger.id}
                  className={clsx(
                    "flex items-center gap-2 px-3 py-2 rounded-2xl transition-colors cursor-pointer",
                    isSelected 
                      ? "bg-yellow-100 dark:bg-yellow-900/30 border-2 border-yellow-300 dark:border-yellow-700"
                      : "hover:bg-gray-50 dark:hover:bg-gray-800/50"
                  )}
                  onClick={() => handleTriggerSelect(trigger)}
                >
                  <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded bg-yellow-100 dark:bg-yellow-900/30">
                    <TriggerIcon className="w-4 h-4 text-gray-700 dark:text-gray-300" />
                  </div>
                  <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <div className="text-sm font-medium text-slate-900 dark:text-slate-100 text-left mb-1 truncate">
                        {trigger.name}
                      </div>
                      {trigger.description && (
                        <div className="text-xs text-slate-400 dark:text-slate-500 line-clamp-2 leading-relaxed">
                          {trigger.description}
                        </div>
                      )}
                    </div>
                    {isSelected && (
                      <div className="text-xs text-yellow-600 dark:text-yellow-400 font-semibold">
                        Selected
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
}

