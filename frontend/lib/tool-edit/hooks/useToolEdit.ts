import { useState, useEffect, useCallback } from 'react';
import { McpTool, PathParameter, QueryParameter, Header } from '@/app/[locale]/app/settings/developers/types';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import {
    ToolUpdatePayload,
    normalizeParameters,
    normalizeParametersForSending,
    createValidatedToolUpdatePayload,
    enrichToolWithPathParameters
} from '../utils/tool-helpers';

export interface UseToolEditReturn {
    toolState: McpTool | null;
    setToolState: React.Dispatch<React.SetStateAction<McpTool | null>>;
    isEditing: boolean;
    hasChanges: boolean;
    originalTool: McpTool | null;
    saving: boolean;
    success: string | null;
    configError: string | null;
    setSuccess: React.Dispatch<React.SetStateAction<string | null>>;
    setConfigError: React.Dispatch<React.SetStateAction<string | null>>;
    handleEdit: () => void;
    handleEditCancel: () => void;
    handleEditSave: (testResult: any, updateTestResponse: (result: any) => Promise<void>) => Promise<void>;
    updateEndpoint: (endpoint: string, setPathParameters: React.Dispatch<React.SetStateAction<PathParameter[]>>) => void;
    detectChanges: (pathParameters: PathParameter[], queryParameters: QueryParameter[], headers: Header[], bodyParameters: any[]) => boolean;
    createDeltaUpdate: (pathParameters: PathParameter[], queryParameters: QueryParameter[], headers: Header[], bodyParameters: any[]) => ToolUpdatePayload;
}

