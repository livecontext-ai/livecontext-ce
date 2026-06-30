import React, { useState, useEffect, useCallback } from 'react';
import { ChevronRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Card } from '@/components/ui/card';
import DeleteToolModal from './DeleteToolModal';
import { McpTool, ApiConfig } from '../types';
import { detectPathParameters, detectQueryParameters, buildQueryString } from '../utils';
import {
  ToolHeader,
  ToolTabs,
  ConfigTab,
  ParameterListTab,
  ResponseTab,
  TestTab
} from './tool-category';

// Custom styles for tabs scroll
const tabsScrollStyles = `
  .mobile-tabs-scroll::-webkit-scrollbar,
  .medium-tabs-scroll::-webkit-scrollbar {
    display: none;
  }
  .mobile-tabs-scroll,
  .medium-tabs-scroll {
    -ms-overflow-style: none;
    scrollbar-width: none;
  }
`;

interface ToolCategoryGroupProps {
  category: string;
  tools: McpTool[];
  onToolUpdate: (toolIndex: number, updatedTool: McpTool) => void;
  onTestEndpoint: (toolIndex: number) => void;
  apiConfig: ApiConfig;
  mcpTools: McpTool[];
  setMcpTools: (tools: McpTool[]) => void;
}

const ToolCategoryGroup: React.FC<ToolCategoryGroupProps> = ({
  category,
  tools,
  onToolUpdate,
  onTestEndpoint,
  apiConfig,
  mcpTools,
  setMcpTools
}) => {
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [collapsedTools, setCollapsedTools] = useState<Set<string>>(new Set());
  const [selectedLanguages, setSelectedLanguages] = useState<Record<string, string>>({});
  const [activeTabs, setActiveTabs] = useState<Record<string, string>>({});
  const [editingDescriptions, setEditingDescriptions] = useState<Record<string, boolean>>({});
  const [showTabInfo, setShowTabInfo] = useState<Record<string, boolean>>({});
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [toolToDelete, setToolToDelete] = useState<McpTool | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [endpointEditTimeouts, setEndpointEditTimeouts] = useState<Record<string, NodeJS.Timeout>>({});
  const [editingToolNames, setEditingToolNames] = useState<Record<string, boolean>>({});
  const [editingToolNameValues, setEditingToolNameValues] = useState<Record<string, string>>({});
  const t = useTranslations('developers');

  // Initialize default tab for each tool
  useEffect(() => {
    tools.forEach((tool, toolIndex) => {
      const tabKey = `${tool.name}-${toolIndex}`;
      if (!activeTabs[tabKey]) {
        setActiveTabs(prev => ({ ...prev, [tabKey]: 'config' }));
      }
    });
  }, [tools, activeTabs]);

  // Cleanup timeouts on unmount
  useEffect(() => {
    return () => {
      Object.values(endpointEditTimeouts).forEach(timeout => {
        if (timeout) clearTimeout(timeout);
      });
    };
  }, [endpointEditTimeouts]);

  // Helper functions
  const getToolKey = (tool: McpTool, toolIndex: number) => `${tool.name}-${toolIndex}`;

  const toggleToolCollapse = (toolKey: string) => {
    setCollapsedTools(prev => {
      const newSet = new Set(prev);
      if (newSet.has(toolKey)) {
        newSet.delete(toolKey);
      } else {
        newSet.add(toolKey);
      }
      return newSet;
    });
  };

  const resetTestStatus = useCallback((tool: McpTool): McpTool => ({
    ...tool,
    testStatus: undefined,
    testResult: undefined
  }), []);

  const getGlobalIndex = useCallback((localToolIndex: number): number => {
    const groupedTools = mcpTools.reduce((acc, t) => {
      if (!acc[t.toolCategory]) {
        acc[t.toolCategory] = [];
      }
      acc[t.toolCategory].push(t);
      return acc;
    }, {} as Record<string, McpTool[]>);

    const categoryNames = Object.keys(groupedTools);
    const currentCategoryIndex = categoryNames.indexOf(category);

    let toolsBeforeCurrentCategory = 0;
    for (let i = 0; i < currentCategoryIndex; i++) {
      toolsBeforeCurrentCategory += groupedTools[categoryNames[i]].length;
    }

    return toolsBeforeCurrentCategory + localToolIndex;
  }, [mcpTools, category]);

  // Tool update with test reset
  const updateToolWithTestReset = useCallback((toolIndex: number, updatedTool: McpTool) => {
    let finalTool = { ...updatedTool };
    const originalTool = mcpTools[toolIndex];

    // Check if query parameters changed
    if (JSON.stringify(originalTool.queryParameters || []) !== JSON.stringify(updatedTool.queryParameters || [])) {
      const baseEndpoint = updatedTool.endpoint.split('?')[0];
      const newQueryString = buildQueryString(updatedTool.queryParameters);
      finalTool.endpoint = baseEndpoint + (newQueryString ? '?' + newQueryString : '');
    }

    // Handle path parameter changes
    const hasPathParams = updatedTool.pathParameters && updatedTool.pathParameters.length > 0;
    const hadPathParams = originalTool.pathParameters && originalTool.pathParameters.length > 0;

    if (JSON.stringify(originalTool.pathParameters || []) !== JSON.stringify(updatedTool.pathParameters || [])) {
      let updatedEndpoint = updatedTool.endpoint.split('?')[0];

      if (hasPathParams) {
        const originalParams = originalTool.pathParameters || [];
        const updatedParams = updatedTool.pathParameters || [];

        updatedParams.forEach((newParam, index) => {
          if (!newParam.name || !newParam.name.trim()) {
            if (originalParams[index] && originalParams[index].name) {
              const oldPlaceholder = `{${originalParams[index].name}}`;
              updatedEndpoint = updatedEndpoint.replace(
                new RegExp(oldPlaceholder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'),
                ''
              );
            }
            return;
          }

          const placeholder = `{${newParam.name}}`;

          if (originalParams[index] && originalParams[index].name !== newParam.name && originalParams[index].name) {
            const oldPlaceholder = `{${originalParams[index].name}}`;
            updatedEndpoint = updatedEndpoint.replace(
              new RegExp(oldPlaceholder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'),
              placeholder
            );
          } else if (!originalParams[index] || !originalParams[index].name) {
            if (!updatedEndpoint.includes(placeholder)) {
              if (updatedEndpoint.includes('{')) {
                updatedEndpoint = updatedEndpoint.replace(/(\{[^}]+\}[^\/]*)$/, `$1/${placeholder}`);
              } else {
                updatedEndpoint += `/${placeholder}`;
              }
            }
          }
        });

        if (hadPathParams && updatedTool.pathParameters && updatedTool.pathParameters.length < originalTool.pathParameters!.length) {
          const currentNames = updatedTool.pathParameters?.map(p => p.name).filter(n => n && n.trim()) || [];
          const originalNames = originalTool.pathParameters?.map(p => p.name).filter(n => n && n.trim()) || [];
          const removedParams = originalNames.filter(name => !currentNames.includes(name));

          for (const removedParam of removedParams) {
            if (removedParam && removedParam.trim()) {
              const placeholder = `{${removedParam}}`;
              updatedEndpoint = updatedEndpoint.replace(new RegExp(placeholder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'), '');
            }
          }
        }
      } else if (hadPathParams) {
        updatedEndpoint = updatedEndpoint.replace(/\/\{[^}]+\}/g, '');
        updatedEndpoint = updatedEndpoint.replace(/\/+/g, '/').replace(/\/$/, '') || '/';
      }

      updatedEndpoint = updatedEndpoint.replace(/\/+/g, '/').replace(/\/$/, '') || '/';
      const queryString = updatedTool.endpoint.split('?')[1];
      finalTool.endpoint = updatedEndpoint + (queryString ? '?' + queryString : '');
    }

    const toolWithResetTest = resetTestStatus(finalTool);
    onToolUpdate(toolIndex, toolWithResetTest);
  }, [mcpTools, onToolUpdate, resetTestStatus]);

  // Endpoint update with parameter detection
  const updateEndpointWithParams = useCallback((toolIndex: number, endpoint: string) => {
    const localTool = tools[toolIndex];
    if (!localTool) return;

    const detectedPathParams = detectPathParameters(endpoint, localTool.pathParameters);
    const detectedQueryParams = detectQueryParameters(endpoint, localTool.queryParameters);

    const updatedTool = {
      ...localTool,
      endpoint,
      pathParameters: detectedPathParams,
      queryParameters: detectedQueryParams
    };

    const toolWithResetTest = resetTestStatus(updatedTool);
    onToolUpdate(toolIndex, toolWithResetTest);
  }, [tools, onToolUpdate, resetTestStatus]);

  // Tool name editing
  const startEditingToolName = (toolKey: string, currentName: string) => {
    setEditingToolNames(prev => ({ ...prev, [toolKey]: true }));
    setEditingToolNameValues(prev => ({ ...prev, [toolKey]: currentName }));
  };

  const cancelEditingToolName = (toolKey: string) => {
    setEditingToolNames(prev => ({ ...prev, [toolKey]: false }));
    setEditingToolNameValues(prev => {
      const newValues = { ...prev };
      delete newValues[toolKey];
      return newValues;
    });
  };

  const saveToolName = (toolKey: string, toolIndex: number) => {
    const newName = editingToolNameValues[toolKey];
    if (newName && newName.trim() && newName !== tools[toolIndex].name) {
      const updatedTool = { ...tools[toolIndex], name: newName.trim() };
      onToolUpdate(toolIndex, updatedTool);
    }
    cancelEditingToolName(toolKey);
  };

  const handleToolNameKeyPress = (e: React.KeyboardEvent, toolKey: string, toolIndex: number) => {
    if (e.key === 'Enter') {
      saveToolName(toolKey, toolIndex);
    } else if (e.key === 'Escape') {
      cancelEditingToolName(toolKey);
    }
  };

  // Delete tool
  const handleDeleteTool = (tool: McpTool) => {
    setToolToDelete(tool);
    setShowDeleteModal(true);
  };

  const confirmDeleteTool = async () => {
    if (!toolToDelete) return;

    setIsDeleting(true);
    try {
      const globalIndex = getGlobalIndex(tools.findIndex(t => t === toolToDelete));
      const newTools = mcpTools.filter((_, index) => index !== globalIndex);
      setMcpTools(newTools);
      setShowDeleteModal(false);
      setToolToDelete(null);
    } catch (error) {
      console.error('Error deleting tool:', error);
    } finally {
      setIsDeleting(false);
    }
  };

  // Tab info toggle
  const toggleTabInfo = (toolKey: string, tabName: string) => {
    const key = `${toolKey}-${tabName}`;
    setShowTabInfo(prev => ({ ...prev, [key]: !prev[key] }));
  };

  // Path params info text
  const pathParamsInfoText = (
    <>
      <p className="mb-1">
        <strong>{t('toolCategory.pathParams.autoDetectionLabel')}</strong> {t('toolCategory.pathParams.autoDetectionText')}
      </p>
      <p className="mb-2">
        {t('toolCategory.pathParams.example')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">/api/users/{'{userId}'}/posts/{'{postId}'}</code>
      </p>
      <p>
        {t('toolCategory.pathParams.createsAutomatically')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">userId</code> {t('toolCategory.pathParams.and')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">postId</code>
      </p>
    </>
  );

  // Query params info text
  const queryParamsInfoText = (
    <>
      <p className="mb-1">
        <strong>{t('toolCategory.queryParams.autoDetectionLabel')}</strong> {t('toolCategory.queryParams.autoDetectionText')}
      </p>
      <p className="mb-2">
        {t('toolCategory.queryParams.example')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">/api/users?limit=10&offset=0</code>
      </p>
      <p>
        {t('toolCategory.queryParams.createsAutomatically')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">limit</code> {t('toolCategory.queryParams.and')} <code className="bg-blue-100 dark:bg-blue-800 px-2 py-1 rounded">offset</code>
      </p>
    </>
  );

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: tabsScrollStyles }} />
      <Card className="bg-theme-secondary">
        {/* Category header */}
        <div
          className="flex items-center justify-between p-4 cursor-pointer hover:bg-theme-primary/50 transition-colors duration-200"
          onClick={() => setIsCollapsed(!isCollapsed)}
        >
          <div className="flex items-center space-x-3">
            <ChevronRight
              className={`w-5 h-5 text-theme-muted transition-transform duration-200 ${isCollapsed ? '' : 'rotate-90'}`}
            />
            <h3 className="text-lg font-semibold text-theme-primary">{category}</h3>
            <span className="text-sm text-theme-muted bg-theme-primary px-2 py-1 rounded break-all">
              {tools.length} {tools.length > 1 ? t('toolCategory.tools') : t('toolCategory.tool')}
            </span>
          </div>
        </div>

        {/* Tool content */}
        {!isCollapsed && (
          <div className="border-t border-theme">
            {tools.map((tool, toolIndex) => {
              const toolKey = getToolKey(tool, toolIndex);
              const activeTab = activeTabs[toolKey] || 'config';
              const isToolCollapsed = collapsedTools.has(toolKey);

              return (
                <div key={toolKey} className="border-b border-theme last:border-b-0">
                  {/* Tool header */}
                  <ToolHeader
                    tool={tool}
                    toolIndex={toolIndex}
                    toolKey={toolKey}
                    isCollapsed={isToolCollapsed}
                    onToggleCollapse={() => toggleToolCollapse(toolKey)}
                    onTestEndpoint={() => onTestEndpoint(toolIndex)}
                    onDeleteTool={() => handleDeleteTool(tool)}
                    onToolUpdate={onToolUpdate}
                    isEditingName={editingToolNames[toolKey] || false}
                    editingNameValue={editingToolNameValues[toolKey] || tool.name}
                    onStartEditingName={() => startEditingToolName(toolKey, tool.name)}
                    onSaveToolName={() => saveToolName(toolKey, toolIndex)}
                    onCancelEditingName={() => cancelEditingToolName(toolKey)}
                    onNameValueChange={(value) => setEditingToolNameValues(prev => ({ ...prev, [toolKey]: value }))}
                    onNameKeyPress={(e) => handleToolNameKeyPress(e, toolKey, toolIndex)}
                  />

                  {/* Detailed tool content with tabs */}
                  {!isToolCollapsed && (
                    <div className="p-6 bg-theme-primary/20">
                      <ToolTabs
                        toolKey={toolKey}
                        activeTab={activeTab}
                        onTabChange={(tab) => setActiveTabs(prev => ({ ...prev, [toolKey]: tab }))}
                      />

                      <div className="space-y-6">
                        {activeTab === 'config' && (
                          <ConfigTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            apiConfig={apiConfig}
                            onToolUpdate={updateToolWithTestReset}
                            isEditingDescription={editingDescriptions[toolKey] || false}
                            onToggleEditingDescription={() =>
                              setEditingDescriptions(prev => ({ ...prev, [toolKey]: !prev[toolKey] }))
                            }
                            onEndpointChange={(endpoint) => updateEndpointWithParams(toolIndex, endpoint)}
                          />
                        )}

                        {activeTab === 'pathParams' && (
                          <ParameterListTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            onToolUpdate={updateToolWithTestReset}
                            parameterType="pathParameters"
                            title={t('toolCategory.pathParameters')}
                            infoText={pathParamsInfoText}
                            showTabInfo={showTabInfo[`${toolKey}-pathParams`] || false}
                            onToggleTabInfo={() => toggleTabInfo(toolKey, 'pathParams')}
                          />
                        )}

                        {activeTab === 'queryParams' && (
                          <ParameterListTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            onToolUpdate={updateToolWithTestReset}
                            parameterType="queryParameters"
                            title={t('toolCategory.queryParameters')}
                            infoText={queryParamsInfoText}
                            showTabInfo={showTabInfo[`${toolKey}-queryParams`] || false}
                            onToggleTabInfo={() => toggleTabInfo(toolKey, 'queryParams')}
                          />
                        )}

                        {activeTab === 'headers' && (
                          <ParameterListTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            onToolUpdate={updateToolWithTestReset}
                            parameterType="headers"
                            title={t('toolCategory.headers')}
                            showTabInfo={false}
                            onToggleTabInfo={() => {}}
                          />
                        )}

                        {activeTab === 'body' && (
                          <ParameterListTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            onToolUpdate={updateToolWithTestReset}
                            parameterType="bodyParams"
                            title={t('toolCategory.bodyParameters')}
                            showTabInfo={false}
                            onToggleTabInfo={() => {}}
                          />
                        )}

                        {activeTab === 'response' && (
                          <ResponseTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            apiConfig={apiConfig}
                            onToolUpdate={updateToolWithTestReset}
                          />
                        )}

                        {activeTab === 'test' && (
                          <TestTab
                            tool={tool}
                            toolIndex={toolIndex}
                            toolKey={toolKey}
                            apiConfig={apiConfig}
                            onToolUpdate={onToolUpdate}
                            selectedLanguage={selectedLanguages[toolKey] || 'curl'}
                            onLanguageChange={(lang) =>
                              setSelectedLanguages(prev => ({ ...prev, [toolKey]: lang }))
                            }
                          />
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {/* Delete Tool Modal */}
      <DeleteToolModal
        isOpen={showDeleteModal}
        tool={toolToDelete}
        onConfirm={confirmDeleteTool}
        onCancel={() => {
          setShowDeleteModal(false);
          setToolToDelete(null);
        }}
        loading={isDeleting}
      />
    </>
  );
};

export default ToolCategoryGroup;
