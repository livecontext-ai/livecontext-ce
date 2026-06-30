import { useState, useEffect, useCallback } from 'react';
import { PathParameter, QueryParameter, Header, McpTool } from '@/app/[locale]/app/settings/developers/types';

export interface BodyParameter {
    name: string;
    value: string;
    type: 'text' | 'file';
    description?: string;
    required?: boolean;
}

export interface UseToolParametersReturn {
    pathParameters: PathParameter[];
    queryParameters: QueryParameter[];
    headers: Header[];
    bodyParameters: BodyParameter[];
    setPathParameters: React.Dispatch<React.SetStateAction<PathParameter[]>>;
    setQueryParameters: React.Dispatch<React.SetStateAction<QueryParameter[]>>;
    setHeaders: React.Dispatch<React.SetStateAction<Header[]>>;
    setBodyParameters: React.Dispatch<React.SetStateAction<BodyParameter[]>>;
    addParameter: (type: 'path' | 'query' | 'header' | 'body') => void;
    removeParameter: (type: 'path' | 'query' | 'header' | 'body', index: number) => void;
    updateParameter: (type: 'path' | 'query' | 'header' | 'body', index: number, field: string, value: any) => void;
    getDisplayParameters: (type: 'path' | 'query' | 'header' | 'body') => any[];
    initializeFromTool: (tool: McpTool) => void;
    syncWithToolState: (toolState: McpTool | null) => McpTool | null;
}

export function useToolParameters(tool: McpTool | null, isEditing: boolean): UseToolParametersReturn {
    const [pathParameters, setPathParameters] = useState<PathParameter[]>([]);
    const [queryParameters, setQueryParameters] = useState<QueryParameter[]>([]);
    const [headers, setHeaders] = useState<Header[]>([]);
    const [bodyParameters, setBodyParameters] = useState<BodyParameter[]>([]);

    // Initialize parameters from tool
    const initializeFromTool = useCallback((tool: McpTool) => {
        const allParameters = tool.parameters || [];

        const newPathParams = [...(allParameters.filter(p =>
            (p as any).parameterType === 'path' || (p as any).type === 'path'
        ) || [])] as PathParameter[];

        if (tool.endpoint) {
            const matches = tool.endpoint.match(/\{([^}]+)\}/g);
            if (matches) {
                matches.map(m => m.slice(1, -1)).forEach(name => {
                    if (!newPathParams.find(p => p.name === name)) {
                        newPathParams.push({
                            name,
                            type: 'string',
                            parameterType: 'path',
                            required: true,
                            description: ''
                        } as PathParameter);
                    }
                });
            }
        }

        setPathParameters(newPathParams);
        setQueryParameters(allParameters.filter(p => (p as any).parameterType === 'query' || (p as any).type === 'query') as unknown as QueryParameter[]);
        setHeaders(allParameters.filter(p => (p as any).parameterType === 'header' || (p as any).type === 'header') as unknown as Header[]);
        setBodyParameters(allParameters.filter(p => (p as any).parameterType === 'body' || (p as any).type === 'body') as unknown as BodyParameter[]);
    }, []);

    // Sync parameters with tool state
    const syncWithToolState = useCallback((toolState: McpTool | null): McpTool | null => {
        if (!toolState) return null;
        return {
            ...toolState,
            pathParameters,
            queryParameters,
            headers,
            bodyParams: bodyParameters
        };
    }, [pathParameters, queryParameters, headers, bodyParameters]);

    // Initialize from tool when tool changes (only when not editing)
    useEffect(() => {
        if (tool && !isEditing) {
            const allParameters = tool.parameters || [];

            const newPathParams = [...(allParameters.filter(p =>
                (p as any).parameterType === 'path' || (p as any).type === 'path'
            ) || [])] as PathParameter[];

            if (tool.endpoint) {
                const matches = tool.endpoint.match(/\{([^}]+)\}/g);
                if (matches) {
                    matches.map(m => m.slice(1, -1)).forEach(name => {
                        if (!newPathParams.find(p => p.name === name)) {
                            newPathParams.push({
                                name,
                                type: 'string',
                                parameterType: 'path',
                                required: true,
                                description: ''
                            } as PathParameter);
                        }
                    });
                }
            }

            const newQueryParams = allParameters.filter(p => (p as any).parameterType === 'query' || (p as any).type === 'query') as unknown as QueryParameter[];
            const newHeaderParams = allParameters.filter(p => (p as any).parameterType === 'header' || (p as any).type === 'header') as unknown as Header[];
            const newBodyParams = allParameters.filter(p => (p as any).parameterType === 'body' || (p as any).type === 'body') as unknown as BodyParameter[];

            if (JSON.stringify(newPathParams) !== JSON.stringify(pathParameters)) setPathParameters(newPathParams);
            if (JSON.stringify(newQueryParams) !== JSON.stringify(queryParameters)) setQueryParameters(newQueryParams);
            if (JSON.stringify(newHeaderParams) !== JSON.stringify(headers)) setHeaders(newHeaderParams);
            if (JSON.stringify(newBodyParams) !== JSON.stringify(bodyParameters)) setBodyParameters(newBodyParams);
        }
    }, [tool, isEditing]);

    const addParameter = useCallback((type: 'path' | 'query' | 'header' | 'body') => {
        const newParam = {
            name: '',
            type: 'string' as const,
            description: '',
            required: false,
            example: '',
            exampleValue: '',
            parameterType: type,
            id: `temp-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            defaultValue: null
        };

        switch (type) {
            case 'path': setPathParameters(prev => [...prev, newParam as PathParameter]); break;
            case 'query': setQueryParameters(prev => [...prev, newParam as QueryParameter]); break;
            case 'header': setHeaders(prev => [...prev, { ...newParam, value: '' } as Header]); break;
            case 'body': setBodyParameters(prev => [...prev, { ...newParam, value: '', type: 'text' as const }]); break;
        }
    }, []);

    const removeParameter = useCallback((type: 'path' | 'query' | 'header' | 'body', index: number) => {
        const removeFn = (prev: any[]) => prev.filter((_, i) => i !== index);
        switch (type) {
            case 'path': setPathParameters(removeFn); break;
            case 'query': setQueryParameters(removeFn); break;
            case 'header': setHeaders(removeFn); break;
            case 'body': setBodyParameters(removeFn); break;
        }
    }, []);

    const updateParameter = useCallback((type: 'path' | 'query' | 'header' | 'body', index: number, field: string, value: any) => {
        const updateFn = (prev: any[]) => {
            const newParams = [...prev];
            newParams[index] = { ...newParams[index], [field]: value };
            if (field === 'example') newParams[index].exampleValue = value;
            if (field === 'value') { newParams[index].example = value; newParams[index].exampleValue = value; }
            return newParams;
        };

        switch (type) {
            case 'path': setPathParameters(updateFn); break;
            case 'query': setQueryParameters(updateFn); break;
            case 'header': setHeaders(updateFn); break;
            case 'body': setBodyParameters(updateFn); break;
        }
    }, []);

    const getDisplayParameters = useCallback((type: 'path' | 'query' | 'header' | 'body') => {
        switch (type) {
            case 'path': return pathParameters || [];
            case 'query': return queryParameters || [];
            case 'header': return headers || [];
            case 'body': return bodyParameters || [];
            default: return [];
        }
    }, [pathParameters, queryParameters, headers, bodyParameters]);

    return {
        pathParameters, queryParameters, headers, bodyParameters,
        setPathParameters, setQueryParameters, setHeaders, setBodyParameters,
        addParameter, removeParameter, updateParameter, getDisplayParameters,
        initializeFromTool, syncWithToolState
    };
}
