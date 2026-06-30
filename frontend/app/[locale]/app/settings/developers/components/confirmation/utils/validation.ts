import { ApiConfig, McpTool, MonetizationConfig } from '../../../types';

interface ValidationParams {
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  apiConfig: ApiConfig;
  mcpTools: McpTool[];
  isAuthenticated: boolean;
}

interface ValidationStatus {
  status: 'success' | 'warning' | 'error';
  message: string;
}

/**
 * Checks if basic info is complete
 */
export function hasBasicInfo(
  apiName: string,
  apiDescription: string,
  selectedCategory: string,
  selectedSubcategory: string
): boolean {
  return !!(
    apiName.trim() &&
    apiDescription.trim() &&
    selectedCategory &&
    selectedSubcategory
  );
}

/**
 * Checks if API config is complete
 */
export function hasApiConfig(apiConfig: ApiConfig): boolean {
  return !!(
    apiConfig.baseUrl.trim() &&
    (apiConfig.authorization.type === 'none' ||
      (apiConfig.authorization.type === 'basic' &&
        apiConfig.authorization.username?.trim() &&
        apiConfig.authorization.password?.trim()) ||
      (apiConfig.authorization.type !== 'basic' &&
        apiConfig.authorization.headerName?.trim() &&
        apiConfig.authorization.headerValue?.trim()))
  );
}

/**
 * Checks if tools are configured
 */
export function hasTools(mcpTools: McpTool[]): boolean {
  return mcpTools.length > 0;
}

/**
 * Checks if all tools are tested successfully
 */
export function allToolsTested(mcpTools: McpTool[]): boolean {
  return mcpTools.length > 0 && mcpTools.every(tool => tool.testStatus === 'success');
}

/**
 * Checks if form can be submitted
 */
export function canSubmit(params: ValidationParams): boolean {
  const { apiName, apiDescription, selectedCategory, selectedSubcategory, apiConfig, mcpTools, isAuthenticated } = params;

  return (
    hasBasicInfo(apiName, apiDescription, selectedCategory, selectedSubcategory) &&
    hasApiConfig(apiConfig) &&
    hasTools(mcpTools) &&
    isAuthenticated
  );
}

/**
 * Gets validation status with message key
 */
export function getValidationStatus(params: ValidationParams): ValidationStatus {
  const { apiName, apiDescription, selectedCategory, selectedSubcategory, apiConfig, mcpTools, isAuthenticated } = params;

  if (!isAuthenticated) {
    return { status: 'error', message: 'validation.mustAuthenticate' };
  }
  if (!hasBasicInfo(apiName, apiDescription, selectedCategory, selectedSubcategory)) {
    return { status: 'error', message: 'validation.missingBasicInfo' };
  }
  if (!hasApiConfig(apiConfig)) {
    return { status: 'error', message: 'validation.incompleteApiConfig' };
  }
  if (!hasTools(mcpTools)) {
    return { status: 'error', message: 'validation.noToolConfigured' };
  }
  if (!allToolsTested(mcpTools)) {
    return { status: 'warning', message: 'validation.toolsNotTested' };
  }
  return { status: 'success', message: 'validation.ready' };
}
