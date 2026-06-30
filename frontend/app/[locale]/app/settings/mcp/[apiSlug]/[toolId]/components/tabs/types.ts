import { McpTool, PathParameter, QueryParameter, Header, ApiConfig } from '@/app/[locale]/app/settings/developers/types';

export interface ToolTabProps {
    tool: McpTool | null;
    toolState: McpTool | null;
    setToolState: React.Dispatch<React.SetStateAction<McpTool | null>>;
    isEditing: boolean;
    api?: any;
    setConfigError?: (error: string | null) => void;
}

export interface ParameterTabProps extends ToolTabProps {
    parameters: any[];
    setParameters: React.Dispatch<React.SetStateAction<any[]>>;
    parameterType: 'path' | 'query' | 'header' | 'body';
}

export interface TestTabProps extends ToolTabProps {
    pathParameters: PathParameter[];
    queryParameters: QueryParameter[];
    headers: Header[];
    bodyParameters: any[];
    testTool: () => void;
    testingTool: boolean;
    testResult: any;
    generateEnhancedCurl: (tool: McpTool, apiData: any) => string;
    generateEnhancedJavaScript: (tool: McpTool, apiData: any) => string;
    generateEnhancedPython: (tool: McpTool, apiData: any) => string;
    buildRealApiConfig: (apiData: any) => ApiConfig;
    selectedLanguage: string;
    setSelectedLanguage: (lang: string) => void;
}

export interface ResponseTabProps extends ToolTabProps {
    toolResponses: any[];
    loadingResponses: boolean;
}
