import { PathParameter, QueryParameter, Header, ApiConfig, McpTool } from '@/app/[locale]/app/settings/developers/types';

// Interface for tool update payload
export interface ToolUpdatePayload {
    name?: string;
    description?: string;
    method?: string;
    endpoint?: string;
    status?: string;
    isActive?: boolean;
    pricing?: string;
    rateLimit?: number;
    pathParameters?: PathParameter[];
    queryParameters?: QueryParameter[];
    headers?: Header[];
    bodyParams?: any[];
    bodySchema?: any;
    response?: any;
    version?: string;
}

// Allowed properties for tool updates
export const ALLOWED_TOOL_UPDATE_PROPERTIES: (keyof ToolUpdatePayload)[] = [
    'name', 'description', 'method', 'endpoint', 'status', 'isActive',
    'pricing', 'rateLimit', 'pathParameters', 'queryParameters',
    'headers', 'bodyParams', 'bodySchema', 'response', 'version'
];

// Filter and validate tool update payload
export const createValidatedToolUpdatePayload = (data: any): ToolUpdatePayload => {
    const payload: Partial<ToolUpdatePayload> = {};
    ALLOWED_TOOL_UPDATE_PROPERTIES.forEach(property => {
        if (data.hasOwnProperty(property) && data[property] !== undefined) {
            (payload as any)[property] = data[property];
        }
    });
    return payload as ToolUpdatePayload;
};

// Normalize parameters for display
export const normalizeParameters = (params: any[], parameterType?: string) => {
    if (!params || !Array.isArray(params)) return [];
    return params.map(param => ({
        id: param.id,
        name: param.name || '',
        type: param.type || 'string',
        description: param.description || '',
        required: param.required || false,
        example: param.example || param.exampleValue || '',
        parameterType: param.parameterType || parameterType || 'path',
        defaultValue: param.defaultValue || null
    }));
};

// Normalize parameters for sending to API
export const normalizeParametersForSending = (params: any[], parameterType?: string) => {
    if (!params || !Array.isArray(params)) return [];
    return params.map(param => ({
        id: param.id,
        name: param.name || '',
        type: param.type || 'string',
        description: param.description || '',
        required: param.required || false,
        example: param.example || param.exampleValue || '',
        parameterType: param.parameterType || parameterType || 'path',
        defaultValue: param.defaultValue || null
    }));
};

// Get method badge color
export const getMethodColor = (method: string): string => {
    const colors: Record<string, string> = {
        'GET': 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300',
        'POST': 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
        'PUT': 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300',
        'DELETE': 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300',
        'PATCH': 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-300',
    };
    return colors[method?.toUpperCase()] || 'bg-gray-100 text-gray-800 dark:bg-gray-900/30 dark:text-gray-300';
};

// Get status badge color
export const getStatusColor = (status?: string): string => {
    const colors: Record<string, string> = {
        'approved': 'bg-green-100 text-green-800 border-green-200 dark:bg-green-900/20 dark:text-green-300 dark:border-green-700',
        'active': 'bg-green-100 text-green-800 border-green-200 dark:bg-green-900/20 dark:text-green-300 dark:border-green-700',
        'draft': 'bg-yellow-100 text-yellow-800 border-yellow-200 dark:bg-yellow-900/20 dark:text-yellow-300 dark:border-yellow-700',
        'paused': 'bg-yellow-100 text-yellow-800 border-yellow-200 dark:bg-yellow-900/20 dark:text-yellow-300 dark:border-yellow-700',
        'submitted': 'bg-blue-100 text-blue-800 border-blue-200 dark:bg-blue-900/20 dark:text-blue-300 dark:border-blue-700',
        'reviewing': 'bg-orange-100 text-orange-800 border-orange-200 dark:bg-orange-900/20 dark:text-orange-300 dark:border-orange-700',
        'rejected': 'bg-red-100 text-red-800 border-red-200 dark:bg-red-900/20 dark:text-red-300 dark:border-red-700',
        'error': 'bg-red-100 text-red-800 border-red-200 dark:bg-red-900/20 dark:text-red-300 dark:border-red-700',
    };
    return colors[status || ''] || 'bg-gray-100 text-gray-800 border-gray-200 dark:bg-gray-900/20 dark:text-gray-300 dark:border-gray-700';
};