export function useToolEdit(
    tool: McpTool | null,
    api: any,
    toolId: string,
    t: ((key: string) => string) | null,
    setPathParameters: React.Dispatch<React.SetStateAction<PathParameter[]>>,
    setQueryParameters: React.Dispatch<React.SetStateAction<QueryParameter[]>>,
    setHeaders: React.Dispatch<React.SetStateAction<Header[]>>,
    setBodyParameters: React.Dispatch<React.SetStateAction<any[]>>
): UseToolEditReturn {
    const [toolState, setToolState] = useState<McpTool | null>(null);
    const [isEditing, setIsEditing] = useState(false);
    const [hasChanges, setHasChanges] = useState(false);
    const [originalTool, setOriginalTool] = useState<McpTool | null>(null);
    const [saving, setSaving] = useState(false);
    const [success, setSuccess] = useState<string | null>(null);
    const [configError, setConfigError] = useState<string | null>(null);

    const getText = (key: string) => t ? t(key) : key;

    useEffect(() => {
        if (tool && (!isEditing || !toolState)) {
            const enrichedTool = enrichToolWithPathParameters(tool);
            if (!toolState || JSON.stringify(enrichedTool) !== JSON.stringify(toolState)) {
                setToolState(enrichedTool);
            }
        }
    }, [tool, isEditing, toolState]);

    const handleEdit = useCallback(() => {
        if (toolState) {
            setOriginalTool(JSON.parse(JSON.stringify(toolState)));
            setPathParameters(toolState.pathParameters || []);
            setQueryParameters(toolState.queryParameters || []);
            setHeaders(toolState.headers || []);
            setBodyParameters(toolState.bodyParams || []);
            setIsEditing(true);
        }
    }, [toolState, setPathParameters, setQueryParameters, setHeaders, setBodyParameters]);

    const handleEditCancel = useCallback(() => {
        if (originalTool) {
            setToolState(originalTool);
            setPathParameters(originalTool.pathParameters || []);
            setQueryParameters(originalTool.queryParameters || []);
            setHeaders(originalTool.headers || []);
            setBodyParameters(originalTool.bodyParams || []);
            setOriginalTool(null);
        }
        setIsEditing(false);
        setHasChanges(false);
    }, [originalTool, setPathParameters, setQueryParameters, setHeaders, setBodyParameters]);

    const detectChanges = useCallback((
        pathParameters: PathParameter[],
        queryParameters: QueryParameter[],
        headers: Header[],
        bodyParameters: any[]
    ): boolean => {
        if (!isEditing || !toolState || !originalTool) return false;

        const hasChanged = (newValue: any, oldValue: any): boolean => {
            if (newValue === oldValue) return false;
            if (newValue === null || newValue === undefined) return false;
            if (oldValue === null || oldValue === undefined) return true;
            if (Array.isArray(newValue) && Array.isArray(oldValue)) {
                return JSON.stringify(normalizeParameters(newValue)) !== JSON.stringify(normalizeParameters(oldValue));
            }
            return JSON.stringify(newValue) !== JSON.stringify(oldValue);
        };

        const toolFields = ['name', 'description', 'method', 'endpoint', 'pricing', 'rateLimit', 'status', 'isActive'];
        for (const field of toolFields) {
            if (hasChanged(toolState[field as keyof McpTool], originalTool[field as keyof McpTool])) return true;
        }

        if (hasChanged(pathParameters, originalTool.pathParameters || [])) return true;
        if (hasChanged(queryParameters, originalTool.queryParameters || [])) return true;
        if (hasChanged(headers, originalTool.headers || [])) return true;
        if (hasChanged(bodyParameters, originalTool.bodyParams || [])) return true;
        if (hasChanged(toolState.bodySchema, originalTool.bodySchema)) return true;
        if (hasChanged(toolState.response, originalTool.response)) return true;

        return false;
    }, [isEditing, toolState, originalTool]);

    const createDeltaUpdate = useCallback((
        pathParameters: PathParameter[],
        queryParameters: QueryParameter[],
        headers: Header[],
        bodyParameters: any[]
    ): ToolUpdatePayload => {
        if (!toolState || !originalTool) return {};

        const delta: any = {};

        const hasChanged = (newValue: any, oldValue: any): boolean => {
            if (newValue === oldValue) return false;
            if (newValue === null || newValue === undefined) return false;
            if (oldValue === null || oldValue === undefined) return true;
            if (Array.isArray(newValue) && Array.isArray(oldValue)) {
                if (newValue.length !== oldValue.length) return true;
                return newValue.some((item, index) => hasChanged(item, oldValue[index]));
            }
            if (typeof newValue === 'object' && typeof oldValue === 'object') {
                const newKeys = Object.keys(newValue);
                const oldKeys = Object.keys(oldValue);
                if (newKeys.length !== oldKeys.length) return true;
                return newKeys.some(key => hasChanged(newValue[key], oldValue[key]));
            }
            return newValue !== oldValue;
        };

        const toolFields: (keyof ToolUpdatePayload)[] = [
            'name', 'description', 'method', 'endpoint', 'pricing', 'rateLimit', 'status', 'isActive'
        ];

        toolFields.forEach(field => {
            const newValue = toolState[field as keyof McpTool];
            const oldValue = originalTool[field as keyof McpTool];
            if (hasChanged(newValue, oldValue)) delta[field] = newValue;
        });

        if (hasChanged(normalizeParametersForSending(pathParameters, 'path'), normalizeParameters(originalTool.pathParameters || [], 'path')) ||
            pathParameters.length !== (originalTool.pathParameters || []).length) {
            delta.pathParameters = normalizeParametersForSending(pathParameters, 'path');
        }

        if (hasChanged(normalizeParametersForSending(queryParameters, 'query'), normalizeParameters(originalTool.queryParameters || [], 'query')) ||
            queryParameters.length !== (originalTool.queryParameters || []).length) {
            delta.queryParameters = normalizeParametersForSending(queryParameters, 'query');
        }

        if (hasChanged(normalizeParametersForSending(headers, 'header'), normalizeParameters(originalTool.headers || [], 'header')) ||
            headers.length !== (originalTool.headers || []).length) {
            delta.headers = normalizeParametersForSending(headers, 'header');
        }

        if (hasChanged(normalizeParametersForSending(bodyParameters, 'body'), normalizeParameters(originalTool.bodyParams || [], 'body')) ||
            bodyParameters.length !== (originalTool.bodyParams || []).length) {
            delta.bodyParams = normalizeParametersForSending(bodyParameters, 'body');
        }

        if (hasChanged(toolState.bodySchema, originalTool.bodySchema)) delta.bodySchema = toolState.bodySchema;
        if (hasChanged(toolState.response, originalTool.response)) delta.response = toolState.response;

        return createValidatedToolUpdatePayload(delta);
    }, [toolState, originalTool]);

    const handleEditSave = useCallback(async (
        testResult: any,
        updateTestResponse: (result: any) => Promise<void>
    ) => {
        if (!tool || !originalTool) return;

        setSaving(true);
        try {
            const deltaUpdate = createDeltaUpdate(
                toolState?.pathParameters || [],
                toolState?.queryParameters || [],
                toolState?.headers || [],
                toolState?.bodyParams || []
            );

            if (Object.keys(deltaUpdate).length === 0) {
                setSuccess(getText('noChangesToSave'));
                setTimeout(() => setSuccess(null), 2000);
                setSaving(false);
                return;
            }

            await unifiedApiService.updateApiTool(api?.id || '', toolId, deltaUpdate);

            const updatedTool = { ...originalTool, ...deltaUpdate };
            setToolState(updatedTool as McpTool);

            if (deltaUpdate.pathParameters) setPathParameters(deltaUpdate.pathParameters);
            if (deltaUpdate.queryParameters) setQueryParameters(deltaUpdate.queryParameters);
            if (deltaUpdate.headers) setHeaders(deltaUpdate.headers);
            if (deltaUpdate.bodyParams) setBodyParameters(deltaUpdate.bodyParams);

            if (testResult) await updateTestResponse(testResult);

            setOriginalTool(null);
            setIsEditing(false);
            setHasChanges(false);
            setSuccess(getText('toolSavedSuccessfully'));

            setTimeout(() => setSuccess(null), 3000);
        } catch (error) {
            console.error('Error saving tool:', error);
            setConfigError(getText('failedToSaveTool'));
        } finally {
            setSaving(false);
        }
    }, [tool, originalTool, toolState, api, toolId, createDeltaUpdate,
        setPathParameters, setQueryParameters, setHeaders, setBodyParameters, getText]);

    const updateEndpoint = useCallback((
        endpoint: string,
        setPathParamsCallback: React.Dispatch<React.SetStateAction<PathParameter[]>>
    ) => {
        setToolState(prev => prev ? { ...prev, endpoint } : null);

        const matches = endpoint.match(/\{([^}]+)\}/g);
        const newParamNames = matches ? matches.map(m => m.slice(1, -1)) : [];

        setPathParamsCallback(prevParams => {
            const existingParamsMap = new Map(prevParams.map(p => [p.name, p]));
            return newParamNames.map(name => {
                if (existingParamsMap.has(name)) return existingParamsMap.get(name)!;
                return {
                    name, type: 'string', parameterType: 'path', description: '', required: true
                } as PathParameter;
            });
        });
    }, []);

    useEffect(() => {
        if (isEditing && originalTool && toolState) {
            const changed = detectChanges(
                toolState.pathParameters || [],
                toolState.queryParameters || [],
                toolState.headers || [],
                toolState.bodyParams || []
            );
            setHasChanges(changed);
        }
    }, [isEditing, originalTool, toolState, detectChanges]);

    return {
        toolState, setToolState, isEditing, hasChanges, originalTool, saving, success, configError,
        setSuccess, setConfigError, handleEdit, handleEditCancel, handleEditSave, updateEndpoint,
        detectChanges, createDeltaUpdate
    };
}
