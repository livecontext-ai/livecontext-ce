import { McpTool, ApiConfig } from '../../types';

export interface ToolComponentProps {
  tool: McpTool;
  toolIndex: number;
  toolKey: string;
  onToolUpdate: (toolIndex: number, updatedTool: McpTool) => void;
}

export interface ToolHeaderProps extends ToolComponentProps {
  isCollapsed: boolean;
  onToggleCollapse: () => void;
  onTestEndpoint: () => void;
  onDeleteTool: () => void;
  isEditingName: boolean;
  editingNameValue: string;
  onStartEditingName: () => void;
  onSaveToolName: () => void;
  onCancelEditingName: () => void;
  onNameValueChange: (value: string) => void;
  onNameKeyPress: (e: React.KeyboardEvent) => void;
}

export interface ToolTabsProps {
  toolKey: string;
  activeTab: string;
  onTabChange: (tab: string) => void;
}

export interface ConfigTabProps extends ToolComponentProps {
  apiConfig: ApiConfig;
  isEditingDescription: boolean;
  onToggleEditingDescription: () => void;
  onEndpointChange: (endpoint: string) => void;
}

export interface ParameterListTabProps extends ToolComponentProps {
  parameterType: 'pathParameters' | 'queryParameters' | 'headers' | 'bodyParams';
  title: string;
  infoText?: React.ReactNode;
  showTabInfo: boolean;
  onToggleTabInfo: () => void;
}

export interface ResponseTabProps extends ToolComponentProps {
  apiConfig: ApiConfig;
}

export interface TestTabProps extends ToolComponentProps {
  apiConfig: ApiConfig;
  selectedLanguage: string;
  onLanguageChange: (language: string) => void;
}

export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE'] as const;

export const TAB_CONFIG = {
  desktop: [
    { id: 'config', label: 'Configuration' },
    { id: 'pathParams', label: 'Path Parameters' },
    { id: 'queryParams', label: 'Query Parameters' },
    { id: 'headers', label: 'Headers' },
    { id: 'body', label: 'Body' },
    { id: 'response', label: 'Response' },
    { id: 'test', label: 'Test' }
  ],
  medium: [
    { id: 'config', label: 'Config' },
    { id: 'pathParams', label: 'Path Params' },
    { id: 'queryParams', label: 'Query Params' },
    { id: 'headers', label: 'Headers' },
    { id: 'body', label: 'Body' },
    { id: 'response', label: 'Response' },
    { id: 'test', label: 'Test' }
  ],
  mobile: [
    { id: 'config', label: 'Config' },
    { id: 'pathParams', label: 'Path' },
    { id: 'queryParams', label: 'Query' },
    { id: 'headers', label: 'Headers' },
    { id: 'body', label: 'Body' },
    { id: 'response', label: 'Resp' },
    { id: 'test', label: 'Test' }
  ]
} as const;

export const TAB_ICONS = {
  config: 'Settings',
  pathParams: 'Code',
  queryParams: 'FileJson',
  headers: 'Shield',
  body: 'FileJson',
  response: 'FileText',
  test: 'TestTube'
} as const;