// Extract path parameters from endpoint
export const extractPathParameters = (endpointPattern: string): PathParameter[] => {
    const pathParams: PathParameter[] = [];
    const paramRegex = /\{([^}]+)\}/g;
    let match;

    while ((match = paramRegex.exec(endpointPattern)) !== null) {
        const paramName = match[1];
        pathParams.push({
            name: paramName,
            type: 'string',
            required: true,
            description: `The ${paramName} parameter`,
            example: `example_${paramName}`
        });
    }

    return pathParams;
};

// Extract query parameters from endpoint
export const extractQueryParameters = (endpointPattern: string): QueryParameter[] => {
    const queryParams: QueryParameter[] = [];
    const queryString = endpointPattern.split('?')[1];
    if (!queryString) return queryParams;

    const paramPairs = queryString.split('&');

    for (const paramPair of paramPairs) {
        if (!paramPair.trim()) continue;

        let paramName = '';
        let defaultValue = '';
        let isRequired = false;

        if (paramPair.includes('=')) {
            const [name, value] = paramPair.split('=', 2);
            paramName = name.trim();

            if (value) {
                const placeholderMatch = value.match(/^\{([^}]+)\}$/);
                if (placeholderMatch) {
                    isRequired = true;
                    defaultValue = `example_${placeholderMatch[1]}`;
                } else {
                    defaultValue = value;
                }
            }
        } else {
            paramName = paramPair.trim();
        }

        if (paramName && !queryParams.some(p => p.name === paramName)) {
            queryParams.push({
                name: paramName,
                type: 'string',
                required: isRequired,
                description: `The ${paramName} parameter`,
                example: defaultValue || `example_${paramName}`,
                defaultValue: isRequired ? undefined : defaultValue || undefined
            });
        }
    }

    return queryParams;
};

// Build API config from API data
export const buildRealApiConfig = (apiData: any): ApiConfig => {
    const defaultConfig: ApiConfig = {
        baseUrl: 'https://api.example.com',
        healthcheckEndpoint: '/health',
        visibility: 'public',
        authorization: { type: 'none', headerName: '', headerValue: '', username: '', password: '' }
    };

    if (!apiData) return defaultConfig;

    return {
        baseUrl: apiData.baseUrl || 'https://api.example.com',
        healthcheckEndpoint: apiData.healthcheckEndpoint || '/health',
        visibility: apiData.visibility || (apiData.isPublic ? 'public' : 'private'),
        authorization: {
            type: (apiData.authType || 'none') as 'apisecret' | 'bearer' | 'basic' | 'none',
            headerName: apiData.authHeaderName || '',
            headerValue: apiData.authHeaderValue || '',
            username: apiData.authUsername || '',
            password: apiData.authPassword || ''
        }
    };
};

// Replace path parameters with example values
export const replacePathParameters = (endpoint: string, pathParameters: any[]): string => {
    if (!endpoint || !pathParameters?.length) return endpoint || '';

    let processedEndpoint = endpoint;
    pathParameters.forEach(param => {
        const exampleValue = param.example || param.exampleValue || param.defaultValue;
        if (param.name && exampleValue) {
            processedEndpoint = processedEndpoint.replace(`{${param.name}}`, exampleValue);
        }
    });

    return processedEndpoint;
};

// Enrich tool with path parameters from URL
export const enrichToolWithPathParameters = (tool: McpTool): McpTool => {
    if (!tool.endpoint) return tool;

    const enrichedTool = { ...tool };
    const matches = tool.endpoint.match(/\{([^}]+)\}/g);
    if (!matches) return enrichedTool;

    const names = matches.map(m => m.slice(1, -1));
    const currentParams = enrichedTool.pathParameters ? [...enrichedTool.pathParameters] : [];
    let hasUpdates = false;

    names.forEach(name => {
        if (!currentParams.find((p: any) => p.name === name)) {
            currentParams.push({
                name,
                type: 'string',
                parameterType: 'path',
                required: true,
                description: ''
            } as any);
            hasUpdates = true;
        }
    });

    if (hasUpdates || !enrichedTool.pathParameters) {
        enrichedTool.pathParameters = currentParams;
    }

    return enrichedTool;
};
