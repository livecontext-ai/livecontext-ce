import { McpTool, ApiConfig } from '@/app/[locale]/app/settings/developers/types';
import { buildRealApiConfig, replacePathParameters } from './tool-helpers';

// Build query string from parameters
const buildQueryString = (queryParameters?: any[]): string => {
    if (!queryParameters?.length) return '';

    const pairs: string[] = [];
    queryParameters.forEach(param => {
        const value = param.example || param.exampleValue || param.defaultValue;
        if (param.name && value) {
            pairs.push(`${param.name}=${encodeURIComponent(value)}`);
        }
    });

    return pairs.length > 0 ? '?' + pairs.join('&') : '';
};

// Build full URL with path and query parameters
const buildFullUrl = (apiConfig: ApiConfig, endpoint: string, queryParameters?: any[]): string => {
    const cleanBaseUrl = apiConfig.baseUrl.replace(/\/$/, '');
    const cleanEndpoint = endpoint.replace(/^\//, '');
    return `${cleanBaseUrl}/${cleanEndpoint}${buildQueryString(queryParameters)}`;
};

// Build authorization header
const buildAuthHeader = (apiConfig: ApiConfig): { name: string; value: string } | null => {
    if (apiConfig.authorization.type === 'none') return null;

    if (apiConfig.authorization.type === 'bearer') {
        const token = apiConfig.authorization.headerValue || 'YOUR_TOKEN';
        const cleanToken = token.startsWith('Bearer ') ? token.substring(7) : token;
        return { name: 'Authorization', value: `Bearer ${cleanToken}` };
    }

    if (apiConfig.authorization.headerName && apiConfig.authorization.headerValue) {
        return { name: apiConfig.authorization.headerName, value: apiConfig.authorization.headerValue };
    }

    return null;
};

// Generate cURL command
export const generateEnhancedCurl = (tool: McpTool, apiData: any, toolState?: McpTool | null): string => {
    const apiConfig = buildRealApiConfig(apiData);
    const currentTool = toolState || tool;
    const processedEndpoint = replacePathParameters(
        currentTool.endpoint || '',
        currentTool.pathParameters || []
    );

    const finalUrl = buildFullUrl(apiConfig, processedEndpoint, tool.queryParameters);
    let curl = `curl -X ${currentTool.method || 'GET'} "${finalUrl}"`;

    // Add tool headers
    const headers = currentTool.headers || [];
    headers.forEach(header => {
        const value = header.value || (header as any).example || (header as any).exampleValue || '';
        if (header.name && value) {
            curl += ` \\\n  -H "${header.name}: ${value}"`;
        }
    });

    // Add authorization
    const authHeader = buildAuthHeader(apiConfig);
    if (authHeader) {
        if (apiConfig.authorization.type === 'basic') {
            const username = apiConfig.authorization.username || 'YOUR_USERNAME';
            const password = apiConfig.authorization.password || 'YOUR_PASSWORD';
            curl += ` \\\n  -u "${username}:${password}"`;
        } else {
            curl += ` \\\n  -H "${authHeader.name}: ${authHeader.value}"`;
        }
    }

    // Add body for POST/PUT
    const method = currentTool.method || 'GET';
    const bodyParams = currentTool.bodyParams || [];
    if ((method === 'POST' || method === 'PUT') && bodyParams.length > 0) {
        const body: Record<string, any> = {};
        bodyParams.forEach(param => {
            const value = param.value || (param as any).exampleValue || (param as any).example || (param as any).defaultValue;
            if (param.name && value) {
                body[param.name] = value;
            }
        });

        if (Object.keys(body).length > 0) {
            curl += ` \\\n  -H "Content-Type: application/json"`;
            curl += ` \\\n  -d '${JSON.stringify(body)}'`;
        }
    }

    return curl;
};

// Generate JavaScript code
export const generateEnhancedJavaScript = (tool: McpTool, apiData: any, toolState?: McpTool | null): string => {
    const apiConfig = buildRealApiConfig(apiData);
    const currentTool = toolState || tool;
    const processedEndpoint = replacePathParameters(
        currentTool.endpoint || '',
        currentTool.pathParameters || []
    );

    const finalUrl = buildFullUrl(apiConfig, processedEndpoint, tool.queryParameters);
    const method = currentTool.method || 'GET';

    let js = `const response = await fetch("${finalUrl}", {\n`;
    js += `  method: "${method}",\n`;

    // Build headers object
    const headersObj: Record<string, string> = {};
    const headers = currentTool.headers || [];
    headers.forEach(header => {
        const value = header.value || (header as any).example || (header as any).exampleValue || '';
        if (header.name && value) {
            headersObj[header.name] = value;
        }
    });

    // Add authorization
    const authHeader = buildAuthHeader(apiConfig);
    if (authHeader) {
        headersObj[authHeader.name] = authHeader.value;
    }

    if (Object.keys(headersObj).length > 0) {
        js += `  headers: {\n`;
        Object.entries(headersObj).forEach(([key, value]) => {
            js += `    "${key}": "${value}",\n`;
        });
        js += `  },\n`;
    }

    // Add body for POST/PUT
    const bodyParams = currentTool.bodyParams || [];
    if ((method === 'POST' || method === 'PUT') && bodyParams.length > 0) {
        const body: Record<string, any> = {};
        bodyParams.forEach(param => {
            const value = param.value || (param as any).exampleValue || (param as any).example || (param as any).defaultValue;
            if (param.name && value) {
                body[param.name] = value;
            }
        });

        if (Object.keys(body).length > 0) {
            js += `  body: JSON.stringify(${JSON.stringify(body, null, 2)}),\n`;
        }
    }

    js += `});\n\nconst data = await response.json();`;
    return js;
};

// Generate Python code
export const generateEnhancedPython = (tool: McpTool, apiData: any, toolState?: McpTool | null): string => {
    const apiConfig = buildRealApiConfig(apiData);
    const currentTool = toolState || tool;
    const processedEndpoint = replacePathParameters(
        currentTool.endpoint || '',
        currentTool.pathParameters || []
    );

    const finalUrl = buildFullUrl(apiConfig, processedEndpoint, tool.queryParameters);
    const method = (currentTool.method || 'GET').toLowerCase();

    let python = `import requests\n\n`;

    // Build headers object
    const headersObj: Record<string, string> = {};
    const headers = currentTool.headers || [];
    headers.forEach(header => {
        const value = header.value || (header as any).example || (header as any).exampleValue || '';
        if (header.name && value) {
            headersObj[header.name] = value;
        }
    });

    // Add authorization
    const authHeader = buildAuthHeader(apiConfig);
    if (authHeader) {
        headersObj[authHeader.name] = authHeader.value;
    }

    python += `headers = ${JSON.stringify(headersObj, null, 2)}\n`;

    // Add body for POST/PUT
    const bodyParams = currentTool.bodyParams || [];
    if ((method === 'post' || method === 'put') && bodyParams.length > 0) {
        const body: Record<string, any> = {};
        bodyParams.forEach(param => {
            const value = param.value || (param as any).exampleValue || (param as any).example || (param as any).defaultValue;
            if (param.name && value) {
                body[param.name] = value;
            }
        });

        if (Object.keys(body).length > 0) {
            python += `data = ${JSON.stringify(body, null, 2)}\n\n`;
            python += `response = requests.${method}("${finalUrl}", headers=headers, json=data)\n`;
        } else {
            python += `\nresponse = requests.${method}("${finalUrl}", headers=headers)\n`;
        }
    } else {
        python += `\nresponse = requests.${method}("${finalUrl}", headers=headers)\n`;
    }

    python += `data = response.json()`;
    return python;
};
