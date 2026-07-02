'use client';

import React, { useState, useEffect, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { useParams, useRouter } from 'next/navigation';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useApiDetails, useApiTools } from '@/hooks/useApiDetails';
import NavigationLoader from '@/components/NavigationLoader';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
    ArrowLeft, Save, X, TestTube, Settings, Code, FileJson,
    FileText, CheckCircle, XCircle, AlertCircle, Shield, Copy, SquarePen
} from 'lucide-react';

// Shared hooks and utils
import { useToolEdit, useToolParameters, useToolTest } from '@/lib/tool-edit/hooks';
import { getMethodColor, getStatusColor, buildRealApiConfig } from '@/lib/tool-edit/utils';
import { generateEnhancedCurl, generateEnhancedJavaScript, generateEnhancedPython } from '@/lib/tool-edit/utils/code-generators';

// Tab components
import { ConfigTab, ParametersTab, ResponseTab, TestTab } from './components/tabs';

export default function ToolEditPage() {
    const t = useTranslations('mcp.overview.toolPage');
    const params = useParams();
    const router = useRouter();
    const { isAuthChecking } = useAuthGuard();
    const apiId = params.apiSlug as string;
    const toolId = params.toolId as string;

    // API data hooks
    const { api, isLoading: apiLoading, error: apiError, refetch: refetchApi } = useApiDetails(apiId);
    const { tools, isLoading: toolsLoading, error: toolsError } = useApiTools(apiId);

    const error = apiError || toolsError;
    const loading = apiLoading || toolsLoading;
    const tool = tools?.find(t => t.id === toolId) || null;
    const isInitialLoading = !apiLoading && !toolsLoading && (!api || !tool) && !error;

    // UI state
    const [activeTab, setActiveTab] = useState('config');
    const [selectedLanguage, setSelectedLanguage] = useState('curl');

    // Tab slider animation
    const tabContainerRef = useRef<HTMLDivElement>(null);
    const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

    useEffect(() => {
        const updateSlider = () => {
            if (!tabContainerRef.current) return;
            const activeButton = tabContainerRef.current.querySelector(`[data-tab-id="${activeTab}"]`) as HTMLButtonElement;
            if (activeButton) {
                const containerRect = tabContainerRef.current.getBoundingClientRect();
                const buttonRect = activeButton.getBoundingClientRect();
                setTabSliderStyle({
                    left: buttonRect.left - containerRect.left,
                    width: buttonRect.width,
                });
            }
        };
        updateSlider();
        window.addEventListener('resize', updateSlider);
        return () => window.removeEventListener('resize', updateSlider);
    }, [activeTab]);

    // Parameters hook (must be before useToolEdit)
    const {
        pathParameters, queryParameters, headers, bodyParameters,
        setPathParameters, setQueryParameters, setHeaders, setBodyParameters,
        addParameter, removeParameter, updateParameter, getDisplayParameters
    } = useToolParameters(tool, false);

    // Edit hook
    const {
        toolState, setToolState, isEditing, hasChanges, saving, success, configError,
        setConfigError, handleEdit, handleEditCancel, handleEditSave, updateEndpoint
    } = useToolEdit(
        tool, api, toolId, t,
        setPathParameters, setQueryParameters, setHeaders, setBodyParameters
    );

    // Test hook
    const {
        testResult, testPassed, testingTool, toolResponses, loadingResponses,
        setTestResult, setTestPassed, testRealEndpoint, isTestValid, fetchToolResponses, updateTestResponse
    } = useToolTest(toolState, api, pathParameters, queryParameters, headers, bodyParameters);

    // Sync parameters with toolState when editing
    useEffect(() => {
        if (toolState && isEditing) {
            const currentPath = toolState.pathParameters || [];
            const currentQuery = toolState.queryParameters || [];
            const currentHeaders = toolState.headers || [];
            const currentBody = toolState.bodyParams || [];

            const pathChanged = JSON.stringify(currentPath) !== JSON.stringify(pathParameters);
            const queryChanged = JSON.stringify(currentQuery) !== JSON.stringify(queryParameters);
            const headersChanged = JSON.stringify(currentHeaders) !== JSON.stringify(headers);
            const bodyChanged = JSON.stringify(currentBody) !== JSON.stringify(bodyParameters);

            if (pathChanged || queryChanged || headersChanged || bodyChanged) {
                setToolState(prev => prev ? {
                    ...prev,
                    pathParameters,
                    queryParameters,
                    headers,
                    bodyParams: bodyParameters
                } : null);
            }
        }
    }, [pathParameters, queryParameters, headers, bodyParameters, isEditing, toolState, setToolState]);

    // Reset test when changes are made
    useEffect(() => {
        if (hasChanges) {
            setTestPassed(false);
            setTestResult(null);
        }
    }, [hasChanges, setTestPassed, setTestResult]);

    // Load responses when response tab is active
    useEffect(() => {
        if (activeTab === 'response' && tool?.id && !loading) {
            fetchToolResponses(tool.id);
        }
    }, [activeTab, tool?.id, loading, fetchToolResponses]);

    // Save handler
    const onSave = async () => {
        await handleEditSave(testResult, updateTestResponse);
    };

    // Error state
    if (error) {
        return (
            <div className="min-h-screen bg-theme-background flex items-center justify-center">
                <div className="text-center">
                    <div className="text-red-500 text-lg mb-4">{error}</div>
                    <Button onClick={() => refetchApi()}>
                        <ArrowLeft className="mr-2 h-4 w-4" />
                        {t('tryAgain')}
                    </Button>
                </div>
            </div>
        );
    }

    // Loading state
    if (loading || isInitialLoading) {
        return (
            <div className="min-h-screen bg-theme-background flex items-center justify-center">
                <div className="text-center">
                    <NavigationLoader />
                    <div className="flex items-center justify-center mt-4">
                        <LoadingSpinner size="lg" text={t('loadingTool')} />
                    </div>
                </div>
            </div>
        );
    }

    // Not found state
    if (!loading && !isInitialLoading && (!api || !tool) && error) {
        return (
            <div className="min-h-screen bg-theme-background flex items-center justify-center">
                <div className="text-center">
                    <AlertCircle className="h-12 w-12 text-yellow-500 mx-auto mb-4" />
                    <h2 className="text-xl font-semibold text-theme-text mb-2">{t('toolNotFound')}</h2>
                    <p className="text-theme-text-secondary mb-4">{t('toolNotFoundDesc')}</p>
                    <Button onClick={() => router.push('/app/settings/mcp')}>
                        <ArrowLeft className="mr-2 h-4 w-4" />
                        {t('backToApis')}
                    </Button>
                </div>
            </div>
        );
    }

    // Safety check
    if ((!api || !tool) && !error) {
        return (
            <div className="min-h-screen bg-theme-background flex items-center justify-center">
                <div className="text-center">
                    <NavigationLoader />
                    <div className="flex items-center justify-center mt-4">
                        <LoadingSpinner />
                        <p className="ml-2 text-theme-text-secondary">{t('finalizingLoading')}</p>
                    </div>
                </div>
            </div>
        );
    }

    // Build tabs config
    const tabs = [
        { id: 'config', label: t('tabs.config'), icon: Settings },
        { id: 'pathParams', label: t('tabs.pathParams'), icon: Code },
        { id: 'queryParams', label: t('tabs.queryParams'), icon: FileJson },
        { id: 'headers', label: t('tabs.headers'), icon: Shield },
        ...(toolState?.method === 'PUT' || toolState?.method === 'POST'
            ? [{ id: 'body', label: t('tabs.body'), icon: FileText }] : []),
        { id: 'response', label: t('tabs.response'), icon: FileText },
        { id: 'test', label: t('tabs.test'), icon: TestTube }
    ];

    return (
        <div className="min-h-screen bg-theme-primary transition-colors duration-300">
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
                {/* Header */}
                <div className="mb-8">
                    <div className="flex flex-col gap-6">
                        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                            <div className="flex items-start gap-4">
                                <div className="space-y-2">
                                    <div className="flex items-center gap-3">
                                        <h2 className="text-2xl font-semibold text-theme-primary sm:text-3xl">
                                            {toolState?.name || 'Tool'}
                                        </h2>
                                    </div>
                                </div>
                            </div>
                            <div className="flex items-center space-x-2">
                                {!isEditing ? (
                                    <Button onClick={handleEdit} disabled={!tool} variant="ghost" size="icon" title={t('edit')} className="h-8 w-8">
                                        <SquarePen className="w-4 h-4" />
                                    </Button>
                                ) : (
                                    <div className="flex items-center gap-2">
                                        {hasChanges && (
                                            <div className="flex items-center gap-2 mr-1 border-r border-theme/20 pr-2">
                                                <Button onClick={testRealEndpoint} disabled={testingTool || !tool} variant="ghost" size="sm" className="h-8" title={t('testChangesBeforeSaving')}>
                                                    {testingTool ? <LoadingSpinner size="sm" /> : <TestTube className="w-4 h-4" />}
                                                    <span className="ml-2 hidden lg:inline">{testingTool ? t('testing') : t('test')}</span>
                                                </Button>
                                                {testResult && (
                                                    <div title={isTestValid() ? t('testPassed') : t('testFailed')}>
                                                        {isTestValid() ? <CheckCircle className="w-4 h-4 text-green-500" /> : <XCircle className="w-4 h-4 text-red-500" />}
                                                    </div>
                                                )}
                                            </div>
                                        )}
                                        <Button onClick={handleEditCancel} disabled={saving} variant="outline" size="sm">{t('cancel')}</Button>
                                        <Button onClick={onSave} disabled={saving || !tool || !hasChanges || !isTestValid()} variant="default" size="sm" title={!isTestValid() && hasChanges ? t('testRequiredBeforeSaving') : t('saveChanges')}>
                                            <Save className="w-4 h-4 mr-2" />
                                            {saving ? t('saving') : t('saveChanges')}
                                        </Button>
                                    </div>
                                )}
                            </div>
                        </div>

                        <div className="flex flex-wrap items-center gap-2">
                            <Badge className={getMethodColor(toolState?.method || 'GET')}>{toolState?.method || 'GET'}</Badge>
                            <Badge className={getStatusColor(toolState?.isActive ? 'active' : 'paused')}>{toolState?.isActive ? t('active') : t('paused')}</Badge>
                            <Badge className={getStatusColor(toolState?.status === 'approved' || toolState?.isActive ? 'active' : 'paused')}>
                                {t('status')}: {toolState?.status || api?.status || (toolState?.isActive ? t('active') : 'Inactive')}
                            </Badge>
                            <Badge variant="outline" className="text-xs cursor-pointer hover:bg-theme-primary/5 transition-colors duration-200"
                                onClick={() => tool?.id && navigator.clipboard.writeText(tool.id)} title={t('copyToolId')}>
                                <Copy className="w-3 h-3 mr-1" />ID: {tool?.id?.slice(0, 8)}...
                            </Badge>
                        </div>
                    </div>
                </div>

                {/* Test Results Banner */}
                {testResult && (
                    <div className="bg-theme-secondary rounded-xl p-6 border border-theme/30 shadow-lg mb-8">
                        <div className={`p-4 rounded-lg border ${testResult.success
                            ? 'bg-green-50 border-green-200 dark:bg-green-900/20 dark:border-green-800'
                            : 'bg-red-50 border-red-200 dark:bg-red-900/20 dark:border-red-800'}`}>
                            <div className="flex items-center space-x-3">
                                {testResult.success ? <CheckCircle className="w-5 h-5 text-green-600" /> : <XCircle className="w-5 h-5 text-red-600" />}
                                <div className="flex-1">
                                    <h4 className={`font-medium ${testResult.success ? 'text-green-800 dark:text-green-300' : 'text-red-800 dark:text-red-300'}`}>
                                        {testResult.success ? t('testSuccessful') : t('testFailed')}
                                    </h4>
                                    <p className={`text-sm mt-1 ${testResult.success ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>
                                        HTTP {testResult.status} - {testResult.responseTime}ms
                                    </p>
                                </div>
                                <Button variant="ghost" size="sm" onClick={() => setTestResult(null)}><X className="w-4 h-4" /></Button>
                            </div>
                            {testResult.success && testResult.data && (
                                <div className="mt-4 p-3 bg-gray-100 dark:bg-gray-800 rounded-md">
                                    <pre className="text-xs text-gray-600 dark:text-gray-400 whitespace-pre-wrap">{JSON.stringify(testResult.data, null, 2)}</pre>
                                </div>
                            )}
                            {!testResult.success && testResult.error && (
                                <div className="mt-4 p-3 bg-red-100 dark:bg-red-900/30 rounded-md">
                                    <p className="text-sm text-red-700 dark:text-red-300">{testResult.error}</p>
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {/* Tab Navigation */}
                <div className="mb-8 flex max-w-full overflow-x-auto scrollbar-hide">
                    <div ref={tabContainerRef} className="relative mx-auto inline-flex w-max items-center gap-1 p-1.5 bg-theme-tertiary rounded-full">
                        <div className="absolute top-1.5 h-[calc(100%-12px)] rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
                            style={{ left: `${tabSliderStyle.left}px`, width: `${tabSliderStyle.width}px` }} />
                        {tabs.map((tab) => {
                            const IconComponent = tab.icon;
                            const isActive = activeTab === tab.id;
                            return (
                                <button key={tab.id} data-tab-id={tab.id} type="button" onClick={() => setActiveTab(tab.id)}
                                    className={`relative z-10 flex items-center gap-2 px-4 py-2 rounded-full text-sm transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 ${isActive
                                        ? 'text-[var(--text-primary)]'
                                        : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50'}`}>
                                    <IconComponent className={`w-4 h-4 transition-colors duration-200 ${isActive ? 'text-[var(--text-primary)]' : ''}`} />
                                    <span className="whitespace-nowrap">{tab.label}</span>
                                </button>
                            );
                        })}
                    </div>
                </div>

                {/* Tab Content */}
                <div className="space-y-8">
                    {activeTab === 'config' && (
                        <ConfigTab tool={tool} toolState={toolState} setToolState={setToolState} isEditing={isEditing} api={api}
                            setConfigError={setConfigError} updateEndpoint={(ep) => updateEndpoint(ep, setPathParameters)} />
                    )}

                    {activeTab === 'pathParams' && (
                        <ParametersTab title={t('pathParamsTab.title')} icon={Code} iconColor="text-purple-600"
                            parameters={pathParameters} isEditing={isEditing} parameterType="path"
                            emptyMessage={t('pathParamsTab.emptyMessage')} emptyHint={t('pathParamsTab.emptyHint')}
                            addParameter={addParameter} updateParameter={updateParameter} removeParameter={removeParameter}
                            getDisplayParameters={getDisplayParameters} />
                    )}

                    {activeTab === 'queryParams' && (
                        <ParametersTab title={t('queryParamsTab.title')} icon={FileJson} iconColor="text-orange-600"
                            parameters={queryParameters} isEditing={isEditing} parameterType="query"
                            emptyMessage={t('queryParamsTab.emptyMessage')} emptyHint={t('queryParamsTab.emptyHint')}
                            addParameter={addParameter} updateParameter={updateParameter} removeParameter={removeParameter}
                            getDisplayParameters={getDisplayParameters} />
                    )}

                    {activeTab === 'headers' && (
                        <ParametersTab title={t('headersTab.title')} icon={Shield} iconColor="text-blue-600"
                            parameters={headers} isEditing={isEditing} parameterType="header"
                            emptyMessage={t('headersTab.emptyMessage')} emptyHint={t('headersTab.emptyHint')}
                            addParameter={addParameter} updateParameter={updateParameter} removeParameter={removeParameter}
                            getDisplayParameters={getDisplayParameters} showValueField={true} />
                    )}

                    {activeTab === 'body' && (toolState?.method === 'PUT' || toolState?.method === 'POST') && (
                        <ParametersTab title={t('bodyTab.title')} icon={FileText} iconColor="text-green-600"
                            parameters={bodyParameters} isEditing={isEditing} parameterType="body"
                            emptyMessage={t('bodyTab.emptyMessage')} emptyHint={t('bodyTab.emptyHint')}
                            addParameter={addParameter} updateParameter={updateParameter} removeParameter={removeParameter}
                            getDisplayParameters={getDisplayParameters} showValueField={true} />
                    )}

                    {activeTab === 'response' && (
                        <ResponseTab toolResponses={toolResponses} loadingResponses={loadingResponses} />
                    )}

                    {activeTab === 'test' && (
                        <TestTab tool={tool} api={api} pathParameters={pathParameters} queryParameters={queryParameters}
                            headers={headers} bodyParameters={bodyParameters} testTool={testRealEndpoint} testingTool={testingTool}
                            generateEnhancedCurl={(t, a) => generateEnhancedCurl(t, a, toolState)}
                            generateEnhancedJavaScript={(t, a) => generateEnhancedJavaScript(t, a, toolState)}
                            generateEnhancedPython={(t, a) => generateEnhancedPython(t, a, toolState)}
                            buildRealApiConfig={buildRealApiConfig}
                            selectedLanguage={selectedLanguage} setSelectedLanguage={setSelectedLanguage} />
                    )}
                </div>
            </div>
        </div>
    );
}
