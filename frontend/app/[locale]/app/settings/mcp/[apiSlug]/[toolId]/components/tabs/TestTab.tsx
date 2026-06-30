"use client";

import React from "react";
import { useTranslations } from 'next-intl';
import { TestTube } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import LoadingSpinner from "@/components/LoadingSpinner";
import { McpTool, PathParameter, QueryParameter, Header, ApiConfig } from "@/app/[locale]/app/settings/developers/types";
import { generateCodeExamples } from "@/app/[locale]/app/settings/developers/utils";

interface TestTabProps {
    tool: McpTool | null;
    api: any;
    pathParameters: PathParameter[];
    queryParameters: QueryParameter[];
    headers: Header[];
    bodyParameters: any[];
    testTool: () => void;
    testingTool: boolean;
    generateEnhancedCurl: (tool: McpTool, apiData: any) => string;
    generateEnhancedJavaScript: (tool: McpTool, apiData: any) => string;
    generateEnhancedPython: (tool: McpTool, apiData: any) => string;
    buildRealApiConfig: (apiData: any) => ApiConfig;
    selectedLanguage: string;
    setSelectedLanguage: (lang: string) => void;
}

const TestTab: React.FC<TestTabProps> = ({
    tool,
    api,
    pathParameters,
    queryParameters,
    headers,
    bodyParameters,
    testTool,
    testingTool,
    generateEnhancedCurl,
    generateEnhancedJavaScript,
    generateEnhancedPython,
    buildRealApiConfig,
    selectedLanguage,
    setSelectedLanguage,
}) => {
    const t = useTranslations('mcp.toolDetail.testTab');
    const langConfigs = {
        curl: { color: 'text-green-400', name: 'cURL' },
        javascript: { color: 'text-yellow-400', name: 'JavaScript (fetch)' },
        python: { color: 'text-blue-400', name: 'Python (requests)' },
        java: { color: 'text-orange-500', name: 'Java (HttpClient)' },
        php: { color: 'text-purple-400', name: 'PHP (cURL)' },
        nodejs: { color: 'text-green-500', name: 'Node.js (https)' },
        go: { color: 'text-cyan-400', name: 'Go (net/http)' },
        ruby: { color: 'text-red-400', name: 'Ruby (net/http)' },
        shell: { color: 'text-gray-400', name: 'Shell (bash)' }
    };

    const getCodePreview = () => {
        if (!tool) return null;

        const realApiConfig = buildRealApiConfig(api);
        const updatedTool = {
            ...tool,
            pathParameters,
            queryParameters,
            headers,
            bodyParams: bodyParameters
        };

        let currentCode: string;
        switch (selectedLanguage) {
            case 'curl':
                currentCode = generateEnhancedCurl(updatedTool, api);
                break;
            case 'javascript':
                currentCode = generateEnhancedJavaScript(updatedTool, api);
                break;
            case 'python':
                currentCode = generateEnhancedPython(updatedTool, api);
                break;
            default:
                currentCode = generateCodeExamples(updatedTool, realApiConfig, selectedLanguage);
        }

        const currentConfig = langConfigs[selectedLanguage as keyof typeof langConfigs];

        return (
            <div className={`bg-gray-900 dark:bg-gray-950 ${currentConfig.color} p-3 rounded-lg font-mono text-sm`}>
                <div className="flex items-center justify-between mb-2">
                    <span className="text-white dark:text-gray-100 font-medium">{currentConfig.name}</span>
                    <button
                        type="button"
                        onClick={() => navigator.clipboard.writeText(currentCode)}
                        className="text-xs bg-gray-700 dark:bg-gray-800 hover:bg-gray-600 dark:hover:bg-gray-700 text-white dark:text-gray-200 px-2 py-1 rounded transition-colors"
                    >
                        {t('copy')}
                    </button>
                </div>
                <pre className="whitespace-pre-wrap break-all text-gray-100 dark:text-gray-200">{currentCode}</pre>
            </div>
        );
    };

    return (
        <div className="space-y-6">
            {/* Request Preview */}
            <div>
                <div className="flex items-center justify-between mb-4">
                    <label className="block text-sm font-medium text-theme-primary dark:text-white">
                        {t('requestPreview')}
                    </label>
                    <Select
                        value={selectedLanguage}
                        onValueChange={setSelectedLanguage}
                    >
                        <SelectTrigger className="w-48">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="curl">cURL</SelectItem>
                            <SelectItem value="javascript">JavaScript (fetch)</SelectItem>
                            <SelectItem value="python">Python (requests)</SelectItem>
                            <SelectItem value="java">Java (HttpClient)</SelectItem>
                            <SelectItem value="php">PHP (cURL)</SelectItem>
                            <SelectItem value="nodejs">Node.js (https)</SelectItem>
                            <SelectItem value="go">Go (net/http)</SelectItem>
                            <SelectItem value="ruby">Ruby (net/http)</SelectItem>
                            <SelectItem value="shell">Shell (bash)</SelectItem>
                        </SelectContent>
                    </Select>
                </div>

                {getCodePreview()}
            </div>

            {/* Test execution */}
            <div className="border-t border-theme pt-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h3 className="text-lg font-medium text-theme-primary dark:text-white">{t('executeTest')}</h3>
                        <p className="text-sm text-theme-secondary dark:text-gray-300">
                            {t('testDescription')}
                        </p>
                    </div>
                    <Button
                        onClick={testTool}
                        disabled={testingTool || !tool}
                        className="flex items-center gap-2"
                    >
                        {testingTool ? (
                            <>
                                <LoadingSpinner size="sm" />
                                <span>{t('testing')}</span>
                            </>
                        ) : (
                            <>
                                <TestTube className="w-4 h-4" />
                                <span>{t('runTest')}</span>
                            </>
                        )}
                    </Button>
                </div>
            </div>
        </div>
    );
};

export default TestTab;
