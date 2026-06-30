import { useState, useCallback } from 'react';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { McpTool, PathParameter, QueryParameter, Header } from '@/app/[locale]/app/settings/developers/types';

export interface TestResult {
    success: boolean;
    status: number;
    responseTime: number;
    data: any;
    headers?: Record<string, string>;
    url: string;
    error?: string;
}

export interface UseToolTestReturn {
    testResult: TestResult | null;
    testPassed: boolean;
    testingTool: boolean;
    toolResponses: any[];
    loadingResponses: boolean;
    setTestResult: React.Dispatch<React.SetStateAction<TestResult | null>>;
    setTestPassed: React.Dispatch<React.SetStateAction<boolean>>;
    testRealEndpoint: () => Promise<void>;
    isTestValid: () => boolean;
    fetchToolResponses: (toolId: string) => Promise<void>;
    updateTestResponse: (testResult: TestResult) => Promise<void>;
}

export function useToolTest(
    toolState: McpTool | null,
    api: any,
    pathParameters: PathParameter[],
    queryParameters: QueryParameter[],
    headers: Header[],
    bodyParameters: any[]
): UseToolTestReturn {
    const [testResult, setTestResult] = useState<TestResult | null>(null);
    const [testPassed, setTestPassed] = useState(false);
    const [testingTool, setTestingTool] = useState(false);
    const [toolResponses, setToolResponses] = useState<any[]>([]);
    const [loadingResponses, setLoadingResponses] = useState(false);

    const isTestValid = useCallback(() => {
        return testResult?.success && testResult.status >= 200 && testResult.status < 300;
    }, [testResult]);

    const testRealEndpoint = useCallback(async () => {
        if (!toolState || !api) return;

        setTestingTool(true);
        setTestResult(null);
        setTestPassed(false);

        try {
            let testUrl = `${api.baseUrl}${toolState.endpoint}`;

            if (pathParameters?.length) {
                pathParameters.forEach(param => {
                    const exampleValue = param.example || (param as any).exampleValue || (param as any).defaultValue;
                    if (param.name && exampleValue) {
                        testUrl = testUrl.replace(`{${param.name}}`, encodeURIComponent(exampleValue));
                    }
                });
            }

            if (queryParameters?.length) {
                const queryPairs: string[] = [];
                queryParameters.forEach(param => {
                    const value = param.example || (param as any).exampleValue || (param as any).defaultValue;
                    if (param.name && value) {
                        queryPairs.push(`${param.name}=${encodeURIComponent(value)}`);
                    }
                });
                if (queryPairs.length) testUrl += '?' + queryPairs.join('&');
            }

            const startTime = Date.now();
            const requestHeaders: Record<string, string> = { 'Content-Type': 'application/json' };

            if (headers?.length) {
                headers.forEach(header => {
                    if (header.name && header.value) requestHeaders[header.name] = header.value;
                });
            }

            if (api.authType && api.authType !== 'none') {
                if (api.authType === 'bearer' && api.authHeaderValue) {
                    requestHeaders['Authorization'] = api.authHeaderValue.startsWith('Bearer ')
                        ? api.authHeaderValue : `Bearer ${api.authHeaderValue}`;
                } else if (api.authHeaderName && api.authHeaderValue) {
                    requestHeaders[api.authHeaderName] = api.authHeaderValue;
                }
            }

            let requestBody = null;
            if ((toolState.method === 'POST' || toolState.method === 'PUT') && bodyParameters?.length) {
                const body: Record<string, any> = {};
                bodyParameters.forEach(param => {
                    const value = param.value || (param as any).exampleValue || (param as any).defaultValue;
                    if (param.name && value) body[param.name] = value;
                });
                if (Object.keys(body).length) requestBody = JSON.stringify(body);
            }

            const response = await unifiedApiService.testExternalEndpoint(
                testUrl, toolState.method || 'GET', requestHeaders, requestBody
            );

            const responseTime = Date.now() - startTime;
            const responseData = response as any;
            const statusCode = responseData.status;

            if (statusCode < 200 || statusCode >= 300) {
                throw new Error(`HTTP ${statusCode}: ${responseData.statusText || 'Error'}`);
            }

            setTestResult({
                success: true, status: statusCode, responseTime,
                data: responseData.data, headers: responseData.headers || {}, url: testUrl
            });
            setTestPassed(true);

        } catch (error) {
            setTestResult({
                success: false,
                error: error instanceof Error ? error.message : 'Unknown error',
                status: 0, responseTime: 0, data: null,
                url: `${api.baseUrl}${toolState.endpoint}`
            });
            setTestPassed(false);
        } finally {
            setTestingTool(false);
        }
    }, [toolState, api, pathParameters, queryParameters, headers, bodyParameters]);

    const fetchToolResponses = useCallback(async (toolId: string) => {
        if (!toolId) return;
        try {
            setLoadingResponses(true);
            const responses = await unifiedApiService.getToolResponses(toolId) as any[];
            setToolResponses(responses || []);
        } catch (error) {
            setToolResponses([]);
        } finally {
            setLoadingResponses(false);
        }
    }, []);

    const updateTestResponse = useCallback(async (testResult: TestResult) => {
        if (!toolState || !testResult) return;

        try {
            const existingResponses = await unifiedApiService.getToolResponses(toolState.id) as any[];
            const responseData = {
                content_type: 'json',
                status_code: testResult.status || 200,
                response_time: testResult.responseTime || 0,
                url: testResult.url || `${api?.baseUrl || ''}${toolState.endpoint}`,
                method: toolState.method || 'GET',
                headers: JSON.stringify(testResult.headers || {}),
                example: JSON.stringify(testResult.data),
            };

            if (existingResponses.length > 0) {
                await unifiedApiService.updateToolResponse(
                    toolState.id, existingResponses[0].id,
                    { ...responseData, updated_at: new Date().toISOString() }
                );
            } else {
                await unifiedApiService.createToolResponse(toolState.id, {
                    ...responseData, tool_id: toolState.id, created_at: new Date().toISOString()
                });
            }

            await fetchToolResponses(toolState.id);
        } catch (error) {
            console.error('[useToolTest] Error updating test response:', error);
        }
    }, [toolState, api, fetchToolResponses]);

    return {
        testResult, testPassed, testingTool, toolResponses, loadingResponses,
        setTestResult, setTestPassed, testRealEndpoint, isTestValid,
        fetchToolResponses, updateTestResponse
    };
}
